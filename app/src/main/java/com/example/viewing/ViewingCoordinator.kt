package com.example.viewing

import android.content.Context
import android.os.SystemClock
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One session per app device/profile. Snapshots never authorize media playback. */
class ViewingCoordinator(context: Context, kind: String, private val scope: CoroutineScope) {
    private val client = ViewingClient(context, kind)
    private var owner = ""
    private var profile = ""
    private var name = "Viewer"
    private var foreground = false
    private var generation = 0L
    private var activeCode: String? = null
    private var receiver: ListenerRegistration? = null
    private var roomListener: ListenerRegistration? = null
    private var heartbeat: Job? = null
    private val commands = Mutex()
    private val _party = MutableStateFlow<WatchParty?>(null)
    val party: StateFlow<WatchParty?> = _party.asStateFlow()
    private val _incoming = MutableStateFlow<IncomingViewing?>(null)
    val incoming: StateFlow<IncomingViewing?> = _incoming.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _devices = MutableStateFlow<List<ViewingDevice>>(emptyList())
    val devices: StateFlow<List<ViewingDevice>> = _devices.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private var local: LocalViewingPlayback? = null
    private var lastPublication: LocalViewingPlayback? = null
    private var lastPublishedAt = 0L
    private var lastSeekAt = 0L
    private var publishJob: Job? = null
    private var pendingPublication: LocalViewingPlayback? = null
    private var pendingForced = false
    private var targetDelivered: String? = null
    val isGuest: Boolean get() = _party.value?.let { !it.isHost && !it.ended } == true
    val currentPlayback get() = local

    fun bind(uid: String?, profileId: String, displayName: String) {
        val next = uid.orEmpty()
        if (next == owner && profileId == profile) { name = displayName.take(40); return }
        val previousOwner = owner; val previousProfile = profile; val previousCode = activeCode
        generation++
        _busy.value = false
        if (previousCode != null && previousOwner.isNotBlank()) scope.launch {
            commands.withLock {
                try { client.call(previousOwner, "leave", JSONObject().put("roomCode", previousCode)); client.saveRoom(previousOwner, previousProfile, null) }
                catch (_: Exception) { /* The departed device lease expires if the old account is offline. */ }
            }
        }
        heartbeat?.cancel(); receiver?.remove(); roomListener?.remove(); publishJob?.cancel()
        owner = next; profile = profileId; name = displayName.take(40)
        _party.value = null; _incoming.value = null; _devices.value = emptyList(); _error.value = null
        local = null; activeCode = null; targetDelivered = null; pendingPublication = null; pendingForced = false
        lastPublication = null; lastPublishedAt = 0
        if (owner.isBlank()) return
        val boundOwner = owner; val bound = generation
        receiver = client.listen(owner, "viewing_commands") { json, connected ->
            if (bound != generation || !connected || json == null) return@listen
            val id = json.optString("id")
            val target = ViewingTarget.parse(json.optJSONObject("target")) ?: return@listen
            val expires = json.optLong("expiresAtMs")
            if (id.isBlank() || client.consumed(boundOwner, id) || client.serverNowMs() >= expires || expires - json.optLong("issuedAtMs") !in 1..30_000) return@listen
            if (isGuest) { _error.value = "Leave the party before receiving another title from your phone."; client.consume(boundOwner, id); return@listen }
            _incoming.value = IncomingViewing(id, target, json.optLong("positionMs").coerceIn(0, 86_400_000), false)
        }
        roomListener = client.listen(owner, "viewing_sessions") { json, connected ->
            if (bound != generation) return@listen
            if (json == null) { if (!connected) _party.update { it?.copy(connected = false) }; return@listen }
            if (json.optString("roomCode") != activeCode) return@listen
            if (json.optBoolean("ended")) { clearParty(); _status.value = "The party has ended"; return@listen }
            WatchParty.parse(json, connected)?.let(::accept)
        }
        startHeartbeat()
        client.savedRoom(owner, profile)?.let { saved -> if (saved.matches(Regex("[A-F0-9]{10}"))) join(saved, restoring = true) }
    }
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) startHeartbeat() else {
            heartbeat?.cancel()
            if (_party.value?.isHost == true) local?.let { publish(it.copy(playing = false, buffering = false), force = true) }
        }
    }
    private fun startHeartbeat() {
        heartbeat?.cancel()
        if (!foreground || owner.isBlank()) return
        val bound = generation
        heartbeat = scope.launch {
            var registered = 0L
            while (isActive && bound == generation && foreground) {
                try {
                    if (SystemClock.elapsedRealtime() - registered > 30_000 || registered == 0L) {
                        client.call(owner, "register", JSONObject().put("name", if (client.kind == "tv") "NetflixPro TV · $name" else name))
                        if (bound != generation) break
                        registered = SystemClock.elapsedRealtime()
                    }
                    if (activeCode == null && !_busy.value) client.savedRoom(owner, profile)?.let { join(it, restoring = true) }
                    activeCode?.let { code -> acceptResponse(client.call(owner, "heartbeat", JSONObject().put("roomCode", code))) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: ViewingException) {
                    if (e.code in setOf("PARTY_ENDED", "NOT_A_MEMBER", "MEMBERSHIP_REQUIRED", "ACCOUNT_CHANGED")) clearParty()
                    else _party.update { it?.copy(connected = false) }
                } catch (_: Exception) { _party.update { it?.copy(connected = false) } }
                delay(20_000)
            }
        }
    }
    private fun acceptResponse(json: JSONObject) { WatchParty.parse(json)?.let(::accept) }
    private fun accept(room: WatchParty) {
        if (room.code != activeCode) return
        val previous = _party.value
        if (previous != null && room.revision < previous.revision) return
        if (room.ended || client.serverNowMs() >= room.expiresMs) { clearParty(); return }
        _party.value = room
        _status.value = if (!room.connected) "Reconnecting to party…" else if (!room.isHost && client.serverNowMs() - room.hostSeenMs > 45_000) "Waiting for host…"
            else if (room.isHost) "You’re hosting · ${room.members.size} viewers" else "Watching together · ${room.members.size} viewers"
        if (!room.isHost && room.target.key != local?.target?.key && targetDelivered != room.target.key) {
            targetDelivered = room.target.key
            _incoming.value = IncomingViewing("party:${room.code}:${room.target.key}", room.target, room.targetPosition(client.serverNowMs()), true)
        }
    }
    fun reopenPartyTitle() {
        val room = _party.value?.takeIf { !it.ended && client.serverNowMs() < it.expiresMs } ?: return
        targetDelivered = room.target.key
        _incoming.value = IncomingViewing("party:${room.code}:${room.target.key}", room.target, room.targetPosition(client.serverNowMs()), true)
    }
    fun acknowledgeIncoming(id: String) {
        if (_incoming.value?.id != id) return
        if (!id.startsWith("party:")) client.consume(owner, id)
        _incoming.value = null
    }
    fun create() = operation {
        val bound = generation; val boundOwner = owner
        val playback = local?.takeIf { it.ready && it.durationMs > 0 } ?: throw ViewingException("CHOOSE_TITLE", "Start a movie or episode before hosting.")
        val response = client.call(boundOwner, "create", JSONObject().put("name", name).put("target", playback.target.json()).put("playback", playback.json()))
        if (bound != generation) { abandonStaleRoom(boundOwner, response.optString("roomCode")); return@operation }
        activeCode = response.optString("roomCode"); client.saveRoom(owner, profile, activeCode); acceptResponse(response)
        lastPublication = playback; lastPublishedAt = SystemClock.elapsedRealtime()
    }
    fun join(code: String, restoring: Boolean = false) = operation(restoring) {
        val bound = generation; val boundOwner = owner
        val normalized = code.uppercase().filterNot(Char::isWhitespace)
        if (!normalized.matches(Regex("[A-F0-9]{10}"))) throw ViewingException("INVALID_ROOM", "Enter the ten-character party code.")
        val response = client.call(boundOwner, "join", JSONObject().put("roomCode", normalized).put("name", name))
        if (bound != generation) { abandonStaleRoom(boundOwner, normalized); return@operation }
        activeCode = normalized; client.saveRoom(owner, profile, activeCode); acceptResponse(response)
        if (_party.value?.isHost == true && local?.target?.key != _party.value?.target?.key) reopenPartyTitle()
    }
    fun leave() = operation {
        val code = activeCode ?: return@operation
        val bound = generation; val boundOwner = owner
        try { client.call(boundOwner, "leave", JSONObject().put("roomCode", code)); if (bound == generation) clearParty() }
        catch (e: ViewingException) { if (bound == generation && e.code in setOf("PARTY_ENDED", "NOT_A_MEMBER")) clearParty() else throw e }
    }
    private suspend fun abandonStaleRoom(boundOwner: String, code: String) {
        try { client.call(boundOwner, "leave", JSONObject().put("roomCode", code)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A request from a previous profile must never update the current UI. */ }
    }
    private fun clearParty() {
        if (owner.isNotBlank()) client.saveRoom(owner, profile, null)
        activeCode = null; _party.value = null; targetDelivered = null
        if (_incoming.value?.party == true) _incoming.value = null
        lastPublication = null; lastPublishedAt = 0; pendingPublication = null; pendingForced = false
    }
    fun dismissError() { _error.value = null }
    private fun operation(silent: Boolean = false, block: suspend () -> Unit) {
        if (_busy.value) return
        val bound = generation
        _busy.value = true; _error.value = null
        scope.launch {
            try {
                if (owner.isBlank()) throw ViewingException("SIGN_IN_REQUIRED", "Sign in to watch together.")
                commands.withLock { if (bound == generation) block() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (bound == generation) {
                    if (silent) { if (e is ViewingException && e.code in setOf("PARTY_ENDED", "NOT_A_MEMBER", "MEMBERSHIP_REQUIRED", "ACCOUNT_CHANGED")) clearParty() } else _error.value = if (e is ViewingException) e.message else "Could not connect. Try again shortly."
                }
            } finally { if (bound == generation) _busy.value = false }
        }
    }
    fun refreshDevices() = operation {
        val bound = generation; val boundOwner = owner
        val response = client.call(boundOwner, "devices")
        if (bound != generation) return@operation
        val list = response.optJSONArray("devices")
        _devices.value = (0 until (list?.length() ?: 0).coerceAtMost(8)).mapNotNull { list?.optJSONObject(it)?.let { d -> ViewingDevice(d.optString("id"), d.optString("name")) } }
    }
    fun sendToTv(deviceId: String) = operation {
        val bound = generation; val boundOwner = owner
        val playback = local ?: throw ViewingException("CHOOSE_TITLE", "Start a title on this phone first.")
        client.call(boundOwner, "handoff", JSONObject().put("targetDeviceId", deviceId).put("target", playback.target.json()).put("positionMs", playback.positionMs.coerceAtLeast(0)))
        if (bound == generation) _status.value = "Sent to your TV"
    }
    fun sample(playback: LocalViewingPlayback): PartyAdjustment {
        local = playback
        val room = _party.value ?: return PartyAdjustment()
        if (room.isHost) {
            if (foreground && playback.durationMs > 0) publish(playback.copy(playing = playback.playing && playback.ready))
            return PartyAdjustment()
        }
        val now = SystemClock.elapsedRealtime()
        val adjustment = PartySyncPolicy.adjustment(room, playback, client.serverNowMs(), now - lastSeekAt >= 2000)
        if (adjustment.seekMs != null) lastSeekAt = now
        if (adjustment.waitingForHost) _status.value = "Waiting for host…"
        if (adjustment.incompatibleEdition) _status.value = "This version has different timing. Leave the party to watch it separately."
        return adjustment
    }
    fun clearPlayback() {
        if (_party.value?.isHost == true) local?.let { publish(it.copy(playing = false, buffering = false), force = true) }
        local = null
    }
    private fun publish(playback: LocalViewingPlayback, force: Boolean = false) {
        val room = _party.value?.takeIf { it.isHost } ?: return
        val now = SystemClock.elapsedRealtime()
        val previous = lastPublication
        val predicted = previous?.let { it.positionMs + if (it.playing && !it.buffering) now - lastPublishedAt else 0 }
        val changed = previous == null || previous.target.key != playback.target.key || previous.playing != playback.playing ||
            previous.buffering != playback.buffering || kotlin.math.abs((predicted ?: playback.positionMs) - playback.positionMs) > 1500
        if (!force && !changed && now - lastPublishedAt < 10_000) return
        // Always retain the newest state, especially a pause while another control is in flight.
        pendingPublication = playback; pendingForced = pendingForced || force
        if (publishJob?.isActive == true) return
        val bound = generation; val boundOwner = owner
        publishJob = scope.launch {
            var staleRetries = 0
            while (bound == generation && pendingPublication != null) {
                delay(if (pendingForced) 0 else 200)
                commands.withLock {
                    if (bound != generation) return@withLock
                    val candidate = pendingPublication ?: return@withLock
                    pendingPublication = null; pendingForced = false
                    val latest = _party.value?.takeIf { it.isHost && it.code == room.code } ?: return@withLock
                    val changedTitle = latest.target.key != candidate.target.key
                    val sent = if (changedTitle) candidate.copy(positionMs = 0) else candidate
                    val sentAt = SystemClock.elapsedRealtime()
                    val data = JSONObject().put("roomCode", latest.code).put("expectedRevision", latest.revision).put("playback", sent.json())
                    if (changedTitle) data.put("target", candidate.target.json())
                    try {
                        val response = client.call(boundOwner, "control", data)
                        if (bound == generation) { acceptResponse(response); lastPublication = sent; lastPublishedAt = sentAt }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: ViewingException) {
                        if (bound == generation) {
                            if (e.code in setOf("PARTY_ENDED", "NOT_A_MEMBER", "MEMBERSHIP_REQUIRED")) clearParty()
                            else if (e.code == "STALE_CONTROL" && staleRetries++ < 2) {
                                // A join can revise the room while a pause is in flight.
                                // Refresh and retain that final intent after backgrounding.
                                try {
                                    val refreshed = client.call(boundOwner, "heartbeat", JSONObject().put("roomCode", room.code))
                                    if (bound == generation) {
                                        acceptResponse(refreshed)
                                        pendingPublication = pendingPublication ?: candidate
                                        pendingForced = true
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (bound == generation) _party.update { it?.copy(connected = false) } }
                            }
                            else if (e.code != "STALE_CONTROL") _party.update { it?.copy(connected = false) }
                        }
                    } catch (_: Exception) { if (bound == generation) _party.update { it?.copy(connected = false) } }
                }
            }
        }
    }
    fun close() { generation++; heartbeat?.cancel(); publishJob?.cancel(); receiver?.remove(); roomListener?.remove() }
}
