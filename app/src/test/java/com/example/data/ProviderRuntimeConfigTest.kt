package com.example.data

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderRuntimeConfigTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "runtime_config_test"
    private fun prefs() = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun client(block: (Request) -> Pair<String, Int>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        val (body, code) = block(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Set-Cookie", "ignored=value; Path=/").body(body.toResponseBody()).build()
    }.build()

    @Test fun refreshReachesActivePublicPathAndKeepsResolutionSnapshotStable() = runBlocking {
        prefs().edit().clear().putLong("remote_config_checked_at", System.currentTimeMillis()).commit()
        var timestamp = System.currentTimeMillis()
        val http = client { request ->
            when (request.url.encodedPath) {
                "/updates/streaming.json" -> """{"providerBaseUrl":"https://net53.cc","tokenHint":{"masterMode":"future","hash1":"cccccccccccccccccccccccccccccccc"}}""" to 200
                "/search.php" -> {
                    // Changes arriving during a lookup must only affect the next lookup.
                    prefs().edit().putString("remote_provider_base_url", "https://other.example")
                        .putString("remote_master_mode", "later").commit()
                    """{"searchResult":[{"id":"81458416","t":"Fixture Film","y":"2024"}]}""" to 200
                }
                "/mobile/post.php" -> """{"status":"y","title":"Fixture Film","type":"m","year":"2024"}""" to 200
                "/title/81458416" -> """<script type="application/ld+json">{"@type":"Movie","name":"Fixture Film","datePublished":"2024-01-01"}</script>""" to 200
                "/mobile/playlist.php" -> {
                    assertEquals("net53.cc", request.url.host)
                    """{"sources":[{"file":"/mobile/hls/81458416.m3u8?in=unknown::ek"}]}""" to 200
                }
                "/mobile/hls/81458416.m3u8" -> {
                    assertEquals("net53.cc", request.url.host)
                    val parts = request.url.queryParameter("in")!!.split("::")
                    assertEquals("cccccccccccccccccccccccccccccccc", parts[0])
                    assertEquals("future", parts[3])
                    "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                }
                else -> throw AssertionError("Unexpected fixture path")
            }
        }
        val runtime = ProviderRuntimeConfig(context, http, name) { timestamp }
        runtime.refresh()
        val resolver = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, runtimeConfig = runtime)
        val result = resolver.resolve("Fixture Film", "2024", "movie", 0, 0)
        assertEquals("net53.cc", result.url.toHttpUrl().host)
        assertEquals("other.example", runtime.snapshot().baseUrl.toHttpUrl().host)
    }

    @Test fun failuresBackOffAndRetainLastGoodSettingsWithoutPersistingCookies() = runBlocking {
        prefs().edit().clear().commit()
        var timestamp = System.currentTimeMillis()
        val calls = AtomicInteger()
        val http = client {
            val call = calls.incrementAndGet()
            if (call == 1) """{"providerBaseUrl":"https://net53.cc","tokenHint":{"masterMode":"future"}}""" to 200
            else "" to 503
        }
        val runtime = ProviderRuntimeConfig(context, http, name) { timestamp }
        runtime.refresh()
        val good = runtime.snapshot()
        timestamp += 6 * 3_600_000L
        coroutineScope { repeat(3) { launch { runtime.refresh() } } }
        assertEquals(2, calls.get()); assertEquals(good, runtime.snapshot())
        timestamp += 30 * 60_000L
        runtime.refresh()
        assertEquals(3, calls.get())
        assertFalse(prefs().all.keys.any { it.contains("cookie", ignoreCase = true) })
    }

    @Test fun invalidHintsAndUnsafeProviderOriginsRetainKnownGoodSettings() {
        val previous = ProviderRuntimeConfig.Snapshot("https://net53.cc", "cccccccccccccccccccccccccccccccc", "future")
        for (base in listOf("http://net53.cc", "https://user:pass@net53.cc", "https://net53.cc/path", "https://net53.cc?x=1",
            "https://net53.cc#fragment", "https://net53.cc:8443", "https://127.0.0.1", "https://localhost", "https://host.local")) {
            val invalid = JSONObject().put("providerBaseUrl", base).put("tokenHint", JSONObject().put("hash1", "bad").put("masterMode", "a::b"))
            assertEquals(previous, ProviderRuntimeConfig.parse(invalid, previous))
        }
        val issued = "https://cdn.example/mobile/hls/movie.m3u8?in=issued::independent&lang=eng"
        assertEquals(issued, ProviderMasterRequest.resolve(issued, "81458416", previous.baseUrl, previous))
        val providerIssued = "https://net53.cc/mobile/hls/movie.m3u8?in=issued::independent&lang=eng"
        assertEquals(providerIssued, ProviderMasterRequest.resolve(providerIssued, "81458416", previous.baseUrl, previous))
    }

    @Test fun bootstrapCandidatesPreferConfiguredHostAndRemainFixedForThatAttempt() {
        val settings = ProviderRuntimeConfig.Snapshot("https://net53.cc")
        val fallbacks = mutableListOf("net52.cc", "net53.cc", "netmirror.app", "localhost", "net52.cc/path")
        val candidates = settings.bootstrapDomains(fallbacks)
        fallbacks.clear()
        assertEquals(listOf("net53.cc", "net52.cc", "netmirror.app"), candidates)
    }

    @Test fun originRequirementNeverReusesAnotherMirrorsCachedSession() {
        assertTrue(ProviderRuntimeConfig.sessionMatches("net52.cc", null))
        assertTrue(ProviderRuntimeConfig.sessionMatches("net53.cc", "net53.cc"))
        assertFalse(ProviderRuntimeConfig.sessionMatches("net52.cc", "net53.cc"))
        assertFalse(ProviderRuntimeConfig.sessionMatches("localhost", "localhost"))
        assertFalse(ProviderRuntimeConfig.sessionMatches("net53.cc/path", "net53.cc/path"))
    }
}
