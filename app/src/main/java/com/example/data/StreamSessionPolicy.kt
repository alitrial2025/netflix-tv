package com.example.data

internal class PlaybackRateLimitedException(val retryAfterMs: Long? = null) :
    java.io.IOException("Playback service is busy. Please wait before retrying.")

internal object StreamSessionPolicy {
    const val TTL_MS = 10L * 60 * 60 * 1_000
    const val EXPIRY_MARGIN_MS = 60_000L
    private val authError = Regex(
        "\"(?:error|status)\"\\s*:\\s*\"(?:expired|unauthorized|forbidden|invalid_session)\"",
        RegexOption.IGNORE_CASE
    )

    fun retryAfterDelayMs(value: String?, nowMs: Long): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        header.toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return seconds.coerceAtMost(86_400L) * 1_000L
        }
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            format.isLenient = false
            format.parse(header)?.time?.let { (it - nowMs).coerceIn(0L, 86_400_000L) }
        } catch (_: java.text.ParseException) { null }
    }

    fun isFresh(fetchedAt: Long, now: Long): Boolean =
        fetchedAt > 0 && fetchedAt <= now && now - fetchedAt < TTL_MS - EXPIRY_MARGIN_MS

    // A cached manifest must never give an old token another ten hours of life.
    fun tokenIssuedAt(timestamp: String, fetchedAt: Long): Long {
        val seconds = timestamp.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1_000 }
            ?: return 0L
        val issuedAt = seconds * 1_000
        // Allow server clock skew of up to 1 hour, but clamp to fetchedAt.
        if (issuedAt > fetchedAt && issuedAt - fetchedAt > 3600_000L) return 0L
        return minOf(issuedAt, fetchedAt)
    }

    // A provider can return HTTP 200 with a waiting-video variant and real audio.
    // Duration alone is not evidence: children's episodes can legitimately be nine minutes.
    private val waitingVideo = Regex("""/files/220884(?:[/?."\s]|$)""", RegexOption.IGNORE_CASE)
    private val limitMessage = Regex("""\brate[_ -]?limit(?:ed)?\b|too many requests|limit exceeded""", RegexOption.IGNORE_CASE)
    fun isWaitingVideo(value: String): Boolean = waitingVideo.containsMatchIn(value.replace("\\/", "/"))

    fun isRateLimited(code: Int, body: String): Boolean = code == 429 ||
        isWaitingVideo(body) || limitMessage.containsMatchIn(body)

    fun isSessionRejected(code: Int, body: String): Boolean = code == 401 || code == 403 ||
        listOf("in=unknown", "session expired", "token expired", "invalid token", "unauthenticated", "login required", "only valid users allowed")
            .any { body.contains(it, ignoreCase = true) } || authError.containsMatchIn(body)

    // The authenticated search/post endpoints return JSON. HTML from a captive
    // portal or a temporary challenge is not by itself proof of cookie expiry.
    fun isUnexpectedAuthenticatedHtml(requestPath: String, code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        val expectsJson = listOf("/search.php", "/post.php", "/episodes.php", "/episode.php")
            .any { requestPath.endsWith(it, ignoreCase = true) }
        return expectsJson && body.trimStart().startsWith("<")
    }

    fun isAuthLandingPage(requestPath: String, finalPath: String, code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        val isHtml = isUnexpectedAuthenticatedHtml(requestPath, code, body)
        if (!isHtml && finalPath == requestPath) return false
        val redirectedToAuth = finalPath != requestPath &&
            listOf("/home", "/login", "/verify", "/signin")
                .any { finalPath.contains(it, ignoreCase = true) }
        val providerAuthPage = isHtml &&
            listOf("t_hash_t", "addhash", "verify2.php", "/mobile/home")
                .any { body.contains(it, ignoreCase = true) }
        return redirectedToAuth || providerAuthPage
    }
}
