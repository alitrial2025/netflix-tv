@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.util

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.example.data.NetMirrorStream
import com.example.data.PlaybackRateLimitedException
import com.example.data.StreamPurpose
import com.example.data.StreamSessionPolicy
import com.example.data.toNetMirrorStream
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.ui.NetflixViewModel
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HomePreviewState(
    val owner: String? = null,
    val player: ExoPlayer? = null,
    val firstFrameReady: Boolean = false,
    val contentKey: String? = null
)

internal fun homePreviewKey(movie: Movie): String =
    "${movie.catalogMediaKind()}:${movie.id}:${movie.title.trim().lowercase(Locale.ROOT)}"

/**
 * Borrows the shared player for Home and category rows. All calls and player access
 * occur on Main. Stream discovery runs on IO, with one latest request only.
 * This player never records progress, changes resume state, or publishes Watch Next.
 */
class HomePreviewController(
    context: Context,
    private val scope: CoroutineScope,
    private val viewModel: NetflixViewModel,
    private val onPlayingChanged: (Boolean) -> Unit
) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lowMemory = TvImagePolicy.isLowMemoryDevice(context)
    private val budget = HomePreviewRequestBudget(SystemClock::uptimeMillis)
    private val _state = MutableStateFlow(HomePreviewState())
    val state: StateFlow<HomePreviewState> = _state.asStateFlow()
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    private data class Request(val owner: String, val movie: Movie, val audible: Boolean, val trailerOnly: Boolean)
    private data class Choice(val season: Int, val episode: Int, var startMs: Long? = null)
    private val choices = object : LinkedHashMap<String, Choice>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Choice>?): Boolean = size > 24
    }
    private var desired: Request? = null
    private var generation = 0L
    private var work: Job? = null
    private var idleRelease: Job? = null
    private var player: ExoPlayer? = null
    private val playbackOwner = "home-preview:${java.util.UUID.randomUUID()}"
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) { }
        override fun onLowMemory() { stopAll() }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) stopAll()
        }
    }

    init { this.context.registerComponentCallbacks(memoryCallbacks) }

    fun request(owner: String, movie: Movie, audible: Boolean) = onMain {
        requestOnMain(owner, movie, audible)
    }

    private fun requestOnMain(owner: String, movie: Movie, audible: Boolean) {
        val trailerOnly = !viewModel.isUserLoggedIn() || viewModel.isMovieLocked(movie) || movie.isComingSoon
        val previousRequest = desired
        if (previousRequest?.owner == owner && key(previousRequest.movie) == key(movie) &&
            previousRequest.audible == audible && previousRequest.trailerOnly == trailerOnly) return
        val request = Request(owner, movie, audible, trailerOnly)
        val requestedAt = RuntimeTiming.start()
        val focusedAt = SystemClock.uptimeMillis()
        desired = request
        val ticket = ++generation
        val previousWork = work
        previousWork?.cancel()
        idleRelease?.cancel()
        clearPlayback()
        work = scope.launch {
            // Some providers finish cancellation asynchronously. Do not overlap
            // their cleanup with a new resolution or keep a queue of old cards.
            previousWork?.join()
            try {
                val resolved = FocusedPreviewPolicy.resolve(FocusedPreviewPolicy.remainingStartupMs(focusedAt, SystemClock.uptimeMillis())) {
                    HomeStartupGate.awaitBrowsingIdle()
                    // Optional surfaces never queue a restart after a provider cooldown.
                    if (budget.cooldownMillis() > 0L) return@resolve null
                    val choice = if (trailerOnly) Choice(1, 1, 0L) else choose(movie)
                    val purpose = if (audible) StreamPurpose.HERO_PREVIEW else StreamPurpose.SILENT_PREVIEW
                    var stream = if (trailerOnly) null else viewModel.getCachedStream(movie, choice.season, choice.episode, purpose)
                    if (stream == null) {
                        val wait = budget.waitMillis()
                        if (wait >= FocusedPreviewPolicy.remainingStartupMs(focusedAt, SystemClock.uptimeMillis())) return@resolve null
                        if (wait > 0L) delay(wait)
                        HomeStartupGate.awaitBrowsingIdle()
                        currentCoroutineContext().ensureActive()
                        stream = if (trailerOnly) null else viewModel.getCachedStream(movie, choice.season, choice.episode, purpose)
                        if (stream == null) {
                            budget.onResolutionStarted()
                            stream = withContext(Dispatchers.IO) {
                                if (trailerOnly) viewModel.resolveTrailerStream(movie)
                                    ?.takeUnless { it.type == "youtube" }
                                    ?.toNetMirrorStream(movie.title, "trailer_${movie.id}")
                                else viewModel.resolveStream(movie, choice.season, choice.episode, purpose)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    stream?.let { choice to it }
                }
                if (resolved == null) {
                    budget.onFailure()
                    return@launch
                }
                val (choice, stream) = resolved
                if (stream.isRateLimited) {
                    budget.onRateLimited()
                    return@launch
                }
                budget.onSuccess()
                RuntimeTiming.elapsed("preview_requested_to_resolved", requestedAt)
                play(request, choice, stream, ticket, requestedAt, focusedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: PlaybackRateLimitedException) {
                budget.onRateLimited(limited.retryAfterMs)
            } catch (error: Exception) {
                budget.onFailure()
                android.util.Log.w("HomePreview", "Optional preview unavailable", error)
            } finally {
                if (generation == ticket) {
                    clearPlayback()
                    releaseWhenIdle(ticket)
                }
            }
        }
    }

    fun stop(owner: String, releaseImmediately: Boolean = false) = onMain {
        if (desired?.owner == owner) stopAllOnMain(releaseImmediately)
    }

    fun stopAll(releaseImmediately: Boolean = true) = onMain { stopAllOnMain(releaseImmediately) }

    private fun stopAllOnMain(releaseImmediately: Boolean) {
        desired = null
        val ticket = ++generation
        work?.cancel()
        // Keep the cancelled Job until the next request has joined its cleanup.
        clearPlayback()
        idleRelease?.cancel()
        if (releaseImmediately) releasePlayer() else releaseWhenIdle(ticket)
    }

    fun release() = onMain {
        context.unregisterComponentCallbacks(memoryCallbacks)
        stopAllOnMain(releaseImmediately = true)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun key(movie: Movie): String =
        homePreviewKey(movie)

    private fun choose(movie: Movie): Choice {
        val key = key(movie)
        // A preview does not need two metadata calls to choose a random episode.
        // The public resolver verifies S1E1; foreground episode selection is unchanged.
        return choices.getOrPut(key) { Choice(1, 1) }
    }

    private fun obtainPlayer(): ExoPlayer {
        viewModel.claimSharedPlayback(playbackOwner, stopPreview = false)
        return viewModel.sharedExoPlayer.also { player = it }
    }

    fun attachSurface(view: androidx.media3.ui.PlayerView) {
        viewModel.attachSharedPlaybackView(playbackOwner, view)
    }

    fun detachSurface(view: androidx.media3.ui.PlayerView) {
        viewModel.detachSharedPlaybackView(view)
    }

    private suspend fun play(request: Request, choice: Choice, stream: NetMirrorStream, ticket: Long, requestedAt: Long, focusedAt: Long) {
        val activePlayer = obtainPlayer()
        val mediaId = "preview:${key(request.movie)}" + if (request.trailerOnly) ":trailer" else ""
        val finished = CompletableDeferred<Unit>()
        var seekChosen = false
        var previewEndMs = 0L
        var revealed = false
        var firstFrameSeen = false
        fun revealFrame() {
            if (generation != ticket || !viewModel.ownsSharedPlayback(playbackOwner) ||
                !seekChosen || revealed || !firstFrameSeen || activePlayer.currentMediaItem?.mediaId != mediaId) return
            if (activePlayer.currentPosition + 1_000L < (choice.startMs ?: 0L)) return
            revealed = true
            RuntimeTiming.elapsed("preview_requested_to_visible", requestedAt)
            _state.value = HomePreviewState(request.owner, activePlayer, true, key(request.movie))
            onPlayingChanged(true)
        }
        fun chooseSeekPosition(): Boolean {
            if (activePlayer.currentMediaItem?.mediaId != mediaId) return false
            if (seekChosen) return true
            val duration = activePlayer.duration
            if (!request.trailerOnly && duration in 535_000L..545_000L) {
                budget.onRateLimited()
                viewModel.recordPlaybackRateLimit()
                finished.complete(Unit)
                return false
            }
            if (duration == C.TIME_UNSET || duration <= 0L || !activePlayer.isCurrentMediaItemSeekable) return false
            val start = homePreviewStartMs(duration, choice.startMs)
            choice.startMs = start
            previewEndMs = minOf(start + 60_000L, duration)
            seekChosen = true
            if (kotlin.math.abs(activePlayer.currentPosition - start) > 500L) {
                firstFrameSeen = false
                activePlayer.seekTo(start)
            } else revealFrame()
            activePlayer.play()
            return true
        }
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (generation == ticket && viewModel.ownsSharedPlayback(playbackOwner)) {
                    // VOD duration arrives with the manifest. Seek before downloading
                    // and decoding an opening buffer which will immediately be discarded.
                    chooseSeekPosition()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation != ticket || !viewModel.ownsSharedPlayback(playbackOwner) ||
                    activePlayer.currentMediaItem?.mediaId != mediaId) return
                if (playbackState == Player.STATE_READY && !chooseSeekPosition()) {
                    finished.complete(Unit)
                } else if (playbackState == Player.STATE_ENDED) finished.complete(Unit)
            }

            override fun onRenderedFirstFrame() {
                firstFrameSeen = true
                // A renderer may have prepared a frame at position zero before
                // the seek. Reveal only when the requested random position is ready.
                revealFrame()
            }

            override fun onCues(cueGroup: CueGroup) {
                if (generation == ticket) {
                    _caption.value = cueGroup.cues.mapNotNull { it.text?.toString() }
                        .joinToString("\n").take(600)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != ticket) return
                if (isRateLimited(error)) {
                    val retryAfter = rateLimitDelayMs(error)
                    budget.onRateLimited(retryAfter)
                    viewModel.recordPlaybackRateLimit(retryAfter)
                } else {
                    budget.onFailure()
                    // A bad cached URL must not fail forever when focus returns.
                    viewModel.evictCachedStream(request.movie, choice.season, choice.episode)
                }
                finished.complete(Unit)
            }
        }
        activePlayer.addListener(listener)
        try {
            activePlayer.playWhenReady = false
            activePlayer.repeatMode = Player.REPEAT_MODE_OFF
            activePlayer.volume = if (request.audible) 1.0f else 0f
            activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setMaxVideoSize(if (lowMemory) 640 else 960, if (lowMemory) 360 else 540)
                .setMaxVideoBitrate(if (lowMemory) 800_000 else 1_500_000)
                .setMaxVideoFrameRate(30)
                .setForceLowestBitrate(lowMemory)
                .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                .setPreferredAudioLanguages("en", "eng")
                .setPreferredTextLanguages("en", "eng")
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !request.audible)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            val http = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(stream.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(10_000)
            // DirectCDN can return a local master manifest with remote segments.
            val sourceFactory = DefaultMediaSourceFactory(com.example.data.GuardedPlaybackDataSourceFactory(DefaultDataSource.Factory(context, http), manifestHeaders = stream.headers))
                .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(1) {
                    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                        if (isRateLimited(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
                })
            val item = MediaItem.Builder().setUri(stream.url).setMediaId(mediaId)
            when {
                stream.url.contains(".m3u8", true) -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                stream.url.contains(".mp4", true) -> item.setMimeType(MimeTypes.VIDEO_MP4)
            }
            val englishCaption = stream.captions.firstOrNull {
                it.isVerified && !it.type.equals("thumbnails", true) && it.url.isNotBlank() &&
                    !it.url.substringBefore('?').endsWith(".m3u8", true) &&
                    (it.languageCode.equals("en", true) || it.languageCode.equals("eng", true) ||
                        it.language.startsWith("English", true))
            }
            if (englishCaption != null) item.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(englishCaption.url))
                    .setMimeType(if (englishCaption.type.equals("srt", true) ||
                        englishCaption.url.contains(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                    .setLanguage("en").setLabel("English").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            ))
            _caption.value = ""
            _state.value = HomePreviewState(request.owner, activePlayer, contentKey = key(request.movie))
            activePlayer.setMediaSource(sourceFactory.createMediaSource(item.build()), choice.startMs ?: 0L)
            activePlayer.prepare()
            // The minute counts media time, so buffering does not shorten it.
            // A stalled source still has a finite lifetime, with no automatic retry.
            withTimeoutOrNull(90_000L) {
                while (!finished.isCompleted) {
                    if (!viewModel.ownsSharedPlayback(playbackOwner)) break
                    if (seekChosen && activePlayer.currentPosition >= previewEndMs) break
                    if (!revealed && FocusedPreviewPolicy.remainingStartupMs(focusedAt, SystemClock.uptimeMillis()) == 0L) break
                    delay(100L)
                }
            }
        } finally {
            activePlayer.removeListener(listener)
        }
    }

    private fun clearPlayback() {
        _state.value = HomePreviewState()
        _caption.value = ""
        onPlayingChanged(false)
        player?.let {
            // Stop optional segment loading on blur. A screen which already
            // claimed the player keeps its source and decoder.
            if (viewModel.ownsSharedPlayback(playbackOwner)) {
                it.stop()
                it.clearMediaItems()
            }
        }
    }

    private fun isRateLimited(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8)
            .any { it is com.example.data.PlaybackRateLimitedException ||
                (it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 429) }

    private fun rateLimitDelayMs(error: Throwable): Long? {
        val response = generateSequence(error) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull { it.responseCode == 429 } ?: return null
        val header = response.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
        return StreamSessionPolicy.retryAfterDelayMs(header, System.currentTimeMillis())
    }

    private fun releaseWhenIdle(ticket: Long) {
        idleRelease?.cancel()
        idleRelease = scope.launch {
            delay(15_000L)
            if (generation == ticket && _state.value.player == null) releasePlayer()
        }
    }

    private fun releasePlayer() {
        viewModel.releaseSharedPlayback(playbackOwner)
        player = null
    }
}
