package com.example.ui.screens

import com.example.discovery.ReleasePolicy
import com.example.discovery.recommendationTitle
import com.example.model.Movie
import org.junit.Assert.*
import org.junit.Test

class HomeReleaseRowsTest {
    private fun movie(id: String, date: String? = null, coming: Boolean = false,
        type: String = "Movie", rating: String = "18+") = Movie(id, "Title $id", "Drama", "", "poster",
        rating = rating, type = type, duration = if (type == "Series") "2 Seasons" else "Feature",
        releaseDate = date, isComingSoon = coming)

    private fun rows(tab: String, catalog: List<Movie>, history: List<Movie> = emptyList(), kids: Boolean = false) =
        buildAlgorithmicRowsForTab(tab, catalog, catalog.filter { it.type == "Movie" },
            catalog.filter { it.type == "Series" }, emptyList(), emptyList(), emptyList(), "Alex", kids, 12,
            watchHistoryMovies = history)

    @Test fun comingSoonNeverInventsAnnouncementsOrPadsOldTitles() {
        val today = ReleasePolicy.day()
        val upper = ReleasePolicy.shiftMonths(today, 3)
        val items = listOf(movie("old", "2000-01-01", true), movie("unknown", null, true),
            movie("today", today, true), movie("upper", upper, true), movie("late", ReleasePolicy.shiftMonths(today, 4), true),
            movie("notFlagged", today, false))
        assertEquals(listOf("today", "upper"), buildComingSoonList(items).map { it.id })
        assertTrue(buildComingSoonList(items.take(2)).isEmpty())
    }

    @Test fun becauseYouWatchedNeedsActualHistoryOnEveryTabIncludingKids() {
        for (kids in listOf(false, true)) for (tab in listOf("Home", "Films", "Series")) {
            val items = listOf(movie("1", type="Movie", rating="7+"), movie("2", type="Movie", rating="7+"),
                movie("3", type="Series", rating="7+"), movie("4", type="Series", rating="7+"))
            assertFalse(rows(tab, items, kids=kids).any { it.first.startsWith("Because You Watched") })
            assertTrue(rows(tab, items, history=items, kids=kids).any { it.first.startsWith("Because You Watched") })
        }
    }

    @Test fun kidsRecommendationsNeverFallBackToAnAdultCatalogue() {
        val adult = listOf(movie("1"), movie("2", type="Series"))
        assertTrue(rows("Home", adult, kids=true).flatMap { it.second }.isEmpty())
    }

    @Test fun newReleaseRailExcludesOldAndUndatedItems() {
        val items = listOf(movie("old", "2000-01-01"), movie("unknown"), movie("new", ReleasePolicy.day()))
        val rail = rows("My Netflix", items).single { it.first.startsWith("New Releases") }.second
        assertEquals(listOf("new"), rail.map { it.id })
    }

    @Test fun animatedSeriesKeepTelevisionIdentityInDiscoveryAndReminders() {
        val series = movie("12", type="Animation").copy(duration="2 Seasons")
        assertEquals("tv:12", series.recommendationTitle().key)
        assertEquals("movie:12", movie("12").recommendationTitle().key)
    }
}
