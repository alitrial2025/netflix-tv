package com.example.data

import android.graphics.Bitmap
import com.example.ui.screens.ThumbnailCue
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SeekFrameExtractorTest {
    private val base = "https://cdn.example/movie/master.m3u8".toHttpUrl()
    private data class Fixture(val data: ByteArray, val code: Int = 200, val headers: Map<String, String> = emptyMap()) {
        constructor(text: String, code: Int = 200, headers: Map<String, String> = emptyMap()) : this(text.toByteArray(), code, headers)
    }
    private fun client(seen: MutableList<Request>, response: (Request) -> Fixture) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            val fixture = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(fixture.code).message("fixture")
                .apply { fixture.headers.forEach { (name, value) -> header(name, value) } }
                .body(fixture.data.toResponseBody()).build()
        }.build()
    private fun stream(url: String = base.toString(), headers: Map<String, String> = emptyMap(), raw: String? = null) =
        NetMirrorStream(url, headers, emptyList(), "Public HLS [NewTV]", Long.MAX_VALUE, "Fixture", rawVideoUrl = raw)
    private fun bitmap() = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
    private fun extractor(http: OkHttpClient, gate: CookieFreePlaybackGate = CookieFreePlaybackGate { 1_000L },
                          decode: (File, Long) -> Bitmap?) = SeekFrameExtractor(RuntimeEnvironment.getApplication(), http, gate, decode)

    @Test fun choosesLowestVideoAndIgnoresCheaperAudioOnlyVariant() {
        val parsed = SeekHlsPlaylist.parse(base, """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
audio.js
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
high.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240,CODECS="avc1.420015"
low.m3u8
""")
        assertEquals("https://cdn.example/movie/low.m3u8", parsed.lowestVideo.toString())
    }

    @Test fun timedSegmentsIgnoreCamouflagedJsJpgAndTsSuffixes() {
        val parsed = SeekHlsPlaylist.parse(base, """#EXTM3U
#EXTINF:10,
first.js
#EXTINF:7.5,
second.jpg
#EXTINF:12,
third.ts
#EXT-X-ENDLIST
""")
        assertEquals("first.js", parsed.segmentAt(9_999)!!.url.pathSegments.last())
        assertEquals("second.jpg", parsed.segmentAt(10_000)!!.url.pathSegments.last())
        assertEquals("third.ts", parsed.segmentAt(17_500)!!.url.pathSegments.last())
        assertNull(parsed.segmentAt(29_501))
        assertTrue(parsed.endList)
    }

    @Test fun byteRangesCarryOffsetsOnlyForTheSameResource() {
        val parsed = SeekHlsPlaylist.parse(base, """#EXTM3U
#EXTINF:10,
#EXT-X-BYTERANGE:8@16
media.js
#EXTINF:10,
#EXT-X-BYTERANGE:12
media.js
#EXT-X-ENDLIST
""")
        assertEquals(SeekHlsPlaylist.ByteRange(8, 16), parsed.segments[0].range)
        assertEquals(SeekHlsPlaylist.ByteRange(12, 24), parsed.segments[1].range)
        assertThrows(IOException::class.java) {
            SeekHlsPlaylist.parse(base, "#EXTM3U\n#EXTINF:10,\n#EXT-X-BYTERANGE:8\nother.js\n#EXT-X-ENDLIST")
        }
    }

    @Test fun refusesUnsafeSegmentUrlsAndMalformedTimelines() {
        assertThrows(IOException::class.java) {
            SeekHlsPlaylist.parse(base, "#EXTM3U\n#EXTINF:10,\nhttp://cdn.example/clear.ts\n#EXT-X-ENDLIST")
        }
        assertThrows(IOException::class.java) {
            SeekHlsPlaylist.parse(base, "#EXTM3U\n#EXTINF:NaN,\nmedia.ts\n#EXT-X-ENDLIST")
        }
        assertEquals(".ts", SeekHlsPlaylist.mediaExtension(byteArrayOf(0x47, 0, 0, 0)))
        assertEquals(".mp4", SeekHlsPlaylist.mediaExtension(byteArrayOf(0, 0, 0, 8) + "ftyp".toByteArray()))
    }

    @Test fun extractsRequestedRealSegmentCopiesHeadersAndCachesTheRenderedFrame() = runBlocking {
        val seen = mutableListOf<Request>()
        var decodes = 0
        val realTarget = byteArrayOf(0x47, 1, 2, 3, 4)
        val http = client(seen) { request ->
            assertEquals("https://net52.cc/", request.header("Referer"))
            assertEquals("fixture-session", request.header("Cookie"))
            when (request.url.encodedPath) {
                "/movie/master.m3u8" -> Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200\nhigh.m3u8")
                "/movie/low.m3u8" -> Fixture("#EXTM3U\n#EXTINF:10,\nfirst.js\n#EXTINF:10,\nreal-target.jpg\n#EXTINF:10,\nthird.ts\n#EXT-X-ENDLIST")
                "/movie/real-target.jpg" -> Fixture(realTarget)
                else -> throw AssertionError("Unrelated segment downloaded")
            }
        }
        val extractor = extractor(http) { file, relativePosition ->
            decodes++
            assertArrayEquals(realTarget, file.readBytes())
            assertEquals("ts", file.extension)
            assertEquals(0L, relativePosition)
            bitmap()
        }
        val source = stream(headers = mapOf("Referer" to "https://net52.cc/", "Cookie" to "fixture-session"))
        val cue = extractor.frame(source, 12_000)
        assertNotNull(cue)
        assertEquals(10_000L, cue!!.startMs)
        assertEquals(64, cue.w)
        assertTrue(File(java.net.URI(cue.imageUrl)).length() > 0)
        assertNotNull(extractor.frame(source, 19_999))
        assertEquals(1, decodes)
        assertEquals(3, seen.size)
    }

    @Test fun fragmentedMp4CombinesInitAndOnlyTheSelectedJsSegment() = runBlocking {
        val seen = mutableListOf<Request>()
        val initialization = byteArrayOf(0, 0, 0, 8) + "ftyp".toByteArray()
        val fragment = byteArrayOf(0, 0, 0, 8) + "moof".toByteArray()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/movie/master.m3u8" -> Fixture("#EXTM3U\n#EXT-X-MAP:URI=\"init.jpg\"\n#EXTINF:10,\nfirst.js\n#EXTINF:10,\nsecond.js\n#EXT-X-ENDLIST")
            "/movie/init.jpg" -> Fixture(initialization)
            "/movie/second.js" -> Fixture(fragment)
            else -> throw AssertionError("Unexpected segment")
        } }
        val extractor = extractor(http) { file, _ ->
            assertEquals("mp4", file.extension)
            assertArrayEquals(initialization + fragment, file.readBytes())
            bitmap()
        }
        assertNotNull(extractor.frame(stream(headers = mapOf("Fixture" to "mp4")), 10_000))
        assertEquals(3, seen.size)
    }

    @Test fun authorizedAes128SegmentUsesMediaSequenceIvAndNoPosterSubstitute() = runBlocking {
        val seen = mutableListOf<Request>()
        val secret = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).apply { this[15] = 42 }
        val plaintext = byteArrayOf(0x47, 5, 6, 7)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv)); doFinal(plaintext)
        }
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/movie/master.m3u8" -> Fixture("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.js\"\n#EXTINF:30,\nmedia.jpg\n#EXT-X-ENDLIST")
            "/movie/media.jpg" -> Fixture(encrypted)
            "/movie/key.js" -> Fixture(secret)
            else -> throw AssertionError("Unexpected URI")
        } }
        val extractor = extractor(http) { file, _ -> assertArrayEquals(plaintext, file.readBytes()); bitmap() }
        assertNotNull(extractor.frame(stream(headers = mapOf("Fixture" to "aes")), 0))
        assertEquals(3, seen.size)
    }

    @Test fun downloadsOnlyTheDeclaredByteRange() = runBlocking {
        val seen = mutableListOf<Request>()
        val selected = byteArrayOf(0x47, 9, 8, 7, 6, 5, 4, 3)
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/movie/master.m3u8" -> Fixture("#EXTM3U\n#EXTINF:10,\n#EXT-X-BYTERANGE:8@0\nmedia.js\n#EXTINF:10,\n#EXT-X-BYTERANGE:8\nmedia.js\n#EXT-X-ENDLIST")
            "/movie/media.js" -> {
                assertEquals("bytes=8-15", request.header("Range"))
                Fixture(selected, 206, mapOf("Content-Range" to "bytes 8-15/16"))
            }
            else -> throw AssertionError("Unexpected URI")
        } }
        val extractor = extractor(http) { file, _ -> assertArrayEquals(selected, file.readBytes()); bitmap() }
        assertNotNull(extractor.frame(stream(headers = mapOf("Fixture" to "range")), 10_000))
        assertEquals(2, seen.size)
    }

    @Test fun providerRateLimitReturnsNoFakeImageAndDoesNotRepeatNetworkRequests() = runBlocking {
        val seen = mutableListOf<Request>()
        val gate = CookieFreePlaybackGate { 1_000L }
        val http = client(seen) { Fixture("Too many requests", 429, mapOf("Retry-After" to "120")) }
        val extractor = extractor(http, gate) { _, _ -> fail("Rate-limited data was decoded"); null }
        val source = stream(headers = mapOf("Fixture" to "limited"))
        assertNull(extractor.frame(source, 0))
        assertNull(extractor.frame(source, 10_000))
        assertEquals(120_000L, gate.remainingMs())
        assertEquals(1, seen.size)
    }

    @Test fun oversized429KeyResponseStillHonorsRetryAfter() = runBlocking {
        val seen = mutableListOf<Request>()
        val gate = CookieFreePlaybackGate { 1_000L }
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/movie/master.m3u8" -> Fixture("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.js\"\n#EXTINF:30,\nmedia.jpg\n#EXT-X-ENDLIST")
            "/movie/media.jpg" -> Fixture(byteArrayOf(0x47, 1, 2, 3))
            "/movie/key.js" -> Fixture("Too many requests".repeat(10_000), 429, mapOf("Retry-After" to "120"))
            else -> throw AssertionError("Unexpected URI")
        } }
        val extractor = extractor(http, gate) { _, _ -> fail("Rate-limited key decoded"); null }
        val source = stream(headers = mapOf("Fixture" to "limited-key"))
        assertNull(extractor.frame(source, 0))
        assertEquals(120_000L, gate.remainingMs())
        assertNull(extractor.frame(source, 10_000))
        assertEquals(3, seen.size)
    }

    @Test fun cancelledExtractionCancelsTheActiveHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.countDown()
            throw IOException("Cancelled")
        }.build()
        val extractor = extractor(http) { _, _ -> fail("Cancelled download decoded"); null }
        val job = async(kotlinx.coroutines.Dispatchers.IO) { extractor.frame(stream(headers = mapOf("Fixture" to "cancel")), 0) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    @Test fun localMasterUsesIssuedRawOriginAndDoesNotDownloadTheMasterAgain() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val local = File(context.cacheDir, "master_seek_fixture.m3u8")
        local.writeText("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nhttps://cdn.example/movie/low.m3u8")
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/movie/low.m3u8" -> Fixture("#EXTM3U\n#EXTINF:10,\nmedia.jpg\n#EXT-X-ENDLIST")
            "/movie/media.jpg" -> Fixture(byteArrayOf(0x47, 1, 2))
            else -> throw AssertionError("Local master should not trigger HTTP")
        } }
        try {
            val extractor = extractor(http) { _, _ -> bitmap() }
            assertNotNull(extractor.frame(stream(local.toURI().toString(), mapOf("Fixture" to "local"), base.toString()), 0))
            assertEquals(2, seen.size)
        } finally { local.delete() }
    }
}
