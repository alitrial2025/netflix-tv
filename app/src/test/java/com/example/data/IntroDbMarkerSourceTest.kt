package com.example.data

import com.example.ui.screens.PlaybackMarkers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IntroDbMarkerSourceTest {
    // Shapes transcribed from the published CloudStream adapters, not live service captures.
    private val introDb = """{"imdb_id":"tt0903747","season":1,"episode":2,
      "intro":{"start_ms":31000,"end_ms":64000,"confidence":0.95,"submission_count":7},
      "outro":{"start_ms":2410000,"end_ms":2460000,"confidence":0.9,"submission_count":3}}"""
    private val movie = """{"tmdb_id":550,"type":"movie",
      "intro":[{"start_ms":9000,"end_ms":28000}],
      "credits":[{"start_ms":8200000,"end_ms":8340000}]}"""
    private data class Fixture(val body: String, val code: Int = 200, val retry: String? = null)
    private fun client(seen: MutableList<Request>, respond: (Request) -> Fixture): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            val response = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(response.code).message("fixture").apply { response.retry?.let { header("Retry-After", it) } }
                .body(response.body.toResponseBody()).build()
        }.build()

    @Test fun tvLookupUsesExactIdentityAndCachesWithoutSecondProvider() = runBlocking {
        val seen = mutableListOf<Request>()
        val source = IntroDbMarkerSource(client(seen) { Fixture(introDb) })
        val result = source.markers("1396", "tt0903747", true, 1, 2, 2460000)
        assertEquals(PlaybackMarkers(31000, 64000, 2410000), result)
        assertEquals(result, source.markers("1396", "tt0903747", true, 1, 2, 2460000))
        assertEquals(1, seen.size)
        assertEquals("api.introdb.app", seen.single().url.host)
        assertEquals("/segments", seen.single().url.encodedPath)
        assertEquals("tt0903747", seen.single().url.queryParameter("imdb_id"))
        assertEquals("1", seen.single().url.queryParameter("season"))
        assertEquals("2", seen.single().url.queryParameter("episode"))
    }

    @Test fun movieLookupUsesPublishedV2EndpointWithoutEpisodeParameters() = runBlocking {
        val seen = mutableListOf<Request>()
        val source = IntroDbMarkerSource(client(seen) { Fixture(movie) })
        assertEquals(PlaybackMarkers(9000, 28000, 8200000), source.markers("550", null, false, 1, 1, 8340000))
        assertEquals("api.theintrodb.org", seen.single().url.host)
        assertEquals("/v2/media", seen.single().url.encodedPath)
        assertEquals("550", seen.single().url.queryParameter("tmdb_id"))
        assertNull(seen.single().url.queryParameter("season"))
        assertNull(seen.single().url.queryParameter("episode"))
    }

    @Test fun episodeOrTitleMismatchAndWeakSamplesDoNotCreatePills() {
        for (bad in listOf(
            JSONObject(introDb).put("imdb_id", "tt1234567"),
            JSONObject(introDb).put("season", 2),
            JSONObject(introDb).put("episode", 3),
            JSONObject(introDb).put("episode", 2.5)
        )) assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseIntroDb(bad, "tt0903747", 1, 2, 2460000))
        for (field in listOf("confidence", "submission_count")) {
            val bad = JSONObject(introDb)
            for (segment in listOf("intro", "outro")) bad.getJSONObject(segment).put(field, if (field == "confidence") 0.4 else 1)
            assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseIntroDb(bad, "tt0903747", 1, 2, 2460000))
        }
    }

    @Test fun timingBoundsAndAmbiguousEditionsAreRejected() {
        val bad = JSONObject(introDb)
        bad.getJSONObject("intro").put("end_ms", 31000)
        bad.getJSONObject("outro").put("end_ms", 2460001)
        assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseIntroDb(bad, "tt0903747", 1, 2, 2460000))
        val editions = JSONObject(movie)
        editions.getJSONArray("intro").put(JSONObject("""{"start_ms":12000,"end_ms":31000}"""))
        assertNull(IntroDbMarkerParser.parseTheIntroDb(editions, 550, false, 1, 1, 8340000).introStart)
        assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseTheIntroDb(JSONObject(movie), 551, false, 1, 1, 8340000))
    }

    @Test fun overlappingIntroAndCreditsAreNotAdjustedIntoInventedTimings() {
        val bad = JSONObject(introDb)
        bad.getJSONObject("outro").put("start_ms", 50000)
        assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseIntroDb(bad, "tt0903747", 1, 2, 2460000))
    }

    @Test fun explicitCreditsStartDoesNotNeedAnEndButIntroAlwaysDoes() {
        val root = JSONObject(movie)
        root.getJSONArray("intro").getJSONObject(0).remove("end_ms")
        root.getJSONArray("credits").getJSONObject(0).remove("end_ms")
        assertEquals(PlaybackMarkers(null, null, 8200000), IntroDbMarkerParser.parseTheIntroDb(root, 550, false, 1, 1, 8340000))
        root.put("type", "unknown")
        assertEquals(PlaybackMarkers(), IntroDbMarkerParser.parseTheIntroDb(root, 550, false, 1, 1, 8340000))
    }

    @Test fun markerRateLimitStaysWithItsProviderAndHonorsRetryAfter() = runBlocking {
        var now = 1000L
        val seen = mutableListOf<Request>()
        val source = IntroDbMarkerSource(client(seen) { request ->
            if (request.url.host == "api.introdb.app") Fixture("", 429, "120")
            else Fixture("""{"tmdb_id":1396,"type":"episode","intro":[],"credits":[]}""")
        }, nowMs = { now })
        source.markers("1396", "tt0903747", true, 1, 2, 2460000)
        now += 31_000
        source.markers("1396", "tt0903747", true, 1, 3, 2460000)
        assertEquals(1, seen.count { it.url.host == "api.introdb.app" })
        assertEquals(2, seen.count { it.url.host == "api.theintrodb.org" })
        now += 90_000
        source.markers("1396", "tt0903747", true, 1, 4, 2460000)
        assertEquals(2, seen.count { it.url.host == "api.introdb.app" })
    }

    @Test fun networkFailureDoesNotCacheMissingMarkersOrPreventLaterLookup() = runBlocking {
        val seen = mutableListOf<Request>()
        var offline = true
        val source = IntroDbMarkerSource(client(seen) {
            if (offline) throw IOException("Offline")
            Fixture(movie)
        })
        assertEquals(PlaybackMarkers(), source.markers("550", null, false, 1, 1, 8340000))
        offline = false
        assertEquals(PlaybackMarkers(9000, 28000, 8200000), source.markers("550", null, false, 1, 1, 8340000))
        assertEquals(2, seen.size)
    }

    @Test fun v2EpisodeQueriesAndOptionalEchoesMustMatch() = runBlocking {
        val seen = mutableListOf<Request>()
        val source = IntroDbMarkerSource(client(seen) {
            assertEquals("3", it.url.queryParameter("season"))
            assertEquals("8", it.url.queryParameter("episode"))
            Fixture("""{"tmdb_id":1396,"type":"episode","season":3,"episode":9,
              "intro":[{"start_ms":31000,"end_ms":64000}],"credits":[]}""")
        })
        assertEquals(PlaybackMarkers(), source.markers("1396", null, true, 3, 8, 2460000))
        assertEquals(1, seen.size)
    }

    @Test fun missingIdentityOrInvalidRuntimeMakesNoRequests() = runBlocking {
        val seen = mutableListOf<Request>()
        val source = IntroDbMarkerSource(client(seen) { throw AssertionError("No request expected") })
        assertEquals(PlaybackMarkers(), source.markers("", null, false, 1, 1, 8340000))
        assertEquals(PlaybackMarkers(), source.markers("550", null, false, 1, 1, -1))
        assertEquals(PlaybackMarkers(), source.markers("1396", "tt0903747", true, 0, 1, 2460000))
        assertTrue(seen.isEmpty())
    }
}
