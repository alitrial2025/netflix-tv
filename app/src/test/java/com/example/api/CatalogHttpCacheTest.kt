package com.example.api

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogHttpCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aFreshCatalogueResponseSurvivesClientRestartWithoutAnotherNetworkRequest() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=3600").setBody("catalogue"))
        server.start()
        val cacheDirectory = folder.newFolder("catalogue")
        val request = Request.Builder().url(server.url("/3/trending/all/week")).build()
        try {
            val first = catalogHttpClient(cacheDirectory)
            first.newCall(request).execute().use { assertEquals("catalogue", it.body!!.string()) }
            first.cache!!.close()
            val restarted = catalogHttpClient(cacheDirectory)
            try {
                restarted.newCall(request).execute().use {
                    assertEquals("catalogue", it.body!!.string())
                    assertNotNull(it.cacheResponse)
                    assertNull(it.networkResponse)
                }
                assertEquals(1, server.requestCount)
            } finally { restarted.cache!!.close() }
        } finally { server.shutdown() }
    }
}
