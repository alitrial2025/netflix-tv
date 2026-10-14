package com.example.data

import com.example.ui.screens.PlaybackMarkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackMarkerRepositoryTest {
    private val chapters = """WEBVTT

        chapter-intro
        00:00:45.000 --> 00:01:30.000 align:start
        Intro

        chapter-credits
        00:28:00.000 --> 00:30:00.000
        End Credits
    """.trimIndent()
    private fun stream(type: String = "chapters", verified: Boolean = true) = NetMirrorStream(
        "https://cdn.example/video.m3u8", mapOf("Referer" to "https://net52.cc/", "User-Agent" to "player"),
        listOf(Caption("https://cdn.example/chapters.vtt?signature=issued", "Chapters", type, isVerified = verified)),
        "fixture", Long.MAX_VALUE, "Fixture")
    private fun client(seen: MutableList<Request>, body: (Request) -> String = { chapters }): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body(request).toResponseBody()).build()
        }.build()

    @Test fun explicitChapterLabelsSupplyExactBoundariesWithoutSubtitleGuessing() {
        assertEquals(PlaybackMarkers(45_000, 90_000, 1_680_000), PlaybackChapterParser.parseText(chapters, 1_800_000))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(chapters.replace("Intro", "Conversation").replace("End Credits", "Last scene"), 1_800_000))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText("Dialogue stops here for a minute", 1_800_000))
    }

    @Test fun invalidRangesAndUnknownDurationAreDiscardedRatherThanClamped() {
        val tooLong = "WEBVTT\n\n00:00:45.000 --> 01:30:00.000\nIntro"
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(tooLong, 1_800_000))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(chapters, 0))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(chapters, Long.MAX_VALUE))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText("WEBVTT\n\n00:01:30.000 --> 00:00:45.000\nIntro", 1_800_000))
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText("WEBVTT\n\n00:00:99.000 --> 00:03:00.000\nIntro", 1_800_000))
    }

    @Test fun metadataCommentsAndAmbiguousLabelsDoNotBecomeSkipMarkers() {
        val text = """WEBVTT

            NOTE Documentation only
            00:00:00.000 --> 00:01:00.000
            Intro

            00:00:00.000 --> 00:01:00.000
            Opening

            00:01:00.000 --> 00:02:00.000
            Introduction to the investigation
        """.trimIndent()
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(text, 1_800_000))
    }

    @Test fun overlappingIntroAndCreditsAreRejected() {
        val text = chapters.replace("00:28:00.000", "00:01:00.000")
        assertEquals(PlaybackMarkers(), PlaybackChapterParser.parseText(text, 1_800_000))
    }

    @Test fun concurrentLoadsShareTheAdvertisedSourceAndPreserveItsIssuedHeaders() = runBlocking {
        val seen = mutableListOf<Request>()
        val repository = PlaybackMarkerRepository(client(seen), publicSource = null)
        val first = async { repository.markers("95350", isTv = true, season = 1, episode = 2, durationMs = 1_800_000, stream = stream()) }
        val second = async { repository.markers("95350", isTv = true, season = 1, episode = 2, durationMs = 1_800_000, stream = stream()) }
        assertEquals(PlaybackMarkers(45_000, 90_000, 1_680_000), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, seen.size)
        assertEquals("https://net52.cc/", seen.single().header("Referer"))
        assertEquals("player", seen.single().header("User-Agent"))
        assertEquals("/chapters.vtt", seen.single().url.encodedPath)
        assertEquals("issued", seen.single().url.queryParameter("signature"))
    }

    @Test fun missingUnverifiedOrSubtitleSourcesNeverProduceSyntheticMarkers() = runBlocking {
        val seen = mutableListOf<Request>()
        val repository = PlaybackMarkerRepository(client(seen), publicSource = null)
        for (source in listOf(null, stream("vtt"), stream(verified = false))) {
            assertEquals(PlaybackMarkers(), repository.markers("95350", isTv = true, season = 1, episode = 1, durationMs = 1_800_000, stream = source))
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun cacheDoesNotCarryMarkersAcrossEpisodesAndRetriesAfterATransportFailure() = runBlocking {
        val seen = mutableListOf<Request>()
        var now = 1_000L
        var fail = true
        val repository = PlaybackMarkerRepository(client(seen) {
            if (fail) throw IOException("offline") else chapters
        }, nowMs = { now }, publicSource = null)
        assertEquals(PlaybackMarkers(), repository.markers("95350", isTv = true, season = 1, episode = 1, durationMs = 1_800_000, stream = stream()))
        fail = false
        now += 30_001L
        assertNotNull(repository.markers("95350", isTv = true, season = 1, episode = 1, durationMs = 1_800_000, stream = stream()).introStart)
        assertNotNull(repository.markers("95350", isTv = true, season = 1, episode = 2, durationMs = 1_800_000, stream = stream()).introStart)
        assertEquals(3, seen.size)
    }

    @Test fun cancellationPropagatesInsteadOfCompletingWithFabricatedMarkers() = runBlocking {
        val seen = mutableListOf<Request>()
        val repository = PlaybackMarkerRepository(client(seen) { Thread.sleep(200); chapters }, publicSource = null)
        try {
            withTimeout(30) { repository.markers("95350", isTv = true, season = 1, episode = 1, durationMs = 1_800_000, stream = stream()) }
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
        assertTrue(seen.size <= 1)
    }
}
