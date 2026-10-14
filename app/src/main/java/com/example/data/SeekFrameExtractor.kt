package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.example.ui.screens.ThumbnailCue
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Extracts real frames on demand; it never changes or seeks the playback player. */
internal class SeekFrameExtractor(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
    private val newTvGate: CookieFreePlaybackGate = NewTvPlaybackGate,
    private val decode: (File, Long) -> Bitmap? = ::decodeSeekFrame
) {
    private val appCache = context.applicationContext.cacheDir.canonicalFile
    private val directory = File(appCache, "seek-frames").apply { mkdirs() }
    private val work = Mutex()
    private val manifests = LinkedHashMap<String, String>(8, 0.75f, true)
    private val media = LinkedHashMap<String, ByteArray>(3, 0.75f, true)
    private val keys = LinkedHashMap<String, ByteArray>(4, 0.75f, true)
    private var owner = ""
    private var lastNetworkAtMs = 0L

    suspend fun frame(stream: NetMirrorStream, positionMs: Long): ThumbnailCue? = withContext(Dispatchers.IO) {
        work.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (stream.isRateLimited || positionMs < 0 || stream.expiresAt <= System.currentTimeMillis()) return@withLock null
                val identity = digest(stream.url + "\n" + stream.sessionVersion + "\n" +
                    stream.headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" })
                if (owner != identity) {
                    owner = identity
                    manifests.clear(); media.clear(); keys.clear()
                }
                val bucket = positionMs / FRAME_INTERVAL_MS * FRAME_INTERVAL_MS
                val output = File(directory, "${digest("$identity:$bucket")}.png")
                if (output.isFile && output.length() > 0L) {
                    output.setLastModified(System.currentTimeMillis())
                    return@withLock cue(output, bucket)
                }
                val playlist = playlist(stream)
                val segment = playlist.segmentAt(bucket) ?: return@withLock null
                if (segment.key?.let { it.method != "AES-128" || it.keyFormat != "identity" } == true) return@withLock null
                val init = segment.init?.let { download(stream, it.url, it.range, MAX_INIT_BYTES) } ?: ByteArray(0)
                var data = download(stream, segment.url, segment.range, MAX_MEDIA_BYTES)
                segment.key?.let { encryption ->
                    if (encryption.method != "AES-128" || encryption.keyFormat != "identity") return@withLock null
                    val keyUrl = encryption.url ?: return@withLock null
                    val secret = keys[keyUrl.toString()] ?: download(stream, keyUrl, null, 16).also {
                        if (it.size != 16) throw IOException("Invalid HLS key length")
                        keys[keyUrl.toString()] = it
                        trim(keys, 4)
                    }
                    val iv = encryption.iv ?: ByteArray(16).apply {
                        var sequence = segment.sequence
                        for (index in 15 downTo 0) { this[index] = sequence.toByte(); sequence = sequence ushr 8 }
                    }
                    data = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv)); doFinal(data)
                    }
                }
                if (init.size.toLong() + data.size > MAX_MEDIA_BYTES) return@withLock null
                // CDN suffixes are opaque: .jpg/.js can contain MPEG-TS or fMP4.
                val extension = SeekHlsPlaylist.mediaExtension(if (init.isNotEmpty()) init else data)
                val local = File.createTempFile("seek-", extension, directory)
                val bitmap = try {
                    local.outputStream().use { it.write(init); it.write(data) }
                    currentCoroutineContext().ensureActive()
                    decode(local, (bucket - segment.startMs).coerceAtLeast(0L))
                } finally { local.delete() }
                if (bitmap == null) return@withLock null
                try {
                    currentCoroutineContext().ensureActive()
                    val pending = File.createTempFile("frame-", ".png", directory)
                    try {
                        pending.outputStream().use {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Frame encoding failed")
                        }
                        currentCoroutineContext().ensureActive()
                        if (!pending.renameTo(output)) throw IOException("Frame cache unavailable")
                    } finally { pending.delete() }
                    trimDisk()
                    ThumbnailCue(bucket, bucket + FRAME_INTERVAL_MS, output.toURI().toString(), 0, 0, bitmap.width, bitmap.height)
                } finally { bitmap.recycle() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unsupported codecs, transport failures and provider backoff have
                // no substitute image. In particular, do not renew a rate block.
                null
            }
        }
    }

    private fun cue(file: File, startMs: Long): ThumbnailCue? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ThumbnailCue(startMs, startMs + FRAME_INTERVAL_MS, file.toURI().toString(), 0, 0, options.outWidth, options.outHeight)
    }

    private suspend fun playlist(stream: NetMirrorStream): SeekHlsPlaylist {
        var url = stream.url.toHttpUrlOrNull()
        var text: String
        if (url == null) {
            val uri = android.net.Uri.parse(stream.url)
            val file = File(uri.path ?: throw IOException("Missing local master" )).canonicalFile
            if (uri.scheme != "file" || !file.path.startsWith(appCache.path + File.separator) ||
                !file.name.startsWith("master_") || file.length() > MAX_MANIFEST_BYTES) throw IOException("Invalid local master")
            text = file.readText()
            url = stream.rawVideoUrl?.toHttpUrlOrNull() ?: throw IOException("Missing media origin")
        } else text = manifest(stream, url)
        repeat(4) {
            val parsed = SeekHlsPlaylist.parse(url!!, text)
            val variant = parsed.lowestVideo
            if (variant == null) {
                if (!parsed.endList) throw IOException("Seek previews require a complete timeline")
                return parsed
            }
            url = variant
            text = manifest(stream, variant)
        }
        throw IOException("Too many HLS master levels")
    }

    private suspend fun manifest(stream: NetMirrorStream, url: HttpUrl): String = manifests[url.toString()] ?: run {
        val result = request(stream, url, null, MAX_MANIFEST_BYTES, manifest = true)
        val text = result.bytes.toString(Charsets.UTF_8)
        if (result.code !in 200..299 || !text.trimStart().startsWith("#EXTM3U")) throw IOException("Seek manifest unavailable")
        manifests[url.toString()] = text
        trim(manifests, 8)
        text
    }

    private suspend fun download(stream: NetMirrorStream, url: HttpUrl, range: SeekHlsPlaylist.ByteRange?, max: Long): ByteArray {
        val cacheKey = "$url:$range"
        media[cacheKey]?.let { if (it.size <= max) return it }
        if (StreamSessionPolicy.isWaitingVideo(url.toString())) throw PlaybackRateLimitedException(remaining(stream))
        val response = request(stream, url, range, max)
        if (response.code !in 200..299) throw IOException("Seek media unavailable")
        if (range != null) {
            val expected = "bytes ${range.offset}-${range.offset + range.length - 1}/"
            if (response.code != 206 || response.contentRange?.startsWith(expected) != true || response.bytes.size.toLong() != range.length)
                throw IOException("Seek byte range unavailable")
        }
        media[cacheKey] = response.bytes
        while (media.size > 3 || media.values.sumOf { it.size.toLong() } > 24L * 1024 * 1024) media.remove(media.keys.first())
        return response.bytes
    }

    private fun remaining(stream: NetMirrorStream) = if (stream.sourceId.contains("[NewTV]")) newTvGate.remainingMs() else PlaybackServiceGate.remainingMs()
    private fun checkResponse(stream: NetMirrorStream, code: Int, body: String, retryAfter: String?, url: HttpUrl) {
        if (stream.sourceId.contains("[NewTV]")) newTvGate.checkResponse(code, body, retryAfter, url.toString())
        else PlaybackServiceGate.checkResponse(code, body, retryAfter, url.toString())
    }

    private suspend fun request(stream: NetMirrorStream, url: HttpUrl, range: SeekHlsPlaylist.ByteRange?, max: Long,
                                manifest: Boolean = false): SeekBytes {
        // The URL policy checks scheme, credentials and local network addresses.
        val safe = PlaybackUrlPolicy.resolve(url, url.toString())
        val block: suspend () -> SeekBytes = {
            val wait = 250L - (System.nanoTime() / 1_000_000L - lastNetworkAtMs)
            if (wait > 0L) delay(wait)
            currentCoroutineContext().ensureActive()
            lastNetworkAtMs = System.nanoTime() / 1_000_000L
            val request = Request.Builder().url(safe).apply {
                stream.headers.forEach { (name, value) -> header(name, value) }
                if (range != null) header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
            }.build()
            http.seekBytes(request, max).also { response ->
                // Record an actual rejection while still holding the queue, so a
                // queued thumbnail cannot slip through a freshly issued block.
                checkResponse(stream, response.code,
                    if (manifest || response.code !in 200..299) response.bytes.toString(Charsets.UTF_8) else "",
                    response.retryAfter, url)
            }
        }
        return if (stream.sourceId.contains("[NewTV]")) newTvGate.request(block)
        else PlaybackServiceGate.request(preview = true, block = block)
    }

    private fun trimDisk() {
        val files = directory.listFiles { file -> file.extension == "png" }?.sortedByDescending(File::lastModified) ?: return
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= 48 || bytes > 8L * 1024 * 1024) file.delete()
        }
    }

    companion object {
        const val FRAME_INTERVAL_MS = 10_000L
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val MAX_INIT_BYTES = 2L * 1024 * 1024
        private const val MAX_MEDIA_BYTES = 12L * 1024 * 1024
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun <T> trim(cache: LinkedHashMap<String, T>, max: Int) { while (cache.size > max) cache.remove(cache.keys.first()) }
    }
}

private fun decodeSeekFrame(file: File, positionMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        val frame = retriever.getFrameAtTime(positionMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        if (frame.width <= 320) frame else {
            val scaled = Bitmap.createScaledBitmap(frame, 320, (frame.height * 320f / frame.width).toInt().coerceAtLeast(1), true)
            if (scaled !== frame) frame.recycle()
            scaled
        }
    } finally { retriever.release() }
}

private data class SeekBytes(val code: Int, val bytes: ByteArray, val retryAfter: String?, val contentRange: String?)

private suspend fun OkHttpClient.seekBytes(request: Request, maxBytes: Long): SeekBytes = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    if (!continuation.isActive) return
                    // Retry-After is valid even when an error body exceeds the
                    // requested key/segment size; do not lose a genuine 429.
                    if (it.code == 429) return@use SeekBytes(it.code, ByteArray(0), it.header("Retry-After"), it.header("Content-Range"))
                    val body = it.body ?: throw IOException("Empty seek response")
                    val success = it.code in 200..299
                    val limit = if (success) maxBytes else 32L * 1024
                    if (success && body.contentLength() > limit) throw IOException("Seek response too large")
                    val source = body.source()
                    source.request(limit + 1)
                    if (success && source.buffer.size > limit) throw IOException("Seek response too large")
                    SeekBytes(it.code, source.readByteArray(minOf(source.buffer.size, limit)), it.header("Retry-After"), it.header("Content-Range"))
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    })
}

/** Small strict HLS timeline parser independent of Media3's active player. */
internal data class SeekHlsPlaylist(val lowestVideo: HttpUrl?, val segments: List<Segment>, val endList: Boolean) {
    data class ByteRange(val length: Long, val offset: Long)
    data class Init(val url: HttpUrl, val range: ByteRange?)
    data class Key(val method: String, val url: HttpUrl?, val iv: ByteArray?, val keyFormat: String)
    data class Segment(val url: HttpUrl, val startMs: Long, val endMs: Long, val sequence: Long,
                       val init: Init?, val range: ByteRange?, val key: Key?)
    fun segmentAt(positionMs: Long): Segment? = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
        ?: segments.lastOrNull()?.takeIf { positionMs == it.endMs }

    companion object {
        fun mediaExtension(bytes: ByteArray): String =
            if (bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) in listOf("ftyp", "styp", "moof", "moov")) ".mp4" else ".ts"

        fun parse(base: HttpUrl, body: String): SeekHlsPlaylist {
            if (!body.trimStart().startsWith("#EXTM3U")) throw IOException("Invalid HLS")
            val lines = body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val variants = mutableListOf<Pair<Long, HttpUrl>>()
            val segments = mutableListOf<Segment>()
            var duration: Double? = null
            var elapsed = 0.0
            var sequence = 0L
            var init: Init? = null
            var key: Key? = null
            var rangeText: String? = null
            var previousRange: Pair<HttpUrl, ByteRange>? = null
            var variant: Map<String, String>? = null
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> variant = attributes(line.substringAfter(':'))
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid sequence")
                    line.startsWith("#EXT-X-MAP:") -> {
                        val values = attributes(line.substringAfter(':'))
                        val mapUrl = resolve(base, values["URI"] ?: throw IOException("Missing init URI"))
                        init = Init(mapUrl, values["BYTERANGE"]?.let { range(it, ByteRange(0, 0)) })
                        if (key != null) throw IOException("Encrypted initialization unsupported")
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val values = attributes(line.substringAfter(':'))
                        val method = values["METHOD"] ?: throw IOException("Missing key method")
                        key = if (method == "NONE") null else Key(method, values["URI"]?.let { resolve(base, it) },
                            values["IV"]?.let(::iv), values["KEYFORMAT"] ?: "identity")
                        if (init != null && key != null) throw IOException("Encrypted initialization unsupported")
                    }
                    line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0 && it <= 21_600 } ?: throw IOException("Invalid segment duration")
                    line.startsWith("#EXT-X-BYTERANGE:") -> rangeText = line.substringAfter(':')
                    !line.startsWith('#') -> {
                        val uri = resolve(base, line)
                        variant?.let { values ->
                            val codecs = values["CODECS"].orEmpty()
                            if (codecs.isEmpty() || values.containsKey("RESOLUTION") ||
                                listOf("avc", "hvc", "hev", "vp9", "vp09", "av01", "mp4v").any { codecs.contains(it, true) }) {
                                variants += (values["AVERAGE-BANDWIDTH"] ?: values["BANDWIDTH"])?.toLongOrNull()
                                    ?.takeIf { it > 0 }?.let { it to uri } ?: (Long.MAX_VALUE to uri)
                            }
                            variant = null
                        } ?: duration?.let { seconds ->
                            val previous = previousRange?.takeIf { it.first == uri }?.second
                            val selectedRange = rangeText?.let { range(it, previous) }
                            val start = (elapsed * 1_000).toLong()
                            elapsed += seconds
                            if (!elapsed.isFinite() || elapsed > 7 * 24 * 3600) throw IOException("Seek timeline too large")
                            segments += Segment(uri, start, (elapsed * 1_000).toLong(), sequence++, init, selectedRange, key)
                            previousRange = selectedRange?.let { uri to it }
                            duration = null; rangeText = null
                            if (segments.size > 100_000) throw IOException("Too many HLS segments")
                        }
                    }
                }
            }
            return SeekHlsPlaylist(variants.minByOrNull { it.first }?.second, segments, lines.any { it == "#EXT-X-ENDLIST" })
        }

        private fun attributes(value: String): Map<String, String> =
            Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""").findAll(value).associate {
                it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3])
            }

        private fun resolve(base: HttpUrl, value: String) = PlaybackUrlPolicy.resolve(base, value)
        private fun range(value: String, previous: ByteRange?): ByteRange {
            val parts = value.split('@')
            val length = parts[0].toLongOrNull()?.takeIf { it in 1..12L * 1024 * 1024 } ?: throw IOException("Invalid range length")
            val offset = if (parts.size == 2) parts[1].toLongOrNull() else previous?.let { it.offset + it.length }
                ?: throw IOException("Missing range offset")
            if (offset == null || offset < 0 || offset > Long.MAX_VALUE - length) throw IOException("Invalid range offset")
            return ByteRange(length, offset)
        }
        private fun iv(value: String): ByteArray {
            val hex = value.removePrefix("0x").removePrefix("0X")
            if (hex.isEmpty() || hex.length > 32 || !hex.all { it in "0123456789abcdefABCDEF" }) throw IOException("Invalid IV")
            return hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
