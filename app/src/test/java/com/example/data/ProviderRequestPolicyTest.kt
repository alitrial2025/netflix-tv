package com.example.data

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestPolicyTest {
    private fun paced(url: String, cookie: String? = null): Boolean {
        val request = Request.Builder().url(url).apply { if (cookie != null) header("Cookie", cookie) }.build()
        return ProviderRequestPolicy.needsPacing(request, "provider.invalid", listOf("backup.invalid"))
    }

    @Test fun providerOriginsAndRedirectedSessionEndpointsRetainPacing() {
        assertTrue(paced("https://provider.invalid/mobile/home"))
        assertTrue(paced("https://userver.provider.invalid/"))
        assertTrue(paced("https://backup.invalid/mobile/verify2.php"))
        assertTrue(paced("https://new-mirror.invalid/mobile/search.php", "t_hash_t=fixture"))
        assertTrue(paced("https://new-mirror.invalid/mobile/verify2.php", "addhash=fixture"))
    }

    @Test fun signedCdnAndTmdbNeverUseTheProviderQueue() {
        assertFalse(paced("https://cdn.invalid/video.m3u8?in=provider-issued-signature"))
        assertFalse(paced("https://api.themoviedb.org/3/tv/4607"))
        assertFalse(paced("https://notprovider.invalid/video.m3u8"))
    }
}
