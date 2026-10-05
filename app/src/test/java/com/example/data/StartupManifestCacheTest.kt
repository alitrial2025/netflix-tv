package com.example.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StartupManifestCacheTest {
    @Test fun validatedMasterIsHandedToPlayerWithoutSecondNetworkFetch() {
        val url = "https://cdn.example/cached.m3u8?in=unique-startup"
        val body = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"
        val headers = mapOf("Referer" to "https://net52.cc/")
        VerifiedManifestHandoff.offer(url, headers, body)
        val upstream = DataSource.Factory { object : DataSource {
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(spec: DataSpec): Long = error("Duplicate manifest request")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("Unexpected read")
            override fun getUri(): Uri? = null
            override fun close() = error("Unopened upstream must not close")
        } }
        val source = GuardedPlaybackDataSourceFactory(upstream, manifestHeaders = headers).createDataSource()
        assertEquals(body.toByteArray().size.toLong(), source.open(DataSpec(Uri.parse(url))))
        val buffer = ByteArray(1024)
        val read = source.read(buffer, 0, buffer.size)
        assertEquals(body, buffer.copyOf(read).toString(Charsets.UTF_8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(url, source.uri.toString())
        source.close()
        assertNull(VerifiedManifestHandoff.take(url, headers))
    }
    @Test fun staleOrNonManifestResponsesAreNeverHandedOff() {
        val url = "https://cdn.example/expired.m3u8"
        StartupManifestCache.put(url, "not a manifest")
        assertNull(StartupManifestCache.take(url))
        StartupManifestCache.put(url, "#EXTM3U\n#EXTINF:10,\nx.ts")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        assertNull(StartupManifestCache.take(url))
    }
    @Test fun titlesAndDescriptionsCannotTriggerGlobalCooldown() {
        assertFalse(StreamSessionPolicy.isRateLimited(200, """{"title":"Rate Limited","description":"Too many requests, limit exceeded","status":"ok"}"""))
        assertFalse(StreamSessionPolicy.isRateLimited(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=123\nrate_limited.m3u8"))
        assertFalse(StreamSessionPolicy.isRateLimited(200, "<html><title>Rate Limited</title><p>Too many requests</p></html>"))
        assertTrue(StreamSessionPolicy.isRateLimited(200, """{"error":"rate_limited"}"""))
        assertTrue(StreamSessionPolicy.isRateLimited(200, """{"sources":[{"file":"https://cdn.example/files/220884/limit.m3u8"}]}"""))
        assertTrue(StreamSessionPolicy.isRateLimited(429, ""))
    }
}
