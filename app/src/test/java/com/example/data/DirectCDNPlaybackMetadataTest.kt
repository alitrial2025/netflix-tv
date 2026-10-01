package com.example.data

import android.content.Context
import com.example.model.Movie
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectCDNPlaybackMetadataTest {
    private val movie = Movie(id = "fixture", title = "Fixture", year = "2001", type = "Movie", duration = "", description = "", backdropUrl = "", posterUrl = "")

    private class Fixture(val separateAudio: Boolean = false, val smallville: Boolean = false, var rejectVideoOnce: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val requests = mutableListOf<Request>()
        val now = System.currentTimeMillis()
        val videoToken = "${"a".repeat(32)}::${"b".repeat(32)}::${now / 1000}::su::myes"
        val audioToken = "${"c".repeat(32)}::${"d".repeat(32)}::${now / 1000}::future::audio"
        val videoUrl = "https://dynamic-cdn.invalid/library/episode/720p/custom.m3u8?in=$videoToken&lang=eng"
        val audioUrl = "https://audio-cdn.invalid/library/sound/eng.m3u8?in=$audioToken"
        val resolver: DirectCDNResolver

        init {
            val prefs = context.getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE)
            val session = JSONObject().put("domain", "provider.invalid").put("addhashRaw", "fixture")
                .put("addhashEncoded", "fixture").put("tHashTEncoded", "fixture-cookie").put("fetchedAt", now)
            prefs.edit().clear().commit()
            prefs.edit().putString("directcdn_sessions", JSONArray().put(session).toString()).commit()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = when (request.url.encodedPath) {
                    "/3/movie/fixture" -> """{"title":"Fixture","release_date":"2001-01-01"}"""
                    "/3/tv/4607" -> """{"name":"Smallville","first_air_date":"2001-01-01"}"""
                    "/mobile/search.php", "/search.php" -> if (smallville) """{"searchResult":[]}""" else """{"searchResult":[{"id":"episode","t":"Fixture","y":"2001"}]}"""
                    "/mobile/pv/search.php" -> """{"searchResult":[{"id":"smallville","t":"Smallville","y":"2001"}]}"""
                    "/mobile/pv/post.php" -> """{"season":[{"id":"season1","s":"Season 1"},{"id":"season4","s":"Season 4"}]}"""
                    "/mobile/pv/episodes.php" -> {
                        assertEquals("season4", request.url.queryParameter("s"))
                        """{"episodes":[{"id":"82171151","ep":"Episode 8"}],"nextPageShow":1,"nextPage":2}"""
                    }
                    "/mobile/playlist.php", "/mobile/pv/playlist.php" -> {
                        if (smallville) {
                            assertEquals("82171151", request.url.queryParameter("id"))
                            assertEquals("Smallville", request.url.queryParameter("t"))
                            assertTrue(request.header("Cookie").orEmpty().contains("SEsmallville=82171151"))
                            assertTrue(request.header("Cookie").orEmpty().contains("ott=pv"))
                        }
                        JSONObject().put("sources", JSONArray().put(JSONObject()
                            .put("file", if (smallville) "/mobile/pv/hls/82171151.m3u8?in=unknown::future-mode&lang=eng" else "/mobile/hls/episode.m3u8?in=unknown")))
                            .put("tracks", JSONArray()).toString()
                    }
                    "/mobile/hls/episode.m3u8", "/mobile/pv/hls/82171151.m3u8" -> buildString {
                        assertFalse(request.url.queryParameter("in").orEmpty().contains("unknown"))
                        assertFalse(request.url.queryParameter("in").orEmpty().contains("future-mode"))
                        if (smallville) assertEquals("eng", request.url.queryParameter("lang"))
                        append("#EXTM3U\n")
                        if (separateAudio) append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"eng\",DEFAULT=YES,URI=\"$audioUrl\"\n")
                        append("#EXT-X-STREAM-INF:BANDWIDTH=2500000")
                        if (separateAudio) append(",AUDIO=\"audio\"")
                        append("\n$videoUrl\n")
                    }
                    "/library/episode/720p/custom.m3u8" -> if (rejectVideoOnce) {
                        rejectVideoOnce = false
                        "<html>Temporarily unavailable</html>"
                    } else "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST"
                    else -> throw AssertionError("Unexpected speculative audio/handshake/metadata call: ${request.method} ${request.url.encodedPath}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }.build()
            resolver = DirectCDNResolver(context, client)
            assertTrue("Fixture cookie must be present before resolution", resolver.hasValidSession())
        }
    }

    @Test fun fullPlaybackRetainsIndependentIssuedAudioSignatureWithoutHeadProbe() = runBlocking {
        val fixture = Fixture(separateAudio = true)
        val stream = fixture.resolver.resolveStream(movie)
        assertEquals(fixture.videoUrl, stream.rawVideoUrl)
        val master = File(URI(stream.url)).readText()
        assertTrue(master.contains(fixture.audioUrl))
        assertTrue(master.contains(fixture.videoUrl))
        assertTrue(master.contains("AUDIO=\"audio\""))
        assertEquals(1, fixture.requests.count { it.url.encodedPath == "/mobile/hls/episode.m3u8" })
        assertFalse(fixture.requests.any { it.method == "HEAD" })
        assertEquals(0L, fixture.resolver.sessionVersion)
    }

    @Test fun knownVideoOnlyMasterDoesNotRefetchMetadataOrGuessLegacyAudio() = runBlocking {
        val fixture = Fixture()
        val stream = fixture.resolver.resolveStream(movie)
        assertEquals(fixture.videoUrl, stream.url)
        assertEquals(1, fixture.requests.count { it.url.encodedPath == "/mobile/hls/episode.m3u8" })
        assertFalse(fixture.requests.any { it.method == "HEAD" })
    }

    @Test fun smallvilleS4E8UsesPvEpisodeCookieAndKeepsItWhenRefreshingTheRoute() = runBlocking {
        val fixture = Fixture(smallville = true, rejectVideoOnce = true)
        val show = movie.copy(id = "4607", title = "Smallville", type = "Series", duration = "10 Seasons")
        val stream = fixture.resolver.resolveStream(show, season = 4, episode = 8)
        assertEquals(fixture.videoUrl, stream.rawVideoUrl)
        assertEquals(0L, fixture.resolver.sessionVersion)
        assertEquals(2, fixture.requests.count { it.url.encodedPath == "/mobile/pv/playlist.php" })
        assertEquals(1, fixture.requests.count { it.url.encodedPath == "/mobile/pv/episodes.php" })
        assertEquals(2, fixture.requests.count { it.url.encodedPath == "/library/episode/720p/custom.m3u8" })
        val fetchedRequests = fixture.requests.size
        assertEquals(stream.url, fixture.resolver.resolveStream(show, season = 4, episode = 8).url)
        assertEquals(fetchedRequests, fixture.requests.size)
    }

    @Test fun warmDirectCdnDoesNotWaitBehindAnUnrelatedProviderRequest() = runBlocking {
        val fixture = Fixture()
        fixture.resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW)
        fixture.resolver.evictCachedStream(movie.id, "movie")
        val queueEntered = CompletableDeferred<Unit>()
        val releaseQueue = CompletableDeferred<Unit>()
        val backgroundLookup = launch {
            PlaybackServiceGate.request {
                queueEntered.complete(Unit)
                releaseQueue.await()
            }
        }
        try {
            withTimeout(5_000L) { queueEntered.await() }
            val result = withTimeout(5_000L) {
                fixture.resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW)
            }
            assertEquals(fixture.videoUrl, result.rawVideoUrl)
            assertFalse(releaseQueue.isCompleted)
            assertEquals(2, fixture.requests.count { it.url.encodedPath == "/library/episode/720p/custom.m3u8" })
        } finally {
            releaseQueue.complete(Unit)
            backgroundLookup.join()
        }
    }
}
