package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider signatures and episode routes are opaque; never invent a quality or token mode. */
internal object CdnRoutePolicy {
    data class Token(val hash1: String, val hash2: String, val timestamp: String, val mode: String, val suffix: String) {
        val value: String get() = listOf(hash1, hash2, timestamp, mode).joinToString("::") +
            if (suffix.isBlank()) "" else "::$suffix"
    }
    private fun parse(raw: String): Token? {
        val parts = raw.split("::")
        if (parts.size < 4 || parts.take(2).any { it.isBlank() } || parts[2].toLongOrNull() == null) return null
        return Token(parts[0], parts[1], parts[2], parts[3], parts.drop(4).joinToString("::"))
    }
    fun token(url: String): Token? = url.toHttpUrlOrNull()?.queryParameter("in")?.let(::parse)
    fun tokenInManifest(body: String): Token? {
        val raw = Regex("""in=([^\s"'<>;&]+)""").find(body)?.groupValues?.get(1) ?: return null
        return parse(java.net.URLDecoder.decode(raw, "UTF-8"))
    }
    fun manifestTokensAreFresh(body: String, now: Long): Boolean = Regex("""in=([^\s"'<>;&]+)""").findAll(body).all { match ->
        val token = parse(java.net.URLDecoder.decode(match.groupValues[1], "UTF-8"))
        token == null || StreamSessionPolicy.isFresh(StreamSessionPolicy.tokenIssuedAt(token.timestamp, now), now)
    }
    fun videoRoute(body: String): String? {
        val lines = body.lineSequence().map(String::trim).toList()
        val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
            .mapNotNull { lines.getOrNull(it + 1)?.takeIf { url -> url.startsWith("https://") } }
        return (variants.firstOrNull { it.contains("/720p/") } ?: variants.firstOrNull())
    }
    fun isFresh(url: String, fetchedAt: Long, now: Long): Boolean = StreamSessionPolicy.isFresh(fetchedAt, now) &&
        (token(url)?.let { StreamSessionPolicy.isFresh(StreamSessionPolicy.tokenIssuedAt(it.timestamp, fetchedAt), now) } ?: true)
    fun isCdnSource(url: String, providerHost: String? = null): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && (providerHost == null || parsed.host != providerHost) &&
            !StreamSessionPolicy.isWaitingVideo(url)
    }
    fun playbackUrl(providerUrl: String, host: String, replacementToken: String): String {
        val parsed = providerUrl.toHttpUrlOrNull() ?: throw java.io.IOException("Invalid provider media route")
        if (!parsed.isHttps || parsed.host != host || StreamSessionPolicy.isWaitingVideo(providerUrl))
            throw java.io.IOException("Invalid provider media route")
        // Preserve the original provider encoding/order when its signature is unchanged.
        if (parsed.queryParameter("in") == replacementToken) return providerUrl
        // Retain path/quality and unrelated query parameters. Signature refresh is explicit.
        return parsed.newBuilder().setQueryParameter("in", replacementToken).build().toString()
    }
}
