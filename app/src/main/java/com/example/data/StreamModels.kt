package com.example.data

enum class StreamPurpose { PLAYBACK, HERO_PREVIEW, SILENT_PREVIEW }

data class NetMirrorStream(
    val url: String,
    val headers: Map<String, String>,
    val captions: List<Caption>,
    val sourceId: String,
    val expiresAt: Long,
    val title: String,
    val isRateLimited: Boolean = false,
    val rawVideoUrl: String? = null,
    val sessionVersion: Long? = null
)

data class Caption(
    val url: String,
    val language: String,
    val type: String,
    val languageCode: String = "",
    val isVerified: Boolean = true
)

data class TmdbInfo(
    val title: String,
    val year: String
)

data class SearchResult(
    val id: String,
    val title: String,
    val year: String,
    val ott: String,
    val score: Int
)

fun TrailerStream.toNetMirrorStream(title: String = "Trailer", sourceId: String = "trailer"): NetMirrorStream =
    NetMirrorStream(
        url = url,
        headers = headers,
        captions = emptyList(),
        sourceId = sourceId,
        expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L,
        title = title
    )
