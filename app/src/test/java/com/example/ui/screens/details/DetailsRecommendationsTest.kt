package com.example.ui.screens.details

import com.example.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsRecommendationsTest {
    private fun movie(id: String, type: String = "Movie", rating: String = "18+") =
        Movie(id, "Title $id", "", "", "", type = type, duration = if (type == "Series") "2 Seasons" else "90 min", rating = rating)

    @Test fun prefersSameMediaTypeAndKeepsDistinctMovieAndTvNamespaces() {
        val current = movie("1", "Series")
        val catalog = listOf(movie("1"), movie("2"), current, movie("2", "Series"), movie("2", "Series"), movie("3", "Series"))
        assertEquals(listOf(movie("2", "Series"), movie("3", "Series"), movie("1")),
            selectDetailsRecommendations(catalog, current, false, 3))
    }

    @Test fun kidsRecommendationsNeverIncludeAdultFallbackTitles() {
        val safe = movie("3", rating = "7+")
        assertEquals(listOf(safe), selectDetailsRecommendations(listOf(movie("2"), safe, safe), movie("1"), true))
        assertEquals(emptyList<Movie>(), selectDetailsRecommendations(listOf(safe), movie("1"), true, 0))
    }
}
