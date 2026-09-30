package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePaginationTest {

    data class MockEpisodeResponse(
        val episodes: List<MockEpisode>,
        val nextPageShow: Int,
        val nextPage: Int
    )

    data class MockEpisode(
        val id: String,
        val ep: String,
        val s: String,
        val title: String
    )

    @Test
    fun testEpisodePaginationLogic() {
        val page1 = MockEpisodeResponse(
            episodes = (1..10).map { MockEpisode("ep_id_$it", "E$it", "S1", "Episode $it") },
            nextPageShow = 1,
            nextPage = 2
        )
        val page2 = MockEpisodeResponse(
            episodes = (11..15).map { MockEpisode("ep_id_$it", "E$it", "S1", "Episode $it") },
            nextPageShow = 0,
            nextPage = 3
        )

        val pages = mapOf(1 to page1, 2 to page2)
        val collectedEpisodes = mutableListOf<Triple<Int, Int, String>>()

        var currentPage = 1
        var hasMorePages = true
        val maxPages = 20

        while (hasMorePages && currentPage <= maxPages) {
            val res = pages[currentPage] ?: break
            for ((idx, ep) in res.episodes.withIndex()) {
                val epNum = ep.ep.lowercase().replace("episode", "").replace("ep", "").replace("e", "").trim().toIntOrNull()
                    ?: ((currentPage - 1) * 10 + idx + 1)
                val sNum = ep.s.lowercase().replace("season", "").replace("s", "").trim().toIntOrNull() ?: 1
                collectedEpisodes.add(Triple(sNum, epNum, ep.id))
            }

            if (res.nextPageShow == 1) {
                currentPage = if (res.nextPage > currentPage) res.nextPage else currentPage + 1
            } else {
                hasMorePages = false
            }
        }

        // Verify all 15 episodes are collected
        assertEquals(15, collectedEpisodes.size)

        // Verify Episode 11 is collected and mapped properly
        val ep11 = collectedEpisodes.find { it.first == 1 && it.second == 11 }
        assertTrue("Episode 11 should be found", ep11 != null)
        assertEquals("ep_id_11", ep11?.third)

        // Verify Episode 15 is collected
        val ep15 = collectedEpisodes.find { it.first == 1 && it.second == 15 }
        assertTrue("Episode 15 should be found", ep15 != null)
        assertEquals("ep_id_15", ep15?.third)
    }

    @Test
    fun testEpisodeNumberParsingVariants() {
        val testCases = listOf(
            "E1" to 1,
            "e11" to 11,
            "Episode 12" to 12,
            "ep 14" to 14,
            "15" to 15
        )

        for ((input, expected) in testCases) {
            val parsed = input.lowercase().replace("episode", "").replace("ep", "").replace("e", "").trim().toIntOrNull()
            assertEquals("Failed parsing for $input", expected, parsed)
        }
    }
}
