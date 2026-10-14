package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** Public provider-master request flow; returned CDN signatures remain provider-issued. */
internal object ProviderMasterRequest {
    fun direct(contentId: String, ott: String, baseUrl: String = "https://net52.cc", settings: ProviderRuntimeConfig.Snapshot = ProviderRuntimeConfig.Snapshot()): String {
        if (!PublicIdentityDiscovery.validId(contentId, ott)) throw java.io.IOException("Invalid provider content ID")
        val prefix = when (ott) { "nf" -> "/mobile"; "pv", "hs" -> "/mobile/$ott"; else -> throw java.io.IOException("Unsupported provider route") }
        val url = baseUrl.toHttpUrl().newBuilder().encodedPath("$prefix/hls/$contentId.m3u8").query(null).build()
        return resolve(url.toString(), contentId, baseUrl, settings)
    }

    fun resolve(source: String, contentId: String, baseUrl: String = "https://net52.cc", settings: ProviderRuntimeConfig.Snapshot = ProviderRuntimeConfig.Snapshot()): String {
        val url = source.toHttpUrl()
        val base = baseUrl.toHttpUrl()
        if (!url.isHttps || url.host != base.host || url.port != base.port ||
            !Regex("^/mobile/(?:[a-z]+/)?hls/").containsMatchIn(url.encodedPath)) return source
        if (url.queryParameter("in")?.startsWith("unknown") == false) return source
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val hash = MessageDigest.getInstance("MD5").digest((timestamp + contentId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val request = "${settings.masterHash}::$hash::$timestamp::${settings.masterMode}::m"
        val builder = url.newBuilder().setQueryParameter("in", request)
        // Normal mobile provider entry parameters; keep explicitly issued values.
        for ((name, value) in listOf("hd" to "off", "lang" to "eng", "hp" to "yes")) {
            if (url.queryParameter(name) == null) builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }
}
