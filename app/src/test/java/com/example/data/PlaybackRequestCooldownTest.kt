package com.example.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestCooldownTest {
    @Test fun retryOfAnotherTitleSharesRemainingWait() {
        var now = 1_000L
        val cooldown = PlaybackRequestCooldown { now }
        assertEquals(60_000L, cooldown.onRateLimited())
        now += 20_000L
        assertEquals(40_000L, cooldown.remainingMs())
        now += 40_000L
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test fun longerProviderWaitIsNeverShortenedByAnotherResponse() {
        val cooldown = PlaybackRequestCooldown { 1_000L }
        assertEquals(900_000L, cooldown.onRateLimited(900_000L))
        assertEquals(900_000L, cooldown.onRateLimited())
    }

    @Test fun duplicateFailureWithoutNewProviderDelayCannotRestartAnActiveWait() {
        var now = 1_000L
        val cooldown = PlaybackRequestCooldown { now }
        cooldown.onRateLimited()
        repeat(5) {
            now += 10_000L
            assertEquals(60_000L - (it + 1) * 10_000L, cooldown.onRateLimited())
        }
        now += 10_000L
        assertEquals(0L, cooldown.remainingMs())
        now += 5 * 60_000L
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test fun genuinelyNewServerDelayCanExtendTheActiveDeadline() {
        var now = 1_000L
        val cooldown = PlaybackRequestCooldown { now }
        cooldown.onRateLimited()
        now += 30_000L
        assertEquals(600_000L, cooldown.onRateLimited(600_000L))
        now += 5 * 60_000L
        assertEquals(300_000L, cooldown.remainingMs())
        now += 300_000L
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test fun repeatedFreshRejectionsUseBoundedBackoffWithoutInventingNineMinutes() {
        var now = 1_000L
        val cooldown = PlaybackRequestCooldown { now }
        for (expectedDelay in listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            assertEquals(expectedDelay, cooldown.onRateLimited())
            now += expectedDelay
            assertEquals(0L, cooldown.remainingMs())
        }
    }
}
