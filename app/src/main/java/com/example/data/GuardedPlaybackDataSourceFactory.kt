package com.example.data

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Inspect every HLS refresh before the player can request its audio/video segments. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class GuardedPlaybackDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val sourceId: String? = null,
    private val newTvGate: CookieFreePlaybackGate = NewTvPlaybackGate
) : DataSource.Factory {
    override fun createDataSource(): DataSource = GuardedSource(upstream.createDataSource(), sourceId, newTvGate)
    private class GuardedSource(private val delegate: DataSource, sourceId: String?, private val newTvGate: CookieFreePlaybackGate) : DataSource by delegate {
        private val newTv = sourceId?.contains("[NewTV]", ignoreCase = true) == true
        private fun remainingMs() = if (newTv) newTvGate.remainingMs() else PlaybackServiceGate.remainingMs()
        private fun check() = if (newTv) newTvGate.check() else PlaybackServiceGate.check()
        private fun checkResponse(code: Int, body: String, retryAfter: String?, url: String) {
            if (newTv) newTvGate.checkResponse(code, body, retryAfter, url)
            else PlaybackServiceGate.checkResponse(code, body, retryAfter, url)
        }
        private var manifest: ByteArrayInputStream? = null
        override fun open(dataSpec: DataSpec): Long {
            manifest = null
            val url = dataSpec.uri.toString()
            val isManifest = dataSpec.uri.path?.endsWith(".m3u8", true) == true
            val providerHost = dataSpec.uri.host.orEmpty()
            val usesPlaybackProvider = newTv || providerHost.contains("freecdn", true) ||
                providerHost in listOf("net52.cc", "netmirror.app", "netmirror.gg", "mobidetect.art") ||
                dataSpec.uri.path?.startsWith("/files/") == true ||
                (dataSpec.uri.scheme == "file" && dataSpec.uri.lastPathSegment?.startsWith("master_") == true)
            if (isManifest) {
                if (usesPlaybackProvider) check()
                // Reject a cached waiting clip without treating its URL as a new
                // response. Reopening an old URL must not restart the cooldown.
                if (StreamSessionPolicy.isWaitingVideo(url)) throw PlaybackRateLimitedException(remainingMs())
            }
            try {
                val length = delegate.open(dataSpec)
                if (!isManifest) return length
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8_192)
                while (true) {
                    val n = delegate.read(chunk, 0, chunk.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    bytes.write(chunk, 0, n)
                    if (bytes.size() > 8 * 1024 * 1024) throw IOException("Playback manifest is too large")
                }
                val data = bytes.toByteArray()
                val retryAfter = delegate.responseHeaders.entries.firstOrNull {
                    it.key.equals("Retry-After", true)
                }?.value?.firstOrNull()
                checkResponse(200, data.toString(Charsets.UTF_8), retryAfter, url)
                manifest = ByteArrayInputStream(data)
                return data.size.toLong()
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (usesPlaybackProvider && error.responseCode == 429) {
                    val retryAfter = error.headerFields.entries.firstOrNull {
                        it.key.equals("Retry-After", true)
                    }?.value?.firstOrNull()
                    checkResponse(429, "", retryAfter, url)
                }
                throw error
            } catch (error: IOException) {
                // The loader also closes on failure; early close stops any pending audio.
                runCatching { delegate.close() }
                throw error
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            manifest?.read(buffer, offset, length) ?: delegate.read(buffer, offset, length)
        override fun close() { manifest = null; delegate.close() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(16)
            .any { it is PlaybackRateLimitedException ||
                (it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 429) })
            C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}
