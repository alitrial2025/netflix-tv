package com.example.viewing

import org.json.JSONObject
import kotlin.math.abs

data class ViewingTarget(val kind: String, val id: String, val title: String, val season: Int = 1, val episode: Int = 1) {
    val key get() = "$kind:$id:$season:$episode"
    fun json() = JSONObject().put("kind", kind).put("id", id).put("title", title).put("season", season).put("episode", episode)
    companion object {
        fun parse(value: JSONObject?): ViewingTarget? {
            value ?: return null
            val result = ViewingTarget(value.optString("kind"), value.optString("id"), value.optString("title"), value.optInt("season", 1), value.optInt("episode", 1))
            return result.takeIf { it.kind in setOf("movie", "tv") && it.id.matches(Regex("[1-9][0-9]{0,9}")) && it.title.isNotBlank() && it.title.length <= 200 && it.season in 1..1000 && it.episode in 1..10000 }
        }
    }
}
data class LocalViewingPlayback(val target: ViewingTarget, val positionMs: Long, val durationMs: Long,
    val playing: Boolean, val buffering: Boolean, val ready: Boolean = true) {
    fun json(): JSONObject {
        val boundedDuration = durationMs.coerceIn(0, 86_400_000)
        return JSONObject().put("positionMs", positionMs.coerceIn(0, boundedDuration))
            .put("durationMs", boundedDuration).put("playing", playing).put("buffering", buffering)
    }
}
data class PartyMember(val id: String, val name: String, val host: Boolean)
data class WatchParty(val code: String, val revision: Long, val target: ViewingTarget,
    val positionMs: Long, val durationMs: Long, val playing: Boolean, val buffering: Boolean,
    val anchorMs: Long, val hostSeenMs: Long, val expiresMs: Long, val isHost: Boolean,
    val ended: Boolean, val members: List<PartyMember>, val connected: Boolean = true) {
    fun targetPosition(nowMs: Long): Long = (positionMs + if (playing && !buffering) (nowMs - anchorMs).coerceIn(0, 45_000) else 0)
        .coerceIn(0, durationMs.takeIf { it > 0 } ?: 86_400_000)
    companion object {
        fun parse(json: JSONObject, connected: Boolean = true): WatchParty? {
            val target = ViewingTarget.parse(json.optJSONObject("target")) ?: return null
            val playback = json.optJSONObject("playback") ?: return null
            val code = json.optString("roomCode")
            if (!code.matches(Regex("[A-F0-9]{10}"))) return null
            val members = json.optJSONArray("members")
            return WatchParty(code, json.optLong("revision"), target,
                playback.optLong("positionMs").coerceIn(0, 86_400_000), playback.optLong("durationMs").coerceIn(0, 86_400_000),
                playback.optBoolean("playing"), playback.optBoolean("buffering"), json.optLong("updatedAtMs"),
                json.optLong("hostSeenAtMs"), json.optLong("expiresAtMs"), json.optBoolean("isHost"), json.optBoolean("ended"),
                (0 until (members?.length() ?: 0).coerceAtMost(8)).mapNotNull { i -> members?.optJSONObject(i)?.let { PartyMember(it.optString("id"), it.optString("name").take(40), it.optBoolean("isHost")) } }, connected)
        }
    }
}
data class IncomingViewing(val id: String, val target: ViewingTarget, val positionMs: Long, val party: Boolean)
data class ViewingDevice(val id: String, val name: String)
data class PartyAdjustment(val seekMs: Long? = null, val playing: Boolean? = null, val waitingForHost: Boolean = false, val incompatibleEdition: Boolean = false)
object PartySyncPolicy {
    fun adjustment(room: WatchParty, local: LocalViewingPlayback, nowMs: Long, maySeek: Boolean): PartyAdjustment {
        if (room.isHost || !local.ready || room.target.key != local.target.key) return PartyAdjustment()
        if (room.ended || !room.connected || nowMs >= room.expiresMs || nowMs - room.hostSeenMs > 45_000 || nowMs < room.hostSeenMs - 5_000)
            return PartyAdjustment(playing = false, waitingForHost = true)
        if (room.durationMs > 0 && local.durationMs > 0 && abs(room.durationMs - local.durationMs) > 10_000)
            return PartyAdjustment(playing = false, incompatibleEdition = true)
        val target = room.targetPosition(nowMs)
        val play = room.playing && !room.buffering
        return PartyAdjustment(if (maySeek && abs(local.positionMs - target) > 1800) target else null,
            if (local.playing != play) play else null)
    }
}
