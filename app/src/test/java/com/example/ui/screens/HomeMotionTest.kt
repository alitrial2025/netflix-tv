package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeMotionTest {
    // The hero and categories occupy different heights; interpolation must
    // follow their actual anchors, not assume every focus step is one row.
    private fun target(level: Int): Float = when {
        level < 0 -> 0f
        level == 0 -> -465f
        else -> -561f - 395f * (level - 1)
    }

    @Test fun viewportLandsExactlyOnEveryFocusAnchor() {
        for (level in -2..20) {
            assertEquals(target(level), homeScrollOffset(level.toFloat(), ::target), 0.001f)
        }
    }

    @Test fun heroAndCategoryTransitionsUseTheirOwnMeasuredDistances() {
        assertEquals(-232.5f, homeScrollOffset(-0.5f, ::target), 0.001f)
        assertEquals(-513f, homeScrollOffset(0.5f, ::target), 0.001f)
        assertEquals(-758.5f, homeScrollOffset(1.5f, ::target), 0.001f)
    }

    @Test fun navbarAndBillboardFocusDoNotMoveTheViewport() {
        assertEquals(0f, homeScrollOffset(-1.5f, ::target), 0.001f)
        assertEquals(1f, homeBillboardAlpha(-1.5f), 0.001f)
    }

    @Test fun fadesMatchFocusAndStayWithinDrawableAlphaBounds() {
        assertEquals(0.5f, homeBillboardAlpha(-0.5f), 0.001f)
        assertEquals(0f, homeBillboardAlpha(2f), 0.001f)
        assertEquals(0.65f, homeCategoriesAlpha(-2f), 0.001f)
        assertEquals(1f, homeCategoriesAlpha(0f), 0.001f)
        assertEquals(0.5f, homeCategoriesAlpha(0.5f), 0.001f)
        assertEquals(0f, homeCategoriesAlpha(20f), 0.001f)
    }
}
