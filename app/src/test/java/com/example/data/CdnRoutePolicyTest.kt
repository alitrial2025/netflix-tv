package com.example.data

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class CdnRoutePolicyTest {
    private val first = "a".repeat(32)
    private val second = "b".repeat(32)
    private val now = 1_800_000_000_000L
    private fun token(mode: String, suffix: String = "") = "$first::$second::${now / 1000}::$mode" + if (suffix.isBlank()) "" else "::$suffix"

    @Test fun currentProviderModesAndSuffixesRoundTripWithoutInventingEk() {
        for (signature in listOf(token("su"), token("su", "myes"), token("ek"), token("ek", "myes"), token("future-v2", "new-tail::version3"))) {
            val route = "https://s23.nm-cdn9.top/files/episode/1080p/custom.m3u8?in=$signature&lang=eng"
            val parsed = requireNotNull(CdnRoutePolicy.token(route))
            assertEquals(route, CdnRoutePolicy.playbackUrl(route, "s23.nm-cdn9.top", signature))
            assertEquals(signature, parsed.value)
            assertEquals(signature, CdnRoutePolicy.playbackUrl(route, "s23.nm-cdn9.top", parsed.value).toHttpUrl().queryParameter("in"))
            assertEquals(signature, CdnRoutePolicy.tokenInManifest("#EXTM3U\n$route")?.value)
        }
    }
    @Test fun discoveryKeepsNonCanonicalLayoutQualityAndAdditionalQuery() {
        val route = "https://s23.nm-cdn9.top/library/episode/full-hd/index.m3u8?in=${token("su", "myes")}&hd=on&language=eng"
        val resolved = CdnRoutePolicy.playbackUrl(route, "s23.nm-cdn9.top", token("su", "myes")).toHttpUrl()
        assertEquals("/library/episode/full-hd/index.m3u8", resolved.encodedPath)
        assertEquals("on", resolved.queryParameter("hd"))
        assertEquals("eng", resolved.queryParameter("language"))
        assertTrue(CdnRoutePolicy.isCdnSource(route))
        assertTrue(CdnRoutePolicy.isCdnSource("https://future-cdn.invalid/signed-playlist?signature=opaque", "net52.cc"))
    }
    @Test fun oldTokenCannotBorrowTheNewerRoutingCacheTimestamp() {
        val route = "https://s23.nm-cdn9.top/files/episode/master.m3u8?in=${token("su").replace((now / 1000).toString(), ((now - StreamSessionPolicy.TTL_MS) / 1000).toString())}"
        assertFalse(CdnRoutePolicy.isFresh(route, now, now))
        assertTrue(CdnRoutePolicy.isFresh("https://s23.nm-cdn9.top/files/episode/master.m3u8?in=${token("su")}", now, now))
        assertFalse(CdnRoutePolicy.isFresh(route, now + 1, now))
    }
    @Test fun dummyWaitingVideoAndHostSubstitutionAreRejected() {
        assertFalse(CdnRoutePolicy.isCdnSource("https://s23.nm-cdn9.top/files/220884/master.m3u8"))
        try { CdnRoutePolicy.playbackUrl("https://other.invalid/title.m3u8", "s23.nm-cdn9.top", token("su")); fail() } catch (_: java.io.IOException) {}
    }
}
