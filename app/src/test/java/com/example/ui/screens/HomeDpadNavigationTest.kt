package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDpadNavigationTest {
    @Test fun adultHomeTraversesBillboardCategoriesAndEveryRow() {
        var level = -2
        val visited = (1..7).map {
            level = homeVerticalTarget(level, 1, 3, hasCategories = true, hasBillboard = true)
            level
        }
        assertEquals(listOf(-1, 0, 1, 2, 3, 3, 3), visited)
        assertEquals(2, homeVerticalTarget(3, -1, 3, true, true))
        assertEquals(-2, homeVerticalTarget(-1, -1, 3, true, true))
    }

    @Test fun reversingWhileNextRowAttachesUsesLatestRequestedPosition() {
        var requested = 1
        // The old row's focus node can remain attached throughout these presses.
        requested = homeVerticalTarget(requested, 1, 6, true, true)
        requested = homeVerticalTarget(requested, 1, 6, true, true)
        requested = homeVerticalTarget(requested, -1, 6, true, true)
        assertEquals(2, requested)
    }

    @Test fun kidsTabsSkipNonexistentBillboardAndCategories() {
        assertEquals(-2, homeVerticalTarget(0, -1, 3, false, false))
        assertEquals(0, homeVerticalTarget(-2, 1, 3, false, false))
        assertEquals(2, homeVerticalTarget(2, 1, 3, false, false))
    }

    @Test fun kidsHomeAndAdultOtherTabsRetainBillboard() {
        assertEquals(-1, homeVerticalTarget(0, -1, 3, false, true))
        assertEquals(0, homeVerticalTarget(-1, 1, 3, false, true))
    }

    @Test fun catalogShrinkingClampsRequestedRowAndEmptyHomeKeepsCategories() {
        assertEquals(2, homeVerticalTarget(20, 1, 2, true, true))
        assertEquals(0, homeVerticalTarget(0, 1, 0, true, true))
        assertEquals(-1, homeVerticalTarget(-1, 1, 0, false, true))
        assertEquals(-2, homeVerticalTarget(-2, 1, 0, false, false))
    }
}
