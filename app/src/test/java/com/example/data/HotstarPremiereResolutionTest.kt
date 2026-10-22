package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HotstarPremiereResolutionTest {
    private val path = "/tv-shows/perfect-crown/HOTSTAR_DTH_TVSHOW_1271531261"
    private fun episodes(season: Int, numbers: IntRange) = JSONArray().apply {
        numbers.forEach { number -> put(JSONObject().put("id", "127153${season}9${number}").put("s", "S$season")
            .put("ep", "E$number").put("date", if (season == 1) "10 Apr 2026" else "10 Apr 2028")) }
    }
    private fun resolver(seen: MutableList<Request>, latestSeason: Int = 1) = PublicPlaybackResolver(
        cookieFreeRoutes = false, backgroundCatalogRefresh = false,
        catalog = PublicProviderCatalog(ApplicationProvider.getApplicationContext<Application>()),
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            val body = when (request.url.encodedPath) {
                path -> """<p id="banner-content-release-year">2028</p><script>{"@type":"VideoObject","name":"Perfect Crown","uploadDate":"1970-01-01T00:00:00.000Z"}</script>"""
                "/mobile/hs/post.php" -> {
                    assertEquals("1271531261", request.url.queryParameter("id"))
                    JSONObject().put("status", "y").put("type", "t").put("title", "Perfect Crown").put("year", "2028")
                        .put("season", JSONArray("""[{"s":"1","id":"1271531149"},{"s":"2","id":"1271531150"}]"""))
                        .put("episodes", episodes(latestSeason, 1..10)).put("nextPageShow", 1).toString()
                }
                "/mobile/hs/episodes.php" -> {
                    assertEquals("1271531261", request.url.queryParameter("series"))
                    assertEquals("1271531149", request.url.queryParameter("s"))
                    val secondPage = request.url.queryParameter("page") == "2"
                    JSONObject().put("episodes", episodes(1, if (secondPage) 11..12 else 1..10))
                        .put("nextPageShow", if (secondPage) 0 else 1).toString()
                }
                "/mobile/hs/playlist.php" -> """{"sources":[{"file":"https://cdn.example/crown.m3u8?in=issued"}]}"""
                "/crown.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
                "/search.php", "/mobile/pv/search.php" -> """{"status":"n"}"""
                "/3/tv/278573", "/sparql", "/tv-shows", "/tv-shows/english-tv-shows" -> "{}"
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
    )

    @Test fun perfectCrownUsesPublishedIndexAndDatedPilotWithoutSlowerDiscovery() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(seen).resolve("Perfect Crown", "2026", "tv", 1, 1, "278573")
        assertEquals("hs", result.ott)
        assertEquals("127153191", result.contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/post.php" })
        assertFalse(seen.any { it.url.host.contains("themoviedb") || it.url.host.contains("wikidata") || it.url.encodedPath.endsWith("search.php") })
    }

    @Test fun finalEpisodeUsesActualPaginatedSeasonMapping() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(seen).resolve("Perfect Crown", "2026", "tv", 1, 12, "278573")
        assertEquals("1271531912", result.contentId)
        assertTrue(seen.any { it.url.encodedPath == "/mobile/hs/episodes.php" && it.url.queryParameter("page") == "2" })
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/post.php" })
    }

    @Test fun laterSeasonPostVerifiesPremiereUsingPublishedSeasonOneIdAndRetainsRequestedEpisode() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(seen, latestSeason = 2).resolve("Perfect Crown", "2026", "tv", 2, 1, "278573")
        assertEquals("127153291", result.contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/episodes.php" })
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/post.php" })
    }

    @Test fun genericLaterSeasonYearCannotVerifyWrongPremiereOrRequestPlayback() = runBlocking {
        val seen = mutableListOf<Request>()
        try {
            resolver(seen).resolve("Perfect Crown", "2028", "tv", 1, 1, "278573")
            fail("Generic display year must not override the pilot premiere year")
        } catch (_: IOException) { }
        assertFalse(seen.any { it.url.encodedPath.endsWith("playlist.php") || it.url.encodedPath.endsWith(".m3u8") })
    }
}
