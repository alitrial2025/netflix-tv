package com.example.data

import com.example.ui.screens.PlaybackMarkers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlaybackMarkerIntegrationTest {
    private fun http(seen: MutableList<Request>, body: (Request) -> String) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body(request).toResponseBody()).build()
        }.build()

    @Test fun publishedEpisodeMarkersFillMissingCreditsAndRespectAdvertisedIntro() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = http(seen) { request -> when (request.url.host) {
            "cdn.example" -> "WEBVTT\n\n00:00:45.000 --> 00:01:30.000\nIntro"
            "api.introdb.app" -> {
                assertEquals("tt0903747", request.url.queryParameter("imdb_id"))
                assertEquals("2", request.url.queryParameter("episode"))
                assertNull(request.header("Referer"))
                """{"imdb_id":"tt0903747","season":1,"episode":2,
                    "intro":{"start_ms":50000,"end_ms":100000,"confidence":0.95,"submission_count":3},
                    "outro":{"start_ms":1680000,"end_ms":1800000,"confidence":0.95,"submission_count":3}}"""
            }
            else -> throw AssertionError("Unexpected marker source")
        } }
        val stream = NetMirrorStream("https://cdn.example/media.m3u8", mapOf("Referer" to "https://net52.cc/"),
            listOf(Caption("https://cdn.example/chapters.vtt", "Chapters", "chapters")), "fixture", Long.MAX_VALUE, "Fixture")
        val repository = PlaybackMarkerRepository(client)
        val result = repository.markers("95350", "tt0903747", true, 1, 2, 1_800_000, stream)
        assertEquals(PlaybackMarkers(45_000, 90_000, 1_680_000), result)
        assertEquals(result, repository.markers("95350", "tt0903747", true, 1, 2, 1_800_000, stream))
        assertEquals(2, seen.size)
    }

    @Test fun moviesCanReceiveActualCreditsWithoutAnyCaptionTrack() = runBlocking {
        val seen = mutableListOf<Request>()
        val repository = PlaybackMarkerRepository(http(seen) { request ->
            assertEquals("api.theintrodb.org", request.url.host)
            assertEquals("550", request.url.queryParameter("tmdb_id"))
            assertNull(request.url.queryParameter("season"))
            """{"tmdb_id":550,"type":"movie","intro":[],"credits":[{"start_ms":700000,"end_ms":800000}]}"""
        })
        assertEquals(PlaybackMarkers(outroStart = 700_000), repository.markers("550", null, false, 1, 1, 800_000, null))
        assertEquals(1, seen.size)
    }
}
