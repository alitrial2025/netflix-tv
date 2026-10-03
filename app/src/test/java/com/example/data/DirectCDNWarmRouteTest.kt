package com.example.data

import android.content.Context
import com.example.model.Movie
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectCDNWarmRouteTest {
    @Test fun cachedWarmRouteUsesExactProviderSignatureWithoutHandshake() = exerciseRoute(false)
    @Test fun cdnRejectionStopsWithoutDiscardingUnusedLegacySession() = exerciseRoute(true)
    @Test fun unsignedProviderRouteIsNotGivenAnotherTitlesNonce() = exerciseRoute(false, unsigned = true)
    private fun exerciseRoute(rejected: Boolean, unsigned: Boolean = false) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val now = System.currentTimeMillis()
        val token = "${"a".repeat(32)}::${"b".repeat(32)}::${now / 1000}::su::myes"
        val url = "https://dynamic-cdn.invalid/custom/full-hd/media" + if (unsigned) "" else "?in=$token&lang=eng"
        val session = JSONObject().put("domain", "provider.invalid").put("addhashRaw", "fixture")
            .put("addhashEncoded", "fixture").put("tHashTEncoded", "fixture-cookie").put("fetchedAt", now)
        prefs.edit().putString("last_token_mode", "ek").putString("directcdn_sessions", JSONArray().put(session).toString())
            .putString("freecdn_routing_v1", JSONObject().put("episode", JSONObject()
                .put("host", "dynamic-cdn.invalid").put("freecdnUrl", url).put("fetchedAt", now)).toString()).commit()
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requested += req.url.encodedPath
            val body = when (req.url.encodedPath) {
                "/3/movie/fixture" -> """{"title":"Fixture","release_date":"2001-01-01"}"""
                "/mobile/playlist.php" -> JSONObject().put("sources", JSONArray().put(JSONObject().put("file", url))).put("tracks", JSONArray()).toString()
                "/search.php" -> """{"searchResult":[{"id":"8100000003","t":"Fixture","y":"2001"}]}"""
                "/title/8100000003" -> """<script type="application/ld+json">{"@type":"Movie","name":"Fixture","datePublished":"2001-01-01"}</script>"""
                "/custom/full-hd/media" -> {
                    assertEquals(if (unsigned) null else token, req.url.queryParameter("in"))
                    if (rejected) "Only valid users allowed" else "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST"
                }
                else -> throw AssertionError("Unexpected handshake or metadata call: ${req.url.encodedPath}")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        // A silent preview uses the exact video route and requires no optional audio/caption probes.
        val resolver = DirectCDNResolver(context, client)
        val movie = Movie(id = "fixture", title = "Fixture", year = "2001", type = "Movie", duration = "", description = "", backdropUrl = "", posterUrl = "")
        if (rejected) {
            try { resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW); fail("Rejected media must not be returned") }
            catch (_: java.io.IOException) { }
            assertEquals(1, requested.count { it == "/custom/full-hd/media" })
        } else {
            val result = resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW)
            assertEquals(url, result.url)
            val requestsBefore = requested.size
            resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW)
            assertEquals("Warm cache must make zero network requests", requestsBefore, requested.size)
        }
        assertEquals(0L, resolver.sessionVersion)
        assertTrue(resolver.hasValidSession())
        assertFalse(requested.any { it.contains("verify") || it.contains("home") })
        assertFalse("Card metadata avoids an extra TMDB fetch", requested.any { it.startsWith("/3/movie/") })
    }
}
