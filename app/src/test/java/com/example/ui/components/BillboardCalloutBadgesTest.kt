package com.example.ui.components

import com.example.data.TmdbBillboardMeta
import com.example.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillboardCalloutBadgesTest {

    @Test
    fun `resolves Recently added and Golden Globe Nominee for recent acclaimed animated film`() {
        val movie = Movie(
            id = "901362",
            title = "Trolls Band Together",
            description = "Poppy discovers that Branch was once part of a boy band.",
            backdropUrl = "https://image.tmdb.org/t/p/w780/trolls.jpg",
            posterUrl = "https://image.tmdb.org/t/p/w342/trolls.jpg",
            rating = "PG",
            year = "2024",
            duration = "1h 31m",
            type = "Family"
        )
        val meta = TmdbBillboardMeta(
            itemId = "901362",
            isTv = false,
            voteAverage = 7.4,
            voteCount = 1200,
            releaseDate = "2024-11-17",
            genreIds = listOf(16L, 10751L),
            sourceFeeds = setOf("now_playing", "award_winning"),
            isEnriched = true
        )

        val badges = BillboardCalloutGenerator.resolveBadges(
            movie = movie,
            meta = meta,
            rotationSlot = 0
        )

        assertEquals(2, badges.size)
        assertNotEquals(badges[0].category, badges[1].category)
        val allLabels = (0..5).flatMap { slot ->
            BillboardCalloutGenerator.resolveBadges(movie, meta, rotationSlot = slot).map { it.label }
        }.toSet()
        assertTrue(allLabels.contains("Recently added"))
        assertTrue(allLabels.contains("Golden Globe Nominee"))
    }

    @Test
    fun `resolves Emmy Nominee and We thought you'll like this for acclaimed TV series`() {
        val series = Movie(
            id = "256953",
            title = "The Crown",
            description = "Follows the political rivalries and romance of Queen Elizabeth II's reign.",
            backdropUrl = "https://image.tmdb.org/t/p/w780/crown.jpg",
            posterUrl = "https://image.tmdb.org/t/p/w342/crown.jpg",
            rating = "16+",
            year = "2024",
            duration = "Series",
            type = "Series"
        )
        val meta = TmdbBillboardMeta(
            itemId = "256953",
            isTv = true,
            voteAverage = 8.3,
            voteCount = 2400,
            releaseDate = "2016-11-04",
            lastAirDate = "2024-12-14",
            numberOfSeasons = 6,
            keywords = listOf("emmy winner", "british monarchy"),
            genreNames = listOf("Drama", "History"),
            sourceFeeds = setOf("top_rated"),
            isEnriched = true
        )

        val allLabels = (0..5).flatMap { slot ->
            BillboardCalloutGenerator.resolveBadges(
                movie = series,
                meta = meta,
                favoriteGenres = listOf("Drama"),
                rotationSlot = slot
            ).map { it.label }
        }.toSet()

        assertTrue(allLabels.contains("Emmy Nominee"))
        assertTrue(allLabels.contains("We thought you'll like this"))
    }

    @Test
    fun `resolves Sequel of predecessor from TMDB collection metadata`() {
        val movie = Movie(
            id = "693134",
            title = "Dune: Part Two",
            description = "Paul Atreides unites with Chani and the Fremen.",
            backdropUrl = "https://image.tmdb.org/t/p/w780/dune2.jpg",
            posterUrl = "https://image.tmdb.org/t/p/w342/dune2.jpg",
            rating = "16+",
            year = "2024",
            duration = "Feature",
            type = "Sci-Fi"
        )
        val meta = TmdbBillboardMeta(
            itemId = "693134",
            isTv = false,
            voteAverage = 8.4,
            voteCount = 5200,
            releaseDate = "2024-02-27",
            collectionName = "Dune Collection",
            sequelOfTitle = "Dune",
            isFirstInCollection = false,
            runtimeMinutes = 166,
            isEnriched = true
        )

        val sequelTitle = BillboardCalloutGenerator.inferSequelTitle(movie, meta)
        assertEquals("Dune", sequelTitle)

        val badges = BillboardCalloutGenerator.resolveBadges(movie = movie, meta = meta, rotationSlot = 1)
        assertTrue(badges.any { it.label == "Sequel of Dune" && it.emoji == "🎬" })

        val enriched = BillboardCalloutGenerator.enrichMovieWithMeta(movie, meta)
        assertEquals("2h 46m", enriched.duration)
    }

    @Test
    fun `consecutive billboard movies produce dynamic varied callout combinations`() {
        val movies = listOf(
            Movie("101", "Dune: Part Two", "Sci-fi epic sequel", "", "", "16+", "2024", "2h 46m", "Sci-Fi") to
                TmdbBillboardMeta("101", false, voteAverage = 8.4, sequelOfTitle = "Dune", isEnriched = true),
            Movie("202", "Shōgun", "Feudal Japan series", "", "", "18+", "2024", "1 Season", "Series") to
                TmdbBillboardMeta("202", true, voteAverage = 8.7, keywords = listOf("emmy", "miniseries"), tvType = "Miniseries", isEnriched = true),
            Movie("303", "Demon Slayer: Infinity Castle", "Tanjiro faces Muzan", "", "", "16+", "2025", "2h 34m", "Anime") to
                TmdbBillboardMeta("303", false, voteAverage = 8.2, originalLanguage = "ja", genreIds = listOf(16L), keywords = listOf("manga", "sequel"), isEnriched = true)
        )

        val badgePairs = movies.mapIndexed { idx, (movie, meta) ->
            BillboardCalloutGenerator.resolveBadges(movie = movie, meta = meta, rotationSlot = idx)
                .map { "${it.emoji} ${it.label}" }
        }

        assertEquals(3, badgePairs.distinct().size)
    }
}
