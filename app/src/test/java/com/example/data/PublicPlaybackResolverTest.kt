package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
class PublicPlaybackResolverTest {
    @Test fun unlabelledDefaultEpisodesCannotOverrideTheRequestedSeason() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> """{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"wrong-default","ep":"E1"}],"season":[{"id":"1271685002","s":"S2"}]}""" to 200
                "/mobile/hs/episodes.php" -> {
                    assertEquals("1271685002", request.url.queryParameter("s"))
                    """{"episodes":[{"id":"1271685003","ep":"E1"}]}""" to 200
                }
                "/mobile/hs/playlist.php" -> {
                    assertEquals("1271685003", request.url.queryParameter("id"))
                    """{"sources":[{"file":"https://cdn.invalid/season-two.m3u8?in=issued"}]}""" to 200
                }
                "/season-two.m3u8" -> "#EXTM3U\n#EXTINF:1,\nsegment.jpg" to 200
                else -> throw AssertionError("Unexpected endpoint")
            }
        })
        assertEquals("1271685003", resolver.resolve("Lanterns", "2026", "tv", 2, 1, "95350").contentId)
    }
    @Test fun staleNetflixPlaylistFallsBackToExactPrimeIdentityWithoutCookies() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"81458416","t":"Fixture Film","y":"2024"}]}""" to 200
                "/mobile/search.php" -> """{"status":"n","head":"Top Searches"}""" to 200
                "/mobile/playlist.php" -> "[]" to 200
                "/title/81458416" -> """<script type="application/ld+json">{"@type":"Movie","name":"Fixture Film","datePublished":"2024-01-01"}</script>""" to 200
                "/detail/0O70LSZ5KT12QBNQRUQGGRIWDP" -> primeMovie("Fixture Film", 2024) to 200
                "/mobile/pv/search.php" -> """{"searchResult":[{"id":"0O70LSZ5KT12QBNQRUQGGRIWDP","t":"Fixture Film","y":"2024"}]}""" to 200
                "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/master.m3u8?in=issued"}]}""" to 200
                "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected request")
            }
        })
        assertEquals("pv",resolver.resolve("Fixture Film","2024","movie",0,0).ott)
        assertEquals(1,seen.count { it.url.encodedPath == "/mobile/playlist.php" })
        assertFalse(seen.any { it.header("Cookie") != null || it.header("Authorization") != null })
    }
    private val html = """<script type="application/ld+json">{"@type":"TVSeries","name":"Smallville"}</script><select name="seasonSelect"><option value="60031634">Season 1</option><option value="70037632">Season 4</option></select><a href="/title/99999">Recommendation</a>"""
    private fun client(seen: MutableList<Request>, body: (Request) -> Pair<String, Int>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); seen += request
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        val (text, code) = body(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Set-Cookie", "t_hash_t=unused; Path=/; Secure").body(text.toResponseBody()).build()
    }.build()
    private fun primeMovie(title: String, year: Int) = """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"film":{"title":"$title","titleType":"movie","releaseYear":$year}}}}}}}}}</script>"""

    @Test fun smallvilleResolvesPublicSeasonAndPaginatedEpisodeWithoutPostOrWarmup() = runBlocking {
        val seen = mutableListOf<Request>()
        val video = "https://s1.freecdn1.top/video/issued.m3u8?in=issued-video&lang=eng"
        val audio = "https://s1.freecdn1.top/audio/eng.m3u8?in=issued-audio"
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            val body = when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"70155584","t":"Smallville"}]}"""
                "/title/70155584" -> html
                "/mobile/episodes.php" -> {
                    assertEquals("60031634", request.url.queryParameter("s"))
                    assertEquals("70155584", request.url.queryParameter("series"))
                    if (request.url.queryParameter("page") == "2") """{"episodes":[{"id":"82171098","s":"S1","ep":"E21","t":"Tempest"}],"nextPageShow":0}"""
                    else """{"episodes":[{"id":"82171078","s":"S1","ep":"E1","t":"Pilot"}],"nextPageShow":1}"""
                }
                "/mobile/playlist.php" -> {
                    assertEquals("82171098", request.url.queryParameter("id"))
                    """[{"sources":[{"file":"/mobile/hls/82171098.m3u8?in=unknown::ek&lang=eng"}],"tracks":[{"file":"/captions/en.vtt","kind":"subtitles","srclang":"en"}]}]"""
                }
                "/mobile/hls/82171098.m3u8" -> {
                    assertFalse(request.url.queryParameter("in")!!.startsWith("unknown"))
                    "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",LANGUAGE=\"en\",URI=\"$audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO=\"a\"\n$video"
                }
                "/video/issued.m3u8" -> { assertEquals(video, request.url.toString()); "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST" }
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            }; body to 200
        })
        val result = resolver.resolve("Smallville", "2001", "tv", 1, 21)
        assertEquals("82171098", result.contentId)
        assertTrue(result.url.startsWith("https://net52.cc/mobile/hls/82171098.m3u8"))
        assertEquals("https://net52.cc/captions/en.vtt", result.captions.single().url)
        assertFalse(result.headers.containsKey("Cookie"))
        assertFalse(seen.any { it.url.encodedPath.contains("post.php") || it.url.encodedPath.contains("home") || it.url.encodedPath.contains("verify") })
        resolver.resolve("Smallville", "2001", "tv", 1, 21)
        assertEquals("Public seasons are cached", 1, seen.count { it.url.host == "www.netflix.com" })
    }

    @Test fun rejectsWrongSeasonInsteadOfPlayingAnUnrelatedEpisode() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"70155584","t":"Smallville"}]}""" to 200
                "/title/70155584" -> html to 200
                "/mobile/episodes.php" -> """{"episodes":[{"id":"wrong","s":"S4","ep":"E1"}]}""" to 200
                "/mobile/search.php", "/mobile/pv/search.php" -> """{"status":"n","head":"Top Searches"}""" to 200
                else -> throw AssertionError("Wrong episode must not reach a playlist")
            }
        })
        try { resolver.resolve("Smallville", "2001", "tv", 1, 1); fail("Wrong season accepted") }
        catch (e: IOException) { assertEquals("Provider returned a different season", e.message) }
        assertFalse(seen.any { it.url.encodedPath.contains("playlist") })
    }

    @Test fun refusesAmbiguousTitleIdentity() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) {
            """{"searchResult":[{"id":"one","t":"Road House","y":"2024"},{"id":"two","t":"Road House","y":"2024"}]}""" to 200
        })
        try { resolver.resolve("Road House", "2024", "movie", 0, 0); fail("Ambiguous match accepted") }
        catch (e: IOException) { assertEquals("Provider title identity is ambiguous", e.message) }
        assertEquals(1, seen.size)
    }

    @Test fun primeOpaqueIdAndIssuedCdnSignatureAreKeptIntact() = runBlocking {
        val seen = mutableListOf<Request>()
        val nativeId = "0O70LSZ5KT12QBNQRUQGGRIWDP"
        val issued = "https://s1.freecdn1.top/movies/media.m3u8?in=provider-issued&lang=eng"
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php", "/mobile/search.php" -> """{"head":"Top Searches","status":"n","searchResult":[{"id":"wrong","t":"Road House"}]}""" to 200
                "/mobile/pv/search.php" -> """{"searchResult":[{"id":"$nativeId","t":"Road House","y":"2024"},{"id":"remake","t":"Road House","y":"1989"}]}""" to 200
                "/detail/$nativeId" -> primeMovie("Road House", 2024) to 200
                "/mobile/pv/playlist.php" -> { assertEquals(nativeId, request.url.queryParameter("id")); """{"sources":[{"file":"$issued"}]}""" to 200 }
                "/movies/media.m3u8" -> { assertEquals(issued, request.url.toString()); "#EXTM3U\n#EXTINF:10,\nsegment.jpg" to 200 }
                else -> throw AssertionError("Unexpected endpoint")
            }
        })
        val result = resolver.resolve("Road House", "2024", "movie", 0, 0)
        assertEquals(nativeId, result.contentId); assertEquals("pv", result.ott); assertEquals(issued, result.url)
    }

    @Test fun netflixParserBindsSeasonSelectorToShowAndRejectsDuplicates() {
        assertEquals(mapOf(1 to "60031634", 4 to "70037632"), PublicPlaybackResolver.parseNetflixSeasons(html, "Smallville"))
        for (bad in listOf(html.replace("Smallville", "Other Show"), html.replace("Season 4", "Season 1"), html.replace("seasonSelect", "recommendations"))) {
            try { PublicPlaybackResolver.parseNetflixSeasons(bad, "Smallville"); fail("Untrusted seasons accepted") } catch (_: IOException) { }
        }
    }

    @Test fun publicMasterConstructionIsScopedToProviderHlsOnly() {
        val signed = "https://s1.freecdn1.top/video.m3u8?in=issued::signature"
        assertEquals(signed, ProviderMasterRequest.resolve(signed, "82171078"))
        val cdnPlaceholder = "https://s1.freecdn1.top/mobile/hls/id.m3u8?in=unknown::ek"
        assertEquals(cdnPlaceholder, ProviderMasterRequest.resolve(cdnPlaceholder, "id"))
        val unrelated = "https://net52.cc/other/id.m3u8?in=unknown::ek"
        assertEquals(unrelated, ProviderMasterRequest.resolve(unrelated, "id"))
        val master = ProviderMasterRequest.resolve("https://net52.cc/mobile/pv/hls/id.m3u8?lang=eng", "opaque")
        assertTrue(master.contains("lang=eng")); assertTrue(master.contains("in="))
    }

    @Test fun oversizedMetadataIsRejectedByTheCancellableTransport() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { "x".repeat(64) to 200 }
        try { http.fetchText(Request.Builder().url("https://net52.cc/search.php").build(), 16); fail("Oversized response accepted") }
        catch (e: IOException) { assertEquals("Playback response is too large", e.message) }
    }

    @Test fun authorizationFailureStopsWithoutTryingWarmupOrAnotherCatalog() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { "Invalid User" to 403 })
        try { resolver.resolve("Smallville", "2001", "tv", 1, 1); fail("Unauthorized lookup accepted") }
        catch (e: IOException) { assertEquals("Playback metadata requires authorization", e.message) }
        assertEquals(listOf("/search.php"), seen.map { it.url.encodedPath })
    }
    @Test fun lanternsUsesItsPublicNativeIdWithoutSearchOrCookieHandshake() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> """{"status":"y","title":"Lanterns","year":"2026","type":"t","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""" to 200
                "/mobile/hs/playlist.php" -> """[{"sources":[{"file":"/mobile/hs/hls/1271684191.m3u8?in=unknown::ek"}]}]""" to 200
                "/mobile/hs/hls/1271684191.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nhttps://s1.freecdn1.top/video/issued.m3u8?in=issued" to 200
                "/video/issued.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST" to 200
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            }
        })
        val result = resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertEquals("hs", result.ott)
        assertEquals("1271684191", result.contentId)
        assertTrue(seen.none { it.url.encodedPath.contains("search") || it.url.encodedPath.contains("verify") })
    }

    @Test fun unavailableSeasonRetriesAnotherVerifiedHostingIdentity() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php", "/mobile/search.php" -> """{"searchResult":[{"id":"70155584","t":"Fixture Series","y":"2026","type":"t"}]}""" to 200
                "/title/70155584" -> """<script type="application/ld+json">{"@type":"TVSeries","name":"Fixture Series"}</script><select name="seasonSelect"><option value="60031634">Season 1</option></select>""" to 200
                "/mobile/pv/search.php" -> """{"searchResult":[{"id":"0SERIES123456789","t":"Fixture Series","type":"t"}]}""" to 200
                "/detail/0SERIES123456789" -> """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"show":{"titleType":"season","title":"Fixture Series - Season 2"}}},"seasons":{"show":[{"sequenceNumber":2,"seasonLink":"/detail/0SEASON2123456789"}]}}}}}}}</script>""" to 200
                "/mobile/pv/episodes.php" -> {
                    assertEquals("0SEASON2123456789", request.url.queryParameter("s"))
                    """{"episodes":[{"id":"0EPISODE123456789","s":"S2","ep":"E1"}]}""" to 200
                }
                "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/master.m3u8?in=issued"}]}""" to 200
                "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            }
        })
        val result = resolver.resolve("Fixture Series", "2022", "tv", 2, 1)
        assertEquals("pv", result.ott)
        assertEquals("0EPISODE123456789", result.contentId)
        assertFalse(seen.any { it.url.encodedPath == "/mobile/playlist.php" })
    }

    @Test fun newSeriesOutsideBothBundledAndRemoteIndexesIsFoundFromPublishedLinks() = runBlocking {
        val seen = mutableListOf<Request>()
        val path = "/tv-shows/brand-new-show/HOTSTAR_DTH_TVSHOW_1971999001"
        val resolver = PublicPlaybackResolver(client(seen) { request ->
            when (request.url.encodedPath) {
                "/search.php", "/mobile/search.php", "/mobile/pv/search.php" -> """{"status":"n","head":"Top Searches"}""" to 200
                "/tv-shows" -> """<a href="$path">Brand New Show</a>""" to 200
                path -> """<p id="banner-content-release-year">2026</p><script>{"@type":"VideoObject","name":"Brand New Show"}</script>""" to 200
                "/mobile/hs/post.php" -> {
                    assertEquals("1971999001", request.url.queryParameter("id"))
                    """{"status":"y","title":"Brand New Show","year":"2026","type":"t","episodes":[{"id":"1971999011","s":"S1","ep":"E1"}]}""" to 200
                }
                "/mobile/hs/playlist.php" -> """{"sources":[{"file":"https://cdn.example/master.m3u8?in=issued"}]}""" to 200
                "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("New title must not depend on Wikidata or an APK update: ${request.url.encodedPath}")
            }
        })
        val result = resolver.resolve("Brand New Show", "2025", "tv", 1, 1, "999999001")
        assertEquals("hs", result.ott)
        assertEquals("1971999011", result.contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/post.php" })
    }

    @Test fun primeSeasonLinksAreBoundToTheSeriesAndExcludeRecommendations() {
        val json="""<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"show":{"title":"Slow Horses - Season 1","titleType":"season"}}},"seasons":{"show":[{"sequenceNumber":1,"seasonLink":"/detail/0MEYJKN34E2DBOY4OY7Z31N4Q4?ref_=s1"},{"sequenceNumber":2,"seasonLink":"https://evil.example/detail/0UNTRUSTED12345"}]}}}}}}}</script>"""
        assertEquals(mapOf(1 to "0MEYJKN34E2DBOY4OY7Z31N4Q4"), PublicPlaybackResolver.parsePrimeSeasons(json, "Slow Horses"))
        try { PublicPlaybackResolver.parsePrimeSeasons(json, "Different Show"); fail("Expected identity rejection") } catch (_: IOException) { }
    }

}
