package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** Public provider-master request flow; returned CDN signatures remain provider-issued. */
internal object ProviderMasterRequest {
    fun resolve(source: String, contentId: String, baseUrl: String = "https://net52.cc"): String {
        val url = source.toHttpUrl()
        val base = baseUrl.toHttpUrl()
        if (!url.isHttps || url.host != base.host || url.port != base.port ||
            !Regex("^/mobile/(?:[a-z]+/)?hls/").containsMatchIn(url.encodedPath)) return source
        if (url.queryParameter("in")?.startsWith("unknown") == false) return source
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val hash = MessageDigest.getInstance("MD5").digest((timestamp + contentId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val request = "235ca31540ab8d90fcef4a00de8a247c::$hash::$timestamp::ek::m"
        return url.newBuilder().setQueryParameter("in", request).build().toString()
    }
}
