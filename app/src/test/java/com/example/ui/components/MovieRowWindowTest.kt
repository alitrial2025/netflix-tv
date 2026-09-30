package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRowWindowTest {
    @Test fun movingViewportKeepsOutgoingAndIncomingPosters() {
        val window = movieRowWindow(1000, 4, 20, isInfinite = true)
        assertEquals(999..1004, window)
        assertTrue(1000 in window && 1001 in window)
    }

    @Test fun widerViewportsKeepTheExtraVisiblePosters() {
        assertEquals(39..47, movieRowWindow(40, 7, 20, isInfinite = true))
    }

    @Test fun finiteRowsNeverWrapAtEitherEnd() {
        assertEquals(0..4, movieRowWindow(0, 4, 8, isInfinite = false))
        assertEquals(6..7, movieRowWindow(7, 4, 8, isInfinite = false))
        assertEquals(0..0, movieRowWindow(0, 4, 1, isInfinite = false))
    }

    @Test fun aLargeCatalogueDoesNotIncreaseTheMountedWindow() {
        assertEquals(6, movieRowWindow(100_000, 4, 5000, isInfinite = true).count())
    }

    @Test fun settledRowsDropTheHiddenPrecedingPoster() {
        assertEquals(1000..1004,
            movieRowWindow(1000, 4, 20, isInfinite = true, includePrevious = false))
    }

    @Test fun emptyRowsMountNoPosters() {
        assertTrue(movieRowWindow(0, 4, 0, isInfinite = false).isEmpty())
    }
}
