package com.example

import com.example.data.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbTest {
    @Test
    fun testTrending() = runBlocking {
        val repo = TmdbRepository()
        val trending = repo.getTrending()
        println("TRENDING SIZE: ${trending.size}")
        if (trending.isEmpty()) {
            try {
                // Manually call API to see the error
                com.example.api.TmdbClient.instance.getTrending("8baba8ab6b8bbe247645bcae7df63d0d")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        assertTrue(trending.isNotEmpty())
    }
}
