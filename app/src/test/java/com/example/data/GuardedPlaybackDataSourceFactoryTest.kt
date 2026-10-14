package com.example.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GuardedPlaybackDataSourceFactoryTest {
    private val manifest = DataSpec(Uri.parse("https://tv.imgcdn.kim/newtv/hls/hs/1271684191.m3u8"))
    private fun factory(body: String, gate: CookieFreePlaybackGate, opens: AtomicInteger,
                        failure: IOException? = null,
                        headers: Map<String, List<String>> = mapOf("Retry-After" to listOf("120"))): DataSource.Factory = GuardedPlaybackDataSourceFactory(
        upstream = DataSource.Factory { FixtureSource(body, opens, failure, headers) },
        sourceId = "Public HLS [NewTV] [HS]", newTvGate = gate
    )

    @Test fun waitingManifestBlocksRepeatNetworkRequestsWithoutLimitingTheOtherProvider() {
        val gate = CookieFreePlaybackGate { 1_000L }
        val opens = AtomicInteger()
        val legacyBefore = PlaybackServiceGate.remainingMs()
        val sourceFactory = factory("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8", gate, opens)
        repeat(2) {
            val source = sourceFactory.createDataSource()
            try { assertThrows(PlaybackRateLimitedException::class.java) { source.open(manifest) } }
            finally { source.close() }
        }
        assertEquals(1, opens.get())
        assertEquals(120_000L, gate.remainingMs())
        assertEquals(legacyBefore, PlaybackServiceGate.remainingMs())
    }

    @Test fun transientTransportFailureDoesNotStartAProviderCooldownAndNextRequestCanRecover() {
        val gate = CookieFreePlaybackGate { 1_000L }
        val opens = AtomicInteger()
        val failed = factory("", gate, opens, IOException("Network interrupted")).createDataSource()
        try { assertThrows(IOException::class.java) { failed.open(manifest) } }
        finally { failed.close() }
        assertEquals(0L, gate.remainingMs())
        val body = "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST"
        val recovered = factory(body, gate, opens).createDataSource()
        try {
            assertEquals(body.toByteArray().size.toLong(), recovered.open(manifest))
            val bytes = ByteArray(1_024)
            assertEquals(body, String(bytes, 0, recovered.read(bytes, 0, bytes.size)))
        } finally { recovered.close() }
        assertEquals(2, opens.get())
        assertEquals(0L, gate.remainingMs())
    }

    @Test fun legitimateNineMinutePlaylistIsNotTreatedAsARateLimitVideo() {
        val gate = CookieFreePlaybackGate { 1_000L }
        val opens = AtomicInteger()
        val body = "#EXTM3U\n#EXTINF:540,\nlegitimate-episode.jpg\n#EXT-X-ENDLIST"
        val source = factory(body, gate, opens).createDataSource()
        try { assertEquals(body.toByteArray().size.toLong(), source.open(manifest)) }
        finally { source.close() }
        assertEquals(0L, gate.remainingMs())
        assertEquals(1, opens.get())
    }

    @Test fun localRejectionsCountDownAndRecoveryAfterFiveMinutesOpensTheNetwork() {
        var now = 1_000L
        val gate = CookieFreePlaybackGate { now }
        val opens = AtomicInteger()
        val waiting = factory("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8",
            gate, opens, headers = emptyMap())
        waiting.createDataSource().let { source ->
            try { assertThrows(PlaybackRateLimitedException::class.java) { source.open(manifest) } }
            finally { source.close() }
        }
        assertEquals(60_000L, gate.remainingMs())
        repeat(5) {
            now += 10_000L
            val error = assertThrows(PlaybackRateLimitedException::class.java) { gate.check() }
            assertEquals(60_000L - (it + 1) * 10_000L, error.retryAfterMs)
        }
        assertEquals(1, opens.get())
        now += 5 * 60_000L
        gate.check()
        assertEquals(0L, gate.remainingMs())
        val body = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"
        factory(body, gate, opens).createDataSource().let { source ->
            try { assertEquals(body.length.toLong(), source.open(manifest)) }
            finally { source.close() }
        }
        assertEquals(2, opens.get())
    }

    @Test fun reopeningCachedWaitingUrlDoesNotCreateOrExtendACooldown() {
        var now = 1_000L
        val gate = CookieFreePlaybackGate { now }
        val opens = AtomicInteger()
        val cachedWaiting = DataSpec(Uri.parse("https://tv.imgcdn.kim/files/220884/video.m3u8"))
        val sources = factory("#EXTM3U", gate, opens)
        repeat(7) {
            sources.createDataSource().let { source ->
                try {
                    val error = assertThrows(PlaybackRateLimitedException::class.java) { source.open(cachedWaiting) }
                    assertEquals(0L, error.retryAfterMs)
                } finally { source.close() }
            }
            now += 60_000L
            assertEquals(0L, gate.remainingMs())
        }
        assertEquals(0, opens.get())
    }

    @Test fun genuineRetryAfterLongerThanFiveMinutesIsRespected() {
        var now = 1_000L
        val gate = CookieFreePlaybackGate { now }
        assertThrows(PlaybackRateLimitedException::class.java) {
            gate.checkResponse(429, "", "600", manifest.uri.toString())
        }
        now += 5 * 60_000L
        assertEquals(300_000L, gate.remainingMs())
        assertThrows(PlaybackRateLimitedException::class.java) { gate.check() }
        now += 300_000L
        gate.check()
        assertEquals(0L, gate.remainingMs())
    }

    private class FixtureSource(body: String, private val opens: AtomicInteger,
                                private val failure: IOException?,
                                private val headers: Map<String, List<String>>) : DataSource {
        private val bytes = body.toByteArray()
        private var input = ByteArrayInputStream(bytes)
        private var requestedUri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            failure?.let { throw it }
            requestedUri = dataSpec.uri
            input = ByteArrayInputStream(bytes)
            return bytes.size.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            input.read(buffer, offset, length).takeIf { it >= 0 } ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = requestedUri
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() { input.close() }
    }
}
