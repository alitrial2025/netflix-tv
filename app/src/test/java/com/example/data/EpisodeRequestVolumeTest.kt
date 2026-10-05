package com.example.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.robolectric.util.ReflectionHelpers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EpisodeRequestVolumeTest {
    // Robolectric resets elapsedRealtime between methods, while Kotlin object state
    // can survive in its sandbox. Isolate only this fixture's mutable queue/backoff.
    @Before @After fun reset() {
        VerifiedManifestHandoff.clear()
        val cooldown = ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(PlaybackServiceGate::class.java, "cooldown")
        ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        ReflectionHelpers.setField(cooldown, "strikes", 0)
        ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
    }
    private fun context(): Context {
        val directory = Files.createTempDirectory("episode-volume").toFile()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
        }
    }
    private fun client(seen: MutableList<Request>, failure: Boolean = false, failedEpisode: Int = 1, live: Boolean = false) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); seen += request
        assertNull(request.header("Cookie"))
        val body = when(request.url.encodedPath) {
            "/mobile/hs/post.php" -> """{"status":"y","title":"Lanterns","year":"2026","type":"t","episodes":[{"id":"1271684191","s":"S1","ep":"E1"},{"id":"1271684192","s":"S1","ep":"E2"},{"id":"1271684193","s":"S1","ep":"E3"}]}"""
            "/mobile/hs/playlist.php" -> """{"sources":[{"file":"https://net52.cc/mobile/hs/hls/${request.url.queryParameter("id")}.m3u8?in=issued"}]}"""
            else -> if (request.url.encodedPath.contains("/hls/"))
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nhttps://s1.freecdn1.top/${request.url.pathSegments.last()}.m3u8?in=issued-video"
            else "#EXTM3U\n#EXTINF:10,\nsegment.ts" + if (live) "" else "\n#EXT-X-ENDLIST"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (failure && request.url.encodedPath == "/mobile/hs/hls/127168419$failedEpisode.m3u8") 503 else 200)
            .message("fixture").body(body.toResponseBody()).build()
    }.build()

    @Test fun consecutiveEpisodesReuseVerifiedShowAndEpisodeIdsAndPlayerReadsEachManifestOnlyOnce() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val resolver = PublicPlaybackResolver(client(seen), catalog = PublicProviderCatalog(context()), backgroundCatalogRefresh = false)
        for (episode in 1..3) {
            val result = resolver.resolve("Lanterns", "2026", "tv", 1, episode, "95350")
            assertEquals("127168419$episode", result.contentId)
            val upstream = CountingSource()
            val source = GuardedPlaybackDataSourceFactory(DataSource.Factory { upstream }, manifestHeaders = result.headers).createDataSource()
            val master = consume(source, result.url)
            val child = master.lineSequence().last { it.startsWith("https://") }
            assertTrue(consume(source, child).contains("#EXT-X-ENDLIST"))
            assertEquals("Media3 must reuse the two already verified manifests", 0, upstream.opens)
        }
        assertEquals("Verified show is not looked up again for each Next", 1, seen.count { it.url.encodedPath.endsWith("post.php") })
        assertEquals(3, seen.count { it.url.encodedPath.endsWith("playlist.php") })
        assertEquals(3, seen.count { it.url.encodedPath.contains("/hls/") })
        assertEquals("one child playlist per selected episode", 3, seen.count { it.url.host.contains("freecdn") })
        assertEquals("No speculative next episode or duplicate playback request", 10, seen.size)
    }

    @Test fun concurrentRequestsJoinAndExplicitEvictionResolvesAgain() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val resolver = PublicPlaybackResolver(client(seen), catalog = PublicProviderCatalog(context()), backgroundCatalogRefresh = false)
        coroutineScope { (1..8).map { async { resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350") } }.forEach { assertEquals("1271684191", it.await().contentId) } }
        assertEquals(4, seen.size)
        resolver.evict("95350", "tv", 1, 1)
        resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertEquals("Explicit Retry repeats media, not show discovery", 7, seen.size)
    }

    @Test fun transientServerFailureStopsAtTheSelectedIdentity() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val resolver = PublicPlaybackResolver(client(seen, failure = true), catalog = PublicProviderCatalog(context()), backgroundCatalogRefresh = false)
        try { resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350"); fail("503 accepted") }
        catch (error: IOException) { assertEquals("Playback endpoint unavailable (HTTP 503)", error.message) }
        assertEquals(3, seen.size)
        assertFalse(seen.any { it.url.encodedPath.contains("search") })
    }

    @Test fun nextEpisode503KeepsVerifiedEpisodeIdAndDoesNotRefetchShowOrEpisodeCatalog() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val catalog = PublicProviderCatalog(context())
        val resolver = PublicPlaybackResolver(client(seen, failure = true, failedEpisode = 2), catalog = catalog, backgroundCatalogRefresh = false)
        resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        try { resolver.resolve("Lanterns", "2026", "tv", 1, 2, "95350"); fail("503 accepted") }
        catch (error: IOException) { assertEquals("Playback endpoint unavailable (HTTP 503)", error.message) }
        assertEquals("Next sends only its playlist and master; no stale-ID repair for 503", 6, seen.size)
        assertEquals("1271684192", catalog.episodeCatalog.episode("https://net52.cc/", "hs", "1271680756", 1, 2, System.currentTimeMillis()))
        assertEquals(1, seen.count { it.url.encodedPath.endsWith("post.php") })
        assertFalse(seen.any { it.url.encodedPath.contains("episodes.php") || it.url.encodedPath.contains("search") })
    }

    @Test fun handoffIsSingleUseExactUrlHeaderScopedAndExpiresBeforeRefresh() {
        val url = "https://cdn.example/one.m3u8?in=issued"
        val body = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"
        val headers = mapOf("Referer" to "https://net52.cc/", "Origin" to "https://net52.cc")
        VerifiedManifestHandoff.offer(url, headers, body)
        assertNull(VerifiedManifestHandoff.take(url.replace("issued", "different"), headers))
        assertNull(VerifiedManifestHandoff.take(url, headers + ("Authorization" to "Bearer other")))
        assertArrayEquals(body.toByteArray(), VerifiedManifestHandoff.take(url, headers))
        assertNull(VerifiedManifestHandoff.take(url, headers))
        VerifiedManifestHandoff.offer(url, headers, body)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertNull(VerifiedManifestHandoff.take(url, headers))
    }

    @Test fun livePlaylistIsNeverHandedOffAndCooldownStillStopsThePlayer() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val http = client(seen, live = true)
        val resolver = PublicPlaybackResolver(http, catalog = PublicProviderCatalog(context()), backgroundCatalogRefresh = false)
        val result = resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertNull(VerifiedManifestHandoff.take(result.url, result.headers))
        VerifiedManifestHandoff.offer(result.url, result.headers, "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST")
        PlaybackServiceGate.recordLimit(1000)
        try { consume(GuardedPlaybackDataSourceFactory(DataSource.Factory { CountingSource() }, manifestHeaders = result.headers).createDataSource(), result.url); fail("Cooldown bypassed") }
        catch (_: PlaybackRateLimitedException) { }
        finally { ShadowSystemClock.advanceBy(Duration.ofMillis(PlaybackServiceGate.remainingMs() + 1L)) }
    }

    private fun consume(source: DataSource, url: String): String {
        try {
            source.open(DataSpec(Uri.parse(url)))
            assertEquals(Uri.parse(url), source.uri)
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
            while(true) { val n = source.read(buffer, 0, buffer.size); if(n == C.RESULT_END_OF_INPUT) break; output.write(buffer, 0, n) }
            return output.toString("UTF-8")
        } finally { source.close() }
    }
    private class CountingSource : DataSource {
        var opens = 0
        private var current: Uri? = null
        private var body: ByteArrayInputStream? = null
        override fun open(dataSpec: DataSpec): Long { opens++; current = dataSpec.uri; val bytes = "#EXTM3U\n#EXTINF:10,\nnetwork.ts\n#EXT-X-ENDLIST".toByteArray(); body = ByteArrayInputStream(bytes); return bytes.size.toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = body?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri() = current
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() { body = null; current = null }
    }
}
