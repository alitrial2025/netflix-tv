package com.example.data

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IndependentPlaybackFallbackTest {
    @Before @After fun resetGate() {
        VerifiedManifestHandoff.clear()
        val cooldown = org.robolectric.util.ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(PlaybackServiceGate::class.java, "cooldown")
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "strikes", 0)
        org.robolectric.util.ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
    }
    private fun context(): Context {
        val directory = Files.createTempDirectory("independent-playback-").toFile()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }
    private val media = "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST"
    private fun resolver(context: Context, seen: MutableList<Request>, response: (Request) -> Pair<String, Int>): PublicPlaybackResolver {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            val (body, code) = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return PublicPlaybackResolver(http, catalog = PublicProviderCatalog(context), backgroundCatalogRefresh = false,
            newTv = NewTvPlaybackResolver(http, gate = CookieFreePlaybackGate()))
    }
    private fun primary(request: Request, title: String = "Red Notice", episode: String = ""): Pair<String, Int> = when (request.url.encodedPath) {
        "/checknewtv.php" -> "{}" to 200
        "/newtv/player.php" -> """{"title":"$title","ep":"$episode","video_link":"https://cdn.example/primary.m3u8"}""" to 200
        "/primary.m3u8" -> media to 200
        else -> "Unavailable" to 503
    }
    @Test fun coldMovieUsesNewTvBeforeAnyNetflixOrLegacyMetadataRequest() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(context(), seen) { primary(it) }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
        assertEquals(PlaybackRoute.NEWTV, result.route)
        assertEquals("81161626", result.contentId)
        assertFalse(seen.any { it.url.host in listOf("net52.cc", "www.netflix.com") })
    }
    @Test fun newTvCanServeColdMovieDuringLegacyProviderCooldown() = runBlocking {
        val seen = mutableListOf<Request>()
        PlaybackServiceGate.recordLimit(120_000L)
        val result = resolver(context(), seen) { primary(it) }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
        assertEquals(PlaybackRoute.NEWTV, result.route)
        assertFalse(seen.any { it.url.host == "net52.cc" })
        assertTrue(PlaybackServiceGate.remainingMs() > 0L)
    }
    @Test fun restartedResolverUsesPreviouslyVerifiedExactEpisodeWithoutLiveMetadata() = runBlocking {
        val context = context(); val now = System.currentTimeMillis(); val seen = mutableListOf<Request>()
        ProviderEpisodeCatalog(context).saveEpisodes("https://net52.cc", "nf", "70155584", 4, mapOf(8 to "82171151"), now)
        val result = resolver(context, seen) { request ->
            if (request.url.encodedPath == "/newtv/player.php") assertEquals("82171151", request.url.queryParameter("id"))
            primary(request, "Smallville", "E8 • S4")
        }.resolve("Smallville", "2001", "tv", 4, 8, "4604")
        assertEquals("82171151", result.contentId)
        assertEquals(PlaybackRoute.NEWTV, result.route)
        assertFalse(seen.any { it.url.host in listOf("net52.cc", "www.netflix.com") })
    }

    private fun rememberMovie(context: Context) {
        PublicProviderCatalog(context).save("https://net52.cc/:movie:512195:rednotice:2021",
            "81161626", "nf", System.currentTimeMillis(), listOf("Red Notice"))
    }
    @Test fun directHlsIsSecondWhenPrimaryFailsAndMetadataIsOffline() = runBlocking {
        val context = context(); rememberMovie(context); val seen = mutableListOf<Request>()
        val result = resolver(context, seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/player.php" -> "Unavailable" to 503
            "/mobile/hls/81161626.m3u8" -> { assertNull(request.url.queryParameter("in")); media to 200 }
            else -> "Metadata offline" to 503
        } }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
        assertEquals(PlaybackRoute.DIRECT, result.route)
        assertEquals(listOf("/checknewtv.php", "/newtv/player.php", "/mobile/hls/81161626.m3u8"), seen.map { it.url.encodedPath })
    }
    @Test fun originalIssuedResolverIsThirdAfterBothOptionalRoutesFail() = runBlocking {
        val context = context(); rememberMovie(context); val seen = mutableListOf<Request>()
        val result = resolver(context, seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/player.php" -> "Unavailable" to 503
            "/mobile/hls/81161626.m3u8" -> "Unavailable" to 503
            "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/issued.m3u8?in=unchanged-issued"}]}""" to 200
            "/issued.m3u8" -> media to 200
            else -> throw AssertionError("A verified identity must not repeat live metadata: ${request.url.encodedPath}")
        } }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertEquals("https://cdn.example/issued.m3u8?in=unchanged-issued", result.url)
        assertEquals(listOf("/checknewtv.php", "/newtv/player.php", "/mobile/hls/81161626.m3u8", "/mobile/playlist.php", "/issued.m3u8"), seen.map { it.url.encodedPath })
    }
    @Test fun unverifiedColdMovieRejectsMissingOrConflictingPrimaryMetadataBeforeFetchingVideo() = runBlocking {
        for (fields in listOf("", "\"title\":\"Other movie\",", "\"title\":\"Red Notice\",\"type\":\"t\",",
            "\"title\":\"Red Notice\",\"year\":\"1989\",", "\"title\":\"Red Notice\",\"ep\":\"E1 S1\",")) {
            val seen = mutableListOf<Request>()
            try {
                resolver(context(), seen) { request -> when (request.url.encodedPath) {
                    "/checknewtv.php" -> "{}" to 200
                    "/newtv/player.php" -> """{${fields}"video_link":"https://cdn.example/wrong.m3u8"}""" to 200
                    else -> "Metadata offline" to 503
                } }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
                fail("Unverified primary identity accepted")
            } catch (_: java.io.IOException) { }
            assertTrue("The independent service must actually be tried", seen.any { it.url.encodedPath == "/newtv/player.php" })
            assertFalse(seen.any { it.url.host == "cdn.example" })
        }
    }
    @Test fun missingRequestedEpisodeMustNotUseTheSeriesIdOrAnotherCachedEpisode() = runBlocking {
        val context = context(); val seen = mutableListOf<Request>()
        ProviderEpisodeCatalog(context).saveEpisodes("https://net52.cc", "nf", "70155584", 4,
            mapOf(7 to "82171150"), System.currentTimeMillis())
        try {
            resolver(context, seen) { "Metadata offline" to 503 }
                .resolve("Smallville", "2001", "tv", 4, 8, "4604")
            fail("Another episode accepted")
        } catch (_: java.io.IOException) { }
        assertFalse(seen.any { it.url.encodedPath == "/newtv/player.php" })
    }

    @Test fun coldSeriesUsesIndependentSeasonCatalogAndPaginationThenCachesTheExactEpisodeAcrossRestart() = runBlocking {
        val context = context(); val seen = mutableListOf<Request>(); var first = true
        val respond: (Request) -> Pair<String, Int> = { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/post.php" -> {
                assertTrue("Restart should reuse the verified episode", first)
                assertEquals("70155584", request.url.queryParameter("id"))
                """{"title":"Smallville","type":"t","year":"2010","episodes":[{"id":"82171299","s":"S10","ep":"E8"}],"season":[{"s":"4","id":"70037632"},{"s":"10","id":"70148114"}]}""" to 200
            }
            "/newtv/episodes.php" -> {
                assertEquals("70037632", request.url.queryParameter("id"))
                if (request.url.queryParameter("page") == "1") """{"episodes":[{"id":"82171144","s":"S4","ep":"E1"}],"nextPageShow":1}""" to 200
                else """{"episodes":[{"id":"82171151","ep":"E8"}],"nextPageShow":0}""" to 200
            }
            "/newtv/player.php" -> {
                assertEquals("82171151", request.url.queryParameter("id"))
                primary(request, "Smallville", "E8 • S4")
            }
            "/primary.m3u8" -> media to 200
            else -> throw AssertionError("Independent metadata must not call Netflix or net52: ${request.url.encodedPath}")
        } }
        assertEquals("82171151", resolver(context, seen, respond).resolve("Smallville", "2001", "tv", 4, 8, "4604").contentId)
        first = false
        assertEquals("82171151", resolver(context, seen, respond).resolve("Smallville", "2001", "tv", 4, 8, "4604").contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/newtv/post.php" })
        assertEquals(2, seen.count { it.url.encodedPath == "/newtv/episodes.php" })
        assertFalse(seen.any { it.url.host == "net52.cc" || it.url.host == "www.netflix.com" })
    }
    @Test fun explicitlyLabelledIndependentPostEpisodeNeedsNoSeparateSeasonRequest() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(context(), seen) { request -> when (request.url.encodedPath) {
            "/newtv/post.php" -> """{"title":"Smallville","type":"t","episodes":[{"id":"82171151","s":"S4","ep":"E8"}]}""" to 200
            else -> primary(request, "Smallville", "E8 S4")
        } }.resolve("Smallville", "2001", "tv", 4, 8, "4604")
        assertEquals(PlaybackRoute.NEWTV, result.route)
        assertEquals("82171151", result.contentId)
        assertFalse(seen.any { it.url.encodedPath == "/newtv/episodes.php" || it.url.host == "net52.cc" })
    }
    @Test fun independentSeriesCatalogRejectsWrongSeasonAmbiguousEpisodesAndUnlabelledDefault() = runBlocking {
        for (scenario in listOf("wrong-season", "ambiguous-episode", "ambiguous-season", "unlabelled-default", "wrong-title", "wrong-type")) {
            val seen = mutableListOf<Request>()
            try {
                resolver(context(), seen) { request -> when (request.url.encodedPath) {
                    "/checknewtv.php" -> "{}" to 200
                    "/newtv/post.php" -> {
                        val seasons = if (scenario == "ambiguous-season") """[{"s":"4","id":"70037632"},{"s":"4","id":"70037633"}]""" else """[{"s":"4","id":"70037632"}]"""
                        when (scenario) {
                            "unlabelled-default" -> """{"title":"Smallville","type":"t","episodes":[{"id":"82171151","ep":"E8"}]}"""
                            "wrong-title" -> """{"title":"Other Show","type":"t"}"""
                            "wrong-type" -> """{"title":"Smallville","type":"m"}"""
                            else -> """{"title":"Smallville","type":"t","season":$seasons}"""
                        } to 200
                    }
                    "/newtv/episodes.php" -> (if (scenario == "wrong-season") """{"episodes":[{"id":"82171151","s":"S3","ep":"E8"}]}"""
                        else """{"episodes":[{"id":"82171151","s":"S4","ep":"E8"},{"id":"82171152","s":"S4","ep":"E8"}]}""") to 200
                    else -> "Metadata offline" to 503
                } }.resolve("Smallville", "2001", "tv", 4, 8, "4604")
                fail("Incorrect independent episode accepted: $scenario")
            } catch (_: java.io.IOException) { }
            assertTrue(seen.any { it.url.encodedPath == "/newtv/post.php" })
            assertFalse(seen.any { it.url.encodedPath == "/newtv/player.php" || it.url.host == "cdn.example" })
        }
    }
    @Test fun newTvCatalogRateLimitStillAllowsTheIndependentIssuedProvider() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(context(), seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/post.php" -> "Too many requests" to 429
            "/search.php" -> """{"searchResult":[{"id":"70155584","t":"Smallville"}]}""" to 200
            "/mobile/episodes.php" -> """{"episodes":[{"id":"82171151","s":"S4","ep":"E8"}]}""" to 200
            "/mobile/hls/82171151.m3u8" -> "Unavailable" to 503
            "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/issued.m3u8?in=unchanged"}]}""" to 200
            "/issued.m3u8" -> media to 200
            else -> throw AssertionError("NewTV rate limit must stay independent: ${request.url.encodedPath}")
        } }.resolve("Smallville", "2001", "tv", 4, 8, "4604")
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertEquals(0L, PlaybackServiceGate.remainingMs())
        assertFalse(seen.any { it.url.encodedPath == "/newtv/player.php" })
    }

    @Test fun verifiedNewTvMetadataStillFeedsDirectAndIssuedPlaybackWhenItsPlayerFails() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(context(), seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/post.php" -> """{"title":"Smallville","type":"t","episodes":[{"id":"82171151","s":"S4","ep":"E8"}]}""" to 200
            "/newtv/player.php" -> "Unavailable" to 503
            "/mobile/hls/82171151.m3u8" -> "Unavailable" to 503
            "/mobile/playlist.php" -> {
                assertEquals("82171151", request.url.queryParameter("id"))
                """{"sources":[{"file":"https://cdn.example/issued.m3u8?in=unchanged"}]}""" to 200
            }
            "/issued.m3u8" -> media to 200
            else -> throw AssertionError("A working independent catalog must not depend on public-site or legacy metadata: ${request.url.encodedPath}")
        } }.resolve("Smallville", "2001", "tv", 4, 8, "4604")
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertEquals(listOf("/checknewtv.php", "/newtv/post.php", "/newtv/player.php", "/mobile/hls/82171151.m3u8", "/mobile/playlist.php", "/issued.m3u8"), seen.map { it.url.encodedPath })
    }

    @Test fun playerApiBackoffDoesNotDisableIndependentEpisodeMetadataForTheNextEpisode() = runBlocking {
        val seen = mutableListOf<Request>(); var posts = 0
        val resolver = resolver(context(), seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/post.php" -> {
                posts++
                val id = if (posts == 1) "82171151" else "82171152"
                val ep = if (posts == 1) "E8" else "E9"
                """{"title":"Smallville","type":"t","episodes":[{"id":"$id","s":"S4","ep":"$ep"}]}""" to 200
            }
            "/newtv/player.php" -> "Page Not Found" to 403
            "/mobile/hls/82171151.m3u8", "/mobile/hls/82171152.m3u8" -> media to 200
            else -> throw AssertionError("Player rejection must not disable a healthy catalog: ${request.url.encodedPath}")
        } }
        assertEquals(PlaybackRoute.DIRECT, resolver.resolve("Smallville", "2001", "tv", 4, 8, "4604").route)
        val next = resolver.resolve("Smallville", "2001", "tv", 4, 9, "4604")
        assertEquals("82171152", next.contentId)
        assertEquals(PlaybackRoute.DIRECT, next.route)
        assertEquals(2, posts)
        assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
    }

    @Test fun coldMovieWithMalformedPrimaryHlsStillUsesVerifiedMetadataForTheRemainingRoutes() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = resolver(context(), seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> "{}" to 200
            "/newtv/player.php" -> """{"title":"Red Notice","type":"m","year":"2021","video_link":"https:///files/81161626/master.m3u8"}""" to 200
            "/mobile/hls/81161626.m3u8" -> "Unavailable" to 503
            "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/issued.m3u8?in=unchanged"}]}""" to 200
            "/issued.m3u8" -> media to 200
            else -> throw AssertionError("Valid movie metadata must survive an unusable NewTV HLS URL: ${request.url.encodedPath}")
        } }.resolve("Red Notice", "2021", "movie", 0, 0, "512195")
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertEquals("81161626", result.contentId)
        assertEquals(listOf("/checknewtv.php", "/newtv/player.php", "/mobile/hls/81161626.m3u8", "/mobile/playlist.php", "/issued.m3u8"), seen.map { it.url.encodedPath })
    }
}
