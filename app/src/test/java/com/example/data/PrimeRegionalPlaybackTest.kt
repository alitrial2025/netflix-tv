package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PrimeRegionalPlaybackTest {
    private val showId = "0TZXL00XCKTUHFBO67U01EGMCX"
    private val seasonFive = "0OBDSPAICFE4VNWZNFPW2I8TYU"
    private val episodeId = "0I5QBOS5FWA8IES045ZH7O06SN"
    // Reduced official metadata captured on 23 October 2026; no session data or media URLs.
    private val html: String get() = "<script>" + requireNotNull(javaClass.getResourceAsStream(
        "/provider/expanse-prime-regional.json")).bufferedReader().use { it.readText() } + "</script>"

    @Test fun regionalSeasonSelectorRetainsAllSixExplicitSeasons() {
        val seasons = PublicPlaybackResolver.parsePrimeSeasons(html, "The Expanse", showId)
        assertEquals((1..6).toSet(), seasons.keys)
        assertEquals(showId, seasons[1])
        assertEquals(seasonFive, seasons[5])
        assertEquals("0P5PBGVVW11X6D7IBS6OP020Q8", seasons[6])
    }

    @Test fun regionalTitleRedirectReachesTheRequestedEpisodeWithoutCookies() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308)) {
            resetGate()
            val seen = mutableListOf<Request>()
            val client = client(seen) { request, response -> when (request.url.encodedPath) {
                "/search.php" -> response.body("""{"searchResult":[]}""".toResponseBody())
                "/mobile/pv/search.php" -> response.body("""{"searchResult":[{"id":"$showId","t":"The Expanse","y":"2015","r":"Series"}]}""".toResponseBody())
                "/detail/$showId" -> response.code(status).header("Location", "/-/en/region/eu/detail/$showId?language=en")
                "/-/en/region/eu/detail/$showId" -> response.body(html.toResponseBody())
                "/mobile/pv/episodes.php" -> {
                    assertEquals(seasonFive, request.url.queryParameter("s"))
                    assertEquals(showId, request.url.queryParameter("series"))
                    response.body("""{"episodes":[{"id":"$episodeId","s":"S5","ep":"E1"}]}""".toResponseBody())
                }
                "/mobile/pv/playlist.php" -> {
                    assertEquals(episodeId, request.url.queryParameter("id"))
                    response.body("""{"sources":[{"file":"https://cdn.example/expanse.m3u8?in=issued"}]}""".toResponseBody())
                }
                "/expanse.m3u8" -> response.body("#EXTM3U\n#EXTINF:10,\nsegment.ts".toResponseBody())
                else -> throw AssertionError("Unexpected request ${request.url.encodedPath}")
            } }
            val result = PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                .resolve("The Expanse", "2015", "tv", 5, 1)
            assertEquals(episodeId, result.contentId)
            assertEquals("pv", result.ott)
            assertEquals(1, seen.count { it.url.encodedPath == "/detail/$showId" })
            assertEquals(1, seen.count { it.url.encodedPath == "/-/en/region/eu/detail/$showId" })
        }
    }

    @Test fun homepageAndSearchAcceptTheSameRegionalTitleLinks() {
        for (path in listOf("/detail/$showId", "/-/en/detail/$showId/", "/region/eu/detail/$showId",
            "/-/en/region/eu/detail/The-Expanse/$showId?language=en")) {
            assertEquals(listOf(showId to "pv"), PublicIdentityDiscovery.homepageIds("https://www.primevideo.com$path"))
            val search = """<script>{"init":{"preparations":{"body":{"containers":[{"entities":[{"title":"The Expanse - Season 1","entityType":"TVSeason","releaseYear":2015,"link":{"url":"$path"}}]}]}}}}</script>"""
            assertEquals(listOf(showId to "pv"), PublicIdentityDiscovery.primeSearchIds(search, listOf("The Expanse"), "2015", "tv"))
        }
        for (url in listOf("http://www.primevideo.com/detail/$showId", "https://user:password@www.primevideo.com/detail/$showId",
            "https://www.primevideo.com:444/detail/$showId", "https://www.primevideo.com.evil.example/detail/$showId",
            "https://www.primevideo.com/auth/redirect/detail/$showId", "https://www.primevideo.com/search?id=$showId")) {
            assertNull(PublicIdentityDiscovery.primeDetailId(url.toHttpUrl()))
        }
    }

    @Test fun unsafeDifferentTitleMissingAndLoopingRedirectsAreNeverFollowed() = runBlocking {
        for (location in listOf(null, "", "http://www.primevideo.com/detail/$showId",
            "https://evil.example/detail/$showId", "https://user:password@www.primevideo.com/detail/$showId",
            "https://www.primevideo.com:444/detail/$showId", "/region/eu/detail/0OTHERTITLE12345", "/auth/redirect?title=$showId", "/detail/$showId")) {
            resetGate()
            val seen = mutableListOf<Request>()
            val client = client(seen) { request, response -> when (request.url.encodedPath) {
                "/search.php" -> response.body("""{"searchResult":[]}""".toResponseBody())
                "/mobile/pv/search.php" -> response.body("""{"searchResult":[{"id":"$showId","t":"The Expanse","y":"2015"}]}""".toResponseBody())
                "/detail/$showId" -> response.code(307).apply { if (location != null) header("Location", location) }
                else -> throw AssertionError("Rejected redirect was followed: ${request.url}")
            } }
            try {
                PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                    .resolve("The Expanse", "2015", "tv", 1, 1)
                fail("Unsafe redirect accepted")
            } catch (_: IOException) { }
            assertEquals(1, seen.count { it.url.host == "www.primevideo.com" })
        }
    }

    @Test fun repeatedRegionalRedirectsHaveABoundedRequestBudget() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = client(seen) { request, response -> when (request.url.encodedPath) {
            "/search.php" -> response.body("""{"searchResult":[]}""".toResponseBody())
            "/mobile/pv/search.php" -> response.body("""{"searchResult":[{"id":"$showId","t":"The Expanse","y":"2015"}]}""".toResponseBody())
            else -> response.code(307).header("Location", "/region/eu/detail/$showId?hop=${seen.size}")
        } }
        try {
            PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                .resolve("The Expanse", "2015", "tv", 1, 1)
            fail("Endless redirects accepted")
        } catch (_: IOException) { }
        assertEquals(4, seen.count { it.url.host == "www.primevideo.com" })
        assertFalse(seen.any { it.url.encodedPath.endsWith("playlist.php") })
    }

    @Test fun unavailableDefaultLocaleUsesEnglishMetadataOnceForSeasonOnePilot() = runBlocking {
        for (available in listOf(true, false)) {
            resetGate()
            val seen = mutableListOf<Request>()
            val pilotId = "0PILOTFIXTURE12345"
            val client = client(seen) { request, response -> when (request.url.encodedPath) {
                "/search.php" -> response.body("""{"searchResult":[]}""".toResponseBody())
                "/mobile/pv/search.php" -> response.body("""{"searchResult":[{"id":"$showId","t":"The Expanse","y":"2015"}]}""".toResponseBody())
                "/detail/$showId" -> response.code(307).header("Location", "/region/eu/detail/$showId")
                "/region/eu/detail/$showId" -> response.code(503)
                "/-/en/detail/$showId" -> response.code(307).header("Location", "/-/en/region/eu/detail/$showId?language=en")
                "/-/en/region/eu/detail/$showId" -> response.body(html.toResponseBody())
                "/mobile/pv/episodes.php" -> {
                    assertEquals(showId, request.url.queryParameter("s"))
                    response.body((if (available) """{"episodes":[{"id":"$pilotId","s":"S1","ep":"E1"}]}""" else """{"lang":[],"nextPageShow":0}""").toResponseBody())
                }
                "/mobile/pv/playlist.php" -> {
                    assertTrue(available)
                    assertEquals(pilotId, request.url.queryParameter("id"))
                    response.body("""{"sources":[{"file":"https://cdn.example/pilot.m3u8?in=issued"}]}""".toResponseBody())
                }
                "/pilot.m3u8" -> response.body("#EXTM3U\n#EXTINF:10,\nsegment.ts".toResponseBody())
                else -> throw AssertionError("Unexpected request ${request.url.encodedPath}")
            } }
            val resolver = PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
            if (available) assertEquals(pilotId, resolver.resolve("The Expanse", "2015", "tv", 1, 1).contentId)
            else {
                try { resolver.resolve("The Expanse", "2015", "tv", 1, 1); fail("Unavailable pilot accepted") }
                catch (error: IOException) { assertTrue(error.message.orEmpty().contains("Requested episode")) }
                assertFalse(seen.any { it.url.encodedPath.endsWith("playlist.php") })
            }
            assertEquals(1, seen.count { it.url.encodedPath == "/-/en/detail/$showId" })
            assertEquals(1, seen.count { it.url.encodedPath.endsWith("episodes.php") })
        }
    }

    @Test fun officialAuthorizationAndRateErrorsDoNotTriggerLocaleRetries() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) {
            resetGate()
            val seen = mutableListOf<Request>()
            val client = client(seen) { request, response -> when (request.url.encodedPath) {
                "/search.php" -> response.body("""{"searchResult":[]}""".toResponseBody())
                "/mobile/pv/search.php" -> response.body("""{"searchResult":[{"id":"$showId","t":"The Expanse","y":"2015"}]}""".toResponseBody())
                "/detail/$showId", "/-/en/detail/$showId" -> response.code(status)
                else -> throw AssertionError("Unbounded retry ${request.url.encodedPath}")
            } }
            try {
                PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                    .resolve("The Expanse", "2015", "tv", 1, 1)
                fail("Unavailable metadata accepted")
            } catch (_: IOException) { }
            assertEquals(if (status == 503) 2 else 1, seen.count { it.url.host == "www.primevideo.com" })
        }
    }

    private fun client(seen: MutableList<Request>, respond: (Request, Response.Builder) -> Response.Builder) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            respond(request, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body("".toResponseBody())).build()
        }.build()

    @org.junit.Before @org.junit.After fun resetGate() {
        val cooldown = org.robolectric.util.ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(PlaybackServiceGate::class.java, "cooldown")
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "strikes", 0)
        org.robolectric.util.ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
        VerifiedManifestHandoff.clear()
    }
}
