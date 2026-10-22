package com.example.viewing

import org.junit.Assert.*
import org.junit.Test

class ViewingServerClockTest {
    @Test fun initialWallClockIsReplacedByTheFirstConfirmedServerSample() {
        var elapsed = 1_000L
        val clock = ViewingServerClock({ elapsed }, { 9_999_999L })
        assertEquals(9_999_999L, clock.nowMs())
        clock.observe(100_000, 800, 1_000)
        assertEquals(100_100L, clock.nowMs())
        elapsed += 500;assertEquals(100_600L, clock.nowMs())
    }
    @Test fun wallClockChangesCannotChangeAnEstablishedServerClock() {
        var elapsed = 1_000L;var wall = 900_000L
        val clock = ViewingServerClock({ elapsed }, { wall })
        clock.observe(100_000, 1_000, 1_000)
        wall = 1;elapsed += 1_000;assertEquals(101_000L, clock.nowMs())
        wall = Long.MAX_VALUE;elapsed += 1_000;assertEquals(102_000L, clock.nowMs())
    }
    @Test fun olderOrBackwardResponsesCannotRewindTheTimeline() {
        var elapsed = 1_000L;val clock = ViewingServerClock({ elapsed })
        clock.observe(100_000, 1_000, 1_000)
        elapsed = 2_000;clock.observe(90_000, 2_000, 2_000);assertEquals(101_000L, clock.nowMs())
        clock.observe(999_999, 500, 500);assertEquals(101_000L, clock.nowMs())
    }
    @Test fun invalidAndExcessivelySlowSamplesAreIgnored() {
        val clock = ViewingServerClock({ 1_000L }, { 42L })
        clock.observe(100_000, 0, 10_001);clock.observe(100_000, 100, 99);clock.observe(0, 0, 100)
        assertEquals(42L, clock.nowMs())
    }
}
