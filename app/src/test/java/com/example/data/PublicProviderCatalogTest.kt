package com.example.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicProviderCatalogTest {
    @Test fun joinedCatalogUsesMediaTypeAndTmdbIdentityAndCacheSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = PublicProviderCatalog(context)
        assertTrue(store.candidates("movie", "238").any { it.second == "nf" })
        assertTrue(store.candidates("invalid", "238").isEmpty())
        assertTrue(store.hotstarTitles("tv","The Penguin").any { it.first == "1971003606" })
        assertTrue(store.hotstarTitles("movie","The Penguin").isEmpty())
        assertTrue(store.hotstarTitles("movie","Tumse Na Ho Payega").any { it.first == "1091922" })
        val key = "fixture:movie:238:thegodfather:1972"
        store.evict(key)
        assertNull(store.verified(key, 100_000L))
        store.save(key,"60011152","nf",100_000L)
        assertEquals("60011152" to "nf", PublicProviderCatalog(context).verified(key, 100_001L))
        assertNull(store.verified(key, 100_000L + 7 * 86_400_000L))
        store.evict(key)
        assertNull(PublicProviderCatalog(context).verified(key,100_001L))
        store.save(key,"https://evil.example/","nf",100_000L)
        assertNull(store.verified(key,100_001L))
    }
}
