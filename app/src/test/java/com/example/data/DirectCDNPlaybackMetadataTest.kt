package com.example.data

import android.content.Context
import com.example.model.Movie
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectCDNPlaybackMetadataTest {
    @Test fun publicMasterRetainsAlternateAudioAndCachesWithoutStoredSession() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val seen = mutableListOf<Request>()
        val video = "https://dynamic-cdn.invalid/video/media.m3u8?in=issued-video"
        val audio = "https://audio-cdn.invalid/audio/eng.m3u8?in=issued-audio"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); seen += req; assertNull(req.header("Cookie"))
            val body = when (req.url.encodedPath) {
                "/mobile/post.php" -> "{}"
                "/3/movie/fixture" -> """{"title":"Fixture","release_date":"2001-01-01"}"""
                "/search.php" -> """{"searchResult":[{"id":"8100000002","t":"Fixture","y":"2001"}]}"""
                "/title/8100000002" -> """<script type="application/ld+json">{"@type":"Movie","name":"Fixture","datePublished":"2001-01-01"}</script>"""
                "/mobile/playlist.php" -> """{"sources":[{"file":"/mobile/hls/content.m3u8?in=unknown::ek"}],"tracks":[{"file":"/captions/en.vtt","kind":"subtitles","srclang":"en"}]}"""
                "/mobile/hls/content.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",URI=\"$audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO=\"audio\"\n$video"
                "/video/media.m3u8" -> { assertEquals(video, req.url.toString()); "#EXTM3U\n#EXTINF:10,\ns.jpg" }
                else -> throw AssertionError("Unexpected handshake ${req.url.encodedPath}")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(if (req.url.encodedPath == "/mobile/post.php") 404 else 200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        val movie = Movie(id = "fixture", title = "Fixture", year = "2001", type = "Movie", duration = "", description = "", backdropUrl = "", posterUrl = "")
        val resolver = DirectCDNResolver(context, client)
        val stream = resolver.resolveStream(movie)
        assertTrue(stream.url.startsWith("https://net52.cc/mobile/hls/content.m3u8"))
        assertFalse(stream.url.contains("unknown")); assertNull(stream.headers["Cookie"])
        assertFalse(resolver.requiresWarmSession); assertFalse(resolver.hasValidSession())
        assertEquals(0L, stream.sessionVersion)
        assertFalse("Card metadata avoids an extra TMDB fetch", seen.any { it.url.encodedPath.startsWith("/3/movie/") })
        assertEquals("https://net52.cc/captions/en.vtt", stream.captions.single().url)
        val count = seen.size
        assertEquals(stream.url, resolver.resolveStream(movie, purpose = StreamPurpose.SILENT_PREVIEW).url)
        assertEquals(count, seen.size)
        assertEquals(stream.captions, resolver.fetchSubtitlesForEpisode(movie, 0, 0))
        assertEquals(count, seen.size)
    }
}
