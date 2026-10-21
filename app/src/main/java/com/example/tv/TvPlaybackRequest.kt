package com.example.tv

import android.net.Uri

/** Launcher data is navigation input, never proof of an admitted subscription. */
internal data class TvPlaybackRequest(val uri: Uri) {
    val route: String get() = buildString {
        append("player/").append(Uri.encode(uri.lastPathSegment))
        append("?season=").append(uri.getQueryParameter("season") ?: "1")
        append("&episode=").append(uri.getQueryParameter("episode") ?: "1")
        append("&pos=").append(uri.getQueryParameter("pos") ?: "0")
        append("&mediaKind=").append(Uri.encode(uri.getQueryParameter("mediaKind").orEmpty()))
        append("&title=").append(Uri.encode(uri.getQueryParameter("title").orEmpty()))
    }
    fun canOpen(sessionAdmitted: Boolean, hasProfile: Boolean, currentRoute: String): Boolean =
        sessionAdmitted && hasProfile && currentRoute.isNotEmpty() &&
            currentRoute !in setOf("splash", "profiles", "tvAuth", "profileSetup") &&
            !currentRoute.startsWith("editProfile")

    companion object {
        fun isPlaybackLink(uri: Uri?) = uri?.scheme == "netflixpro" && uri.host == "play"
        fun parse(value: String?): TvPlaybackRequest? = runCatching {
            val uri = Uri.parse(value ?: return null)
            if (!isPlaybackLink(uri) || uri.pathSegments.size != 1 ||
                uri.lastPathSegment.isNullOrBlank() || uri.lastPathSegment!!.length > 160) return null
            for (key in listOf("season", "episode")) {
                val raw = uri.getQueryParameter(key) ?: continue
                if ((raw.toIntOrNull() ?: return null) <= 0) return null
            }
            uri.getQueryParameter("pos")?.let { if ((it.toLongOrNull() ?: return null) < 0) return null }
            uri.getQueryParameter("mediaKind")?.let { if (it !in setOf("tv", "movie")) return null }
            if ((uri.getQueryParameter("title")?.length ?: 0) > 500) return null
            TvPlaybackRequest(uri)
        }.getOrNull()
    }
}
