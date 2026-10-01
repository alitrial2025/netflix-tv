package com.example

import com.example.data.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TmdbTest2 {
    @Test
    fun testTrending() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in to live TMDB integration checks", System.getenv("RUN_LIVE_TMDB_TESTS") == "true")
        val apiKey = System.getenv("TMDB_API_KEY").orEmpty()
        org.junit.Assume.assumeTrue("Provide TMDB_API_KEY securely", apiKey.isNotBlank())
        val repo = TmdbRepository()
        try {
            val tmdb = com.example.api.TmdbClient.instance.getTrending(apiKey)
            println("TMDB SUCCESS: \${tmdb.results.size}")
            
            // Try to map
            val method = TmdbRepository::class.java.getDeclaredMethod("toMovie", com.example.api.TmdbItemDto::class.java)
            method.isAccessible = true
            tmdb.results.forEach { 
                method.invoke(repo, it)
            }
            println("MAPPING SUCCESS")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
