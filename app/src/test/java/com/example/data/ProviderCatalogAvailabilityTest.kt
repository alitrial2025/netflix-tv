package com.example.data

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
class ProviderCatalogAvailabilityTest {
    private fun catalog(): PublicProviderCatalog {
        val directory = Files.createTempDirectory("typed-netflix-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        return PublicProviderCatalog(context)
    }

    private suspend fun resolve(title: String, year: String, tmdb: String, type: String, row: String): PublicPlaybackResult {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Cookie"))
            paths += request.url.encodedPath
            val (body, code) = when (request.url.encodedPath) {
                "/search.php" -> """{"searchResult":[$row]}""" to 200
                "/mobile/pv/search.php", "/sparql", "/3/$type/$tmdb" -> "{}" to 200
                "/title/70155584", "/title/81161626" -> "Public title site unavailable" to 503
                "/mobile/episodes.php" -> {
                    assertEquals("60031634", request.url.queryParameter("s"))
                    assertEquals("70155584", request.url.queryParameter("series"))
                    """{"episodes":[{"id":"82171078","s":"S1","ep":"E1","t":"Pilot"}]}""" to 200
                }
                "/mobile/playlist.php" -> """{"sources":[{"file":"https://cdn.example/actual.m3u8?in=issued"}]}""" to 200
                "/actual.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST" to 200
                else -> "Unavailable" to 404
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val result = PublicPlaybackResolver(client, catalog = catalog(), backgroundCatalogRefresh = false,
            cookieFreeRoutes = false).resolve(title, year, type, if (type == "tv") 1 else 0, if (type == "tv") 1 else 0, tmdb)
        assertFalse("Typed live catalog lookup must not require the public website", paths.any { it.startsWith("/title/") })
        return result
    }

    @Test fun smallvilleUsesTypedIdentityLiveCatalogAndVerifiedSeasonWhenPublicWebsiteIsUnavailable() = runBlocking {
        assertEquals("82171078", resolve("Smallville", "2001", "4604", "tv", """{"id":"70155584","t":"Smallville"}""").contentId)
    }

    @Test fun redNoticeUsesTypedIdentityAndLiveCatalogWhenPublicWebsiteIsUnavailable() = runBlocking {
        assertEquals("81161626", resolve("Red Notice", "2021", "512195", "movie", """{"id":"81161626","t":"Red Notice"}""").contentId)
    }

    @Test fun typedMappingDoesNotAcceptProviderIdWithDifferentTitleOrExplicitlyWrongType() = runBlocking {
        for (row in listOf("""{"id":"81161626","t":"Other movie"}""", """{"id":"81161626","t":"Red Notice","type":"t"}""", """{"id":"81161626","t":"Red Notice","type":"m","y":"1989"}""")) {
            try { resolve("Red Notice", "2021", "512195", "movie", row); fail("Conflicting live catalog accepted") }
            catch (_: java.io.IOException) { }
        }
    }

    private suspend fun houseOfTheDragon(details: String): PublicPlaybackResult {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> {
                    assertEquals("1971002877", request.url.queryParameter("id"))
                    details
                }
                "/mobile/hs/episodes.php" -> {
                    assertEquals("1971009236", request.url.queryParameter("s"))
                    assertEquals("1971002877", request.url.queryParameter("series"))
                    """{"episodes":[{"id":"1971034981","s":"S1","ep":"E1","t":"The Heirs of the Dragon"}]}"""
                }
                "/mobile/hs/playlist.php" -> {
                    assertEquals("1971034981", request.url.queryParameter("id"))
                    """{"sources":[{"file":"https://cdn.example/house.m3u8?in=issued"}]}"""
                }
                "/house.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST"
                "/search.php", "/mobile/pv/search.php" -> "{}"
                else -> throw AssertionError("Unexpected lookup: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return PublicPlaybackResolver(client, backgroundCatalogRefresh = false, cookieFreeRoutes = false)
            .resolve("House of the Dragon", "2022", "tv", 1, 1, "94997")
    }

    private val currentHouseMetadata = """{"status":"y","title":"House Of The Dragon","type":"t","year":"2024","season":[{"s":"1","id":"1971009236"},{"s":"3","id":"1271537449"}],"episodes":[{"id":"1271654364","s":"S3","ep":"E1","t":"Later season default"}]}"""

    @Test fun houseOfTheDragonUsesCurrentNativeIdentityAndExactRequestedSeason() = runBlocking {
        val result = houseOfTheDragon(currentHouseMetadata)
        assertEquals("1971034981", result.contentId)
        assertEquals("hs", result.ott)
    }

    @Test fun houseOfTheDragonCorrectionStillRejectsDifferentTitleAndWrongType() = runBlocking {
        for (details in listOf(currentHouseMetadata.replace("House Of The Dragon", "Other show"),
            currentHouseMetadata.replace("\"type\":\"t\"", "\"type\":\"m\""))) {
            try { houseOfTheDragon(details); fail("Incorrect provider identity accepted") }
            catch (_: java.io.IOException) { }
        }
        assertNull(PublicProviderIdentity.seed("94997", "movie", "2022"))
        assertNull(PublicProviderIdentity.seed("94997", "tv", "2024"))
    }
}
