package com.example.ui.screens

import com.example.discovery.ReleasePolicy
import com.example.model.Movie
import com.example.model.isKidSafeMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BillboardAlgorithmTest {
    @Test fun boundedSelectionMatchesFullStableSortIncludingDuplicateIdsAndFilters() {
        val random = Random(42)
        val catalog = (0..1000).map { index ->
            Movie(id = "${random.nextInt(200)}", title = if (index % 3 == 0) "Dune" else "Title $index",
                description = "A detailed dramatic adventure ".repeat(random.nextInt(1, 5)),
                backdropUrl = if (index % 9 == 0) "" else "https://image.tmdb.org/t/p/w1280/$index.jpg",
                posterUrl = "poster", year = "${random.nextInt(2000, 2027)}",
                type = if (index % 2 == 0) "Series" else "Movie", rating = if (index % 4 == 0) "7+" else "18+",
                isComingSoon = index % 15 == 0,
                releaseDate = if (index % 7 == 0) "2099-01-01" else "2020-01-01")
        }
        for (tab in listOf("Home", "Series", "Films")) for (kids in listOf(false, true)) for (limit in listOf(1, 8, 250)) {
            val expected = catalog.filter { (!kids || isKidSafeMovie(it)) && !it.isComingSoon &&
                ReleasePolicy.isPlayableDate(it.releaseDate) && it.backdropUrl.isNotBlank() }
                .map { it to BillboardAlgorithm.calculateBillboardScore(it, tab) }.filter { it.second > 0 }
                .sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }.take(limit)
            assertEquals(expected, BillboardAlgorithm.rankBillboardMovies(catalog, tab, kids, limit))
        }
        assertTrue(BillboardAlgorithm.rankBillboardMovies(catalog, limit = 0).isEmpty())
    }
}
