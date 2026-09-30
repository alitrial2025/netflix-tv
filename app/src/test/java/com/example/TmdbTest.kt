package com.example

import com.example.data.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbTest {
    @Test
    fun testTrending() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in to live TMDB integration checks", System.getenv("RUN_LIVE_TMDB_TESTS") == "true")
        val apiKey = System.getenv("TMDB_API_KEY").orEmpty()
        org.junit.Assume.assumeTrue("Provide TMDB_API_KEY securely", apiKey.isNotBlank())
        val repo = TmdbRepository()
        val trending = repo.getTrending()
        println("TRENDING SIZE: ${trending.size}")
        if (trending.isEmpty()) {
            try {
                // Manually call API to see the error
                com.example.api.TmdbClient.instance.getTrending(apiKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        assertTrue(trending.isNotEmpty())
    }
}
