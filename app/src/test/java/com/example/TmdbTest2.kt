package com.example

import com.example.data.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TmdbTest2 {
    @Test
    fun testTrending() = runBlocking {
        val repo = TmdbRepository()
        try {
            val tmdb = com.example.api.TmdbClient.instance.getTrending("8baba8ab6b8bbe247645bcae7df63d0d")
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
