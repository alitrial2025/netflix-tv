package com.example.data

import org.junit.Assert.*
import org.junit.Test

class StreamSessionPolicyTest {
    private val now = 1_700_000_000_000L

    @Test fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(120_000L, StreamSessionPolicy.retryAfterDelayMs("120", now))
        assertEquals(60_000L, StreamSessionPolicy.retryAfterDelayMs("Tue, 14 Nov 2023 22:14:20 GMT", now))
        assertEquals(0L, StreamSessionPolicy.retryAfterDelayMs("Tue, 14 Nov 2023 22:12:20 GMT", now))
        assertNull(StreamSessionPolicy.retryAfterDelayMs("-3", now))
        assertNull(StreamSessionPolicy.retryAfterDelayMs("invalid", now))
    }

    @Test fun missingFutureAndExpiredTimestampsAreRejected() {
        assertFalse(StreamSessionPolicy.isFresh(0, now))
        assertFalse(StreamSessionPolicy.isFresh(now + 1, now))
        assertFalse(StreamSessionPolicy.isFresh(now - StreamSessionPolicy.TTL_MS, now))
        assertTrue(StreamSessionPolicy.isFresh(now - 1_000, now))
    }

    @Test fun rereadingAnOldManifestDoesNotExtendItsTokenLifetime() {
        val issuedAt = now - StreamSessionPolicy.TTL_MS
        val effectiveTime = StreamSessionPolicy.tokenIssuedAt((issuedAt / 1_000).toString(), now)
        assertEquals(issuedAt, effectiveTime)
        assertFalse(StreamSessionPolicy.isFresh(effectiveTime, now))
    }

    @Test fun normalFrameRateMetadataIsNotARateLimit() {
        assertFalse(StreamSessionPolicy.isRateLimited(200, "#EXTM3U\n#EXT-X-STREAM-INF:FRAME-RATE=24.000\nvideo.m3u8"))
        assertTrue(StreamSessionPolicy.isRateLimited(429, ""))
        assertTrue(StreamSessionPolicy.isRateLimited(200, "Too many requests"))
        assertFalse(StreamSessionPolicy.isSessionRejected(429, "Too many requests"))
    }

    @Test fun expiredAuthenticationIsDetected() {
        assertTrue(StreamSessionPolicy.isSessionRejected(401, ""))
        assertTrue(StreamSessionPolicy.isSessionRejected(403, ""))
        assertTrue(StreamSessionPolicy.isSessionRejected(200, "#EXTM3U\nvideo.m3u8?in=unknown"))
        assertTrue(StreamSessionPolicy.isSessionRejected(200, "{\"error\":\"expired\"}"))
        assertFalse(StreamSessionPolicy.isSessionRejected(503, "Service unavailable"))
        assertFalse(StreamSessionPolicy.isSessionRejected(200, """{"sources":[{"file":"/mobile/hls/episode.m3u8?in=unknown"}]}"""))
    }

    @Test fun onlyAnActualProviderCookieCanBeInvalidatedByPlaybackFailure() {
        assertNull(StreamSessionPolicy.providerCookieHash(null))
        assertNull(StreamSessionPolicy.providerCookieHash("ott=nf; addhash=fixture"))
        assertNull(StreamSessionPolicy.providerCookieHash("other_t_hash_t=fixture"))
        assertNull(StreamSessionPolicy.providerCookieHash("t_hash_t="))
        assertEquals("fixture", StreamSessionPolicy.providerCookieHash("addhash=other; t_hash_t=fixture; lang=eng"))
    }

    @Test fun authenticatedSearchRedirectsAndHtmlTriggerSessionRenewal() {
        val search = "/mobile/search.php"
        assertTrue(StreamSessionPolicy.isAuthLandingPage(search, "/mobile/home", 200, "{}"))
        assertTrue(StreamSessionPolicy.isAuthLandingPage(search, search, 200, "<html>verify2.php</html>"))
        assertFalse(StreamSessionPolicy.isAuthLandingPage(search, search, 200, "<html>Temporary gateway error</html>"))
        assertTrue(StreamSessionPolicy.isUnexpectedAuthenticatedHtml(search, 200, "<html>Temporary gateway error</html>"))
        assertFalse(StreamSessionPolicy.isAuthLandingPage(search, "/search.php", 200, "{\"searchResult\":[]}"))
        assertFalse(StreamSessionPolicy.isAuthLandingPage(search, search, 503, "<html>Service unavailable</html>"))
        assertFalse(StreamSessionPolicy.isAuthLandingPage("/mobile/home", "/mobile/home", 200, "<html>Home</html>"))
    }
}
