package com.example.data

import android.net.Uri

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
internal class GuardedPlaybackDataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = GuardedSource(upstream.createDataSource())
    private class GuardedSource(private val delegate: DataSource) : DataSource by delegate {
        private var manifest: ByteArrayInputStream? = null
        private var prefetchedUri: Uri? = null
        private var openedDelegate = false
        override fun open(dataSpec: DataSpec): Long {
            manifest = null
            prefetchedUri = null
            openedDelegate = false
            val url = dataSpec.uri.toString()
            val isManifest = dataSpec.uri.path?.endsWith(".m3u8", true) == true
            val providerHost = dataSpec.uri.host.orEmpty()
            val usesPlaybackProvider = providerHost.contains("freecdn", true) ||
                providerHost in listOf("net52.cc", "netmirror.app", "netmirror.gg", "mobidetect.art") ||
                dataSpec.uri.path?.startsWith("/files/") == true ||
                (dataSpec.uri.scheme == "file" && dataSpec.uri.lastPathSegment?.startsWith("master_") == true)
            if (isManifest) {
                if (usesPlaybackProvider) PlaybackServiceGate.check()
                PlaybackServiceGate.checkResponse(200, "", null, url)
            }
            if (isManifest && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                StartupManifestCache.take(url)?.let { data ->
                    manifest = ByteArrayInputStream(data)
                    prefetchedUri = dataSpec.uri
                    return data.size.toLong()
                }
            }
            try {
                openedDelegate = true
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
                PlaybackServiceGate.checkResponse(200, data.toString(Charsets.UTF_8), retryAfter, url)
                manifest = ByteArrayInputStream(data)
                return data.size.toLong()
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (usesPlaybackProvider && error.responseCode == 429) {
                    val retryAfter = error.headerFields.entries.firstOrNull {
                        it.key.equals("Retry-After", true)
                    }?.value?.firstOrNull()
                    PlaybackServiceGate.checkResponse(429, "", retryAfter, url)
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
        override fun getUri(): Uri? = prefetchedUri ?: delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = if (prefetchedUri != null) emptyMap() else delegate.responseHeaders
        override fun close() {
            manifest = null
            prefetchedUri = null
            if (openedDelegate) delegate.close()
            openedDelegate = false
        }
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
