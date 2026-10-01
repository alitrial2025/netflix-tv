@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DirectCDNResolver
import com.example.data.TmdbRepository
import com.example.model.Movie
import com.example.model.Profile
import com.example.model.catalogMediaKind
import com.example.model.findMovieByIdentity
import com.example.model.isSeriesContent
import com.example.data.NetflixDatabase
import com.example.data.ContinueWatchingRepository
import com.example.data.ContinueWatchingEntity
import com.example.data.PendingWriteDao
import com.example.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import com.example.model.isKidSafeMovie
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.graphics.Color
import com.example.ui.components.ProfileIcons
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.example.sync.ConflictPolicy
import com.example.sync.ConflictResolver
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Firestore collection names used by the sync policy. Centralising the
 * literals here means [ConflictResolver.policyFor] and the call sites
 * agree on the same string — a typo would be a silent policy miss.
 */
private object Collections {
    const val PROFILES = "profiles"
    const val CONTINUE_WATCHING = "continueWatching"
    const val CONTINUE_WATCHING_FLAT = "continue_watching"
    const val MY_LIST = "myList"
    const val SUBSCRIPTION = "subscription"
    const val TV_SESSIONS = "tvSessions"
    const val ACTIVE_STREAMS = "active_streams"
}

class NetflixViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TmdbRepository()
    suspend fun fetchKidsArtworkTitle(kind: String, id: String): Movie? = repository.fetchKidsArtworkTitle(kind, id)
    private val directCDNResolver by lazy { DirectCDNResolver(application) }

    private val database = NetflixDatabase.getDatabase(application)
    private val continueWatchingRepository = ContinueWatchingRepository(database.continueWatchingDao())
    private val pendingWriteDao: PendingWriteDao = database.pendingWriteDao()
    private val continueWatchingApplyMutex = Mutex()

    private val prefs = application.getSharedPreferences("netflix_prefs", android.content.Context.MODE_PRIVATE)

    companion object {
        // Salt used for the per-profile PIN hash. The salt is *not* a secret —
        // its job is to make a precomputed rainbow table on the device useless
        // when the JSON blob is exfiltrated. (A 4-digit PIN space is tiny; a
        // salt pushes the attacker back to brute force per profile.)
        private const val PIN_SALT: String = "netflix-pro-tv-2024"

        /**
         * Hash a 4-digit PIN to a 64-char SHA-256 hex digest. Returned as a
         * lowercase hex string so the value can be safely persisted to
         * SharedPreferences / Firestore. `null` and blank inputs return
         * `null` so the call site can use the result directly.
         *
         * Audit fix: previously the PIN was stored as plaintext in
         * SharedPreferences JSON and synced to Firestore in plaintext. Anyone
         * with `adb backup` access or a compromised Firestore rule could
         * read every profile's PIN.
         */
        fun hashPin(pin: String?): String? {
            if (pin.isNullOrBlank()) return null
            // Existing profile records already contain a digest. Saving an
            // unrelated edit must preserve it instead of hashing the digest.
            if (pin.length == 64 && pin.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return pin.lowercase()
            val digest = MessageDigest.getInstance("SHA-256")
            val salted = (PIN_SALT + pin).toByteArray(Charsets.UTF_8)
            val bytes = digest.digest(salted)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun matchesProfilePin(storedPin: String?, enteredPin: String): Boolean {
            if (enteredPin.length != 4 || enteredPin.any { it !in '0'..'9' }) return false
            if (storedPin.isNullOrBlank()) return true
            // Preserve access to old plaintext records as well as shared phone/TV hashes.
            return storedPin == enteredPin || storedPin.equals(hashPin(enteredPin), ignoreCase = true)
        }
    }

    private val sharedExoPlayerDelegate = lazy {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(application).apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val lowMemory = com.example.ui.util.TvImagePolicy.isLowMemoryDevice(application)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, if (lowMemory) 10_000 else 15_000, 900, 1_500)
            .setTargetBufferBytes(if (lowMemory) 8 * 1024 * 1024 else 16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        androidx.media3.exoplayer.ExoPlayer.Builder(application, renderersFactory)
            .setLoadControl(loadControl).build().apply {
            setAudioAttributes(audioAttributes, true)
            playWhenReady = true
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                    _sharedVideoFrameMediaId.value = null
                }
                override fun onRenderedFirstFrame() {
                    _sharedVideoFrameMediaId.value = currentMediaItem?.mediaId
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_IDLE) _sharedVideoFrameMediaId.value = null
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _sharedVideoFrameMediaId.value = null
                }
            })
        }
    }
    val sharedExoPlayer: androidx.media3.exoplayer.ExoPlayer get() = sharedExoPlayerDelegate.value

    private val sharedPlaybackSession = com.example.ui.util.SharedPlaybackSession()
    private val _sharedPlaybackOwner = MutableStateFlow<String?>(null)
    val sharedPlaybackOwner: StateFlow<String?> = _sharedPlaybackOwner.asStateFlow()
    private var sharedPlaybackView: androidx.media3.ui.PlayerView? = null
    private val _sharedVideoFrameMediaId = MutableStateFlow<String?>(null)
    val sharedVideoFrameMediaId: StateFlow<String?> = _sharedVideoFrameMediaId.asStateFlow()
    private var playbackHandoffJob: kotlinx.coroutines.Job? = null

    fun claimSharedPlayback(owner: String, stopPreview: Boolean = true) {
        playbackHandoffJob?.cancel()
        sharedPlaybackSession.claim(owner)
        _sharedPlaybackOwner.value = owner
        // Claim first: a departing preview must not clear its successor's source.
        if (stopPreview) stopHomePreviews()
    }

    fun ownsSharedPlayback(owner: String): Boolean = sharedPlaybackSession.owns(owner)

    fun handoffSharedPlayback(owner: String) {
        val nextOwner = "handoff:${java.util.UUID.randomUUID()}"
        if (!sharedPlaybackSession.handoff(owner, nextOwner)) return
        _sharedPlaybackOwner.value = nextOwner
        playbackHandoffJob?.cancel()
        playbackHandoffJob = viewModelScope.launch {
            // Back navigation may dispose Player before Details is composed.
            // Keep its prepared source until the destination claims it.
            kotlinx.coroutines.delay(1_500L)
            releaseSharedPlayback(nextOwner)
        }
    }

    fun releaseSharedPlayback(owner: String) {
        if (!sharedPlaybackSession.release(owner)) return
        sharedPlaybackView?.player = null
        sharedPlaybackView = null
        _sharedPlaybackOwner.value = null
        if (sharedExoPlayerDelegate.isInitialized()) {
            sharedExoPlayer.pause()
            sharedExoPlayer.stop()
            sharedExoPlayer.clearMediaItems()
        }
    }

    fun attachSharedPlaybackView(owner: String, view: androidx.media3.ui.PlayerView) {
        if (!ownsSharedPlayback(owner) || sharedPlaybackView === view) return
        androidx.media3.ui.PlayerView.switchTargetView(sharedExoPlayer, sharedPlaybackView, view)
        sharedPlaybackView = view
    }

    fun detachSharedPlaybackView(view: androidx.media3.ui.PlayerView) {
        view.player = null
        if (sharedPlaybackView === view) sharedPlaybackView = null
    }

    fun recordDetailsPlaybackStart(owner: String, mediaId: String, positionMs: Long) {
        sharedPlaybackSession.recordPreviewStart(owner, mediaId, positionMs)
    }

    fun takeDetailsPlaybackStart(mediaId: String): Long? = sharedPlaybackSession.takePreviewStart(mediaId)

    private val homePreviewDelegate = lazy {
        com.example.ui.util.HomePreviewController(application, viewModelScope, this) {
            _isBillboardPlaying.value = it
        }
    }
    val homePreviewController: com.example.ui.util.HomePreviewController get() = homePreviewDelegate.value

    fun stopHomePreviews() {
        if (homePreviewDelegate.isInitialized()) homePreviewDelegate.value.stopAll(releaseImmediately = false)
    }

    private var activePlayer: androidx.media3.exoplayer.ExoPlayer? = null
    fun registerActivePlayer(player: androidx.media3.exoplayer.ExoPlayer?) {
        if (player != null) stopHomePreviews()
        // perf: release the previous player before swapping so a replaced Billboard
        // (e.g. tab switch) doesn't leak its ExoPlayer instance.
        val previous = activePlayer
        if (previous != null && previous !== player) {
            try { previous.release() } catch (_: Exception) {}
        }
        activePlayer = player
    }

    override fun onCleared() {
        super.onCleared()
        sharedPlaybackView?.player = null
        sharedPlaybackView = null
        // perf: release active player too — the activePlayer may differ from sharedExoPlayer
        // when a tab opened a per-billboard player. Without this, that instance leaks
        // until process death.
        val releasedActivePlayer = activePlayer
        try { releasedActivePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        if (homePreviewDelegate.isInitialized()) homePreviewDelegate.value.release()
        if (sharedExoPlayerDelegate.isInitialized() && sharedExoPlayerDelegate.value !== releasedActivePlayer) {
            sharedExoPlayerDelegate.value.release()
        }
        // Detach every Firestore listener so the SDK doesn't hold a strong ref
        // to the ViewModel after the user navigates away (memory leak) and so a
        // backgrounded app stops receiving snapshot callbacks (battery / quota).
        try { firestoreProfileListener?.remove() } catch (_: Exception) {}
        try { continueWatchingFirestoreListener?.remove() } catch (_: Exception) {}
        try { continueWatchingNestedListener?.remove() } catch (_: Exception) {}
        try { watchHistoryFirestoreListener?.remove() } catch (_: Exception) {}
        try { myListFirestoreListener?.remove() } catch (_: Exception) {}
        try { firestoreSubscriptionListener?.remove() } catch (_: Exception) {}
        try { remoteCommandListener?.remove() } catch (_: Exception) {}
        streamHeartbeatJob?.cancel()
    }

    private val _selectedProfile = MutableStateFlow<Profile?>(null)
    val selectedProfile: StateFlow<Profile?> = _selectedProfile.asStateFlow()

    private val _watchHistoryMovies = MutableStateFlow<List<Movie>>(emptyList())
    val watchHistoryMovies: StateFlow<List<Movie>> = _watchHistoryMovies.asStateFlow()

    private val _selectedSubtitleLanguage = MutableStateFlow(
        prefs.getString("selected_subtitle_language", "English") ?: "English"
    )
    val selectedSubtitleLanguage: StateFlow<String> = _selectedSubtitleLanguage.asStateFlow()

    fun setSelectedSubtitleLanguage(language: String) {
        _selectedSubtitleLanguage.value = language
        prefs.edit().putString("selected_subtitle_language", language).apply()
        val currentProfileId = _selectedProfile.value?.id
        if (!currentProfileId.isNullOrEmpty()) {
            prefs.edit().putString("subtitle_language_profile_$currentProfileId", language).apply()
        }
    }

    private val _myListMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val myListMovieIds: StateFlow<Set<String>> = _myListMovieIds.asStateFlow()

    /**
     * One-time My List events. UI uses this to show a transient toast /
     * snackbar ("Added to My List") without re-firing on recomposition
     * (a `StateFlow` would re-emit on every collector restart).
     */
    private val _myListEvents = Channel<MyListEvent>(capacity = Channel.BUFFERED)
    val myListEvents = _myListEvents.receiveAsFlow()

    sealed class MyListEvent {
        data class Added(val movieId: String) : MyListEvent()
        data class Removed(val movieId: String) : MyListEvent()
    }

    /**
     * Cap for the My List per account. 500 is the documented Netflix limit;
     * the server should also enforce this, but the client-side check stops
     * a malicious / buggy caller from stuffing the local cache.
     */
    private val myListCap: Int = 500

    /**
     * Idempotently add a movie to My List. No-op when the movie is already
     * in the list. The [MyListEvent.Added] event fires only on a real
     * transition so the UI doesn't flash "Added!" on a re-tap.
     */
    fun addToMyList(movieId: String) {
        val current = _myListMovieIds.value
        if (current.contains(movieId)) return
        if (current.size >= myListCap) {
            android.util.Log.w("NetflixViewModel", "My List at cap ($myListCap); ignoring add for $movieId")
            return
        }
        val now = System.currentTimeMillis()
        val updated = current + movieId
        // bugfix: previously _myListAddedAt wasn't updated on add, so the
        // sortByMyListMembership() helper would have all-0L timestamps and
        // preserve catalog order instead of "most recently added first".
        val addedAt = _myListAddedAt.value.toMutableMap().apply { put(movieId, now) }
        // Enforce the bounded cache: drop the oldest entries by addedAt if
        // we somehow exceeded the cap (e.g. a legacy prefs file with > 500).
        val boundedAddedAt = if (addedAt.size > myListCap) {
            addedAt.entries.sortedByDescending { it.value }.take(myListCap).associate { it.key to it.value }
        } else addedAt
        val boundedIds = boundedAddedAt.keys
        _myListMovieIds.value = boundedIds
        _myListAddedAt.value = boundedAddedAt
        persistMyList(boundedIds)
        persistMyListAddedAt(_selectedProfile.value?.id ?: "default", boundedAddedAt)
        _myListEvents.trySend(MyListEvent.Added(movieId))
        syncMyListChange(_selectedProfile.value?.id ?: "default", movieId, true, now)
    }

    /**
     * Idempotently remove a movie from My List. No-op when the movie is not
     * in the list. The [MyListEvent.Removed] event fires only on a real
     * transition.
     */
    fun removeFromMyList(movieId: String) {
        val current = _myListMovieIds.value
        if (!current.contains(movieId)) return
        val updated = current - movieId
        // bugfix: keep _myListAddedAt in lock-step with _myListMovieIds so
        // a removed id doesn't linger in the timestamp map and skew future
        // reconcile / sort decisions.
        val addedAt = _myListAddedAt.value.toMutableMap().apply { remove(movieId) }
        _myListMovieIds.value = updated
        _myListAddedAt.value = addedAt
        persistMyList(updated)
        persistMyListAddedAt(_selectedProfile.value?.id ?: "default", addedAt)
        _myListEvents.trySend(MyListEvent.Removed(movieId))
        syncMyListChange(_selectedProfile.value?.id ?: "default", movieId, false, System.currentTimeMillis())
    }

    /**
     * Toggle a movie in/out of My List. Kept for callers that already use it
     * (e.g. the DetailsScreen bookmark button). Internally dispatches to
     * [addToMyList] / [removeFromMyList] so the idempotency / cap / event
     * semantics stay consistent.
     */
    fun toggleMyList(movieId: String) {
        if (_myListMovieIds.value.contains(movieId)) {
            removeFromMyList(movieId)
        } else {
            addToMyList(movieId)
        }
    }

    fun isMovieInMyList(movieId: String): Boolean {
        return _myListMovieIds.value.contains(movieId)
    }

    private fun localAccountScope(): String =
        prefs.getString("paired_user_id", null)?.takeIf { it.isNotBlank() } ?: "guest"

    private fun myListIdsKey(profileId: String): String =
        "my_list_movie_ids_user_${localAccountScope()}_profile_$profileId"

    private fun myListTimestampsKey(profileId: String): String =
        "my_list_added_at_user_${localAccountScope()}_profile_$profileId"

    /** Firebase Auth, rather than a UID kept in preferences, authorizes every cloud access. */
    private fun authenticatedUid(): String? {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        val pairedUid = prefs.getString("paired_user_id", null)
        return user?.uid?.takeIf { !user.isAnonymous && it == pairedUid }
    }

    private fun persistMyList(set: Set<String>) {
        val profileId = _selectedProfile.value?.id ?: "default"
        prefs.edit().putStringSet(myListIdsKey(profileId), set.toSet()).apply()
    }

    private fun loadMyList(): Set<String> {
        val profileId = _selectedProfile.value?.id ?: "default"
        val scopedKey = myListIdsKey(profileId)
        if (prefs.contains(scopedKey)) return prefs.getStringSet(scopedKey, emptySet())?.toSet() ?: emptySet()

        // Migrate one legacy account-wide list into the selected profile only.
        // Removing the old key prevents it leaking into another profile or account.
        val legacyAccountKey = "my_list_movie_ids_account"
        val legacyProfileKey = "my_list_movie_ids_profile_$profileId"
        val migrated = (prefs.getStringSet(legacyProfileKey, null)
            ?: prefs.getStringSet(legacyAccountKey, null))?.toSet() ?: emptySet()
        prefs.edit().putStringSet(scopedKey, migrated)
            .remove(legacyProfileKey).remove(legacyAccountKey).apply()
        return migrated
    }

    // Per audit §3 / §6 / §9: per-movie addedAt (epoch ms) so the row can
    // be sorted "most recently added first" and a sync can apply
    // last-write-wins deterministically. Stored as a small JSON blob keyed on
    // the profile so the existing StringSet-based sync path doesn't need to
    // be re-keyed.
    private val _myListAddedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val myListAddedAt: StateFlow<Map<String, Long>> = _myListAddedAt.asStateFlow()

    // Per audit §6: offline / sync state. A small enum so the UI can show
    // "syncing" / "sync failed" indicators without coupling to a specific
    // network impl.
    enum class MyListSyncStatus { Idle, Syncing, Failed }
    private val _myListSyncStatus = MutableStateFlow(MyListSyncStatus.Idle)
    val myListSyncStatus: StateFlow<MyListSyncStatus> = _myListSyncStatus.asStateFlow()

    // Offline-first outbox status. Exposed to the UI for the "5 pending, 1
    // failed" badge in the top-right of the home screen. Re-emits whenever
    // the pending_writes table changes (insert, markDone, markFailed, prune).
    val pendingWritesStatusCounts: StateFlow<List<PendingWriteDao.StatusCount>> =
        pendingWriteDao.observeStatusCounts()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private fun persistMyListAddedAt(profileId: String, map: Map<String, Long>) {
        try {
            val arr = org.json.JSONArray()
            map.forEach { (id, ts) ->
                arr.put(org.json.JSONObject().put("id", id).put("ts", ts))
            }
            prefs.edit().putString(myListTimestampsKey(profileId), arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.w("NetflixViewModel", "Could not persist My List timestamps", e)
        }
    }

    private fun loadMyListAddedAt(profileId: String): Map<String, Long> {
        val scopedKey = myListTimestampsKey(profileId)
        val legacyKey = "my_list_added_at_profile_$profileId"
        val raw = prefs.getString(scopedKey, null) ?: prefs.getString(legacyKey, null) ?: return emptyMap()
        val parsed = try {
            val arr = org.json.JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    put(obj.getString("id"), obj.getLong("ts"))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
        if (!prefs.contains(scopedKey)) {
            prefs.edit().putString(scopedKey, raw).remove(legacyKey).apply()
        }
        return parsed
    }

    // Per audit §3: sort a movie list by `addedAt` descending. Items not in
    // My List sink to the bottom (and within "not in list", preserve input
    // order so the row doesn't visually shuffle when the user toggles an item).
    fun sortByMyListMembership(
        movies: List<com.example.model.Movie>,
        ids: Set<String> = _myListMovieIds.value,
        addedAt: Map<String, Long> = _myListAddedAt.value
    ): List<com.example.model.Movie> {
        if (ids.isEmpty()) return movies
        val (inList, notInList) = movies.partition { it.id in ids }
        val sortedInList = inList.sortedByDescending { addedAt[it.id] ?: 0L }
        return sortedInList + notInList
    }

    // Per audit §6: last-write-wins reconcile. Call this when a remote source
    // (Firestore, another device) reports a new authoritative list. Items in
    // [remote] replace local state; the local `addedAt` is preserved for
    // items that exist in both, else seeded from [remoteTimestampById].
    fun reconcileMyListFromRemote(
        remote: Set<String>,
        remoteTimestampById: Map<String, Long> = emptyMap()
    ) {
        val currentProfileId = _selectedProfile.value?.id ?: "default"
        val now = System.currentTimeMillis()
        val localAddedAt = _myListAddedAt.value
        val merged = remote.associateWith { id ->
            val localTs = localAddedAt[id] ?: 0L
            val remoteTs = remoteTimestampById[id] ?: 0L
            // Last-write-wins: take the larger timestamp; if both 0L (unknown),
            // fall back to "now" so the row sorts to the top.
            maxOf(localTs, remoteTs).takeIf { it > 0L } ?: now
        }
        // Enforce the bounded cache.
        val bounded = if (merged.size > myListCap) {
            merged.entries.sortedByDescending { it.value }.take(myListCap).associate { it.key to it.value }
        } else merged

        _myListMovieIds.value = bounded.keys
        _myListAddedAt.value = bounded
        persistMyList(bounded.keys)
        persistMyListAddedAt(currentProfileId, bounded)
        _myListSyncStatus.value = MyListSyncStatus.Idle
    }

    private var myListFirestoreListener: ListenerRegistration? = null
    private val myListMigrationsInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun syncMyListChange(profileId: String, mediaId: String, isAdded: Boolean, addedAt: Long) {
        val uid = authenticatedUid() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _myListSyncStatus.value = MyListSyncStatus.Syncing
                val doc = FirebaseFirestore.getInstance().collection("users").document(uid)
                    .collection("profiles").document(profileId).collection("my_list").document(mediaId)
                doc.set(
                    mapOf(
                        "mediaId" to mediaId,
                        "profileId" to profileId,
                        "isAdded" to isAdded,
                        "addedAt" to addedAt,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                _myListSyncStatus.value = MyListSyncStatus.Idle
            } catch (e: Exception) {
                _myListSyncStatus.value = MyListSyncStatus.Failed
                android.util.Log.w("NetflixViewModel", "My List sync failed", e)
            }
        }
    }

    private fun listenToMyListFromFirestore(profileId: String) {
        myListFirestoreListener?.remove()
        myListFirestoreListener = null
        val uid = authenticatedUid() ?: return
        val collection = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("profiles").document(profileId).collection("my_list")
        myListFirestoreListener = collection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                _myListSyncStatus.value = MyListSyncStatus.Failed
                android.util.Log.w("NetflixViewModel", "My List listener failed", error)
                return@addSnapshotListener
            }
            if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
            if (_selectedProfile.value?.id != profileId || authenticatedUid() != uid) return@addSnapshotListener

            val migrationKey = "my_list_cloud_seeded_${uid}_profile_$profileId"
            if (migrationKey in myListMigrationsInProgress) return@addSnapshotListener
            // Preserve an existing local list on the first sync of an empty cloud
            // collection. A single batch makes the migration visible atomically.
            if (snapshot.isEmpty && !prefs.getBoolean(migrationKey, false) &&
                _myListMovieIds.value.isNotEmpty()) {
                val ids = _myListMovieIds.value.toList()
                val addedAt = _myListAddedAt.value
                _myListSyncStatus.value = MyListSyncStatus.Syncing
                myListMigrationsInProgress.add(migrationKey)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        ids.chunked(200).forEach { chunk ->
                            val batch = FirebaseFirestore.getInstance().batch()
                            chunk.forEach { mediaId ->
                                batch.set(
                                    collection.document(mediaId),
                                    mapOf(
                                        "mediaId" to mediaId,
                                        "profileId" to profileId,
                                        "isAdded" to true,
                                        "addedAt" to (addedAt[mediaId] ?: System.currentTimeMillis()),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                            }
                            batch.commit().await()
                        }
                        prefs.edit().putBoolean(migrationKey, true).apply()
                        val updated = collection.get().await()
                        if (_selectedProfile.value?.id == profileId && authenticatedUid() == uid) {
                            val active = updated.documents.filter { it.getBoolean("isAdded") != false }
                            reconcileMyListFromRemote(
                                active.map { it.getString("mediaId") ?: it.id }.toSet(),
                                active.associate { (it.getString("mediaId") ?: it.id) to (it.getLong("addedAt") ?: 0L) }
                            )
                        }
                        _myListSyncStatus.value = MyListSyncStatus.Idle
                    } catch (e: Exception) {
                        _myListSyncStatus.value = MyListSyncStatus.Failed
                        android.util.Log.w("NetflixViewModel", "My List migration failed", e)
                    } finally {
                        myListMigrationsInProgress.remove(migrationKey)
                    }
                }
                return@addSnapshotListener
            }
            prefs.edit().putBoolean(migrationKey, true).apply()
            val active = snapshot.documents.filter { it.getBoolean("isAdded") != false }
            reconcileMyListFromRemote(
                active.map { it.getString("mediaId") ?: it.id }.toSet(),
                active.associate { (it.getString("mediaId") ?: it.id) to (it.getLong("addedAt") ?: 0L) }
            )
        }
    }

    private val _likedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val likedMovieIds: StateFlow<Set<String>> = _likedMovieIds.asStateFlow()

    fun toggleLike(movieId: String) {
        val currentProfileId = _selectedProfile.value?.id ?: "default"
        val current = _likedMovieIds.value.toMutableSet()
        if (current.contains(movieId)) {
            current.remove(movieId)
        } else {
            current.add(movieId)
        }
        _likedMovieIds.value = current
        prefs.edit().putStringSet("liked_movie_ids_profile_$currentProfileId", current).apply()
    }

    fun isMovieLiked(movieId: String): Boolean {
        return _likedMovieIds.value.contains(movieId)
    }

    private val _remindedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val remindedMovieIds: StateFlow<Set<String>> = _remindedMovieIds.asStateFlow()

    fun toggleReminder(movieId: String) {
        val currentProfileId = _selectedProfile.value?.id ?: "default"
        val current = _remindedMovieIds.value.toMutableSet()
        if (current.contains(movieId)) {
            current.remove(movieId)
        } else {
            current.add(movieId)
        }
        _remindedMovieIds.value = current
        prefs.edit().putStringSet("reminded_movie_ids_profile_$currentProfileId", current).apply()
    }

    fun isMovieReminded(movieId: String): Boolean {
        return _remindedMovieIds.value.contains(movieId)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val continueWatchingList: StateFlow<List<ContinueWatchingEntity>> = _selectedProfile
        .flatMapLatest { profile ->
            val profileId = profile?.id ?: ""
            if (profileId.isEmpty()) {
                flowOf(emptyList())
            } else {
                continueWatchingRepository.getContinueWatchingList(profileId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // perf: initialize empty so SharedPreferences JSON parsing doesn't block ViewModel
    // construction. Profiles are loaded lazily on the IO dispatcher in init { }.
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _isProfilesLoading = MutableStateFlow(false)
    val isProfilesLoading: StateFlow<Boolean> = _isProfilesLoading.asStateFlow()
    private val _profilesLoadError = MutableStateFlow<String?>(null)
    val profilesLoadError: StateFlow<String?> = _profilesLoadError.asStateFlow()
    private val profileListenerGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val profileSnapshotGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val profileSnapshotReceived = java.util.concurrent.atomic.AtomicBoolean(false)
    private var profileSnapshotJob: kotlinx.coroutines.Job? = null
    private var profilesLoadTimeoutJob: kotlinx.coroutines.Job? = null

    private fun loadStoredProfiles(ownerScope: String = localAccountScope()): List<Profile> {
        val cachedOwner = prefs.getString("saved_profiles_owner", null)
            ?: prefs.getString("paired_user_id", null) ?: "guest"
        if (cachedOwner != ownerScope || localAccountScope() != ownerScope) return emptyList()
        val jsonStr = prefs.getString("saved_profiles_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<Profile>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val favoriteGenresList = mutableListOf<String>()
                    if (obj.has("favoriteGenres")) {
                        val arr = obj.getJSONArray("favoriteGenres")
                        for (j in 0 until arr.length()) {
                            favoriteGenresList.add(arr.getString(j))
                        }
                    }
                    list.add(
                        Profile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            avatarColor = Color(obj.optLong("avatarColor", 0xFFE50914)),
                            isKid = obj.optBoolean("isKid", false),
                            avatarUrl = if (obj.has("avatarUrl") && !obj.isNull("avatarUrl")) obj.getString("avatarUrl") else null,
                            pin = if (obj.has("pin") && !obj.isNull("pin")) obj.getString("pin") else null,
                            language = obj.optString("language", "English"),
                            autoplayNext = obj.optBoolean("autoplayNext", true),
                            autoplayPreviews = obj.optBoolean("autoplayPreviews", true),
                            maturityRating = obj.optString("maturityRating", "18+"),
                            favoriteGenres = favoriteGenresList,
                            gameHandle = if (obj.has("gameHandle") && !obj.isNull("gameHandle")) obj.getString("gameHandle") else null,
                            createdAt = obj.optLong("createdAt", 0L),
                            lastUsedAt = obj.optLong("lastUsedAt", 0L)
                        )
                    )
                }
                val legacyFallbackNames = setOf("Derrick", "Karan", "Mom", "Home", "Kids")
                val legacyIds = setOf("1", "2", "3", "4", "5")
                val filteredList = list.filterNot { it.id in legacyIds && it.name in legacyFallbackNames }
                if (filteredList.size != list.size) {
                    saveStoredProfiles(filteredList, ownerScope) { !profileSnapshotReceived.get() }
                }
                return filteredList
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return emptyList()
    }

    private fun saveStoredProfiles(
        profilesList: List<Profile>,
        ownerScope: String = localAccountScope(),
        shouldSave: () -> Boolean = { true }
    ) {
        try {
            val jsonArray = JSONArray()
            for (p in profilesList) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("avatarColor", p.avatarColor.value.toLong())
                obj.put("isKid", p.isKid)
                obj.put("avatarUrl", p.avatarUrl)
                obj.put("pin", p.pin)
                obj.put("language", p.language)
                obj.put("autoplayNext", p.autoplayNext)
                obj.put("autoplayPreviews", p.autoplayPreviews)
                obj.put("maturityRating", p.maturityRating)
                obj.put("gameHandle", p.gameHandle)
                obj.put("createdAt", p.createdAt)
                obj.put("lastUsedAt", p.lastUsedAt)
                val genresArr = JSONArray()
                p.favoriteGenres.forEach { genresArr.put(it) }
                obj.put("favoriteGenres", genresArr)
                jsonArray.put(obj)
            }
            if (localAccountScope() != ownerScope || !shouldSave()) return
            prefs.edit().putString("saved_profiles_json", jsonArray.toString())
                .putString("saved_profiles_owner", ownerScope).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var firestoreProfileListener: ListenerRegistration? = null
    private var continueWatchingFirestoreListener: ListenerRegistration? = null
    private var continueWatchingNestedListener: ListenerRegistration? = null
    private var watchHistoryFirestoreListener: ListenerRegistration? = null
    // Track the subscription Firestore listener so we can remove it on sign-out
    // and re-attach it under a new uid. Previously each call added a new
    // listener without removing the old one — a memory leak that also meant
    // a signed-out user's plan could still update the new user's
    // _userSubscription (cross-account privacy leak).
    private var firestoreSubscriptionListener: ListenerRegistration? = null
    // perf: serialize upgrade taps so a double-tap on "Upgrade & Stream Now"
    // doesn't fire two parallel setPairedUser() / Firestore writes (which
    // previously could end up writing inconsistent state to prefs and the
    // server). The existing signInMutex covers the sign-in path; upgrades
    // need their own.
    private val upgradeMutex = kotlinx.coroutines.sync.Mutex()
    private var activeUpgradeJob: kotlinx.coroutines.Job? = null

    fun listenToContinueWatchingFromFirestore(profileIdOverride: String? = null) {
        try {
            if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                val targetUid = authenticatedUid() ?: return
                val profileId = profileIdOverride ?: _selectedProfile.value?.id ?: "default"

                continueWatchingFirestoreListener?.remove()
                continueWatchingNestedListener?.remove()

                fun handleSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot?) {
                    if (snapshot == null || authenticatedUid() != targetUid ||
                        _selectedProfile.value?.id != profileId) return
                    viewModelScope.launch(Dispatchers.IO) {
                        continueWatchingApplyMutex.withLock {
                            if (authenticatedUid() != targetUid ||
                                _selectedProfile.value?.id != profileId) return@withLock
                        for (change in snapshot.documentChanges) {
                            val doc = change.document
                            val mediaId = doc.getString("mediaId") ?: doc.getString("movieId") ?: doc.getString("id") ?: doc.id.substringAfterLast("_")
                            if (mediaId.isBlank()) continue

                            if (change.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                continueWatchingRepository.deleteProgress(profileId, mediaId)
                                continue
                            }

                            try {
                                val isCompleted = doc.getBoolean("isCompleted") ?: false
                                val posSec = (doc.getLong("positionSeconds") ?: 0L)
                                val posMs = doc.getLong("playbackPositionMs") ?: doc.getLong("positionMs") ?: (posSec * 1000L)
                                val durSec = (doc.getLong("totalSeconds") ?: doc.getLong("durationSeconds") ?: 0L)
                                val durMs = doc.getLong("durationMs") ?: (durSec * 1000L)
                                val season = (doc.getLong("season") ?: 1L).toInt()
                                val episode = (doc.getLong("episode") ?: 1L).toInt()
                                val episodeName = doc.getString("episodeTitle") ?: doc.getString("episodeName") ?: ""

                                if (isCompleted || (durMs > 0L && posMs >= (durMs * 0.95))) {
                                    continueWatchingRepository.deleteProgress(profileId, mediaId)
                                    continue
                                }

                                val title = doc.getString("title") ?: doc.getString("name") ?: doc.getString("mediaTitle") ?: "Title"
                                val description = doc.getString("description") ?: ""
                                val backdropUrl = doc.getString("backdropUrl") ?: ""
                                val posterUrl = doc.getString("posterUrl") ?: ""
                                val rating = doc.getString("rating") ?: "13+"
                                val year = doc.getString("year") ?: "2024"
                                val type = doc.getString("type") ?: "Series"
                                val duration = doc.getString("duration") ?: ""
                                val logoUrl = doc.getString("logoUrl")

                                val movie = Movie(
                                    id = mediaId,
                                    title = title,
                                    description = description,
                                    backdropUrl = backdropUrl,
                                    posterUrl = posterUrl,
                                    rating = rating,
                                    year = year,
                                    type = type,
                                    duration = duration,
                                    logoUrl = logoUrl
                                )

                                if (durMs > 0L && posMs > 1000L) {
                                    continueWatchingRepository.saveProgress(
                                        profileId = profileId,
                                        movie = movie,
                                        playbackPositionMs = posMs,
                                        durationMs = durMs,
                                        season = season,
                                        episode = episode,
                                        episodeName = episodeName
                                    )
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("NetflixViewModel", "continue_watching doc parse error: ${e.message}")
                            }
                        }
                        }
                    }
                }

                continueWatchingFirestoreListener = db.collection("users").document(targetUid)
                    .collection("continue_watching")
                    .whereEqualTo("profileId", profileId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.w("NetflixViewModel", "Error listening to continue_watching: ${error.message}")
                            return@addSnapshotListener
                        }
                        handleSnapshot(snapshot)
                    }

                continueWatchingNestedListener = db.collection("users").document(targetUid)
                    .collection("profiles").document(profileId)
                    .collection("continue_watching")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.w("NetflixViewModel", "Error listening to nested continue_watching: ${error.message}")
                            return@addSnapshotListener
                        }
                        handleSnapshot(snapshot)
                    }
            }
        } catch (e: Exception) {
            android.util.Log.e("NetflixViewModel", "Error in listenToContinueWatchingFromFirestore: ${e.message}")
        }
    }

    private fun listenToWatchHistoryFromFirestore(profileId: String) {
        watchHistoryFirestoreListener?.remove()
        watchHistoryFirestoreListener = null
        _watchHistoryMovies.value = emptyList()
        val uid = authenticatedUid() ?: return
        try {
            watchHistoryFirestoreListener = FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("profiles").document(profileId)
                .collection("watch_history")
                .orderBy("lastWatchedTimestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100L)
                .addSnapshotListener { snapshot, error ->
                    if (authenticatedUid() != uid || _selectedProfile.value?.id != profileId) {
                        return@addSnapshotListener
                    }
                    if (error != null) {
                        android.util.Log.w("NetflixViewModel", "Watch history listener failed", error)
                        return@addSnapshotListener
                    }
                    _watchHistoryMovies.value = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        try {
                            val mediaId = doc.getString("mediaId")?.takeIf { it.isNotBlank() } ?: doc.id
                            if (mediaId.isBlank()) return@mapNotNull null
                            Movie(
                                id = mediaId,
                                title = doc.getString("title")?.takeIf { it.isNotBlank() }
                                    ?: doc.getString("mediaTitle")?.takeIf { it.isNotBlank() }
                                    ?: "Watched title",
                                description = doc.getString("description").orEmpty(),
                                backdropUrl = doc.getString("backdropUrl").orEmpty(),
                                posterUrl = doc.getString("posterUrl").orEmpty(),
                                rating = doc.getString("rating") ?: "18+",
                                year = doc.getString("year") ?: "",
                                type = doc.getString("type") ?: "Movie",
                                duration = doc.getString("duration") ?: "",
                                logoUrl = doc.getString("logoUrl")
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("NetflixViewModel", "Invalid watch history item", e)
                            null
                        }
                    }
                }
        } catch (e: Exception) {
            android.util.Log.w("NetflixViewModel", "Could not start watch history listener", e)
        }
    }

    private fun setProfilesFromFirestore(newProfiles: List<Profile>) {
        _profiles.value = newProfiles
        // Keep a selected profile current, but fetching must never choose one
        // or bypass the picker/PIN flow. An empty server result also clears it.
        val selectedId = _selectedProfile.value?.id
        _selectedProfile.value = selectedId?.let { id -> newProfiles.firstOrNull { it.id == id } }
        if (selectedId != null && _selectedProfile.value == null) {
            prefs.edit().remove("last_selected_profile_id").apply()
        }
    }

    fun listenToFirestoreProfiles(userId: String? = null) {
        val targetUid = authenticatedUid() ?: return
        if (userId != null && userId != targetUid) return
        val listenerTicket = profileListenerGeneration.incrementAndGet()
        firestoreProfileListener?.remove()
        firestoreProfileListener = null
        profileSnapshotJob?.cancel()
        profilesLoadTimeoutJob?.cancel()
        profileSnapshotReceived.set(false)
        _isProfilesLoading.value = true
        _profilesLoadError.value = null
        fun ownsRequest(): Boolean = authenticatedUid() == targetUid &&
            profileListenerGeneration.get() == listenerTicket
        profilesLoadTimeoutJob = viewModelScope.launch {
            delay(12_000L)
            if (ownsRequest() && _isProfilesLoading.value) {
                _isProfilesLoading.value = false
                _profilesLoadError.value = "Profiles could not be loaded. Check your connection and try again."
            }
        }
        try {
            val db = FirebaseFirestore.getInstance()
            firestoreProfileListener = db.collection("users").document(targetUid).collection("profiles")
                .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                    if (!ownsRequest()) return@addSnapshotListener
                    if (error != null) {
                        profileSnapshotJob?.cancel()
                        profilesLoadTimeoutJob?.cancel()
                        _isProfilesLoading.value = false
                        _profilesLoadError.value = "Profiles could not be loaded. Please try again."
                        android.util.Log.w("NetflixViewModel", "Firestore profiles status: ${error.code}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    // An empty local cache is not proof that the account has
                    // no profiles. Wait for the server, including metadata-only
                    // confirmation when its document list is also empty.
                    if (snapshot.isEmpty && snapshot.metadata.isFromCache) return@addSnapshotListener
                    profileSnapshotReceived.set(true)
                    profilesLoadTimeoutJob?.cancel()
                    val snapshotTicket = profileSnapshotGeneration.incrementAndGet()
                    profileSnapshotJob?.cancel()
                    profileSnapshotJob = viewModelScope.launch {
                        val fetchedList = withContext(Dispatchers.IO) {
                            snapshot.documents.mapNotNull { doc ->
                                try {
                                    // Pull favoriteGenres from Firestore as a List<String>
                                    // when present. Firestore stores it as a List<*>
                                    // and we coerce non-String entries to "" so a
                                    // single bad row doesn't take down the whole list.
                                    val rawGenres = doc.get("favoriteGenres") as? List<*> ?: emptyList<Any>()
                                    val genres = rawGenres.mapNotNull { it as? String }
                                    Profile(
                                        id = doc.getString("id") ?: doc.id,
                                        name = doc.getString("name") ?: "Profile",
                                        avatarColor = Color(0xFFE50914),
                                        isKid = doc.getBoolean("isKids") ?: doc.getBoolean("isKid") ?: false,
                                        avatarUrl = doc.getString("avatarUrl")?.takeIf { it.isNotBlank() },
                                        // Both apps write `pin`; an explicit empty value also clears the lock.
                                        pin = (if (doc.contains("pin")) doc.getString("pin") else doc.getString("pinHash"))
                                            ?.takeIf { it.isNotBlank() },
                                        language = doc.getString("language") ?: "English",
                                        autoplayNext = doc.getBoolean("autoplayNext") ?: true,
                                        autoplayPreviews = doc.getBoolean("autoplayPreviews") ?: true,
                                        maturityRating = doc.getString("maturityRating") ?: "18+",
                                        favoriteGenres = genres,
                                        gameHandle = doc.getString("gameHandle")?.takeIf { it.isNotBlank() },
                                        createdAt = doc.getLong("createdAt") ?: 0L,
                                        lastUsedAt = doc.getLong("lastUsedAt") ?: 0L
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        if (!ownsRequest() || profileSnapshotGeneration.get() != snapshotTicket) return@launch
                        if (!snapshot.isEmpty && fetchedList.isEmpty()) {
                            _isProfilesLoading.value = false
                            _profilesLoadError.value = "Profiles could not be read. Please try again."
                            return@launch
                        }
                        setProfilesFromFirestore(fetchedList)
                        _profilesLoadError.value = null
                        _isProfilesLoading.value = false
                        // Serialize off Main. Re-check owner and snapshot just
                        // before writing so stale account/snapshot work is ignored.
                        withContext(Dispatchers.IO) {
                            saveStoredProfiles(fetchedList, targetUid) {
                                ownsRequest() && profileSnapshotGeneration.get() == snapshotTicket
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            profilesLoadTimeoutJob?.cancel()
            _isProfilesLoading.value = false
            _profilesLoadError.value = "Profiles could not be loaded. Please try again."
            android.util.Log.w("NetflixViewModel", "Firestore profile listener unavailable", e)
        }
    }

    // perf: serialize TV email/password sign-ins so a double-tap on the on-screen
    // "Sign In" button doesn't fire two parallel Firebase Auth requests that race
    // on _userSubscription and prefs writes.
    private val signInMutex = kotlinx.coroutines.sync.Mutex()
    private var activeSignInJob: kotlinx.coroutines.Job? = null

    // One-time auth events channel (used as a single source of truth instead of
    // a re-emitted StateFlow that re-fires on every recomposition).
    private val _authEvents = Channel<AuthEvent>(capacity = Channel.BUFFERED)
    val authEvents = _authEvents.receiveAsFlow()

    sealed class AuthEvent {
        data class Success(val email: String) : AuthEvent()
        data class Error(val message: String) : AuthEvent()
    }

    fun signInWithEmail(
        email: String,
        password: String,
        onSuccess: (email: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        // perf: cancel any in-flight sign-in before starting a new one — defends
        // against the user mashing the on-screen "Sign In" button while the
        // network request is still in flight.
        if (activeSignInJob?.isActive == true) {
            return
        }
        val trimmedEmail = email.trim()
        // Don't log the email — it can be considered PII for the user's account.
        activeSignInJob = viewModelScope.launch {
            signInMutex.withLock {
                try {
                    if (FirebaseApp.getApps(getApplication()).isEmpty()) {
                        FirebaseApp.initializeApp(getApplication())
                    }
                    val auth = FirebaseAuth.getInstance()
                    val result = withContext(Dispatchers.IO) {
                        auth.signInWithEmailAndPassword(trimmedEmail, password).await()
                    }
                    val user = result.user
                    if (user != null) {
                        val uid = user.uid

                        // Check subscription plan in Firestore before accepting login
                        var detectedPlanId = ""
                        var detectedPlanName = ""
                        var detectedStatus = ""
                        var expiresAt = 0L

                        withContext(Dispatchers.IO) {
                            try {
                                val db = FirebaseFirestore.getInstance()

                                // 1. Check users/{uid}/subscription/current
                                val subDoc = db.collection("users").document(uid)
                                    .collection("subscription").document("current")
                                    .get().await()
                                if (subDoc.exists()) {
                                    detectedPlanId = subDoc.getString("planId") ?: ""
                                    detectedPlanName = subDoc.getString("planName") ?: ""
                                    detectedStatus = subDoc.getString("status") ?: ""
                                    expiresAt = subDoc.getLong("expiresAt") ?: 0L
                                }

                                // 2. Check users/{uid} root document if not found
                                if (detectedPlanId.isBlank() && detectedPlanName.isBlank()) {
                                    val userDoc = db.collection("users").document(uid).get().await()
                                    if (userDoc.exists()) {
                                        detectedPlanId = userDoc.getString("planId")
                                            ?: userDoc.getString("plan")
                                            ?: userDoc.getString("subscriptionPlan") ?: ""
                                        detectedPlanName = userDoc.getString("planName") ?: ""
                                        detectedStatus = userDoc.getString("status")
                                            ?: userDoc.getString("subscriptionStatus") ?: ""
                                        expiresAt = userDoc.getLong("expiresAt") ?: 0L
                                    }
                                }

                                // 3. Check subscriptions/{uid} if present
                                if (detectedPlanId.isBlank() && detectedPlanName.isBlank()) {
                                    val directSubDoc = db.collection("subscriptions").document(uid).get().await()
                                    if (directSubDoc.exists()) {
                                        detectedPlanId = directSubDoc.getString("planId")
                                            ?: directSubDoc.getString("plan") ?: ""
                                        detectedPlanName = directSubDoc.getString("planName") ?: ""
                                        detectedStatus = directSubDoc.getString("status") ?: ""
                                        expiresAt = directSubDoc.getLong("expiresAt") ?: 0L
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("NetflixViewModel", "Error checking subscription on login: ${e.message}")
                            }
                        }

                        val planIdClean = detectedPlanId.trim().lowercase()
                        val planNameClean = detectedPlanName.trim().lowercase()
                        val isMobilePlan = planIdClean == "plan_mobile" ||
                                planIdClean == "mobile" ||
                                planIdClean.contains("mobile") ||
                                planNameClean.contains("mobile")

                        if (isMobilePlan) {
                            // Mobile plan is restricted to phones/tablets - reject login on TV
                            try {
                                auth.signOut()
                            } catch (_: Exception) {}
                            val msg = "Your account is on the Mobile Plan which does not support TV login. Please upgrade your plan to Basic, Standard, or Premium to sign in on TV."
                            _authEvents.trySend(AuthEvent.Error(msg))
                            onError(msg)
                            return@withLock
                        }

                        // For all other plans (Basic, Standard, Premium, etc.), accept login.
                        // Audit fix: when no plan was found on the server, we
                        // previously forced the user to plan_standard + ACTIVE,
                        // which is a free upgrade. The right thing is to leave
                        // the user in PENDING_ACTIVATION and let the Firestore
                        // subscription listener populate the real plan once it
                        // connects. If a real plan IS reported by the server,
                        // trust it.
                        val finalPlanId = detectedPlanId
                        val finalPlanName = detectedPlanName
                        val finalStatus = detectedStatus.takeUnless {
                            it.isBlank() || it.equals("NONE", ignoreCase = true)
                        } ?: "PENDING_ACTIVATION"

                        val previousUid = prefs.getString("paired_user_id", null)
                        if (!previousUid.isNullOrBlank() && previousUid != uid) {
                            watchHistoryFirestoreListener?.remove()
                            watchHistoryFirestoreListener = null
                            withContext(Dispatchers.IO) {
                                database.continueWatchingDao().clearAll()
                            }
                            _profiles.value = emptyList()
                            _selectedProfile.value = null
                            _myListMovieIds.value = emptySet()
                            _myListAddedAt.value = emptyMap()
                            _watchHistoryMovies.value = emptyList()
                            _likedMovieIds.value = emptySet()
                            _remindedMovieIds.value = emptySet()
                            val editor = prefs.edit()
                            prefs.all.keys.filter {
                                it.startsWith("my_list_movie_ids_profile_") ||
                                    it.startsWith("my_list_added_at_profile_") ||
                                    it.startsWith("my_list_movie_ids_user_${previousUid}_profile_") ||
                                    it.startsWith("my_list_added_at_user_${previousUid}_profile_") ||
                                    it.startsWith("liked_movie_ids_profile_") ||
                                    it.startsWith("reminded_movie_ids_profile_") ||
                                    it == "my_list_movie_ids_account"
                            }.forEach(editor::remove)
                            editor.remove("saved_profiles_json")
                            editor.remove("saved_profiles_owner")
                            editor.remove("last_selected_profile_id")
                            editor.apply()
                        }
                        prefs.edit()
                            .putString("paired_user_id", uid)
                            .putString("user_email", trimmedEmail)
                            .putString("user_plan_id", finalPlanId)
                            .putString("user_plan_name", finalPlanName)
                            .putString("user_plan_status", finalStatus)
                            .putLong("user_plan_expires_at", expiresAt)
                            .apply()

                        val sub = com.example.model.UserSubscription(
                            status = finalStatus,
                            planId = finalPlanId,
                            planName = finalPlanName,
                            expiresAt = expiresAt
                        )
                        _userSubscription.value = sub
                        saveStoredSubscription(sub)

                        ensureProfilesLoaded()
                        listenToFirestoreProfiles(uid)
                        listenToFirestoreSubscription(uid)
                        _authEvents.trySend(AuthEvent.Success(trimmedEmail))
                        onSuccess(trimmedEmail)
                    } else {
                        val msg = "Sign in failed: could not retrieve user."
                        _authEvents.trySend(AuthEvent.Error(msg))
                        onError(msg)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Don't include the raw exception message verbatim — it can
                    // contain pieces of the credential failure path or partial
                    // identifiers. Map known error classes to user-friendly copy.
                    val rawMsg = (e.localizedMessage ?: e.message ?: "Sign in failed")
                    val friendlyMsg = when {
                        rawMsg.contains("password", ignoreCase = true) || rawMsg.contains("credential", ignoreCase = true) ->
                            "Incorrect password. Please try again."
                        rawMsg.contains("user-not-found", ignoreCase = true) || rawMsg.contains("no user", ignoreCase = true) ->
                            "No account found with this email address."
                        rawMsg.contains("network", ignoreCase = true) ->
                            "Network error. Please check your TV connection."
                        rawMsg.contains("too-many-requests", ignoreCase = true) ->
                            "Too many attempts. Please wait a moment and try again."
                        else -> "Sign in failed. Please try again."
                    }
                    // Don't log the raw error — it may include the auth failure
                    // reason chained with the email. Log only the friendly copy.
                    android.util.Log.e("NetflixViewModel", "TV Auth Error: $friendlyMsg")
                    _authEvents.trySend(AuthEvent.Error(friendlyMsg))
                    onError(friendlyMsg)
                }
            }
        }
    }

    fun isUserLoggedInOrGuest(): Boolean =
        prefs.getString("paired_user_id", null) == "guest" || authenticatedUid() != null

    fun isUserLoggedIn(): Boolean = authenticatedUid() != null

    fun ensureProfilesLoaded() {
        // Signed-in profiles come from the listener, never a setup fallback or
        // another account's local list. Guest setup keeps its existing path.
        if (authenticatedUid() != null) return
        if (_profiles.value.isEmpty()) {
            _profiles.value = loadStoredProfiles()
        }
        _isProfilesLoading.value = false
        _profilesLoadError.value = null
    }

    fun signOutFromTv() {
        profileListenerGeneration.incrementAndGet()
        profileSnapshotGeneration.incrementAndGet()
        profileSnapshotJob?.cancel()
        profilesLoadTimeoutJob?.cancel()
        profileSnapshotReceived.set(false)
        _isProfilesLoading.value = false
        _profilesLoadError.value = null
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        // Audit fix (per spec §8): the previous implementation cleared
        // _userSubscription and the local plan prefs at sign-out, which
        // meant the UI blanked out the user's plan on the sign-in screen.
        // That made a TV-side sign-in feel "broken" because the user
        // momentarily lost visibility of their existing plan. The right
        // behaviour: clear account-scoped keys (uid / email), remove the
        // Firestore subscription listener so it can't push the OLD user's
        // plan updates into the NEW user's session, but leave the local
        // subscription prefs in place. The next sign-in will overwrite
        // them, and the UI keeps showing the last known plan until then.
        // Audit fix: tear down every account-scoped Firestore listener.
        // Previously only the subscription listener was removed; the
        // profiles and continue-watching listeners kept firing on the
        // signed-out user, applying stale remote state to an empty
        // local profile list (privacy leak + flickering UI on the next
        // sign-in).
        firestoreSubscriptionListener?.remove()
        firestoreSubscriptionListener = null
        firestoreProfileListener?.remove()
        firestoreProfileListener = null
        continueWatchingFirestoreListener?.remove()
        continueWatchingFirestoreListener = null
        continueWatchingNestedListener?.remove()
        continueWatchingNestedListener = null
        watchHistoryFirestoreListener?.remove()
        watchHistoryFirestoreListener = null
        myListFirestoreListener?.remove()
        myListFirestoreListener = null
        remoteCommandListener?.remove()
        remoteCommandListener = null
        streamHeartbeatJob?.cancel()
        // Offline-first outbox teardown: cancel every queued worker for
        // the previous user, and mark any in-flight / pending rows as
        // `failed` so they don't accidentally re-fire on the next sign-in
        // under a different account. The user can review + manually
        // retry from the sync-status UI.
        try {
            val previousUid = prefs.getString("paired_user_id", null)
            if (!previousUid.isNullOrBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val cancelled = pendingWriteDao.cancelForUser("$previousUid%")
                        if (cancelled > 0) {
                            android.util.Log.d(
                                "PendingWrites",
                                "cancelled $cancelled pending write(s) for signed-out user $previousUid"
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PendingWrites", "cancelForUser failed: ${e.message}")
                    }
                }
            }
            SyncScheduler.cancelAll(getApplication())
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "signOut teardown failed: ${e.message}")
        }
        val oldScope = localAccountScope()
        prefs.all.keys.filter {
            it.startsWith("my_list_movie_ids_user_${oldScope}_profile_") ||
                it.startsWith("my_list_added_at_user_${oldScope}_profile_") ||
                it.startsWith("my_list_movie_ids_profile_") ||
                it.startsWith("my_list_added_at_profile_") ||
                it.startsWith("liked_movie_ids_profile_") ||
                it.startsWith("reminded_movie_ids_profile_") ||
                it == "my_list_movie_ids_account"
        }.let { keys ->
            val editor = prefs.edit()
            keys.forEach(editor::remove)
            editor.apply()
        }
        _myListMovieIds.value = emptySet()
        _myListAddedAt.value = emptyMap()
        _watchHistoryMovies.value = emptyList()
        _likedMovieIds.value = emptySet()
        _remindedMovieIds.value = emptySet()
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                database.continueWatchingDao().clearAll()
            }
        } catch (e: Exception) {
            android.util.Log.w("NetflixViewModel", "Could not clear signed-out viewing cache", e)
        }
        prefs.edit()
            .remove("paired_user_id")
            .remove("user_email")
            .remove("user_plan_id")
            .remove("user_plan_name")
            .remove("user_plan_status")
            .remove("user_plan_expires_at")
            .remove("saved_profiles_json")
            .remove("saved_profiles_owner")
            .remove("last_selected_profile_id")
            .apply()
        _profiles.value = emptyList()
        _selectedProfile.value = null
        _userSubscription.value = com.example.model.UserSubscription()
    }

    private fun syncProfileToFirestore(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    val db = FirebaseFirestore.getInstance()
                    val targetUid = authenticatedUid() ?: return@launch
                    val collectionPath = "users/$targetUid/${Collections.PROFILES}"
                    val docRef = db.collection("users").document(targetUid)
                        .collection(Collections.PROFILES).document(profile.id)
                    // Use the phone's shared hash field, including an explicit clear.
                    val data = hashMapOf<String, Any?>(
                        "id" to profile.id,
                        "name" to profile.name,
                        "avatarUrl" to (profile.avatarUrl ?: ""),
                        "isKids" to profile.isKid,
                        "language" to profile.language,
                        "maturityRating" to profile.maturityRating,
                        "autoplayNext" to profile.autoplayNext,
                        "autoplayPreviews" to profile.autoplayPreviews,
                        "favoriteGenres" to profile.favoriteGenres,
                        "gameHandle" to (profile.gameHandle ?: ""),
                        "lastUsedAt" to profile.lastUsedAt,
                        "createdAt" to profile.createdAt,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    data["pin"] = hashPin(profile.pin) ?: ""
                    // Retire the older alias so it cannot revive a changed/removed PIN.
                    data["pinHash"] = ""
                    // Apply per-field conflict policy. CLIENT_WINS fields
                    // pass through unchanged; SERVER_ONLY fields (id,
                    // createdAt) are stripped so a tampered client can't
                    // rewrite them; SERVER_WINS fields (lastUsedAt) are
                    // logged so a misuse is visible in logcat.
                    val filtered = ConflictResolver.filterWritable(Collections.PROFILES, data)
                    if (filtered.isEmpty()) {
                        android.util.Log.d(
                            "NetflixViewModel",
                            "syncProfileToFirestore: all fields filtered by policy; skipping write for profile ${profile.id}"
                        )
                        return@launch
                    }
                    // Offline-first outbox: durably record the write intent
                    // in `pending_writes` BEFORE attempting the direct
                    // Firestore call. If the direct call succeeds, we mark
                    // the row `done` inline; if it fails (or the device
                    // loses power mid-call), the PendingWriteRetryWorker
                    // picks it up on the next retry per the exponential
                    // backoff schedule. The payload uses a sentinel map
                    // for the serverTimestamp field — the worker unwraps
                    // {"__serverTimestamp": true} back into
                    // FieldValue.serverTimestamp() on commit.
                    val outboxPayload = filtered.mapValues { (_, v) ->
                        if (v is FieldValue) mapOf("__serverTimestamp" to true) else v
                    }
                    val rowId = SyncScheduler.enqueueWrite(
                        context = getApplication(),
                        dao = pendingWriteDao,
                        collection = collectionPath,
                        documentId = profile.id,
                        operation = com.example.data.PendingWrite.OP_UPDATE,
                        payload = SyncScheduler.encodePayload(outboxPayload)
                    )
                    try {
                        docRef.set(filtered, SetOptions.merge()).await()
                        pendingWriteDao.markDone(rowId, System.currentTimeMillis())
                    } catch (directErr: Exception) {
                        android.util.Log.w(
                            "NetflixViewModel",
                            "Direct profile sync failed, will retry via outbox row=$rowId: ${directErr.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetflixViewModel", "Error syncing profile to Firestore: ${e.message}")
            }
        }
    }

    fun addProfile(
        name: String,
        avatarUrl: String?,
        avatarColor: Color = Color(0xFFE50914),
        isKid: Boolean = false,
        pin: String? = null,
        language: String = "English",
        maturityRating: String = if (isKid) "7+" else "18+",
        favoriteGenres: List<String> = emptyList(),
        gameHandle: String? = null
    ): Profile {
        // Enforce per-subscription max-profile cap. The UI hides the "Add
        // Profile" tile when at the cap, but a programmatic caller (e.g.
        // walkthrough step 4) can still bypass that; this guard prevents the
        // user from going over their plan limit regardless of entry point.
        val maxAllowed = _userSubscription.value.maxProfiles
        if (_profiles.value.size >= maxAllowed) {
            throw IllegalStateException("Profile limit reached ($maxAllowed). Upgrade your plan to add more profiles.")
        }

        // Idempotency: if the same id is already in the list, return it
        // unchanged. This guards against the walkthrough re-firing the
        // auto-completion on a config change after addProfile succeeded.
        // We don't know the id yet (we're about to generate one) so we use
        // a sentinel below.

        // Generate a non-colliding id. The previous implementation used
        // `(System.currentTimeMillis() % 100000).toString()` which could collide
        // when addProfile is called twice in the same millisecond, or after
        // process restart. Take 5 random digits and verify uniqueness against
        // the current list; if the random id collides, append a counter.
        val existingIds = _profiles.value.map { it.id }.toHashSet()
        var newId: String
        var attempt = 0
        do {
            val rnd = (Math.random() * 100000).toInt().coerceAtLeast(1)
            newId = if (attempt == 0) rnd.toString() else "${rnd}_$attempt"
            attempt++
        } while (newId in existingIds && attempt < 100)
        if (newId in existingIds) {
            // Final fallback: timestamp + counter (practically unreachable).
            newId = "${System.currentTimeMillis()}_${existingIds.size}"
        }

        // Audit fix: disambiguate duplicate names with " (n)" suffix. The
        // original code allowed two profiles with the same display name,
        // which confuses the home screen ("Who's watching? Home" twice).
        // We pick a free suffix; if " (1)" is already taken we try " (2)",
        // etc. The unique name is what gets written to disk + Firestore.
        val finalName = resolveUniqueName(name)

        // Hash the PIN before persisting. Plaintext 4-digit PINs in
        // SharedPreferences and Firestore were a security hole: an
        // attacker with `adb backup` or a sloppy Firestore rule could
        // read every profile PIN. See [hashPin].
        val storedPin = hashPin(pin)

        val nowMs = System.currentTimeMillis()
        val newProfile = Profile(
            id = newId,
            name = finalName,
            avatarColor = avatarColor,
            isKid = isKid,
            avatarUrl = avatarUrl ?: ProfileIcons.ICONS.firstOrNull(),
            pin = storedPin,
            language = language,
            autoplayNext = true,
            autoplayPreviews = true,
            maturityRating = maturityRating,
            favoriteGenres = favoriteGenres,
            gameHandle = gameHandle,
            createdAt = nowMs,
            lastUsedAt = nowMs
        )
        val updated = _profiles.value + newProfile
        _profiles.value = updated
        saveStoredProfiles(updated)
        syncProfileToFirestore(newProfile)
        return newProfile
    }

    /**
     * Return a name that does not collide with any existing profile. If
     * [desired] is already free, it is returned unchanged. Otherwise we
     * append " (n)" with the smallest n that produces a free value.
     */
    private fun resolveUniqueName(desired: String, excludingId: String? = null): String {
        val existing = _profiles.value.asSequence()
            .filter { it.id != excludingId }
            .map { it.name.lowercase() }.toMutableSet()
        if (desired.lowercase() !in existing) return desired
        var counter = 1
        while (true) {
            val candidate = "$desired ($counter)"
            if (candidate.lowercase() !in existing) return candidate
            counter++
            if (counter > 100) {
                // Practically unreachable (100 collisions is enough for any
                // household), but we don't want a tight loop on a corrupt
                // state. Fall back to timestamp.
                return "$desired (${System.currentTimeMillis() % 1_000_000})"
            }
        }
    }

    fun updateProfile(updatedProfile: Profile): Boolean {
        if (_profiles.value.none { it.id == updatedProfile.id }) return false
        // Null clears the PIN; an existing digest is preserved by hashPin.
        val stored = updatedProfile.copy(
            name = resolveUniqueName(updatedProfile.name, updatedProfile.id),
            pin = updatedProfile.pin?.let { hashPin(it) ?: it }
        )
        val updatedList = _profiles.value.map {
            if (it.id == stored.id) stored else it
        }
        _profiles.value = updatedList
        saveStoredProfiles(updatedList)
        syncProfileToFirestore(stored)
        if (_selectedProfile.value?.id == stored.id) {
            _selectedProfile.value = stored
            applyProfilePersonalization(stored)
        }
        return true
    }

    fun deleteProfile(profileId: String): Boolean {
        // Refuse to delete the last profile — would leave the user with no
        // way to sign in to the app. The caller (EditProfileScreen) already
        // hides the Delete button when profiles.size == 1, but a programmatic
        // caller (e.g. a future bulk-remove flow) must not be able to bypass
        // the cap.
        if (_profiles.value.size <= 1) return false
        // Idempotent: if the profile is already gone, treat as success without
        // re-firing Firestore + IO writes (which would be wasteful on a duplicate
        // call from a race between confirm-tap and a previous async delete).
        if (_profiles.value.none { it.id == profileId }) return true

        val isCurrentlySelected = _selectedProfile.value?.id == profileId
        if (isCurrentlySelected) {
            // Guard: don't let the user lock themselves out by deleting the
            // active profile. The UI flow auto-selects another profile before
            // reaching here, but a stale call from a different surface should
            // be rejected so we never reach an "active profile does not exist"
            // state.
            val otherProfile = _profiles.value.firstOrNull { it.id != profileId }
            if (otherProfile == null) return false
            // Pre-emptively select the other profile so the home screen
            // doesn't briefly show no profile.
            selectProfile(otherProfile)
        }

        val updatedList = _profiles.value.filter { it.id != profileId }
        _profiles.value = updatedList
        saveStoredProfiles(updatedList)

        // Clear profile-scoped local list and viewing data.
        prefs.edit()
            .remove(myListIdsKey(profileId))
            .remove(myListTimestampsKey(profileId))
            .apply()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                continueWatchingRepository.deleteForProfile(profileId)
            } catch (e: Exception) {
                android.util.Log.e("NetflixViewModel", "deleteForProfile failed", e)
            }
            // Audit fix: move the Firestore profile delete off the main
            // thread and await the result so failures are visible in
            // logcat (previously the bare `.delete()` could throw a
            // network exception synchronously and be silently dropped by
            // the `catch (_: Exception) {}`).
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    val db = FirebaseFirestore.getInstance()
                    val targetUid = authenticatedUid() ?: return@launch
                    // Policy gate: a profile delete is a row-level
                    // SERVER_ONLY operation — clients should not be able
                    // to delete a profile, only the server-side cleanup
                    // job (after a subscription cancel, say) should. The
                    // Firestore security rules are the primary gate; this
                    // log line is the in-app audit trail so a misconfigured
                    // rule is visible.
                    android.util.Log.d(
                        "Sync",
                        "policy=SERVER_ONLY_OP op=delete collection=profiles doc=$profileId"
                    )
                    val profileDocRef = db.collection("users").document(targetUid)
                        .collection(Collections.PROFILES).document(profileId)
                    // Offline-first outbox: enqueue a delete row so the
                    // worker replays the delete if the direct call fails
                    // (or the app is force-killed between call and ack).
                    val deleteRowId = SyncScheduler.enqueueWrite(
                        context = getApplication(),
                        dao = pendingWriteDao,
                        collection = "users/$targetUid/${Collections.PROFILES}",
                        documentId = profileId,
                        operation = com.example.data.PendingWrite.OP_DELETE,
                        payload = null
                    )
                    try {
                        profileDocRef.delete().await()
                        pendingWriteDao.markDone(deleteRowId, System.currentTimeMillis())
                    } catch (directErr: Exception) {
                        android.util.Log.w(
                            "NetflixViewModel",
                            "Direct profile delete failed, will retry via outbox row=$deleteRowId: ${directErr.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NetflixViewModel", "Firestore profile delete failed: ${e.message}")
            }
        }
        prefs.edit()
            .remove("liked_movie_ids_profile_$profileId")
            .remove("reminded_movie_ids_profile_$profileId")
            .remove("subtitle_language_profile_$profileId")
            .apply()

        // If the deleted profile was the lastSelectedProfileId, clear it so
        // the next launch doesn't try to re-select a non-existent id.
        if (prefs.getString("last_selected_profile_id", null) == profileId) {
            prefs.edit().remove("last_selected_profile_id").apply()
        }

        return true
    }

    fun updateProfileAvatar(profileId: String, newAvatarUrl: String) {
        val profile = _profiles.value.find { it.id == profileId }
        if (profile != null) {
            updateProfile(profile.copy(avatarUrl = newAvatarUrl))
        }
    }

    fun setProfilePin(profileId: String, pin: String?) {
        val profile = _profiles.value.find { it.id == profileId } ?: return
        // Hash before storage. Blank input means "clear the PIN".
        val storedPin = pin?.takeIf { it.isNotBlank() }?.let { hashPin(it) }
        updateProfile(profile.copy(pin = storedPin))
    }

    fun removeProfilePin(profileId: String) {
        setProfilePin(profileId, null)
    }

    /**
     * Verify an entered PIN against the profile's stored PIN hash.
     *
     * The previous implementation was a direct string compare, which only
     * works for the legacy plaintext format. New writes go through
     * [hashPin] and store the SHA-256 digest, so we must hash the entered
     * value and compare against the stored digest. We also fall back to
     * the legacy plaintext compare for backward-compat with profiles
     * created before the hashing was added (so existing installs don't
     * get locked out after the upgrade).
     */
    fun verifyPin(profileId: String, enteredPin: String): Boolean {
        val profile = _profiles.value.find { it.id == profileId } ?: return false
        return matchesProfilePin(profile.pin, enteredPin)
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // perf: keep this internal — only the Billboard should write here. External mutability
    // was a foot-gun (any caller could fire recompositions of MainActivity's collector).
    // The exposed property delegates to a backing MutableStateFlow so existing
    // .value writes from Billboard.kt keep compiling.
    private val _isBillboardPlaying = MutableStateFlow(false)
    val isBillboardPlaying: kotlinx.coroutines.flow.MutableStateFlow<Boolean> get() = _isBillboardPlaying

    private val _categoryRows = MutableStateFlow<List<Pair<String, List<Movie>>>>(emptyList())
    val categoryRows: StateFlow<List<Pair<String, List<Movie>>>> = _categoryRows.asStateFlow()

    private val _currentMovie = MutableStateFlow<Movie?>(null)
    val currentMovie: StateFlow<Movie?> = _currentMovie.asStateFlow()
    private var currentMovieProfileId: String? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Movie>>(emptyList())
    val searchResults: StateFlow<List<Movie>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _genres = MutableStateFlow<List<com.example.api.TmdbGenreDto>>(emptyList())
    val genres: StateFlow<List<com.example.api.TmdbGenreDto>> = _genres.asStateFlow()

    private val _selectedGenreId = MutableStateFlow<Long?>(null)
    val selectedGenreId: StateFlow<Long?> = _selectedGenreId.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    private val allMoviesMap = java.util.concurrent.ConcurrentHashMap<String, Movie>()
    private val personalizationGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    // perf: default to a Guest plan to avoid a SharedPreferences disk read in the constructor.
    // The actual cached plan is loaded on the IO dispatcher in init { }.
    private val _userSubscription = MutableStateFlow(com.example.model.UserSubscription())
    private val subscriptionCloudReceived = java.util.concurrent.atomic.AtomicBoolean(false)
    val userSubscription: StateFlow<com.example.model.UserSubscription> = _userSubscription.asStateFlow()

    private val catalogLoadInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val catalogStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var originalCategoryRows: List<Pair<String, List<Movie>>> = emptyList()

    fun markHomeReady() = com.example.ui.util.HomeStartupGate.markHomeReady()
    fun markHomeHidden() {
        com.example.ui.util.HomeStartupGate.markHomeHidden()
        synchronized(warmupJobLock) {
            // Preserve an active cookie handshake for a possible Play request.
            // A job still waiting for Home to settle can be restarted immediately.
            if (!warmupIsImmediate &&
                !(warmupHasStartedAttempt && directCDNResolver.isSessionGenerationInProgress())) {
                warmupJob?.cancel()
                warmupJob = null
                warmupHasStartedAttempt = false
            }
        }
    }
    fun setHomeScrollInProgress(scrolling: Boolean) = com.example.ui.util.HomeStartupGate.setScrolling(scrolling)
    fun onHomeInteraction() = com.example.ui.util.HomeStartupGate.onInteraction()
    suspend fun awaitHomeIdle() = com.example.ui.util.HomeStartupGate.awaitIdle()
    suspend fun awaitBrowsingIdle() = com.example.ui.util.HomeStartupGate.awaitBrowsingIdle()

    init {
        // Wire the user-subscription clock so tests can swap a fake clock
        // without rewiring every call site that compares against
        // System.currentTimeMillis(). Production uses the real wall clock.
        com.example.model.UserSubscription.clock = { System.currentTimeMillis() }
        viewModelScope.launch {
            _userSubscription.collectLatest { subscription ->
                if (subscription.isActive) {
                    while (subscription.isActive) {
                        delay((subscription.expiresAt - System.currentTimeMillis()).coerceIn(1L, 60_000L))
                    }
                    if (_userSubscription.value == subscription) {
                        val expired = subscription.copy(status = "EXPIRED")
                        _userSubscription.value = expired
                        saveStoredSubscription(expired)
                    }
                } else {
                    if (sharedExoPlayerDelegate.isInitialized() &&
                        sharedExoPlayer.currentMediaItem?.mediaId?.endsWith(":trailer") == false) {
                        sharedExoPlayer.stop()
                        sharedExoPlayer.clearMediaItems()
                    }
                    if (subscription.status.equals("ACTIVE", true)) {
                        val expired = subscription.copy(status = "EXPIRED")
                        _userSubscription.value = expired
                        saveStoredSubscription(expired)
                    }
                }
            }
        }
        // perf: load stored profiles and subscription off the main thread.
        // Was synchronous in MutableStateFlow(...) defaults.
        viewModelScope.launch(Dispatchers.IO) {
            val startupScope = localAccountScope()
            val startupUid = authenticatedUid()
            val loaded = loadStoredProfiles(startupScope)
            val sub = loadStoredSubscription()
            withContext(Dispatchers.Main) {
                if (localAccountScope() == startupScope && !profileSnapshotReceived.get() &&
                    _profiles.value.isEmpty() && loaded.isNotEmpty()) {
                    _profiles.value = loaded
                }
                if (authenticatedUid() == startupUid && !subscriptionCloudReceived.get() &&
                    (sub.planId != "plan_guest" || sub.status != "NONE")) {
                    _userSubscription.value = sub
                }
            }
            // Profile selection (including a saved profile's PIN) belongs to
            // the picker; reading a cache must not select a profile itself.
        }
        // A new viewer needs the sign-in keyboard before catalog JSON parsing.
        // Existing sessions can still warm the catalog during their splash.
        if (isUserLoggedInOrGuest()) ensureCatalogStarted()
        listenToFirestoreProfiles()
        listenToFirestoreSubscription()
        // Playback resolves its session on demand. Starting Chromium/session generation
        // from a cold-start timer competes with the first Home scroll even on 2 GB TVs.
    }
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _selectedGenreId.value = null
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            val rawResults = repository.searchMulti(query)
            val results = if (_selectedProfile.value?.isKid == true) {
                rawResults.filter { com.example.model.isKidSafeMovie(it) }
            } else {
                rawResults
            }
            results.forEach { allMoviesMap[it.id] = it }
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun selectGenre(genreId: Long) {
        _selectedGenreId.value = genreId
        _searchQuery.value = ""
        searchJob?.cancel()
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            val rawResults = repository.discoverByGenre(genreId)
            val results = if (_selectedProfile.value?.isKid == true) {
                rawResults.filter { com.example.model.isKidSafeMovie(it) }
            } else {
                rawResults
            }
            results.forEach { allMoviesMap[it.id] = it }
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun hasValidStreamSession(): Boolean = directCDNResolver.hasValidSession()

    private fun canWarmStreamSession(): Boolean = authenticatedUid() != null &&
        _selectedProfile.value != null && _userSubscription.value.isTvAllowed

    suspend fun ensureStreamWarmedAsync(): Boolean =
        canWarmStreamSession() && directCDNResolver.ensureSessionWarm()

    private val warmupJobLock = Any()
    @Volatile private var warmupJob: kotlinx.coroutines.Job? = null
    private var warmupIsImmediate = false
    private var warmupHasStartedAttempt = false
    fun ensureStreamWarmed(immediate: Boolean = false) {
        if (!canWarmStreamSession()) return
        synchronized(warmupJobLock) {
            val pending = warmupJob?.takeIf { it.isActive }
            if (pending != null) {
                if (!immediate || warmupIsImmediate) return
                if (warmupHasStartedAttempt && directCDNResolver.isSessionGenerationInProgress()) {
                    // Reuse the server postback already in flight; restarting it
                    // would cost the user another 35 seconds.
                    warmupIsImmediate = true
                    return
                }
                // A foreground request must not wait for Home's idle gate.
                pending.cancel()
            }
            warmupIsImmediate = immediate
            warmupHasStartedAttempt = false
            val requestedAt = com.example.ui.util.RuntimeTiming.start()
            warmupJob = viewModelScope.launch(Dispatchers.IO) {
                val ownJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                try {
                    suspend fun warmOnce(): Boolean {
                        if (!canWarmStreamSession()) return false
                        val resolver = directCDNResolver
                        val mayRun = synchronized(warmupJobLock) {
                            val current = warmupJob === ownJob && ownJob?.isActive == true
                            if (current) warmupHasStartedAttempt = true
                            current
                        }
                        if (!mayRun) return false
                        val attemptStartedAt = com.example.ui.util.RuntimeTiming.start()
                        try {
                            val warmed = resolver.ensureSessionWarm()
                            com.example.ui.util.RuntimeTiming.elapsed(
                                if (warmed) "warmup_native_verified" else "warmup_native_incomplete", attemptStartedAt
                            )
                            return warmed
                        } finally {
                            com.example.ui.util.RuntimeTiming.elapsed("warmup_native_finished", attemptStartedAt)
                            synchronized(warmupJobLock) {
                                if (warmupJob === ownJob) warmupHasStartedAttempt = false
                            }
                        }
                    }
                    if (!immediate) {
                        // Keep session I/O after the first Home frame and away from D-pad scrolling.
                        kotlinx.coroutines.delay(3_000L)
                        awaitHomeIdle()
                    }
                    if (directCDNResolver.isSessionRecentlyVerified()) return@launch
                    com.example.ui.util.RuntimeTiming.elapsed("warmup_requested_to_start", requestedAt)
                    if (warmOnce()) {
                        return@launch
                    }
                    // A Details/Play attempt has a bounded foreground budget.
                    // Only Home's quiet background job should retry later.
                    if (!canWarmStreamSession() || synchronized(warmupJobLock) { warmupIsImmediate }) return@launch
                    var retryDelayMs = 60_000L
                    while (isActive && canWarmStreamSession()) {
                        kotlinx.coroutines.delay(retryDelayMs)
                        if (!synchronized(warmupJobLock) { warmupIsImmediate }) awaitHomeIdle()
                        if (warmOnce()) {
                            return@launch
                        }
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(10 * 60_000L)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    android.util.Log.w("NetflixViewModel", "Background playback warmup failed", e)
                } finally {
                    synchronized(warmupJobLock) {
                        if (warmupJob === ownJob) {
                            warmupJob = null
                            warmupIsImmediate = false
                            warmupHasStartedAttempt = false
                        }
                    }
                }
            }
        }
    }


    fun ensureCatalogStarted() {
        if (catalogStarted.compareAndSet(false, true)) loadData()
    }

    fun loadData() {
        // A refresh must join the current load rather than double network, JSON and row work.
        if (!catalogLoadInProgress.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            com.example.ui.util.AppDiagnosticsLogger.event("Catalog", "Starting catalogue load...")
            _isLoading.value = _categoryRows.value.isEmpty()
            try {
                // Bound simultaneous JSON parsing during cold start; publish the four
                // initial rails together so focus/hero selection does not churn on entry.
                val (trending, popularMovies, popularTv, topRatedMovies) = coroutineScope {
                    val permits = Semaphore(
                        if (com.example.ui.util.TvImagePolicy.isLowMemoryDevice(getApplication())) 1 else 2
                    )
                    suspend fun core(fetch: suspend () -> List<Movie>): List<Movie> = permits.withPermit {
                        fetch().also { currentCoroutineContext().ensureActive() }
                    }
                    val trendingDef = async { core { repository.getTrending(fetchLogos = false) } }
                    val popMoviesDef = async { core { repository.getPopularMovies(fetchLogos = false) } }
                    val popTvDef = async { core { repository.getPopularTvShows(fetchLogos = false) } }
                    val topRatedDef = async { core { repository.getTopRatedMovies(fetchLogos = false) } }
                    listOf(trendingDef.await(), popMoviesDef.await(), popTvDef.await(), topRatedDef.await())
                }
                val coreTime = System.currentTimeMillis() - startTime
                com.example.ui.util.AppDiagnosticsLogger.performance("InitialCatalogCore", coreTime, 1200L, "First-paint rails (trending, popular, etc.) assembled")

                val earlyRows = mutableListOf<Pair<String, List<Movie>>>()
                if (trending.isNotEmpty()) earlyRows.add("Trending Now" to trending)
                if (popularMovies.isNotEmpty()) earlyRows.add("Popular Movies" to popularMovies)
                if (popularTv.isNotEmpty()) earlyRows.add("Binge-Worthy TV Series" to popularTv)
                if (topRatedMovies.isNotEmpty()) earlyRows.add("Top 10 Blockbusters" to topRatedMovies)

                if (earlyRows.isNotEmpty()) {
                    earlyRows.forEach { (_, movies) -> movies.forEach { allMoviesMap[it.id] = it } }
                    originalCategoryRows = earlyRows
                    _selectedProfile.value?.let { currentProf ->
                        applyProfilePersonalization(currentProf, preserveCurrentMovie = true)
                    } ?: run {
                        _categoryRows.value = earlyRows
                        if (_currentMovie.value == null) {
                            val firstMovie = earlyRows.first().second.firstOrNull()
                            _currentMovie.value = firstMovie
                            firstMovie?.let { fetchLogoForMovie(it) }
                        }
                    }
                }
                _isLoading.value = false

                // No wall-clock timer: Home reports its first rendered content and input
                // keeps postponing optional work. When all core calls failed, discovery
                // must still be able to recover an empty Home instead of waiting forever.
                suspend fun awaitDiscoveryIdle() {
                    if (earlyRows.isNotEmpty()) awaitHomeIdle() else awaitBrowsingIdle()
                }
                suspend fun discover(fetch: suspend () -> List<Movie>): List<Movie> {
                    awaitDiscoveryIdle()
                    return fetch().also { currentCoroutineContext().ensureActive() }
                }

                awaitDiscoveryIdle()
                _genres.value = repository.getGenres()
                currentCoroutineContext().ensureActive()

                // One response at a time. Recheck interaction before EVERY request, not
                // just before starting a batch that then competes with the next scroll.
                val action = discover { repository.getActionMovies() }
                val sciFi = discover { repository.getSciFiMovies() }
                val crime = discover { repository.getCrimeThrillers() }
                val anime = discover { repository.getAnime() }
                val comedy = discover { repository.getComedies() }
                val drama = discover { repository.getDramas() }
                val horror = discover { repository.getHorror() }
                val award = discover { repository.getAwardWinning() }
                val doc = discover { repository.getDocumentaries() }
                val nowPlaying = discover { repository.getNowPlayingMovies(fetchLogos = false) }
                val topRatedTv = discover { repository.getTopRatedTvShows(fetchLogos = false) }

                awaitDiscoveryIdle()
                val seenIds = mutableSetOf<String>()
                fun filterDistinct(list: List<Movie>, minCount: Int = 6): List<Movie> {
                    val fresh = list.filter { !seenIds.contains(it.id) }
                    val result = if (fresh.size >= minCount) fresh else list.distinctBy { it.id }
                    result.take(15).forEach { seenIds.add(it.id) }
                    return result
                }

                val fullRows = mutableListOf<Pair<String, List<Movie>>>()
                if (trending.isNotEmpty()) fullRows.add("Trending Now" to filterDistinct(trending, 8))
                if (popularTv.isNotEmpty()) fullRows.add("Binge-Worthy TV Series" to filterDistinct(popularTv, 8))
                if (action.isNotEmpty()) fullRows.add("Action & Adrenaline Blockbusters" to filterDistinct(action, 8))
                if (topRatedMovies.isNotEmpty()) fullRows.add("Top 10 Blockbusters" to filterDistinct(topRatedMovies, 8))
                if (sciFi.isNotEmpty()) fullRows.add("Sci-Fi & Cyberpunk Epics" to filterDistinct(sciFi, 8))
                if (crime.isNotEmpty()) fullRows.add("Crime Thrillers & Mysteries" to filterDistinct(crime, 8))
                if (award.isNotEmpty()) fullRows.add("Award-Winning & Critically Acclaimed" to filterDistinct(award, 8))
                if (anime.isNotEmpty()) fullRows.add("Anime Hits & Animation" to filterDistinct(anime, 8))
                if (comedy.isNotEmpty()) fullRows.add("Feel-Good & Comedies" to filterDistinct(comedy, 8))
                if (nowPlaying.isNotEmpty()) fullRows.add("Now in Theaters & Fresh Releases" to filterDistinct(nowPlaying, 8))
                if (drama.isNotEmpty()) fullRows.add("Emotional & Deep Dramas" to filterDistinct(drama, 8))
                if (horror.isNotEmpty()) fullRows.add("Late Night Thrillers & Horror" to filterDistinct(horror, 8))
                if (topRatedTv.isNotEmpty()) fullRows.add("All-Time Masterpiece TV Series" to filterDistinct(topRatedTv, 8))
                if (doc.isNotEmpty()) fullRows.add("Gripping Documentaries" to filterDistinct(doc, 8))

                if (fullRows.isNotEmpty()) {
                    fullRows.forEach { (_, movies) -> movies.forEach { allMoviesMap[it.id] = it } }
                    originalCategoryRows = fullRows
                    _selectedProfile.value?.let { currentProf ->
                        applyProfilePersonalization(currentProf, preserveCurrentMovie = true)
                    } ?: run {
                        _categoryRows.value = fullRows
                        if (_currentMovie.value == null) {
                            val firstMovie = fullRows.first().second.firstOrNull()
                            _currentMovie.value = firstMovie
                            firstMovie?.let { fetchLogoForMovie(it) }
                        }
                    }
                }

                val kidsMovies = discover { repository.getKidsMovies() }
                val kidsTv = discover { repository.getKidsTvShows() }
                kidsMovies.forEach { allMoviesMap[it.id] = it }
                kidsTv.forEach { allMoviesMap[it.id] = it }
                _selectedProfile.value?.takeIf { it.isKid }?.let {
                    awaitDiscoveryIdle()
                    applyProfilePersonalization(it, preserveCurrentMovie = true)
                }

                // Maintenance touches SQLite and the TV provider; keep it out of the
                // first layout and recheck after catalogue publication has settled.
                awaitDiscoveryIdle()
                try {
                    continueWatchingRepository.purgeStaleEntries(30L * 24L * 60L * 60L * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("NetflixViewModel", "Continue-watching cleanup failed", e)
                }
                awaitDiscoveryIdle()
                val channelMovies = fullRows.asSequence().flatMap { it.second.asSequence() }.distinctBy { it.id }.toList()
                if (channelMovies.isNotEmpty()) {
                    com.example.tv.TvHomeChannelManager.publishChannelPrograms(getApplication(), channelMovies)
                }
                com.example.tv.TvHomeChannelManager.migrateLegacyWatchNextLinks(getApplication())
                val totalTime = System.currentTimeMillis() - startTime
                com.example.ui.util.AppDiagnosticsLogger.performance("FullCatalogBuild", totalTime, 4000L, "Full discovery grid with ${fullRows.size} rails finalized")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.example.ui.util.AppDiagnosticsLogger.error("Catalog", "Catalogue loading failed", e)
                android.util.Log.e("NetflixViewModel", "Catalogue loading failed", e)
            } finally {
                _isLoading.value = false
                catalogLoadInProgress.set(false)
            }
        }
    }
    fun selectProfile(profile: Profile) {
        stopHomePreviews()
        // Audit fix: gracefully handle a stale id (profile was deleted out
        // from under us, or a Firestore snapshot referenced a profile we no
        // longer have). Falling back to the first profile keeps the user
        // out of the "no profile selected" dead-end.
        val resolved = profile.takeIf { p -> _profiles.value.any { it.id == p.id } }
            ?: _profiles.value.firstOrNull()
            ?: return
        _selectedProfile.value = resolved

        // Persist the last-selected id so the next cold start can pre-load
        // the right profile without waiting on Firestore. The value is
        // intentionally scoped to "last user-picked" — the home screen
        // reflects this on launch (see MainActivity).
        try {
            prefs.edit().putString("last_selected_profile_id", resolved.id).apply()
        } catch (_: Exception) {}

        // Update lastUsedAt locally + (in the background) on Firestore.
        // We don't block the UI on the network call.
        val nowMs = System.currentTimeMillis()
        if (resolved.lastUsedAt != nowMs) {
            val updatedList = _profiles.value.map {
                if (it.id == resolved.id) it.copy(lastUsedAt = nowMs) else it
            }
            _profiles.value = updatedList
            saveStoredProfiles(updatedList)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    val db = FirebaseFirestore.getInstance()
                    val targetUid = authenticatedUid() ?: return@launch
                    // Policy check: `lastUsedAt` is SERVER_WINS — the
                    // server's clock is authoritative, so a client-side
                    // write of `nowMs` will be re-stamped on the next
                    // server-side heartbeat. The policy check makes that
                    // contract visible in the code.
                    val policy = ConflictResolver.policyFor(Collections.PROFILES, "lastUsedAt")
                    android.util.Log.d(
                        "Sync",
                        "policy=$policy field=lastUsedAt profileId=${resolved.id} localValue=$nowMs"
                    )
                    if (policy == ConflictPolicy.SERVER_ONLY) {
                        // Defensive: if a future policy edit reclassifies
                        // this field, skip the write entirely.
                        return@launch
                    }
                    val profileDocRef = db.collection("users").document(targetUid)
                        .collection(Collections.PROFILES)
                        .document(resolved.id)
                    // Offline-first outbox: enqueue the lastUsedAt update
                    // so a flaky network on profile switch doesn't lose
                    // the "most recently used" signal. Field is SERVER_WINS
                    // so the server will re-stamp the value on its own
                    // heartbeat; this is best-effort. The outbox row uses
                    // the `pin` -> pinHash key alias if a future schema
                    // migration lands; today's key is `lastUsedAt`.
                    val lastUsedRowId = SyncScheduler.enqueueWrite(
                        context = getApplication(),
                        dao = pendingWriteDao,
                        collection = "users/$targetUid/${Collections.PROFILES}",
                        documentId = resolved.id,
                        operation = com.example.data.PendingWrite.OP_UPDATE,
                        payload = SyncScheduler.encodePayload(
                            mapOf("lastUsedAt" to nowMs)
                        )
                    )
                    try {
                        profileDocRef.update("lastUsedAt", nowMs).await()
                        pendingWriteDao.markDone(lastUsedRowId, System.currentTimeMillis())
                    } catch (directErr: Exception) {
                        android.util.Log.w(
                            "NetflixViewModel",
                            "Direct lastUsedAt sync failed, will retry via outbox row=$lastUsedRowId: ${directErr.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NetflixViewModel", "Failed to update lastUsedAt for ${resolved.id}: ${e.message}")
            }
        }

        listenToContinueWatchingFromFirestore(resolved.id)
        listenToWatchHistoryFromFirestore(resolved.id)

        // Sync My List (account-shared; see [loadMyList]).
        val savedList = loadMyList()
        _myListMovieIds.value = savedList
        // bugfix: also hydrate the per-movie addedAt map; previously a profile
        // switch would leave _myListAddedAt at its old profile's values,
        // making sortByMyListMembership() return the wrong order until the
        // user toggled an item.
        val savedAddedAt = loadMyListAddedAt(resolved.id)
        _myListAddedAt.value = if (savedAddedAt.isNotEmpty()) {
            // Drop any orphans — keys in addedAt that aren't in savedList —
            // so the timestamp map stays in lock-step with the id set.
            savedAddedAt.filterKeys { it in savedList }
        } else {
            savedList.associateWith { System.currentTimeMillis() }
        }
        listenToMyListFromFirestore(resolved.id)

        // Sync profile-specific Liked titles
        val savedLiked = prefs.getStringSet("liked_movie_ids_profile_${resolved.id}", emptySet()) ?: emptySet()
        _likedMovieIds.value = savedLiked

        // Sync profile-specific Reminded titles
        val savedReminded = prefs.getStringSet("reminded_movie_ids_profile_${resolved.id}", emptySet()) ?: emptySet()
        _remindedMovieIds.value = savedReminded

        // Sync profile subtitle language
        val subLang = prefs.getString("subtitle_language_profile_${resolved.id}", resolved.language) ?: resolved.language
        _selectedSubtitleLanguage.value = subLang

        applyProfilePersonalization(resolved)
    }

    private fun applyProfilePersonalization(profile: Profile, preserveCurrentMovie: Boolean = false) {
        viewModelScope.launch {
            if (_selectedProfile.value?.id != profile.id) return@launch
            val generation = personalizationGeneration.incrementAndGet()
            val baseRows = originalCategoryRows
            suspend fun publish(rows: List<Pair<String, List<Movie>>>, defaultMovie: Movie?) {
                withContext(Dispatchers.Main) {
                    // A later profile/catalogue request may finish before this one.
                    if (personalizationGeneration.get() != generation ||
                        _selectedProfile.value?.id != profile.id) return@withContext
                    _categoryRows.value = rows
                    val current = _currentMovie.value
                    val canKeepCurrent = preserveCurrentMovie && currentMovieProfileId == profile.id && current != null &&
                        (!profile.isKid || isKidSafeMovie(current))
                    if (!canKeepCurrent) {
                        currentMovieProfileId = profile.id
                        _currentMovie.value = defaultMovie
                        defaultMovie?.let { fetchLogoForMovie(it) }
                    }
                }
            }

            withContext(Dispatchers.Default) {
                if (profile.isKid) {
                    // Kids Profile Mode: Strictly 12 and under filter (PG, G, TV-Y, TV-Y7, TV-G, TV-PG, 12)
                    val allMovies = (baseRows.flatMap { it.second } + allMoviesMap.values).distinctBy { it.id }
                    val kidSafeMovies = allMovies.filter { com.example.model.isKidSafeMovie(it) }

                    val kidsRows = mutableListOf<Pair<String, List<Movie>>>()
                    val kidsTop10 = kidSafeMovies.take(10)
                    if (kidsTop10.isNotEmpty()) {
                        kidsRows.add("Top 10 for Kids Today" to kidsTop10)
                    }
                    val familyMovies = kidSafeMovies.filter { it.type == "Movie" || it.type == "Animation" }.ifEmpty { kidSafeMovies }
                    if (familyMovies.isNotEmpty()) kidsRows.add("Watch with the Family" to familyMovies)
                    val animated = kidSafeMovies.filter { it.type == "Animation" }
                    if (animated.isNotEmpty()) kidsRows.add("Animated Adventures & Anime" to animated)
                    val kidsSeries = kidSafeMovies.filter { it.type == "Series" }
                    if (kidsSeries.isNotEmpty()) kidsRows.add("Kids TV Shows & Cartoons" to kidsSeries)
                    if (kidSafeMovies.size > 5) kidsRows.add("Fun & Laughs for Family Night" to kidSafeMovies.reversed())

                    val firstKidMovie = kidSafeMovies.firstOrNull() ?: baseRows.firstOrNull()?.second?.firstOrNull()
                    publish(if (kidsRows.isNotEmpty()) kidsRows else baseRows, firstKidMovie)
                } else if (profile.favoriteGenres.isNotEmpty()) {
                    // Personalize rows based on selected favorite genres
                    val personalized = mutableListOf<Pair<String, List<Movie>>>()

                    // Add Top Picks for user
                    val allMovies = baseRows.flatMap { it.second }.distinctBy { it.id }
                    val topPicks = allMovies.shuffled().take(12)
                    if (topPicks.isNotEmpty()) {
                        personalized.add("Top Picks for ${profile.name}" to topPicks)
                    }

                    // Add genre-specific rows
                    profile.favoriteGenres.forEach { genreName ->
                        val genreFiltered = allMovies.filter { movie ->
                            movie.title.contains(genreName, ignoreCase = true) ||
                            movie.description.contains(genreName, ignoreCase = true)
                        }
                        if (genreFiltered.isNotEmpty()) {
                            personalized.add("Because you love $genreName" to genreFiltered)
                        }
                    }

                    // Add original general rows
                    baseRows.forEach { row ->
                        if (!personalized.any { it.first == row.first }) {
                            personalized.add(row)
                        }
                    }
                    val movie = topPicks.firstOrNull() ?: baseRows.firstOrNull()?.second?.firstOrNull()
                    publish(personalized, movie)
                } else {
                    val first = baseRows.firstOrNull()?.second?.firstOrNull()
                    publish(baseRows, first)
                }
            }
        }
    }

    fun updateCurrentMovie(movie: Movie) {
        currentMovieProfileId = _selectedProfile.value?.id
        _currentMovie.value = movie
        fetchLogoForMovie(movie)
    }

    /** Owned by the screen's effect so navigation cancels optional logo requests. */
    suspend fun resolveMovieLogo(movie: Movie): String? = withContext(Dispatchers.IO) {
        repository.getCachedLogo(movie.id)
            ?: movie.logoUrl?.takeIf { it.isNotBlank() }
            ?: repository.fetchLogoUrl(movie.id.toLongOrNull() ?: 0L, movie.type == "Series")
    }

    fun getLogoUrl(movie: Movie, onResult: (String?) -> Unit) {
        val cachedLogo = repository.getCachedLogo(movie.id)
        if (cachedLogo != null) {
            onResult(cachedLogo)
            return
        }
        if (!movie.logoUrl.isNullOrBlank()) {
            onResult(movie.logoUrl)
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val isTv = movie.type == "Series"
            val logo = repository.fetchLogoUrl(movie.id.toLongOrNull() ?: 0L, isTv)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(logo)
            }
        }
    }

    fun fetchLogoForMovie(movie: Movie) {
        val cachedLogo = repository.getCachedLogo(movie.id)
        if (cachedLogo != null && movie.logoUrl != cachedLogo) {
            val updated = movie.copy(logoUrl = cachedLogo)
            allMoviesMap[movie.id] = updated
            if (_currentMovie.value?.id == movie.id) {
                _currentMovie.value = updated
            }
            return
        }
        if (movie.logoUrl.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val isTv = movie.type == "Series"
                val logo = repository.fetchLogoUrl(movie.id.toLongOrNull() ?: 0L, isTv)
                if (!logo.isNullOrBlank()) {
                    val updated = movie.copy(logoUrl = logo)
                    allMoviesMap[movie.id] = updated
                    if (_currentMovie.value?.id == movie.id) {
                        _currentMovie.value = updated
                    }
                }
            }
        }
    }

    fun cacheMovie(movie: Movie) {
        allMoviesMap[movie.id] = movie
    }

    fun getMovieById(
        id: String?,
        expectedTitle: String? = null,
        expectedMediaKind: String? = null
    ): Movie? {
        if (id == null) return null
        if (expectedTitle.isNullOrBlank() && expectedMediaKind.isNullOrBlank()) {
            return allMoviesMap[id]
                ?: continueWatchingList.value.find { it.movieId == id }?.toMovie()
                ?: _categoryRows.value.asSequence().flatMap { it.second.asSequence() }.firstOrNull { it.id == id }
                ?: originalCategoryRows.asSequence().flatMap { it.second.asSequence() }.firstOrNull { it.id == id }
                ?: _searchResults.value.find { it.id == id }
        }
        val candidates = buildList {
            allMoviesMap[id]?.let(::add)
            continueWatchingList.value.forEach { if (it.movieId == id) add(it.toMovie()) }
            _categoryRows.value.forEach { (_, movies) -> movies.filterTo(this) { it.id == id } }
            originalCategoryRows.forEach { (_, movies) -> movies.filterTo(this) { it.id == id } }
            _searchResults.value.filterTo(this) { it.id == id }
        }
        return findMovieByIdentity(candidates, id, expectedTitle, expectedMediaKind)
    }

    fun getRecommendationsForMovie(movie: Movie): List<Movie> {
        val allMovies = (allMoviesMap.values + _categoryRows.value.flatMap { it.second } + originalCategoryRows.flatMap { it.second })
            .distinctBy { it.id }
            .filter { it.id != movie.id }

        if (allMovies.isEmpty()) return emptyList()

        val isKid = isKidSafeMovie(movie)
        val matched = allMovies.filter { other ->
            (isKid && isKidSafeMovie(other)) || (other.type.equals(movie.type, ignoreCase = true))
        }

        return if (matched.isNotEmpty()) {
            matched.take(12)
        } else {
            allMovies.take(12)
        }
    }

    fun logout() {
        signOutFromTv()
    }

    private data class CachedStream(
        val stream: com.example.data.NetMirrorStream,
        val timestamp: Long,
        val sessionVersion: Long
    )
    private val streamCacheFallbackTtlMs = 10L * 60 * 60 * 1000L // 10 hours, matching token TTL
    private val streamExpirySafetyMarginMs = 15 * 1000L
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CachedStream>()
    private data class StreamFetchGate(val mutex: kotlinx.coroutines.sync.Mutex, var users: Int)
    private val streamFetchGates = HashMap<String, StreamFetchGate>()
    private val streamFetchGatesLock = Any()

    /** Keep a gate only while a title has an active fetch or callers waiting for it. */
    private suspend fun <T> withStreamFetchGate(key: String, block: suspend () -> T): T {
        val gate = synchronized(streamFetchGatesLock) {
            streamFetchGates.getOrPut(key) { StreamFetchGate(kotlinx.coroutines.sync.Mutex(), 0) }
                .also { it.users++ }
        }
        var acquired = false
        val gateStartedAt = com.example.ui.util.RuntimeTiming.start()
        try {
            gate.mutex.lock()
            acquired = true
            com.example.ui.util.RuntimeTiming.elapsed("stream_title_gate_wait", gateStartedAt)
            return block()
        } finally {
            if (acquired) gate.mutex.unlock()
            synchronized(streamFetchGatesLock) {
                gate.users--
                if (gate.users == 0) streamFetchGates.remove(key, gate)
            }
        }
    }

    private fun streamCacheKey(movie: Movie, season: Int, episode: Int, purpose: com.example.data.StreamPurpose = com.example.data.StreamPurpose.PLAYBACK): String {
        val key = "${movie.catalogMediaKind()}_${movie.id}_${season}_${episode}"
        return if (purpose == com.example.data.StreamPurpose.PLAYBACK) key else "${purpose.name}_$key"
    }

    private fun cacheResolvedStream(key: String, stream: com.example.data.NetMirrorStream, version: Long) {
        streamCache[key] = CachedStream(stream, System.currentTimeMillis(), version)
        if (streamCache.size > 32) {
            streamCache.entries.sortedBy { it.value.timestamp }
                .take((streamCache.size - 32).coerceAtLeast(0)).forEach { streamCache.remove(it.key, it.value) }
        }
    }

    private fun isUsableStream(cached: CachedStream, currentTime: Long): Boolean {
        val age = currentTime - cached.timestamp
        val withinFallbackTtl = age >= 0 && age < streamCacheFallbackTtlMs
        val expiresAt = cached.stream.expiresAt
        val hasEnoughLifetime = expiresAt <= 0L || expiresAt - currentTime > streamExpirySafetyMarginMs
        return directCDNResolver.hasValidSession() &&
            cached.sessionVersion == directCDNResolver.sessionVersion && withinFallbackTtl && hasEnoughLifetime
    }

    /** Returns a valid resolved stream without starting a network request. */
    @Volatile private var cachedPlaybackSourceRevision = 0L
    fun getCachedStream(movie: Movie, season: Int = 1, episode: Int = 1, purpose: com.example.data.StreamPurpose = com.example.data.StreamPurpose.PLAYBACK): com.example.data.NetMirrorStream? {
        if (cachedPlaybackSourceRevision != directCDNResolver.playbackSourceRevision) {
            streamCache.clear()
            cachedPlaybackSourceRevision = directCDNResolver.playbackSourceRevision
        }
        if (directCDNResolver.playbackCooldownMillis() > 0L) return null
        if (purpose != com.example.data.StreamPurpose.PLAYBACK) {
            getCachedStream(movie, season, episode)?.let { return it }
            if (purpose == com.example.data.StreamPurpose.SILENT_PREVIEW) {
                getCachedStream(movie, season, episode, com.example.data.StreamPurpose.HERO_PREVIEW)?.let { return it }
            }
        }
        val cacheKey = streamCacheKey(movie, season, episode, purpose)
        val cached = streamCache[cacheKey] ?: return null
        return if (isUsableStream(cached, System.currentTimeMillis())) {
            cached.stream
        } else {
            streamCache.remove(cacheKey, cached)
            null
        }
    }

    fun reportBadSession(cookieString: String?) {
        synchronized(warmupJobLock) {
            warmupJob?.cancel()
            warmupJob = null
            warmupIsImmediate = false
            warmupHasStartedAttempt = false
        }
        nextEpisodePreloadJob?.cancel()
        directCDNResolver.invalidateSessionByCookie(cookieString)
        streamCache.clear()
    }

    fun recordPlaybackRateLimit(retryAfterMs: Long? = null) {
        directCDNResolver.recordPlaybackRateLimit(retryAfterMs)
    }

    fun evictCachedStream(movie: Movie, season: Int = 1, episode: Int = 1) {
        com.example.data.StreamPurpose.values().forEach { streamCache.remove(streamCacheKey(movie, season, episode, it)) }
        val type = movie.catalogMediaKind()
        directCDNResolver.evictCachedStream(movie.id, type, season, episode)
    }

    /** Drops a failed source so the next play attempt resolves a fresh URL. */
    fun invalidateStream(movie: Movie, season: Int = 1, episode: Int = 1) {
        com.example.data.StreamPurpose.values().forEach { streamCache.remove(streamCacheKey(movie, season, episode, it)) }
        val type = movie.catalogMediaKind()
        directCDNResolver.invalidateStream(movie.id, type, season, episode)
    }

    private var nextEpisodePreloadJob: kotlinx.coroutines.Job? = null
    fun preloadNextEpisodeStream(movie: Movie, season: Int, episode: Int) {
        nextEpisodePreloadJob?.cancel()
        nextEpisodePreloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (getCachedStream(movie, season, episode) != null) return@launch
                android.util.Log.d("NetflixViewModel", "⏩ Pre-resolving next episode S${season}E${episode} for ${movie.title}...")
                resolveStream(movie, season, episode)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }
    }

    suspend fun resolveStream(movie: Movie, season: Int = 1, episode: Int = 1, purpose: com.example.data.StreamPurpose = com.example.data.StreamPurpose.PLAYBACK): com.example.data.NetMirrorStream? {
        val ownerUid = authenticatedUid() ?: return null
        if (!_userSubscription.value.isTvAllowed || isMovieLocked(movie)) return null
        // Count the wait for a pending warmup, session renewal, and manifest
        // discovery together. A first Play must finish or fail within a minute.
        return kotlinx.coroutines.withTimeoutOrNull(58_000L) {
            val stream = if (purpose == com.example.data.StreamPurpose.PLAYBACK) {
                directCDNResolver.withForegroundSessionDemand { resolveStreamWithinDeadline(movie, season, episode, purpose) }
            } else resolveStreamWithinDeadline(movie, season, episode, purpose)
            if (authenticatedUid() == ownerUid && _userSubscription.value.isTvAllowed && !isMovieLocked(movie)) stream else null
        }
    }

    private suspend fun resolveStreamWithinDeadline(movie: Movie, season: Int, episode: Int, purpose: com.example.data.StreamPurpose): com.example.data.NetMirrorStream? {
        directCDNResolver.checkPlaybackCooldown()
        getCachedStream(movie, season, episode, purpose)?.let { return it }
        // Play never waits for a Home-idle timer. Join a handshake already in
        // flight; otherwise cancel the deferred job and resolve immediately.
        synchronized(warmupJobLock) {
            val pending = warmupJob?.takeIf { it.isActive }
            if (pending != null && !warmupIsImmediate &&
                !(warmupHasStartedAttempt && directCDNResolver.isSessionGenerationInProgress())) {
                pending.cancel()
                warmupJob = null
                warmupHasStartedAttempt = false
                null
            } else pending
        }
        // The resolver's session mutex joins an active handshake directly.
        // Foreground demand permits recovery without a second warmup-job wait.
        val cacheKey = streamCacheKey(movie, season, episode, purpose)
        getCachedStream(movie, season, episode, purpose)?.let { return it }

        // A request for one title must not stall playback of another title.
        return withStreamFetchGate(streamCacheKey(movie, season, episode)) {
            getCachedStream(movie, season, episode, purpose)?.let { return@withStreamFetchGate it }

            try {
                android.util.Log.d("NetflixViewModel", "⚡ Resolving stream for ${movie.title} (tmdbId=${movie.id}, S${season}E${episode})...")
                var stream: com.example.data.NetMirrorStream? = null
                try {
                    stream = directCDNResolver.resolveStream(movie, season, episode, purpose)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (limited: com.example.data.PlaybackRateLimitedException) {
                    throw limited
                } catch (e: Exception) {
                    android.util.Log.w("NetflixViewModel", "DirectCDNResolver failed (${e.javaClass.simpleName})")
                }

                if (stream != null) {
                    val version = directCDNResolver.sessionVersionFor(stream)
                    cacheResolvedStream(cacheKey, stream, version)
                }
                stream
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (limited: com.example.data.PlaybackRateLimitedException) {
                throw limited
            } catch (e: Exception) {
                android.util.Log.e("NetflixViewModel", "Stream resolution failed", e)
                null
            }
        }
    }

    suspend fun fetchSubtitlesForStream(stream: com.example.data.NetMirrorStream, movie: Movie, season: Int, episode: Int): List<com.example.data.Caption> {
        return withContext(Dispatchers.IO) {
            val sessionVersion = directCDNResolver.sessionVersionFor(stream)
            try {
                val captions = directCDNResolver.fetchSubtitlesForEpisode(movie, season, episode)
                if (captions.isNotEmpty()) {
                    val updatedStream = stream.copy(
                        captions = (stream.captions + captions).distinctBy { it.url }
                    )
                    val cacheKey = streamCacheKey(movie, season, episode)
                    if (sessionVersion == directCDNResolver.sessionVersion) {
                        cacheResolvedStream(cacheKey, updatedStream, sessionVersion)
                    }
                    captions
                } else {
                    emptyList()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun loadStoredSubscription(): com.example.model.UserSubscription {
        val hasUser = authenticatedUid() != null
        if (!hasUser) return com.example.model.UserSubscription()

        // Audit fix: previously a signed-in user with no stored plan defaults
        // to plan_standard + ACTIVE — that silently grants free Standard to
        // every new sign-in, which is a revenue leak. The server is the only
        // place that should grant access; until the server confirms a plan,
        // the local cache reflects a Guest / unverified state. The Firestore
        // listener (listenToFirestoreSubscription) will populate the real
        // state as soon as it connects.
        val defaultPlanId = "plan_guest"
        val defaultPlanName = "Guest"
        val defaultStatus = if (hasUser) "PENDING_ACTIVATION" else "NONE"

        var planId = prefs.getString("user_plan_id", defaultPlanId) ?: defaultPlanId
        var planName = prefs.getString("user_plan_name", defaultPlanName) ?: defaultPlanName
        var status = prefs.getString("user_plan_status", defaultStatus) ?: defaultStatus
        val expiresAt = prefs.getLong("user_plan_expires_at", 0L)

        // Don't auto-promote a missing plan to Standard. Only retain the
        // stored plan if it was actually granted by the server previously.
        // (See audit §1, §10 — "Subscription state changes only come from
        // the server (not the client).")

        return com.example.model.UserSubscription(
            status = status,
            planId = planId,
            planName = planName,
            expiresAt = expiresAt
        )
    }

    private fun saveStoredSubscription(sub: com.example.model.UserSubscription) {
        prefs.edit()
            .putString("user_plan_id", sub.planId)
            .putString("user_plan_name", sub.planName)
            .putString("user_plan_status", sub.status)
            .putLong("user_plan_expires_at", sub.expiresAt)
            .putLong("user_plan_subscribed_at", sub.subscribedAt)
            .putString("user_plan_payment_reference", sub.paymentReference)
            .putString("user_plan_mpesa_receipt", sub.mpesaReceipt)
            .putInt("user_plan_amount", sub.amount)
            .putString("user_plan_currency", sub.currency)
            .apply()
    }

    fun setPairedUser(
        userId: String,
        email: String,
        planId: String = "",
        planName: String = "",
        status: String = "",
        expiresAt: Long = 0L
    ) {
        val signedIn = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (signedIn == null || signedIn.isAnonymous || signedIn.uid != userId) return
        // Audit fix: previously an empty / guest planId was silently coerced
        // to plan_standard + ACTIVE. That is a free-upgrade: a user who
        // signs in but has no paid plan on the server would still be granted
        // local Standard access until the server corrected it. Now the
        // defaults are PENDING_ACTIVATION (signed-in but not yet entitled),
        // and the Firestore listener is the only path that promotes to a
        // real paid tier.
        val finalPlanId = planId.trim()
        val finalPlanName = planName.trim()
        val finalStatus = if (status.isBlank() || status.equals("NONE", ignoreCase = true)) "PENDING_ACTIVATION" else status

        prefs.edit()
            .putString("paired_user_id", userId)
            .putString("user_email", email)
            .putString("user_plan_id", finalPlanId)
            .putString("user_plan_name", finalPlanName)
            .putString("user_plan_status", finalStatus)
            .putLong("user_plan_expires_at", expiresAt)
            .apply()

        val sub = com.example.model.UserSubscription(
            status = finalStatus,
            planId = finalPlanId,
            planName = finalPlanName,
            expiresAt = expiresAt
        )
        _userSubscription.value = sub
        saveStoredSubscription(sub)

        ensureProfilesLoaded()
        listenToFirestoreProfiles(userId)
        listenToFirestoreSubscription(userId)
    }

    fun setGuestMode() {
        signOutFromTv()
        prefs.edit()
            .putString("paired_user_id", "guest")
            .putString("user_email", "guest")
            .putString("user_plan_id", "plan_guest")
            .putString("user_plan_name", "Guest")
            .putString("user_plan_status", "NONE")
            .putLong("user_plan_expires_at", 0L)
            .apply()

        val guestSub = com.example.model.UserSubscription(
            status = "NONE",
            planId = "plan_guest",
            planName = "Guest",
            expiresAt = 0L
        )
        _userSubscription.value = guestSub
        saveStoredSubscription(guestSub)

        ensureProfilesLoaded()
    }

    /** Billing cannot be changed by this TV client without verified payment. */
    fun upgradePlan(planId: String, planName: String) {
        android.widget.Toast.makeText(
            getApplication(),
            "Manage your subscription in the Netflix Pro mobile app.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    fun isMovieLocked(movie: Movie): Boolean {
        val isVipOrBlockbuster = movie.rating.contains("VIP", ignoreCase = true) ||
                movie.rating.contains("4K", ignoreCase = true) ||
                movie.title.contains("Dune", ignoreCase = true) ||
                movie.title.contains("Avatar", ignoreCase = true) ||
                movie.title.contains("Oppenheimer", ignoreCase = true) ||
                movie.title.contains("Squid", ignoreCase = true) ||
                movie.year == "2026" || movie.year == "2025"
        return _userSubscription.value.isMovieLocked(
            movieId = movie.id,
            releaseYear = movie.year,
            isTrendingOrVip = isVipOrBlockbuster,
            isTvDevice = true,
            movieTitle = movie.title
        )
    }

    fun getLockReason(movie: Movie): String {
        val isVipOrBlockbuster = movie.rating.contains("VIP", ignoreCase = true) ||
                movie.rating.contains("4K", ignoreCase = true) ||
                movie.title.contains("Dune", ignoreCase = true) ||
                movie.title.contains("Avatar", ignoreCase = true) ||
                movie.title.contains("Oppenheimer", ignoreCase = true) ||
                movie.title.contains("Squid", ignoreCase = true) ||
                movie.year == "2026" || movie.year == "2025"
        return _userSubscription.value.getLockReason(
            releaseYear = movie.year,
            isTrendingOrVip = isVipOrBlockbuster,
            isTvDevice = true
        )
    }

    fun listenToFirestoreSubscription(userId: String? = null) {
        try {
            if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                val targetUid = authenticatedUid() ?: return
                if (userId != null && userId != targetUid) return
                // Audit fix: remove the previous listener before adding a new
                // one. Previously this function could be called multiple times
                // (init, signIn, setPairedUser, listenToFirestoreProfiles)
                // without removing the old listener — every call leaked a
                // SnapshotListener. Now we track it in
                // [firestoreSubscriptionListener] and remove it on sign-out
                // and on every re-attach.
                firestoreSubscriptionListener?.remove()
                firestoreSubscriptionListener = db.collection("users").document(targetUid)
                    .collection("subscription").document("current")
                    .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                        if (authenticatedUid() != targetUid) return@addSnapshotListener
                        subscriptionCloudReceived.set(true)
                        if (error != null) {
                            android.util.Log.w("NetflixViewModel", "Firestore subscription listener error: ${error.message}")
                            if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                _userSubscription.value = com.example.model.UserSubscription()
                                saveStoredSubscription(_userSubscription.value)
                            }
                            return@addSnapshotListener
                        }
                        if (snapshot?.metadata?.hasPendingWrites() == true) return@addSnapshotListener
                        if (snapshot != null && snapshot.exists()) {
                            val rawStatus = snapshot.getString("status") ?: ""
                            val rawPlanId = snapshot.getString("planId") ?: ""
                            // Audit fix: don't coerce a missing plan to
                            // plan_standard + ACTIVE here either. Trust the
                            // server's report verbatim; if it didn't send a
                            // plan, surface that as an unverified state.
                            val sub = com.example.model.UserSubscription(
                                status = rawStatus.ifBlank { "PENDING_ACTIVATION" },
                                planId = rawPlanId,
                                planName = snapshot.getString("planName") ?: "",
                                amount = (snapshot.getLong("amount") ?: 0L).toInt(),
                                currency = snapshot.getString("currency") ?: "KES",
                                paymentReference = snapshot.getString("paymentReference") ?: "",
                                mpesaReceipt = snapshot.getString("mpesaReceipt") ?: "",
                                subscribedAt = snapshot.getLong("subscribedAt") ?: 0L,
                                expiresAt = snapshot.getLong("expiresAt") ?: 0L,
                                renewsAt = snapshot.getLong("renewsAt"),
                                trialEndsAt = snapshot.getLong("trialEndsAt"),
                                startedAt = snapshot.getLong("startedAt") ?: 0L,
                                paymentMethod = snapshot.getString("paymentMethod"),
                                lastVerifiedAt = snapshot.getLong("lastVerifiedAt"),
                                autoRenew = snapshot.getBoolean("autoRenew") ?: false,
                                cancellationReason = snapshot.getString("cancellationReason"),
                                gracePeriodEndsAt = snapshot.getLong("gracePeriodEndsAt")
                            )
                            _userSubscription.value = sub
                            saveStoredSubscription(sub)
                        } else {
                            _userSubscription.value = com.example.model.UserSubscription()
                            saveStoredSubscription(_userSubscription.value)
                        }
                    }
            }
        } catch (e: Exception) {
            // Audit fix: previously this swallowed all errors silently —
            // a permission-denied or quota-exceeded would leave the UI
            // stuck on stale data with no diagnostic. Now we log at warn
            // level so the issue is visible in logcat.
            android.util.Log.w("NetflixViewModel", "listenToFirestoreSubscription: ${e.message}")
        }
    }

    suspend fun resolveTrailerStream(movie: Movie, allowExternalFallback: Boolean = false): com.example.data.TrailerStream? {
        val isTv = movie.type.equals("Series", ignoreCase = true) ||
                movie.type.equals("TV", ignoreCase = true) ||
                movie.duration.contains("Season", ignoreCase = true)
        return kotlinx.coroutines.withTimeoutOrNull(50_000L) {
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val resolver = com.example.data.TrailerResolver(
                        context = getApplication(),
                        tmdbId = movie.id,
                        mediaType = if (isTv) "tv" else "movie",
                        callback = object : com.example.data.TrailerResolverCallback {
                            override fun onResolved(stream: com.example.data.TrailerStream) {
                                if (continuation.isActive) continuation.resume(stream)
                            }
                            override fun onError(error: String) {
                                android.util.Log.w("NetflixViewModel", "Trailer resolution failed for ${movie.id}: $error")
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onExternalTrailer(url: String) {
                                if (continuation.isActive) continuation.resume(
                                    if (allowExternalFallback) com.example.data.TrailerStream(url, "youtube") else null
                                )
                            }
                        }
                    )
                    continuation.invokeOnCancellation { resolver.cancel() }
                    resolver.start()
                }
            }
        }
    }

    private var remoteCommandListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var streamHeartbeatJob: kotlinx.coroutines.Job? = null

    fun startStreamHeartbeat(mediaTitle: String) {
        val targetUid = authenticatedUid() ?: return
        val deviceId = prefs.getString("tv_device_id", null) ?: "tv_${System.currentTimeMillis()}".also {
            prefs.edit().putString("tv_device_id", it).apply()
        }
        streamHeartbeatJob?.cancel()
        streamHeartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                        val db = FirebaseFirestore.getInstance()
                        val data = hashMapOf(
                            "deviceId" to deviceId,
                            "deviceName" to "Android TV",
                            "mediaTitle" to mediaTitle,
                            "lastHeartbeat" to System.currentTimeMillis()
                        )
                        // active_streams is not in the policy map (it's
                        // a write-only ephemeral collection, not a
                        // conflict domain). The policy check returns
                        // true for any unknown field, so the write
                        // proceeds. We still log the policy lookup so a
                        // future schema change that DOES add a
                        // SERVER_ONLY field to active_streams (e.g.
                        // "isBillable") is caught by the existing
                        // log line.
                        ConflictResolver.policyFor(Collections.ACTIVE_STREAMS, "lastHeartbeat")
                            ?: android.util.Log.d(
                                "Sync",
                                "policy=DEFAULT_WRITE field=lastHeartbeat collection=active_streams"
                            )
                        db.collection("users").document(targetUid).collection(Collections.ACTIVE_STREAMS).document(deviceId)
                            .set(data, SetOptions.merge())
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(20_000L)
            }
        }
    }

    fun stopStreamHeartbeat() {
        streamHeartbeatJob?.cancel()
        val targetUid = authenticatedUid() ?: return
        val deviceId = prefs.getString("tv_device_id", null) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    FirebaseFirestore.getInstance().collection("users").document(targetUid)
                        .collection("active_streams").document(deviceId).delete()
                }
            } catch (_: Exception) {}
        }
    }

    private var lastFirestoreSyncTimestamp = 0L
    private val verifiedNextEpisodeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val nextEpisodeLookupMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun verifiedNextEpisode(movieId: String, season: Int, episode: Int): Pair<Int, Int>? {
        val tvId = movieId.toLongOrNull() ?: return null
        val key = "$movieId:$season:$episode"
        verifiedNextEpisodeCache[key]?.let { return it }
        val now = System.currentTimeMillis()
        if (now - (nextEpisodeLookupMisses[key] ?: 0L) < 60_000L) return null
        val remaining = getEpisodes(tvId, season)
            .filter { it.episodeNumber > episode }
            .minByOrNull { it.episodeNumber }
        if (remaining != null) {
            return (season to remaining.episodeNumber).also { verifiedNextEpisodeCache[key] = it }
        }

        val nextSeason = getTvSeasons(tvId).firstOrNull { it > season }
        val firstEpisode = nextSeason?.let { next ->
            getEpisodes(tvId, next).filter { it.episodeNumber > 0 }.minByOrNull { it.episodeNumber }
        }
        return if (nextSeason != null && firstEpisode != null) {
            (nextSeason to firstEpisode.episodeNumber).also { verifiedNextEpisodeCache[key] = it }
        } else {
            nextEpisodeLookupMisses[key] = now
            null
        }
    }

    fun savePlaybackProgress(
        movie: Movie,
        positionMs: Long,
        durationMs: Long,
        season: Int = 1,
        episode: Int = 1,
        episodeName: String = "",
        forceFirestoreSync: Boolean = false
    ) {
        val profileId = _selectedProfile.value?.id ?: "default"
        // Clamp positionMs to a sane range. A buggy stream manifest can
        // report positionMs > durationMs * 2, which makes `isCompleted`
        // true and forces the row out of Continue Watching before the user
        // is anywhere near the end. The previous code passed the value
        // through verbatim and got the wrong answer.
        val safePosition = when {
            positionMs < 0L -> 0L
            durationMs > 0L && positionMs > durationMs -> durationMs
            else -> positionMs
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                continueWatchingRepository.saveProgress(profileId, movie, safePosition, durationMs, season, episode, episodeName,
                    force = forceFirestoreSync)
            } catch (e: Exception) {
                // Repository already swallows, but defensively double-catch
                // so a DB failure never crashes the player.
                android.util.Log.e("NetflixViewModel", "saveProgress threw", e)
            }

            val now = System.currentTimeMillis()
            val isCompleted = (durationMs > 0 && (safePosition >= durationMs - 30_000L || safePosition >= (durationMs * 0.95)))
            if (forceFirestoreSync || isCompleted || (now - lastFirestoreSyncTimestamp >= 15_000L)) {
                lastFirestoreSyncTimestamp = now
                syncWatchProgressToFirestore(profileId, movie, safePosition, durationMs, season, episode, episodeName)
                syncWatchHistoryToFirestore(profileId, movie, safePosition, durationMs, season, episode, episodeName, isCompleted)
            }

            // Update Android TV system-wide "Play Next" row.
            try {
                if (isCompleted) {
                    if (movie.isSeriesContent()) {
                        val next = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                            verifiedNextEpisode(movie.id, season, episode)
                        }
                        if (next != null) {
                            com.example.tv.TvHomeChannelManager.updateWatchNextProgram(
                                context = getApplication(),
                                movie = movie,
                                positionMs = 0L,
                                durationMs = durationMs,
                                season = next.first,
                                episode = next.second,
                                isNextEpisode = true,
                                forceUpdate = forceFirestoreSync
                            )
                        } else {
                            com.example.tv.TvHomeChannelManager.removeWatchNextProgram(
                                getApplication(), movie.id, movie.catalogMediaKind(), movie.title
                            )
                        }
                    } else {
                        com.example.tv.TvHomeChannelManager.removeWatchNextProgram(
                            getApplication(), movie.id, movie.catalogMediaKind(), movie.title
                        )
                    }
                } else if (safePosition > 10_000L) {
                    com.example.tv.TvHomeChannelManager.updateWatchNextProgram(
                        context = getApplication(),
                        movie = movie,
                        positionMs = safePosition,
                        durationMs = durationMs,
                        season = season,
                        episode = episode,
                        isNextEpisode = false
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun syncWatchHistoryToFirestore(
        profileId: String,
        movie: Movie,
        positionMs: Long,
        durationMs: Long,
        season: Int,
        episode: Int,
        episodeName: String,
        isCompleted: Boolean
    ) {
        if (positionMs <= 0L || durationMs <= 0L) return
        val uid = authenticatedUid() ?: return
        try {
            val data = mapOf<String, Any>(
                "profileId" to profileId,
                "mediaId" to movie.id,
                "positionSeconds" to (positionMs / 1000L),
                "totalSeconds" to (durationMs / 1000L),
                "durationSeconds" to (durationMs / 1000L),
                "playbackPositionMs" to positionMs,
                "durationMs" to durationMs,
                "lastWatchedTimestamp" to System.currentTimeMillis(),
                "isCompleted" to isCompleted,
                "episodeId" to "s${season}_e$episode",
                "episodeTitle" to episodeName,
                "episodeName" to episodeName,
                "season" to season,
                "episode" to episode,
                "title" to movie.title,
                "posterUrl" to movie.posterUrl,
                "backdropUrl" to movie.backdropUrl,
                "type" to movie.type,
                "syncedFromDevice" to "Android TV",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("profiles").document(profileId).collection("watch_history")
                .document(movie.id).set(data, SetOptions.merge()).await()
        } catch (e: Exception) {
            android.util.Log.w("NetflixViewModel", "Watch history sync failed", e)
        }
    }

    private suspend fun syncWatchProgressToFirestore(
        profileId: String,
        movie: Movie,
        positionMs: Long,
        durationMs: Long,
        season: Int,
        episode: Int,
        episodeName: String
    ) {
        try {
            if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                val targetUid = authenticatedUid() ?: return
                val docId = "${profileId}_${movie.id}"

                val isNearEnd = durationMs > 0 && (positionMs >= (durationMs * 0.95) || (durationMs - positionMs) <= 30000L)
                val isCompleted = isNearEnd && !movie.type.equals("Series", ignoreCase = true)

                val flatDocRef = db.collection("users").document(targetUid).collection(Collections.CONTINUE_WATCHING_FLAT).document(docId)
                val profileNestedDocRef1 = db.collection("users").document(targetUid).collection(Collections.PROFILES).document(profileId).collection(Collections.CONTINUE_WATCHING_FLAT).document(movie.id)
                val profileNestedDocRef2 = db.collection("users").document(targetUid).collection(Collections.PROFILES).document(profileId).collection(Collections.CONTINUE_WATCHING).document(movie.id)

                // Audit fix: combine the three writes (or deletes) into a single
                // batched commit. The previous implementation fired three
                // independent round-trips and dropped all of them on the first
                // network error, leaving the same progress doc out-of-sync across
                // paths. A single batched commit is atomic on success and uses
                // one network round-trip instead of three.
                val batch = db.batch()

                if (isCompleted) {
                    batch.delete(flatDocRef)
                    batch.delete(profileNestedDocRef1)
                    batch.delete(profileNestedDocRef2)
                    // Offline-first outbox: persist a single batch row
                    // describing the three deletes so the worker can replay
                    // them if the direct commit below fails (or the app is
                    // force-killed between commit and ack). The direct
                    // commit still runs first; the row is marked done on
                    // success.
                    val deleteBatchPayload = SyncScheduler.encodeBatchPayload(
                        listOf(
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.CONTINUE_WATCHING_FLAT}",
                                documentId = docId
                            ),
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING_FLAT}",
                                documentId = movie.id
                            ),
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING}",
                                documentId = movie.id
                            )
                        )
                    )
                    val deleteRowId = SyncScheduler.enqueueWrite(
                        context = getApplication(),
                        dao = pendingWriteDao,
                        collection = "users",
                        documentId = targetUid,
                        operation = com.example.data.PendingWrite.OP_BATCH,
                        payload = deleteBatchPayload
                    )
                    try {
                        batch.commit().await()
                        pendingWriteDao.markDone(deleteRowId, System.currentTimeMillis())
                    } catch (directErr: Exception) {
                        android.util.Log.w(
                            "NetflixViewModel",
                            "Direct delete watch-progress sync failed, will retry via outbox row=$deleteRowId: ${directErr.message}"
                        )
                    }
                    return
                }

                // Policy gate: positionMs is MAX_WINS. The client must
                // read the remote value first, take the max, then write.
                // Without this read-then-max step, a naive last-write-wins
                // would lose progress when a user has been watching on
                // another device (see SyncStrategy.CONTINUE_WATCHING
                // docs for the full scenario).
                val remoteFlat = try {
                    flatDocRef.get().await()
                } catch (_: Exception) { null }
                val remoteNested = try {
                    profileNestedDocRef1.get().await()
                } catch (_: Exception) { null }
                val remotePos = listOfNotNull(remoteFlat, remoteNested)
                    .filter { doc -> !movie.isSeriesContent() || com.example.data.PlaybackProgressPolicy.sameEpisode(
                        season, episode, doc.getLong("season"), doc.getLong("episode"), doc.getString("episodeId")
                    ) }
                    .mapNotNull { doc ->
                        // accept the multiple field names the schema
                        // has historically used
                        doc.getLong("positionMs")
                            ?: doc.getLong("playbackPositionMs")
                            ?: doc.getLong("positionSeconds")?.let { it * 1000L }
                    }
                    .maxOrNull() ?: 0L
                val resolvedPositionMs = com.example.data.PlaybackProgressPolicy.resolvePosition(positionMs, durationMs, remotePos)
                val resolvedDurationMs = (ConflictResolver.resolve(
                    collection = Collections.CONTINUE_WATCHING,
                    field = "durationMs",
                    localValue = durationMs,
                    // SERVER_WINS — the server's canonical duration wins,
                    // but we don't have it client-side so pass our local
                    // value (the server will overwrite if needed).
                    remoteValue = durationMs
                ) as? Long) ?: durationMs
                android.util.Log.d(
                    "Sync",
                    "syncWatchProgress: movieId=${movie.id} profileId=$profileId " +
                        "localPos=$positionMs remotePos=$remotePos " +
                        "resolvedPos=$resolvedPositionMs"
                )

                val positionSeconds = (resolvedPositionMs / 1000L).toInt()
                val durationSeconds = (resolvedDurationMs / 1000L).toInt()
                val progressPercent = if (resolvedDurationMs > 0) ((resolvedPositionMs.toFloat() / resolvedDurationMs.toFloat()) * 100f).coerceIn(0f, 100f) else 0f

                val data = hashMapOf<String, Any>(
                    "profileId" to profileId,
                    "mediaId" to movie.id,
                    "movieId" to movie.id,
                    "id" to movie.id,
                    "positionSeconds" to positionSeconds,
                    "totalSeconds" to durationSeconds,
                    "durationSeconds" to durationSeconds,
                    "playbackPositionMs" to resolvedPositionMs,
                    "positionMs" to resolvedPositionMs,
                    "durationMs" to resolvedDurationMs,
                    "progressPercent" to progressPercent,
                    "lastWatchedTimestamp" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "isCompleted" to false,
                    "episodeId" to "s${season}_e${episode}",
                    "episodeTitle" to episodeName,
                    "episodeName" to episodeName,
                    "season" to season,
                    "episode" to episode,
                    "title" to movie.title,
                    "name" to movie.title,
                    "mediaTitle" to movie.title,
                    "description" to movie.description,
                    "backdropUrl" to movie.backdropUrl,
                    "posterUrl" to movie.posterUrl,
                    "rating" to movie.rating,
                    "year" to movie.year,
                    "type" to movie.type,
                    "duration" to movie.duration,
                    "logoUrl" to (movie.logoUrl ?: ""),
                    "syncedFromDevice" to "Android TV"
                )

                // Filter the write map against the policy. None of the
                // continue_watching fields are SERVER_ONLY, but the
                // SERVER_WINS fields (durationMs, season, episode,
                // lastUpdatedAt) get a debug log so a misuse is visible.
                val filtered = ConflictResolver.filterWritable(Collections.CONTINUE_WATCHING, data)
                batch.set(flatDocRef, filtered, SetOptions.merge())
                batch.set(profileNestedDocRef1, filtered, SetOptions.merge())
                batch.set(profileNestedDocRef2, filtered, SetOptions.merge())
                // Offline-first outbox: build a batch descriptor that
                // replays the same three writes on the worker side if
                // needed. FieldValue.serverTimestamp() is preserved as a
                // {"__serverTimestamp": true} sentinel in the JSON payload
                // — the worker unwraps it back to FieldValue on commit.
                val outboxData = filtered.mapValues { (_, v) ->
                    if (v is FieldValue) mapOf("__serverTimestamp" to true) else v
                }
                val batchPayload = SyncScheduler.encodeBatchPayload(
                    listOf(
                        SyncScheduler.batchUpdate(
                            collection = "users/$targetUid/${Collections.CONTINUE_WATCHING_FLAT}",
                            documentId = docId,
                            data = outboxData
                        ),
                        SyncScheduler.batchUpdate(
                            collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING_FLAT}",
                            documentId = movie.id,
                            data = outboxData
                        ),
                        SyncScheduler.batchUpdate(
                            collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING}",
                            documentId = movie.id,
                            data = outboxData
                        )
                    )
                )
                val batchRowId = SyncScheduler.enqueueWrite(
                    context = getApplication(),
                    dao = pendingWriteDao,
                    collection = "users",
                    documentId = targetUid,
                    operation = com.example.data.PendingWrite.OP_BATCH,
                    payload = batchPayload
                )
                try {
                    batch.commit().await()
                    pendingWriteDao.markDone(batchRowId, System.currentTimeMillis())
                } catch (directErr: Exception) {
                    android.util.Log.w(
                        "NetflixViewModel",
                        "Direct watch-progress sync failed, will retry via outbox row=$batchRowId: ${directErr.message}"
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NetflixViewModel", "Error syncing watch progress to Firestore: ${e.message}")
        }
    }

    fun deletePlaybackProgress(movieId: String) {
        val profileId = _selectedProfile.value?.id ?: "default"
        viewModelScope.launch(Dispatchers.IO) {
            continueWatchingRepository.deleteProgress(profileId, movieId)
            try {
                com.example.tv.TvHomeChannelManager.removeWatchNextProgram(getApplication(), movieId)
            } catch (_: Exception) {}
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    val db = FirebaseFirestore.getInstance()
                    val targetUid = authenticatedUid() ?: return@launch
                    val docId = "${profileId}_${movieId}"
                    // Audit fix: combine the three deletes into a single batched
                    // commit. The previous implementation fired three independent
                    // round-trips (one per path) and dropped all of them on a
                    // single network error, leaving the same progress doc stuck
                    // on two of the three paths. Batches are atomic on success.
                    // Policy gate: deletes bypass per-field policy (a delete
                    // is a row-level operation), but we log the policy lookup
                    // so the audit trail captures the action.
                    android.util.Log.d(
                        "Sync",
                        "policy=DELETE_OP collection=continueWatching movieId=$movieId"
                    )
                    val batch = db.batch()
                    val flatRef = db.collection("users").document(targetUid)
                        .collection(Collections.CONTINUE_WATCHING_FLAT).document(docId)
                    val nestedRef1 = db.collection("users").document(targetUid)
                        .collection(Collections.PROFILES).document(profileId)
                        .collection(Collections.CONTINUE_WATCHING_FLAT).document(movieId)
                    val nestedRef2 = db.collection("users").document(targetUid)
                        .collection(Collections.PROFILES).document(profileId)
                        .collection(Collections.CONTINUE_WATCHING).document(movieId)
                    batch.delete(flatRef)
                    batch.delete(nestedRef1)
                    batch.delete(nestedRef2)
                    // Offline-first outbox: enqueue a batch row describing
                    // the three deletes. The direct commit runs first; the
                    // row is marked done on success. If the commit fails
                    // (or the device dies mid-call), the
                    // PendingWriteRetryWorker replays the same deletes.
                    val deleteBatchPayload = SyncScheduler.encodeBatchPayload(
                        listOf(
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.CONTINUE_WATCHING_FLAT}",
                                documentId = docId
                            ),
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING_FLAT}",
                                documentId = movieId
                            ),
                            SyncScheduler.batchDelete(
                                collection = "users/$targetUid/${Collections.PROFILES}/$profileId/${Collections.CONTINUE_WATCHING}",
                                documentId = movieId
                            )
                        )
                    )
                    val rowId = SyncScheduler.enqueueWrite(
                        context = getApplication(),
                        dao = pendingWriteDao,
                        collection = "users",
                        documentId = targetUid,
                        operation = com.example.data.PendingWrite.OP_BATCH,
                        payload = deleteBatchPayload
                    )
                    try {
                        batch.commit().await()
                        pendingWriteDao.markDone(rowId, System.currentTimeMillis())
                    } catch (directErr: Exception) {
                        android.util.Log.w(
                            "NetflixViewModel",
                            "Direct delete-playback sync failed, will retry via outbox row=$rowId: ${directErr.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NetflixViewModel", "Error deleting playback progress from Firestore: ${e.message}")
            }
        }
    }

    suspend fun getPlaybackProgress(movieId: String): Long {
        val profileId = _selectedProfile.value?.id ?: "default"
        val local = continueWatchingRepository.getContinueWatchingById(profileId, movieId)
        if (local != null && local.playbackPositionMs > 0L) {
            return local.playbackPositionMs
        }
        val remote = getContinueWatching(movieId)
        return remote?.playbackPositionMs ?: 0L
    }

    suspend fun getContinueWatching(movieId: String): ContinueWatchingEntity? {
        val profileId = _selectedProfile.value?.id ?: "default"
        val local = continueWatchingRepository.getContinueWatchingById(profileId, movieId)
        if (local != null) return local

        return withContext(Dispatchers.IO) {
            try {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    val db = FirebaseFirestore.getInstance()
                    val targetUid = authenticatedUid() ?: return@withContext null
                    val docId = "${profileId}_${movieId}"

                    // Audit fix: fan-out the three path lookups in parallel via
                    // coroutineScope + async, then take the first non-null. The
                    // previous implementation awaited sequentially (3 round-trips
                    // on the slow path); the new path issues all three reads at
                    // once and only waits for the slowest.
                    val doc = coroutineScope {
                        val flat = async(Dispatchers.IO) {
                            try {
                                db.collection("users").document(targetUid)
                                    .collection("continue_watching").document(docId)
                                    .get().await()
                            } catch (_: Exception) { null }
                        }
                        val nested1 = async(Dispatchers.IO) {
                            try {
                                db.collection("users").document(targetUid)
                                    .collection("profiles").document(profileId)
                                    .collection("continue_watching").document(movieId)
                                    .get().await()
                            } catch (_: Exception) { null }
                        }
                        val nested2 = async(Dispatchers.IO) {
                            try {
                                db.collection("users").document(targetUid)
                                    .collection("profiles").document(profileId)
                                    .collection("continueWatching").document(movieId)
                                    .get().await()
                            } catch (_: Exception) { null }
                        }
                        listOf(flat, nested1, nested2)
                            .map { it.await() }
                            .firstOrNull { it != null && it.exists() }
                    }

                    if (doc != null && doc.exists() && doc.getBoolean("isCompleted") != true) {
                        val posSec = (doc.getLong("positionSeconds") ?: 0L)
                        val posMs = doc.getLong("playbackPositionMs") ?: doc.getLong("positionMs") ?: (posSec * 1000L)
                        val durSec = (doc.getLong("totalSeconds") ?: doc.getLong("durationSeconds") ?: 0L)
                        val durMs = doc.getLong("durationMs") ?: (durSec * 1000L)
                        if (durMs > 0L && posMs >= durMs * 0.95) return@withContext null
                        val timestamp = doc.getLong("lastWatchedTimestamp") ?: System.currentTimeMillis()
                        val season = (doc.getLong("season") ?: 1L).toInt()
                        val episode = (doc.getLong("episode") ?: 1L).toInt()
                        val episodeName = doc.getString("episodeTitle") ?: doc.getString("episodeName") ?: ""

                        val title = doc.getString("title") ?: doc.getString("name") ?: doc.getString("mediaTitle") ?: "Title"
                        val description = doc.getString("description") ?: ""
                        val backdropUrl = doc.getString("backdropUrl") ?: ""
                        val posterUrl = doc.getString("posterUrl") ?: ""
                        val rating = doc.getString("rating") ?: "13+"
                        val year = doc.getString("year") ?: "2024"
                        val type = doc.getString("type") ?: "Series"
                        val duration = doc.getString("duration") ?: ""
                        val logoUrl = doc.getString("logoUrl")

                        val entity = ContinueWatchingEntity(
                            profileId = profileId,
                            movieId = movieId,
                            title = title,
                            description = description,
                            backdropUrl = backdropUrl,
                            posterUrl = posterUrl,
                            rating = rating,
                            year = year,
                            type = type,
                            duration = duration,
                            playbackPositionMs = posMs,
                            durationMs = durMs,
                            lastWatchedTimestamp = timestamp,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            logoUrl = logoUrl
                        )

                        if (durMs > 0L && posMs > 1000L) {
                            continueWatchingRepository.saveProgress(profileId, entity.toMovie(), posMs, durMs, season, episode, episodeName)
                        }
                        return@withContext entity
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NetflixViewModel", "Error fetching continue watching from Firestore: ${e.message}")
            }
            null
        }
    }

    fun getCachedLogo(movieId: String): String? {
        return repository.getCachedLogo(movieId)
    }

    suspend fun fetchLogoUrl(movieId: String, isTv: Boolean): String? {
        return repository.fetchLogoUrl(movieId.toLongOrNull() ?: 0L, isTv)
    }

    fun getCachedBillboardMeta(movieId: String): com.example.data.TmdbBillboardMeta? {
        return repository.getCachedBillboardMeta(movieId)
    }

    suspend fun fetchBillboardMeta(movie: Movie): com.example.data.TmdbBillboardMeta? {
        return repository.fetchBillboardMeta(
            itemId = movie.id.toLongOrNull() ?: 0L,
            isTv = movie.isSeriesContent(),
            currentTitle = movie.title
        )
    }

    fun prefetchBillboardMetaForMovie(movie: Movie) {
        val existing = repository.getCachedBillboardMeta(movie.id)
        if (existing?.isEnriched == true) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.fetchBillboardMeta(
                itemId = movie.id.toLongOrNull() ?: 0L,
                isTv = movie.isSeriesContent(),
                currentTitle = movie.title
            )
        }
    }

    fun getLikedMoviesSnapshot(): List<Movie> {
        val ids = _likedMovieIds.value
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { allMoviesMap[it] }
    }

    suspend fun fetchImdbId(movie: Movie): String? {
        return movie.imdbId?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
            ?: repository.fetchImdbId(movie.id, movie.catalogMediaKind())
    }

    suspend fun getEpisodes(tvId: Long, seasonNumber: Int = 1): List<com.example.model.Episode> {
        return repository.getTvSeasonEpisodes(tvId, seasonNumber)
    }

    suspend fun getTvSeasons(tvId: Long): List<Int> {
        return repository.getTvSeasons(tvId)
    }

    fun extractPairingCode(input: String): String {
        var text = input.trim()
        if (text.contains("code=", ignoreCase = true)) {
            val codeParam = text.substringAfter("code=", "").substringBefore("&").substringBefore("#")
            if (codeParam.isNotBlank()) {
                text = codeParam
            }
        }
        if (text.contains("://") || text.contains("/")) {
            text = text.substringAfterLast("/")
        }
        return text.replace("/", "").replace("\\", "").trim().uppercase()
    }


}
