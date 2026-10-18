package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
class SeriesEpisodeOrderTest {
    private fun fixture() = JSONObject(javaClass.getResource("/episode-order/black-clover.json")!!.readText())
    private fun rows(season: Int): List<JSONObject> {
        val rows = fixture().getJSONArray("provider")
        return (0 until rows.length()).map(rows::getJSONObject).filter { it.getString("s") == "S$season" }
    }
    private val html = """<script type="application/ld+json">{"@type":"TVSeries","name":"Black Clover","first_air_date":"2017-10-03"}</script><select name="seasonSelect"><option value="80237813">Season 1</option><option value="81313521">Season 2</option><option value="81399561">Season 3</option><option value="81582089">Season 4</option></select>"""

    @Test fun all170EpisodesJoinExactlyOnceDespiteTranslatedTitlesAndIncorrectNumericLabels() {
        val metadata = SeriesEpisodeOrder.metadata(fixture().getJSONObject("tmdb"))
        val mappings = (1..4).flatMap { season -> SeriesEpisodeOrder.join(JSONArray(rows(season)), metadata, season) }
        assertEquals((1..170).toSet(), mappings.map { it.number }.toSet())
        assertEquals(170, mappings.map { it.id }.distinct().size)
        assertEquals(listOf(51, 51, 52, 16), (1..4).map { season -> mappings.count { it.providerSeason == season } })
        assertEquals("81399562", mappings.single { it.number == 103 }.id)
        assertEquals(104, mappings.single { it.number == 103 }.providerEpisode)
        assertEquals("81582107", mappings.single { it.number == 170 }.id)
        assertEquals(171, mappings.single { it.number == 170 }.providerEpisode)
    }

    @Test fun presentationKeepsCanonicalEpisodeCoordinatesAndDoesNotChangeAnotherShow() {
        assertEquals(listOf(1, 2, 3, 4), SeriesEpisodeOrder.seasons("73223"))
        for ((episode, season) in listOf(1 to 1, 51 to 1, 52 to 2, 102 to 2, 103 to 3, 154 to 3, 155 to 4, 170 to 4)) {
            assertEquals(season, SeriesEpisodeOrder.displaySeason("73223", 1, episode))
            assertTrue(SeriesEpisodeOrder.contains("73223", season, episode))
            assertEquals(1, SeriesEpisodeOrder.sourceSeason("73223", season))
            assertEquals(season, SeriesEpisodeOrder.providerSeason("73223", "nf", "80238012", 1, episode))
        }
        assertNull(SeriesEpisodeOrder.seasons("85937"))
        assertEquals(5, SeriesEpisodeOrder.sourceSeason("85937", 5))
        assertNull(SeriesEpisodeOrder.providerSeason("73223", "pv", "0ANOTHER123456", 1, 103))
        assertNull(SeriesEpisodeOrder.providerSeason("73224", "nf", "80238012", 1, 103))
    }

    @Test fun joinsRejectWrongSeriesMetadataWrongSeasonAndAmbiguousEpisodeTitles() {
        val metadata = SeriesEpisodeOrder.metadata(fixture().getJSONObject("tmdb"))
        fun rejected(block: () -> Unit) { try { block(); fail("Invalid mapping accepted") } catch (_: java.io.IOException) {} }
        rejected { SeriesEpisodeOrder.metadata(fixture().getJSONObject("tmdb").put("id", 91273)) }
        rejected { SeriesEpisodeOrder.join(JSONArray(rows(2)), metadata, 1) }
        rejected { SeriesEpisodeOrder.join(JSONArray().put(rows(3).first()).put(JSONObject(rows(3).first().toString()).put("id", "99999999")), metadata, 3) }
        val alias = JSONObject(rows(1)[1].toString()).put("id", "99999999")
        assertTrue(SeriesEpisodeOrder.join(JSONArray().put(alias), metadata, 1).isEmpty())
        rejected { SeriesEpisodeOrder.providerSeason("73223", "nf", "80238012", 4, 52) }
    }

    @org.junit.Before @org.junit.After fun resetGate() {
        val cooldown = org.robolectric.util.ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(PlaybackServiceGate::class.java, "cooldown")
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "strikes", 0)
        org.robolectric.util.ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
        VerifiedManifestHandoff.clear()
    }

    @Test fun actualResolverReachesPageSixAndEveryProviderSeasonBoundary() = runBlocking {
        val expected = mapOf(50 to "80238275", 51 to "80238276", 52 to "81313232", 102 to "81313282", 103 to "81399562", 154 to "81408243", 155 to "81582092", 170 to "81582107")
        for ((number, expectedId) in expected) {
            val seen = mutableListOf<okhttp3.Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); seen += request
                assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
                val body = when (request.url.encodedPath) {
                    "/search.php" -> """{"searchResult":[{"id":"80238012","t":"Black Clover"}]}"""
                    "/title/80238012" -> html
                    "/3/tv/73223/season/1" -> fixture().getJSONObject("tmdb").toString()
                    "/mobile/episodes.php" -> {
                        val season = mapOf("80237813" to 1, "81313521" to 2, "81399561" to 3, "81582089" to 4).getValue(request.url.queryParameter("s")!!)
                        assertEquals("80238012", request.url.queryParameter("series"))
                        val page = request.url.queryParameter("page")?.toInt() ?: 1
                        val all = rows(season); val selected = all.drop((page - 1) * 10).take(10)
                        JSONObject().put("episodes", JSONArray(selected)).put("nextPageShow", if (page * 10 < all.size) 1 else 0).toString()
                    }
                    "/mobile/playlist.php" -> {
                        assertEquals(expectedId, request.url.queryParameter("id"))
                        """{"sources":[{"file":"https://cdn.example/verified.m3u8?in=issued"}]}"""
                    }
                    "/verified.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
                    else -> throw AssertionError("Unexpected path ${request.url.encodedPath}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            val result = PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
                .resolve("Black Clover", "2017", "tv", 1, number, "73223")
            assertEquals(expectedId, result.contentId)
            if (number == 51 || number == 102 || number == 154) assertTrue(seen.any { it.url.queryParameter("page") == "6" })
            assertTrue(seen.size < 15)
        }
    }

    @Test fun mappedEpisodeCacheRestoresProviderCoordinatesWithoutTrustingOldNumericRows() {
        val directory = java.nio.file.Files.createTempDirectory("ordered-episodes-").toFile()
        val context = object : android.content.ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = directory
        }
        val now = System.currentTimeMillis()
        val cache = ProviderEpisodeCatalog(context)
        cache.saveEpisodes("https://net52.cc", "nf", "80238012", 1, mapOf(103 to "81399562"), now)
        assertNull(cache.mappedEpisode("https://net52.cc", "nf", "80238012", 103, now))
        cache.saveMappedEpisodes("https://net52.cc", "nf", "80238012", listOf(SeriesEpisodeOrder.Mapping(103, "81399562", 3, 104)), now)
        assertEquals(SeriesEpisodeOrder.Mapping(103, "81399562", 3, 104), ProviderEpisodeCatalog(context).mappedEpisode("https://net52.cc", "nf", "80238012", 103, now))
    }
    @Test fun exactEpisodeJoinPassesProviderCoordinatesToNewTvInsteadOfCanonicalNumbers() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"80238012","t":"Black Clover"}]}"""
                "/title/80238012" -> html
                "/3/tv/73223/season/1" -> fixture().getJSONObject("tmdb").toString()
                "/mobile/episodes.php" -> {
                    assertEquals("81399561", request.url.queryParameter("s"))
                    JSONObject().put("episodes", JSONArray(rows(3).take(10))).put("nextPageShow", 1).toString()
                }
                "/checknewtv.php" -> """{"token_hash":"aHR0cHM6Ly90di5pbWdjZG4ua2lt"}"""
                "/newtv/player.php" -> {
                    assertEquals("81399562", request.url.queryParameter("id"))
                    """{"ott":"nf","id":"81399562","ep":"S3 E104","video_link":"https://cdn.example/exact.m3u8"}"""
                }
                "/exact.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"
                else -> throw AssertionError("Unexpected path ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val result = PublicPlaybackResolver(client, backgroundCatalogRefresh = false,
            newTv = NewTvPlaybackResolver(client, gate = CookieFreePlaybackGate()))
            .resolve("Black Clover", "2017", "tv", 1, 103, "73223")
        assertEquals(PlaybackRoute.NEWTV, result.route)
        assertEquals("81399562", result.contentId)
        assertFalse(seen.contains("/newtv/post.php"))
        assertFalse(seen.contains("/mobile/playlist.php"))
    }

}
