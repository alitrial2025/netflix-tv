package com.example.data

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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NewTvSeasonLabelTest {
    private val showId = "0SERIES123456789"
    private val seasonId = "0SEASON400000001"
    private val episodeId = "0EPISODE4000001"

    @Test fun episodeCountsDoNotChangeTheRequestedSeasonOrEpisode() = runBlocking {
        for (label in listOf("Season 4 (10 EP)", "S4 (10 episodes)", "4 (10 Episode)",
            " season 4 ( 10 ep ) ", "Season4", "S4", "4")) {
            val seen = mutableListOf<Request>()
            val resolver = resolver(seen, JSONArray()
                .put(JSONObject().put("s", "Season 2 (4 EP)").put("id", "0SEASON200000001"))
                .put(JSONObject().put("s", label).put("id", seasonId)))
            assertEquals(episodeId, resolver.resolveSeriesEpisodeId(showId, "pv", "The Expanse", 4, 1))
            val episodes = seen.single { it.url.encodedPath == "/newtv/episodes.php" }
            assertEquals(seasonId, episodes.url.queryParameter("id"))
            assertEquals("1", episodes.url.queryParameter("page"))
            assertEquals(3, seen.size)
        }
    }

    @Test fun unrelatedNumbersAndUnrecognizedSuffixesCannotSelectASeason() = runBlocking {
        for (label in listOf("Specials 4 (10 EP)", "Released 2024 Season 4", "Episode 4",
            "Season 4 Part 2", "Season 4 (10 EP) unrelated", "S40 (10 EP)")) {
            val seen = mutableListOf<Request>()
            val resolver = resolver(seen, JSONArray().put(JSONObject().put("s", label).put("id", seasonId)))
            try {
                resolver.resolveSeriesEpisodeId(showId, "pv", "The Expanse", 4, 1)
                fail("Unrelated season label accepted: $label")
            } catch (error: IOException) { assertEquals("Requested NewTV season is unavailable", error.message) }
            assertFalse(seen.any { it.url.encodedPath.endsWith("episodes.php") })
        }
    }

    @Test fun conflictingCountedSeasonLabelsAreRejectedBeforeEpisodeLookup() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = resolver(seen, JSONArray()
            .put(JSONObject().put("s", "Season 4 (10 EP)").put("id", seasonId))
            .put(JSONObject().put("s", "S4 (12 episodes)").put("id", "0OTHERSEASON1234")))
        try {
            resolver.resolveSeriesEpisodeId(showId, "pv", "The Expanse", 4, 1)
            fail("Conflicting season identities accepted")
        } catch (error: IOException) { assertEquals("NewTV season identity is ambiguous", error.message) }
        assertFalse(seen.any { it.url.encodedPath.endsWith("episodes.php") })
    }

    private fun resolver(seen: MutableList<Request>, seasons: JSONArray): NewTvPlaybackResolver {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            val body = when (request.url.encodedPath) {
                "/checknewtv.php" -> JSONObject().put("token_hash", Base64.getEncoder()
                    .encodeToString("https://tv.imgcdn.kim".toByteArray())).toString()
                "/newtv/post.php" -> JSONObject().put("status", "y").put("title", "The Expanse")
                    .put("type", "t").put("season", seasons).toString()
                "/newtv/episodes.php" -> """{"episodes":[{"id":"$episodeId","s":"S4","ep":"E1"}],"nextPageShow":0}"""
                else -> throw AssertionError("Unexpected request ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return NewTvPlaybackResolver(client, gate = CookieFreePlaybackGate())
    }
}
