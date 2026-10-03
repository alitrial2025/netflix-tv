package com.example.data

import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicIdentityDiscoveryFlowTest {
    private val showId = "0REQUESTED123456"
    private val episodeId = "0EPISODE0123456"
    private val prime = """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"show":{"title":"Original Fixture Show - Season 1","titleType":"season","releaseYear":2026}}},"seasons":{"show":[{"sequenceNumber":1,"seasonLink":"/detail/$showId"}]}}}}}}}</script>"""

    private fun client(seen: MutableList<Request>, response: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); synchronized(seen) { seen += request }
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(response(request).toResponseBody()).build()
    }.build()

    private fun playback(request: Request): String? = when (request.url.encodedPath) {
        "/detail/$showId" -> prime
        "/mobile/pv/episodes.php" -> """{"episodes":[{"id":"$episodeId","s":"S1","ep":"S1E1"}]}"""
        "/mobile/pv/playlist.php" -> """{"sources":[{"file":"https://cdn.example/master.m3u8?in=issued"}]}"""
        "/master.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
        else -> null
    }

    @Test fun cachedPublicIdentitySkipsBrowseAndPerservesAuthoritativeAlias() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = PublicProviderCatalog(context)
        catalog.saveDiscovered("tv:999998101", listOf(showId to "pv"), listOf("Original Fixture Show"), System.currentTimeMillis())
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { playback(it) ?: throw AssertionError("Cached identity must skip discovery: ${it.url.encodedPath}") },
            catalog = catalog, backgroundCatalogRefresh = false)
        val result = resolver.resolve("Fixture Show", "2026", "tv", 1, 1, "999998101")
        assertEquals(episodeId, result.contentId)
        assertFalse(seen.any { it.url.host.contains("airtel") || it.url.host.contains("wikidata") || it.url.host.contains("themoviedb") })
    }

    @Test fun readyWikidataEntityIdentityDoesNotWaitForSparqlOrProviderSearch() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = PublicPlaybackResolver(client(seen) { request -> playback(request) ?: when (request.url.encodedPath) {
            "/-/en/search" -> "{}"
            "/sparql" -> { kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500L) }; "{}" }
            "/3/tv/999998102" -> """{"id":999998102,"name":"Fixture Show","first_air_date":"2026-01-01","external_ids":{"wikidata_id":"Q999998102"},"alternative_titles":{"results":[{"title":"Original Fixture Show"}]}}"""
            "/wiki/Special:EntityData/Q999998102.json" -> """{"entities":{"Q999998102":{"claims":{"P14440":[{"mainsnak":{"datavalue":{"value":"$showId"}}}]}}}}"""
            else -> throw AssertionError("Ready identity must not wait for another source: ${request.url.encodedPath}")
        } })
        assertEquals(episodeId, resolver.resolve("Fixture Show", "2026", "tv", 1, 1, "999998102").contentId)
        assertFalse(seen.any { it.url.encodedPath.endsWith("search.php") || it.url.host.contains("airtel") })
    }
}
