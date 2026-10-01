package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowVisibilityTest {
    private fun visible(index: Int, scroll: Float = 0f, focus: Int = -3) =
        shouldComposeHomeRow(index, 561f, 395f, scroll, 640f, focus)

    @Test fun entryLoadsFirstArtworkButNotTheWholeCatalogue() {
        assertTrue(visible(0))
        assertEquals(2, (0 until 100).count { visible(it) })
        assertFalse(visible(99))
    }

    @Test fun deepScrollingKeepsBothAdjacentDpadTargets() {
        val scroll = -(561f + 50 * 395f)
        assertTrue(visible(49, scroll, 50))
        assertTrue(visible(50, scroll, 50))
        assertTrue(visible(51, scroll, 50))
        assertTrue((0 until 100).count { visible(it, scroll, 50) } <= 5)
        assertFalse(visible(0, scroll, 50))
    }

    @Test fun largeJumpDoesNotComposeAllRowsBetweenSourceAndDestination() {
        assertTrue(visible(79, 0f, 80))
        assertTrue(visible(80, 0f, 80))
        assertTrue(visible(81, 0f, 80))
        assertFalse(visible(40, 0f, 80))
        assertEquals(5, (0 until 100).count { visible(it, 0f, 80) })
    }

    @Test fun touchOffsetComposesIncomingRowsBeforeFocusChanges() {
        assertTrue(visible(4, -(561f + 4 * 395f), 0))
        assertFalse(visible(20, -(561f + 4 * 395f), 0))
    }

    @Test fun invalidRowDimensionsDoNotAllocateRows() {
        assertFalse(shouldComposeHomeRow(0, 0f, 0f, 0f, 640f, 0))
        assertFalse(shouldComposeHomeRow(-1, 0f, 395f, 0f, 640f, 0))
    }
    @Test fun coldEntryDoesNotDecodeRowsBelowTheViewport() {
        val rows = (0 until 100).filter {
            shouldComposeHomeRow(it, 561f, 395f, 0f, 640f, -3, overscanRows = 0)
        }
        assertEquals(listOf(0), rows)
        assertTrue(shouldComposeHomeRow(51, 561f, 395f, -20311f, 640f, 50, overscanRows = 0))
    }
    @Test fun entryRailSurvivesReturningToTheHeaderWithoutRetainingDeepRows() {
        for (level in -2..1) assertTrue(shouldRetainHomeEntryRow(0, level, true, false, "Home"))
        assertFalse(shouldRetainHomeEntryRow(1, -2, true, false, "Home"))
        assertFalse(shouldRetainHomeEntryRow(0, 2, true, false, "Home"))
        assertFalse(shouldRetainHomeEntryRow(0, -2, false, false, "Home"))
        assertFalse(shouldRetainHomeEntryRow(0, -2, true, true, "Home"))
        assertFalse(shouldRetainHomeEntryRow(0, -2, true, false, "Series"))
    }

}
