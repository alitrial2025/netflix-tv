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
}
