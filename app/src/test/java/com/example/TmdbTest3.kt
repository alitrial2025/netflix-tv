package com.example

import com.example.data.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbTest3 {
    @Test
    fun testTrending() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in to live TMDB integration checks", System.getenv("RUN_LIVE_TMDB_TESTS") == "true")
        val apiKey = System.getenv("TMDB_API_KEY").orEmpty()
        org.junit.Assume.assumeTrue("Provide TMDB_API_KEY securely", apiKey.isNotBlank())
        val tmdb = com.example.api.TmdbClient.instance.getTrending(apiKey)
        val first = tmdb.results.firstOrNull()
        println("FIRST ITEM: backdrop=" + first?.backdropPath + " poster=" + first?.posterPath + " title=" + first?.title + " name=" + first?.name)
    }
}
