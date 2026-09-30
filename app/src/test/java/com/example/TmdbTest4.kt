package com.example

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbTest4 {
    @Test
    fun testTrending() = runBlocking {
        val tmdb = com.example.api.TmdbClient.instance.getTrending("8baba8ab6b8bbe247645bcae7df63d0d")
        val first = tmdb.results.firstOrNull()
        println("FOURTH ITEM: backdrop=" + first?.backdropPath + " poster=" + first?.posterPath + " title=" + first?.title + " name=" + first?.name)
    }
}
