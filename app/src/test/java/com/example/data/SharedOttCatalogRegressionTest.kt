package com.example.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SharedOttCatalogRegressionTest {
    private val movie = "Demon Slayer: Kimetsu no Yaiba Infinity Castle"
    private val edition = "$movie I"
    private val show = "Demon Slayer: Kimetsu no Yaiba"

    private fun page(id: String, title: String, kind: String, year: Int, seasons: JSONArray = JSONArray(), queryId: String = id): String {
        val video = JSONObject().put("__typename", kind).put("videoId", id.toLong()).put("title", title).put("latestYear", year)
        if (kind == "Show") video.put("seasons", JSONObject().put("edges", seasons))
        val metadata = JSONObject().put("videoId", id.toLong()).put("topLevelVideoId", id.toLong()).put("type", kind)
            .put("nextPage", "https://www.netflix.com/title/$id")
        val query = JSONObject().put("query", "clcsHookV2").put("variables", JSONObject().put("parameters",
            JSONArray().put(JSONObject().put("name", "titleId").put("value", JSONObject().put("stringValue", queryId)))))
        val batch = JSONObject("""{"clcsHookV2":{"result":{"screen":{"componentTree":{"nodes":[{"data":{"videos":[]}}]}}}}}""")
        batch.getJSONObject("clcsHookV2").getJSONObject("result").getJSONObject("screen").getJSONObject("componentTree")
            .getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONArray("videos").put(video)
        val root = JSONObject().put("models", JSONObject()
            .put("nmTitleGQL", JSONObject().put("data", JSONObject().put("metaData", metadata)))
            .put("graphqlClcs", JSONObject().put("data", JSONObject().put(query.toString(), batch))))
        return "<script>window.netflix = window.netflix || {};\nnetflix.reactContext = ${root};</script>"
    }
    private fun season(number: Int, id: String, name: String) = JSONObject().put("node", JSONObject()
        .put("__typename", "Season").put("numberLabelV2", "Season $number").put("videoId", id.toLong())
        .put("showMemberType", "SEASON").put("title", name))

    @Test fun modernNetflixMovieMetadataRetainsExactTitleTypeAndYear() {
        val html = page("82723106", edition, "Movie", 2025)
        assertTrue(PublicProviderIdentity.matchesNetflix(html, edition, "2025", "movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html, edition, "2024", "movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html, movie, "2025", "movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html, edition, "2025", "tv", typedMapping = true))
    }
    @Test fun namedAnimeSeasonsUseExplicitLabelsInsteadOfDisplayOrder() {
        val html = page("81091393", show, "Show", 2024, JSONArray()
            .put(season(5, "81743731", "Hashira Training Arc"))
            .put(season(1, "81091394", "Tanjiro Kamado, Unwavering Resolve Arc"))
            .put(season(2, "81513781", "Mugen Train Arc")))
        assertEquals(mapOf(1 to "81091394", 2 to "81513781", 5 to "81743731"), PublicPlaybackResolver.parseNetflixSeasons(html, show))
    }
    @Test fun manyTranslationsCannotDropTheMatchingProviderAlias() {
        val aliases = JSONArray();repeat(35) { aliases.put(JSONObject().put("title", "Translation $it").put("iso_3166_1", "FR")) }
        aliases.put(JSONObject().put("title", edition).put("iso_3166_1", "US"))
        val metadata = JSONObject().put("id", 1311031).put("title", movie).put("release_date", "2025-07-18")
            .put("alternative_titles", JSONObject().put("titles", aliases))
        assertTrue(PublicIdentityDiscovery.metadata(metadata, "1311031", "movie", "2025")!!.aliases.contains(edition))
    }
    @Test fun netflixSearchFailuresDoNotSuppressAHealthyPrimeCatalog() = runBlocking {
        for ((failedBody, failedCode) in listOf("unavailable" to 503, "not-json" to 200)) {
            val seen = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request();seen += request.url.encodedPath
                val (body, code) = when (request.url.encodedPath) {
                    "/search.php" -> failedBody to failedCode
                    "/mobile/pv/search.php" -> """{"searchResult":[{"id":"0MOVIE123456789","t":"Fixture Film","type":"m","y":"2024"}]}""" to 200
                    "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/movie.m3u8?in=issued"}]}""" to 200
                    "/movie.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                    else -> throw AssertionError("Unexpected request ${request.url.encodedPath}")
                }
                assertNull(request.header("Cookie"));assertNull(request.header("Authorization"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
            }.build()
            val result = PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                .resolve("Fixture Film", "2024", "movie", 0, 0)
            assertEquals("pv", result.ott)
            assertEquals(1, seen.count { it == "/search.php" })
        }
    }
    @Test fun shortHotstarHomepagesAndWikidataWebsitesUseTheSameVerifiedLinkContract() {
        assertEquals(listOf("1260057812" to "hs"), PublicIdentityDiscovery.homepageIds("https://hotstar.com/1260057812/watch"))
        val entity = """{"entities":{"Q1":{"claims":{"P856":[{"mainsnak":{"datavalue":{"value":"https://hotstar.com/1260057812/watch"}}}]}}}}"""
        assertEquals(listOf("1260057812"), PublicProviderIdentity.hotstarIds(entity, "Q1"))
    }
    private fun isolatedContext(): android.content.Context {
        val directory = java.nio.file.Files.createTempDirectory("ott-catalog-").toFile()
        return object : android.content.ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = directory
        }
    }
    @org.junit.Before @org.junit.After fun resetGate() {
        val cooldown = org.robolectric.util.ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(PlaybackServiceGate::class.java, "cooldown")
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "strikes", 0)
        org.robolectric.util.ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
        VerifiedManifestHandoff.clear()
    }
    private fun client(seen: MutableList<Request>, response: (Request) -> Pair<String, Int>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request();seen += request
        assertNull(request.header("Cookie"));assertNull(request.header("Authorization"))
        val (body, code) = response(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    @Test fun infinityCastleFindsTheVerifiedEditionAliasAndModernMoviePage() = runBlocking {
        val aliases = JSONArray();repeat(35) { aliases.put(JSONObject().put("title", "Translation $it")) };aliases.put(JSONObject().put("title", edition))
        val tmdb = JSONObject().put("id", 1311031).put("title", movie).put("release_date", "2025-07-18")
            .put("alternative_titles", JSONObject().put("titles", aliases))
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> JSONObject().put("searchResult", JSONArray().put(JSONObject().put("id", "82723106").put("t", edition))).toString() to 200
            "/mobile/pv/search.php", "/sparql" -> "{}" to 200
            "/3/movie/1311031" -> tmdb.toString() to 200
            "/title/82723106" -> page("82723106", edition, "Movie", 2025) to 200
            "/mobile/playlist.php" -> { assertEquals("82723106", request.url.queryParameter("id")); """{"sources":[{"file":"https://cdn.example/infinity.m3u8?in=issued"}]}""" to 200 }
            "/infinity.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        val result = resolver.resolve(movie, "2025", "movie", 0, 0, "1311031")
        assertEquals("nf", result.ott);assertEquals("82723106", result.contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/search.php" })
        assertEquals(1, seen.count { it.url.encodedPath == "/title/82723106" })
    }
    @Test fun allFiveDemonSlayerSeasonsReachTheirExplicitEpisodeIds() = runBlocking {
        val ids = mapOf(1 to ("81091394" to "81091395"), 2 to ("81513781" to "81513782"),
            3 to ("81513768" to "81513769"), 4 to ("81685138" to "81685139"), 5 to ("81743731" to "81743732"))
        val seasons = JSONArray();ids.entries.reversed().forEach { (n, ids) -> seasons.put(season(n, ids.first, "Arc $n")) }
        for ((number, ids) in ids) {
            val seen = mutableListOf<Request>()
            val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"81091393","t":"$show"}]}""" to 200
                "/title/81091393" -> page("81091393", show, "Show", 2024, seasons) to 200
                "/mobile/episodes.php" -> {
                    assertEquals(ids.first, request.url.queryParameter("s"));assertEquals("81091393", request.url.queryParameter("series"))
                    """{"episodes":[{"id":"${ids.second}","s":"S$number","ep":"E1"}]}""" to 200
                }
                "/mobile/playlist.php" -> { assertEquals(ids.second, request.url.queryParameter("id")); """{"sources":[{"file":"https://cdn.example/anime.m3u8?in=issued"}]}""" to 200 }
                "/anime.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            } }, catalog = PublicProviderCatalog(isolatedContext()), backgroundCatalogRefresh = false, cookieFreeRoutes = false)
            assertEquals(ids.second, resolver.resolve(show, "2019", "tv", number, 1, "85937").contentId)
            assertEquals(1, seen.count { it.url.encodedPath == "/search.php" })
        }
    }
    @Test fun unavailablePrimeSearchCannotBlockHotstarPartnerDiscovery() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> "{}" to 200
            "/mobile/pv/search.php" -> "Unavailable" to 503
            "/movies/the-new-mutants/HOTSTAR_DTH_MOVIE_889028" -> """<p id="banner-content-release-year">2020</p><script>{"@type":"VideoObject","name":"The New Mutants"}</script><a href="https://hotstar.com/1260057812/watch">Watch</a>""" to 200
            "/mobile/hs/post.php" -> """{"status":"y","title":"The New Mutants","type":"m","year":"2020"}""" to 200
            "/mobile/hs/playlist.php" -> """{"sources":[{"file":"https://cdn.example/mutants.m3u8?in=issued"}]}""" to 200
            "/mutants.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, catalog = PublicProviderCatalog(isolatedContext()), backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        val result = resolver.resolve("The New Mutants", "2020", "movie", 0, 0, "340102")
        assertEquals("hs", result.ott);assertEquals("1260057812", result.contentId)
    }
    @Test fun modernPageModelsRejectWrongNativeIdsTypesAndQueryScopes() {
        val html = page("82723106", edition, "Movie", 2025)
        assertNull(NetflixPublicMetadata.parse(html, "81091393"))
        assertNull(NetflixPublicMetadata.parse(html.replace("\"topLevelVideoId\":82723106", "\"topLevelVideoId\":81091393")))
        assertNull(NetflixPublicMetadata.parse(html.replace("www.netflix.com", "evil.example")))
        assertNull(NetflixPublicMetadata.parse(page("82723106", edition, "Movie", 2025, queryId = "81091393")))
        assertNull(NetflixPublicMetadata.parse(html + html))
        assertNull(NetflixPublicMetadata.parse(html.replace(";</script>", ";globalThis.sideEffect=true;</script>")))
        assertEquals(edition, NetflixPublicMetadata.parse(html.replace("Demon Slayer", "Demon\\x20Slayer"))!!.title)
        assertFalse(PublicProviderIdentity.matchesNetflix(page("81091393", show, "Show", 2024), show, "2024", "tv"))
    }
    @Test fun ambiguousOrUnlabelledSeasonsAreRejected() {
        for (rows in listOf(
            JSONArray().put(season(1, "81091394", "Arc A")).put(season(1, "81513781", "Arc B")),
            JSONArray().put(season(1, "81091394", "Arc A")).put(season(2, "81091394", "Arc B"))
        )) assertNull(NetflixPublicMetadata.parse(page("81091393", show, "Show", 2024, rows)))
        assertNull(NetflixPublicMetadata.parse(page("81091393", show, "Show", 2024,
            JSONArray().put(season(1, "81091394", "Arc A"))).replace("Season 1", "Arc A")))
    }
    @Test fun cachedAliasesRetainLateEditionNamesAfterRestart() {
        val context = isolatedContext();val now = System.currentTimeMillis();val aliases = (1..50).map { "Translation $it" } + edition
        val catalog = PublicProviderCatalog(context)
        catalog.saveDiscovered("movie:1311031", listOf("82723106" to "nf"), aliases, now)
        catalog.save("movie-key", "82723106", "nf", now, aliases)
        val restored = PublicProviderCatalog(context)
        assertTrue(restored.discovered("movie:1311031", now)!!.aliases.contains(edition))
        assertTrue(restored.verifiedAliases("movie-key").contains(edition))
    }
    @Test fun realRateLimitsAndAuthorizationFailuresStillStopSameServiceRequests() = runBlocking {
        for (code in listOf(401, 403, 429)) {
            resetGate();val seen = mutableListOf<Request>()
            val resolver = PublicPlaybackResolver(client(seen) { request ->
                assertEquals("/search.php", request.url.encodedPath)
                (if (code == 429) "Too many requests" else "Authorization required") to code
            }, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
            try { resolver.resolve("Fixture Film", "2024", "movie", 0, 0);fail("Rejection ignored") }
            catch (error: java.io.IOException) {
                if (code == 429) assertTrue(error is PlaybackRateLimitedException)
                else assertEquals("Playback metadata requires authorization", error.message)
            }
            assertEquals(1, seen.size)
        }
    }
    @Test fun cancellationDuringSearchIsNotConvertedIntoAnotherCatalogRequest() = runBlocking {
        val seen = mutableListOf<Request>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolver = PublicPlaybackResolver(client(seen) {
            started.complete(Unit)
            Thread.sleep(200)
            "Unavailable" to 503
        }, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { resolver.resolve("Fixture Film", "2024", "movie", 0, 0) }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("/search.php"), seen.map { it.url.encodedPath })
    }

    @Test fun unavailableNetflixEpisodeMetadataCanUseVerifiedPrimeSeries() = runBlocking {
        val seen = mutableListOf<Request>();val primeId = "0SERIES123456789";val episodeId = "0EPISODE1234567"
        val details = """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"show":{"title":"$show - Season 1","titleType":"season","first_air_date":"2019-04-06","seasonNumber":1}}},"seasons":{"show":[{"sequenceNumber":1,"seasonLink":"/detail/$primeId"}]}}}}}}}</script>"""
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> """{"searchResult":[{"id":"81091393","t":"$show"}]}""" to 200
            "/title/81091393" -> "Public metadata unavailable" to 503
            "/mobile/pv/search.php" -> """{"searchResult":[{"id":"$primeId","t":"$show","type":"t","y":"2019"}]}""" to 200
            "/detail/$primeId" -> details to 200
            "/mobile/pv/episodes.php" -> """{"episodes":[{"id":"$episodeId","s":"S1","ep":"E1"}]}""" to 200
            "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/fallback.m3u8?in=issued"}]}""" to 200
            "/fallback.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, catalog = PublicProviderCatalog(isolatedContext()), backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        val result = resolver.resolve(show, "2019", "tv", 1, 1, "85937")
        assertEquals("pv", result.ott);assertEquals(episodeId, result.contentId)
        assertEquals(1, seen.count { it.url.encodedPath == "/title/81091393" })
    }
    @Test fun unavailableIssuedNetflixMovieCanUseAnotherVerifiedCatalogWithoutRepeatingPlayback() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> """{"searchResult":[{"id":"81161626","t":"Fixture Film","type":"m","y":"2024"}]}""" to 200
            "/mobile/playlist.php" -> "Unavailable" to 503
            "/mobile/pv/search.php" -> """{"searchResult":[{"id":"0MOVIE123456789","t":"Fixture Film","type":"m","y":"2024"}]}""" to 200
            "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/fallback.m3u8?in=issued"}]}""" to 200
            "/fallback.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        assertEquals("pv", resolver.resolve("Fixture Film", "2024", "movie", 0, 0).ott)
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/playlist.php" })
        assertEquals(1, seen.count { it.url.encodedPath == "/search.php" })
    }

    @Test fun rejectedNetflixCdnSourceCanUsePrimeWithoutRetryingTheRejectedUrl() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> """{"searchResult":[{"id":"82723106","t":"Fixture Film","type":"m","y":"2025"}]}""" to 200
            "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/rejected.m3u8?in=issued"}]}""" to 200
            "/rejected.m3u8" -> "Forbidden" to 403
            "/mobile/pv/search.php" -> """{"searchResult":[{"id":"0MOVIE123456789","t":"Fixture Film","type":"m","y":"2025"}]}""" to 200
            "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/working.m3u8?in=issued"}]}""" to 200
            "/working.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        assertEquals("pv", resolver.resolve("Fixture Film", "2025", "movie", 0, 0).ott)
        assertEquals(1, seen.count { it.url.encodedPath == "/rejected.m3u8" })
    }
    @Test fun typedNetflixMovieDoesNotTreatRuntimeDisplayAsAMediaType() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> when (request.url.encodedPath) {
            "/search.php" -> """{"searchResult":[{"id":"81161626","t":"Red Notice","r":"1h 58m"}]}""" to 200
            "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/working.m3u8?in=issued"}]}""" to 200
            "/working.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts" to 200
            else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
        } }, catalog = PublicProviderCatalog(isolatedContext()), backgroundCatalogRefresh = false, cookieFreeRoutes = false)
        assertEquals("81161626", resolver.resolve("Red Notice", "2021", "movie", 0, 0, "512195").contentId)
    }

}
