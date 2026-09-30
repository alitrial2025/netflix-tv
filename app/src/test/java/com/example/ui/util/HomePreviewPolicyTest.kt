package com.example.ui.util

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePreviewPolicyTest {
    @Test fun changingCardsCannotResetTheResolutionBudget() {
        var now = 0L
        val budget = HomePreviewRequestBudget { now }
        budget.onResolutionStarted()
        now += 2_800L
        assertEquals(7_200L, budget.waitMillis())
        now = 10_000L
        assertEquals(0L, budget.waitMillis())
    }

    @Test fun repeatedLimitsBackOffAndAlsoBlockCachedPlayback() {
        var now = 0L
        val budget = HomePreviewRequestBudget { now }
        budget.onRateLimited()
        assertEquals(60_000L, budget.cooldownMillis())
        now = 60_000L
        budget.onRateLimited()
        assertEquals(120_000L, budget.cooldownMillis())
        now += 120_000L
        assertEquals(0L, budget.cooldownMillis())
    }

    @Test fun theServerCanAskForALongerWait() {
        val budget = HomePreviewRequestBudget { 0L }
        budget.onRateLimited(240_000L)
        assertEquals(240_000L, budget.waitMillis())
        budget.onSuccess()
        assertEquals(240_000L, budget.cooldownMillis())
    }

    @Test fun randomMinutesLeaveRoomForTheWholePreview() {
        val duration = 91L * 60_000L + 25_000L
        repeat(80) { seed ->
            val start = homePreviewStartMs(duration, null, Random(seed))
            assertTrue(start >= 60_000L)
            assertEquals(0L, start % 60_000L)
            assertTrue(start + 60_000L <= duration)
        }
    }

    @Test fun returningToATitleKeepsItsMinuteAndShortClipsStayInBounds() {
        assertEquals(15L * 60_000L, homePreviewStartMs(100L * 60_000L, 15L * 60_000L))
        assertEquals(0L, homePreviewStartMs(35_000L, null))
        assertEquals(0L, homePreviewStartMs(35_000L, 15L * 60_000L))
    }
}
