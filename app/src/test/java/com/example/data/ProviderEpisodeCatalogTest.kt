package com.example.data

import android.content.Context
import android.content.ContextWrapper
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderEpisodeCatalogTest {
    private fun context(): Context {
        val directory = Files.createTempDirectory("provider-episodes-").toFile()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    @Test fun survivesRestartWithOpaqueIdsAndSeparatesProviderCatalogAndShow() {
        val context = context(); val now = 1_000_000L
        val first = ProviderEpisodeCatalog(context)
        first.saveSeasons("https://net52.cc", "pv", "0SERIES123456789", mapOf(2 to "0SEASON2123456789"), now, "official-public")
        first.saveEpisodes("https://net52.cc", "pv", "0SERIES123456789", 2, mapOf(7 to "0EPISODE7123456789"), now)
        val restarted = ProviderEpisodeCatalog(context)
        assertEquals(mapOf(2 to "0SEASON2123456789"), restarted.seasons("https://net52.cc/", "pv", "0SERIES123456789", now))
        assertEquals("0EPISODE7123456789", restarted.episode("https://net52.cc/", "pv", "0SERIES123456789", 2, 7, now))
        assertNull(restarted.episode("https://net53.cc", "pv", "0SERIES123456789", 2, 7, now))
        assertNull(restarted.episode("https://net52.cc", "nf", "0SERIES123456789", 2, 7, now))
        assertNull(restarted.episode("https://net52.cc", "pv", "0OTHER123456789", 2, 7, now))
        assertNull(restarted.episode("https://net52.cc", "pv", "0SERIES123456789", 1, 7, now))
        assertNull(restarted.episode("https://net52.cc", "pv", "0SERIES123456789", 2, 7, now + ProviderEpisodeCatalog.TTL_MS))
        val disk = File(context.filesDir, "verified_provider_episodes_v1.json").readText()
        assertFalse(disk.contains("Cookie")); assertFalse(disk.contains("m3u8")); assertFalse(disk.contains("Authorization"))
    }

    @Test fun conflictingEpisodeMappingIsRemovedAndEvictionPreservesSiblingEpisodesAndSeasons() {
        val cache = ProviderEpisodeCatalog(context()); val now = 1_000_000L
        cache.saveSeasons("https://net52.cc", "nf", "70155584", mapOf(3 to "60000123"), now, "provider-authorized")
        cache.saveEpisodes("https://net52.cc", "nf", "70155584", 3, mapOf(1 to "82171122", 2 to "82171123"), now)
        cache.saveEpisodes("https://net52.cc", "nf", "70155584", 3, mapOf(1 to "82179999"), now)
        assertNull(cache.episode("https://net52.cc", "nf", "70155584", 3, 1, now))
        assertEquals("82171123", cache.episode("https://net52.cc", "nf", "70155584", 3, 2, now))
        cache.evictEpisode("https://net52.cc", "nf", "70155584", 3, 2, now)
        assertEquals(mapOf(3 to "60000123"), cache.seasons("https://net52.cc", "nf", "70155584", now))
        cache.saveEpisodes("https://net52.cc", "nf", "70155584", 3, mapOf(1 to "82171122"), now)
        cache.saveSeasons("https://net52.cc", "nf", "70155584", mapOf(3 to "60000456"), now, "provider-authorized")
        assertNull(cache.episode("https://net52.cc", "nf", "70155584", 3, 1, now))
    }

    @Test fun verifiedSeasonExportWorksWithoutPublicSelectorOrWarmupThenSurvivesRestart() = runBlocking {
        val context = context(); val requests = mutableListOf<Request>()
        ProviderEpisodeCatalog(context).saveSeasons("https://net52.cc", "nf", "70155584", mapOf(3 to "60000123"), System.currentTimeMillis(), "provider-public")
        val client = client(requests) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"70155584","t":"Smallville","type":"t"}]}"""
                "/title/70155584" -> missingSeasons
                "/mobile/episodes.php" -> {
                    assertEquals("60000123", request.url.queryParameter("s"))
                    """{"episodes":[{"id":"82171122","ep":"S3E1"},{"id":"82171123","ep":"S3E2"}]}"""
                }
                "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.invalid/master.m3u8?in=issued"}]}"""
                "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
                else -> throw AssertionError("Unexpected endpoint ${request.url.encodedPath}")
            }
        }
        val first = PublicPlaybackResolver(client, catalog = PublicProviderCatalog(context), backgroundCatalogRefresh = false)
        assertEquals("82171122", first.resolve("Smallville", "2001", "tv", 3, 1).contentId)
        val before = requests.size
        val restarted = PublicPlaybackResolver(client, catalog = PublicProviderCatalog(context), backgroundCatalogRefresh = false)
        assertEquals("82171122", restarted.resolve("Smallville", "2001", "tv", 3, 1).contentId)
        assertFalse(requests.drop(before).any { it.url.encodedPath.contains("episodes.php") || it.url.host == "www.netflix.com" })
    }

    @Test fun failedCachedEpisodeIsRediscoveredOnceWhileSeasonAndOtherEpisodesRemain() = runBlocking {
        val context = context(); val requests = mutableListOf<Request>(); val now = System.currentTimeMillis()
        val cache = ProviderEpisodeCatalog(context)
        cache.saveSeasons("https://net52.cc", "nf", "70155584", mapOf(3 to "60000123"), now, "provider-authorized")
        cache.saveEpisodes("https://net52.cc", "nf", "70155584", 3, mapOf(1 to "82171120", 2 to "82171123"), now)
        val resolver = PublicPlaybackResolver(client(requests) { request ->
            when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[{"id":"70155584","t":"Smallville","type":"t"}]}"""
                "/title/70155584" -> missingSeasons
                "/mobile/episodes.php" -> """{"episodes":[{"id":"82171122","s":"S3","ep":"E1"}]}"""
                "/mobile/playlist.php" -> if (request.url.queryParameter("id") == "82171120") "[]" else """{"sources":[{"file":"https://cdn.invalid/master.m3u8?in=issued"}]}"""
                "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
                "/mobile/hls/82171120.m3u8" -> "unavailable"
                else -> throw AssertionError("Unexpected endpoint")
            }
        }, catalog = PublicProviderCatalog(context), backgroundCatalogRefresh = false)
        assertEquals("82171122", resolver.resolve("Smallville", "2001", "tv", 3, 1).contentId)
        assertEquals(1, requests.count { it.url.encodedPath == "/mobile/episodes.php" })
        val saved = ProviderEpisodeCatalog(context)
        assertEquals("82171123", saved.episode("https://net52.cc", "nf", "70155584", 3, 2, System.currentTimeMillis()))
        assertEquals(mapOf(3 to "60000123"), saved.seasons("https://net52.cc", "nf", "70155584", System.currentTimeMillis()))
    }

    @Test fun identityMismatchAndAmbiguousSeasonLabelsNeverReachPlayback() = runBlocking {
        for (html in listOf(missingSeasons.replace("Smallville", "Other Show"), missingSeasons + """<select name="seasonSelect"><option value="60000123">Season 3</option><option value="60000456">Season 3</option></select>""")) {
            val resolver = PublicPlaybackResolver(client(mutableListOf()) { request ->
                if (request.url.host == "www.netflix.com") html else """{"searchResult":[{"id":"70155584","t":"Smallville","type":"t"}]}"""
            }, backgroundCatalogRefresh = false)
            try { resolver.resolve("Smallville", "2001", "tv", 3, 1); fail("Invalid identity or seasons accepted") }
            catch (_: java.io.IOException) { }
        }
    }

    @Test fun bundledGuideIdsAndSeasonsRespectMovieShowAndOriginNamespaces() {
        val catalog = PublicProviderCatalog(context())
        assertTrue(("1271341039" to "hs") in catalog.candidates("tv", "114471"))
        assertFalse(("1271341039" to "hs") in catalog.candidates("movie", "114471"))
        assertEquals("70037632", catalog.seasonCandidates("https://net52.cc/", "nf", "70155584", listOf("Smallville"))[4])
        assertTrue(catalog.seasonCandidates("https://net53.cc", "nf", "70155584", listOf("Smallville")).isEmpty())
        assertTrue(catalog.seasonCandidates("https://net52.cc", "pv", "70155584", listOf("Smallville")).isEmpty())
        assertTrue(catalog.seasonCandidates("https://net52.cc", "nf", "70155584", listOf("Other Show")).isEmpty())
    }

    private fun client(requests: MutableList<Request>, body: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); synchronized(requests) { requests += request }
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body(request).toResponseBody()).build()
    }.build()

    private val missingSeasons = """<script type="application/ld+json">{"@type":"TVSeries","name":"Smallville","first_air_date":"2001-01-01","numberOfSeasons":0}</script>"""
}
