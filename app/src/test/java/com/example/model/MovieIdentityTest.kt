package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieIdentityTest {
    private fun title(name: String, type: String, duration: String) = Movie(
        id = "1234",
        title = name,
        description = "",
        backdropUrl = "",
        posterUrl = "",
        type = type,
        duration = duration
    )

    @Test fun playNextUsesTheAdvertisedTitleWhenMovieAndSeriesShareAnId() {
        val film = title("Film", "Movie", "Feature")
        val series = title("Show", "Series", "Series")
        assertEquals(series, findMovieByIdentity(listOf(film, series), "1234", "Show", "tv"))
        assertNull(findMovieByIdentity(listOf(film), "1234", "Show", "tv"))
        assertNotEquals(film.playbackMediaId(1, 1), series.playbackMediaId(1, 1))
    }

    @Test fun animatedTelevisionIsResolvedAsTelevision() {
        val animatedSeries = title("Animated Show", "Animation", "Series")
        assertEquals("tv", animatedSeries.catalogMediaKind())
    }
}
