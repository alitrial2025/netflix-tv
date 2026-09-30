package com.example.data

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectCDNSessionCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE)

    @Before fun resetPreferences() { prefs.edit().clear().commit() }

    private fun session(timestamp: Long = System.currentTimeMillis()) = JSONObject()
        .put("domain", "example.invalid")
        .put("addhashRaw", "test-addhash")
        .put("addhashEncoded", "test-addhash")
        .put("tHashTEncoded", "test-cookie")
        .put("fetchedAt", timestamp)

    @Test fun anEmptyPoolNeverResurrectsTheLegacyCookie() {
        prefs.edit().putString("directcdn_session", session().toString())
            .putString("directcdn_sessions", "[]").commit()
        assertFalse(DirectCDNResolver(context).hasValidSession())
    }

    @Test fun legacyCookieIsMigratedOnlyOnce() {
        prefs.edit().putString("directcdn_session", session().toString()).commit()
        assertTrue(DirectCDNResolver(context).hasValidSession())
        assertFalse(prefs.contains("directcdn_session"))
        assertEquals(1, JSONArray(prefs.getString("directcdn_sessions", "[]")).length())
    }

    @Test fun cachedAddhashWithoutTHashIsNotAValidSession() {
        val incomplete = session().apply { remove("tHashTEncoded") }
        prefs.edit().putString("directcdn_sessions", JSONArray().put(incomplete).toString()).commit()

        assertFalse(DirectCDNResolver(context).hasValidSession())
    }

    @Test fun legacyAddhashWithoutTHashIsDiscardedDuringMigration() {
        val incomplete = session().put("tHashTEncoded", "")
        prefs.edit().putString("directcdn_session", incomplete.toString()).commit()

        assertFalse(DirectCDNResolver(context).hasValidSession())
        assertFalse(prefs.contains("directcdn_session"))
        assertEquals(0, JSONArray(prefs.getString("directcdn_sessions", "[]")).length())
    }

    @Test fun completeUnexpiredSessionSurvivesReload() {
        prefs.edit().putString("directcdn_sessions", JSONArray().put(session()).toString()).commit()

        assertTrue(DirectCDNResolver(context).hasValidSession())
        assertTrue(DirectCDNResolver(context).hasValidSession())
        assertEquals(1, JSONArray(prefs.getString("directcdn_sessions", "[]")).length())
    }

    @Test fun expiredCookieJarValueCannotResurrectTHash() {
        val jar = AppCookieJar()
        val url = "https://example.invalid/".toHttpUrl()
        val expired = Cookie.Builder()
            .name("t_hash_t")
            .value("expired")
            .domain("example.invalid")
            .expiresAt(System.currentTimeMillis() - 60_000L)
            .build()
        jar.saveFromResponse(url, listOf(expired))

        assertNull(jar.getCookieValue("example.invalid", "t_hash_t"))
    }

    @Test fun missingTimestampIsNotConsideredFresh() {
        val invalid = session().apply { remove("fetchedAt") }
        prefs.edit().putString("directcdn_sessions", JSONArray().put(invalid).toString()).commit()
        assertFalse(DirectCDNResolver(context).hasValidSession())
    }

    @Test fun cookieLessCdnFailureClearsSessionAndDependentTokensAcrossRestart() {
        prefs.edit().putString("directcdn_sessions", JSONArray().put(session()).toString())
            .putString("directcdn_session", session().toString())
            .putString("freecdn_nonce", "old-token")
            .putString("freecdn_routing_v1", "old-route").commit()
        val resolver = DirectCDNResolver(context)
        resolver.invalidateSessionByCookie(null)
        assertEquals(1L, resolver.sessionVersion)
        assertFalse(DirectCDNResolver(context).hasValidSession())
        assertFalse(prefs.contains("directcdn_session"))
        assertFalse(prefs.contains("freecdn_nonce"))
        assertFalse(prefs.contains("freecdn_routing_v1"))
    }

    @Test fun naturalTenHourExpiryRetiresNewerDependentTokensOnlyOnce() {
        val expired = session(System.currentTimeMillis() - StreamSessionPolicy.TTL_MS)
        prefs.edit().putString("directcdn_sessions", JSONArray().put(expired).toString())
            .putString("freecdn_nonce", "token-created-after-the-cookie")
            .putString("freecdn_routing_v1", "cached-route").commit()
        val resolver = DirectCDNResolver(context)
        assertFalse(resolver.hasValidSession())
        assertEquals(1L, resolver.sessionVersion)
        assertFalse(prefs.contains("freecdn_nonce"))
        assertFalse(prefs.contains("freecdn_routing_v1"))
        assertEquals(0, JSONArray(prefs.getString("directcdn_sessions", "[]")).length())
        assertFalse(resolver.hasValidSession())
        assertEquals(1L, resolver.sessionVersion)
        assertFalse(DirectCDNResolver(context).hasValidSession())
    }

    @Test fun expirySafetyMarginTriggersRenewalBeforeTheTenHourBoundary() {
        val nearExpiry = session(System.currentTimeMillis() - StreamSessionPolicy.TTL_MS + 30_000)
        prefs.edit().putString("directcdn_sessions", JSONArray().put(nearExpiry).toString()).commit()
        assertFalse(DirectCDNResolver(context).hasValidSession())
        assertEquals(0, JSONArray(prefs.getString("directcdn_sessions", "[]")).length())
    }

    @Test fun delayedMediaAndSubtitleResultsCannotAdoptAReplacementSession() {
        prefs.edit().putString("directcdn_sessions", JSONArray().put(session()).toString()).commit()
        val resolver = DirectCDNResolver(context)
        val inFlight = NetMirrorStream(
            url = "https://example.invalid/video.m3u8", headers = emptyMap(), captions = emptyList(),
            sourceId = "fixture", expiresAt = Long.MAX_VALUE, title = "Fixture",
            sessionVersion = resolver.sessionVersion
        )
        // The cookie is replaced after media resolution, before the caller
        // stores its result or an asynchronous subtitle response returns.
        resolver.invalidateSessionByCookie(null)
        val withCaptions = inFlight.copy(captions = listOf(Caption("https://example.invalid/sub.vtt", "English", "vtt")))
        assertEquals(1L, resolver.sessionVersion)
        assertEquals(0L, resolver.sessionVersionFor(inFlight))
        assertEquals(0L, resolver.sessionVersionFor(withCaptions))
        assertNotEquals(resolver.sessionVersion, resolver.sessionVersionFor(withCaptions))
    }
}
