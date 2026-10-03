package com.example.data

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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicMoviePlaybackTest {
    private val netflixId = "81458416"
    private val primeId = "0O70LSZ5KT12QBNQRUQGGRIWDP"
    private val title = "Fixture Film"
    private val official = """<script type="application/ld+json">{"@type":"Movie","name":"Fixture Film","datePublished":"2024-01-01"}</script>"""

    private fun client(seen: MutableList<Request>, body: (Request) -> Pair<String, Int>) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            val (text, code) = body(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Set-Cookie", "unused=not-a-session; Path=/; Secure")
                .body(text.toResponseBody()).build()
        }.build()

    private fun source() = """{"sources":[{"file":"https://cdn.example/fixture.m3u8?in=issued"}]}"""

    @Test fun exactProviderNetflixMovieSkipsSlowDiscoveryAndOfficialDom() = runBlocking {
        val seen = mutableListOf<Request>()
        val catalog = PublicProviderCatalog(RuntimeEnvironment.getApplication())
        catalog.saveDiscovered("movie:999999111", listOf(netflixId to "nf"), listOf(title), System.currentTimeMillis())
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"$netflixId","t":"$title","type":"m","y":"2024"}]}""" to 200
                "/mobile/playlist.php" -> {
                    assertEquals(netflixId, request.url.queryParameter("id"))
                    source() to 200
                }
                "/fixture.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Provider identity must finish before discovery: ${request.url.encodedPath}")
            }
        }, catalog = catalog, backgroundCatalogRefresh = false)
        assertEquals(netflixId, resolver.resolve(title, "2024", "movie", 0, 0, "999999111").contentId)
        assertFalse(seen.any { it.url.host != "net52.cc" && it.url.host != "cdn.example" })
    }

    @Test fun exactPrimeMovieKeepsCatalogNamespaceAndOpaqueId() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php", "/mobile/search.php" -> """{"status":"n"}""" to 200
                "/mobile/pv/search.php" -> """{"searchResult":[{"id":"$primeId","t":"$title","type":"m","y":"2024"}]}""" to 200
                "/mobile/pv/playlist.php" -> {
                    assertEquals(primeId, request.url.queryParameter("id"))
                    source() to 200
                }
                "/fixture.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected cross-catalog or discovery request")
            }
        }, backgroundCatalogRefresh = false)
        assertEquals("pv", resolver.resolve(title, "2024", "movie", 0, 0, "999999112").ott)
        assertFalse(seen.any { it.url.encodedPath == "/mobile/playlist.php" })
    }

    @Test fun incompletePublicSearchUsesOfficialIdentityWithoutGatedPost() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"$netflixId","t":"$title"}]}""" to 200
                "/title/$netflixId" -> official to 200
                "/mobile/playlist.php" -> source() to 200
                "/fixture.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected endpoint")
            }
        }, backgroundCatalogRefresh = false)
        assertEquals(netflixId, resolver.resolve(title, "2024", "movie", 0, 0).contentId)
        assertTrue(seen.any { it.url.host == "www.netflix.com" })
        assertFalse(seen.any { it.url.encodedPath.endsWith("post.php") })
    }

    @Test fun missingMovieTypeCannotEstablishProviderIdentity() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"$netflixId","t":"$title"}]}""" to 200
                "/title/$netflixId" -> official to 200
                "/mobile/playlist.php" -> source() to 200
                "/fixture.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected endpoint")
            }
        }, backgroundCatalogRefresh = false)
        resolver.resolve(title, "2024", "movie", 0, 0)
        assertTrue(seen.any { it.url.encodedPath == "/title/$netflixId" })
    }

    @Test fun wrongTitleYearTypeAndMalformedIdNeverReachMedia() = runBlocking {
        val badRows = listOf(
            """{"id":"$netflixId","t":"$title","type":"m","y":"1989"}""",
            """{"id":"$netflixId","t":"$title","type":"t","y":"2024"}""",
            """{"id":"$netflixId","t":"Other Film","type":"m","y":"2024"}""",
            """{"id":"invalid/route","t":"$title","type":"m","y":"2024"}"""
        )
        for (row in badRows) {
            val seen = mutableListOf<Request>()
            val resolver = PublicPlaybackResolver(client(seen) { request ->
                when (request.url.encodedPath) {
                    "/search.php" -> """{"searchResult":[$row]}""" to 200
                    "/mobile/pv/search.php" -> """{"status":"n"}""" to 200
                    else -> throw AssertionError("Wrong movie identity reached metadata or media")
                }
            }, backgroundCatalogRefresh = false)
            try { resolver.resolve(title, "2024", "movie", 0, 0); fail("Wrong movie accepted") }
            catch (expected: IOException) { assertEquals("No verified public provider identity is available for this title", expected.message) }
            assertFalse(seen.any { it.url.encodedPath.endsWith("playlist.php") })
        }
    }

    @Test fun absentPlaylistUsesGuideMasterAndPreservesReturnedAdaptiveMaster() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"$netflixId","t":"$title","type":"m","y":"2024"}]}""" to 200
                "/mobile/playlist.php" -> "not found" to 404
                "/mobile/hls/$netflixId.m3u8" -> {
                    val parts = request.url.queryParameter("in")!!.split("::")
                    assertEquals(5, parts.size)
                    assertEquals("235ca31540ab8d90fcef4a00de8a247c", parts[0])
                    val expected = java.security.MessageDigest.getInstance("MD5")
                        .digest((parts[2] + netflixId).toByteArray()).joinToString("") { "%02x".format(it) }
                    assertEquals(expected, parts[1]); assertEquals("ek", parts[3]); assertEquals("m", parts[4])
                    assertEquals("off", request.url.queryParameter("hd"))
                    assertEquals("eng", request.url.queryParameter("lang"))
                    assertEquals("yes", request.url.queryParameter("hp"))
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nhttps://cdn.example/fixture.m3u8?in=issued::su::myes" to 200
                }
                "/fixture.m3u8" -> {
                    assertEquals("issued::su::myes", request.url.queryParameter("in"))
                    "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                }
                else -> throw AssertionError("Unexpected endpoint")
            }
        }, backgroundCatalogRefresh = false)
        val result = resolver.resolve(title, "2024", "movie", 0, 0)
        assertEquals("/mobile/hls/$netflixId.m3u8", result.url.toHttpUrl().encodedPath)
        assertFalse(seen.any { it.url.encodedPath.contains("post.php") || it.url.encodedPath.contains("verify") })
    }
}
