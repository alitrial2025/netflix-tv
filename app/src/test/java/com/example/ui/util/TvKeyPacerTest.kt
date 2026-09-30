package com.example.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvKeyPacerTest {
    @Test fun fastPhysicalTapsAreNeverDropped() {
        val pacer = TvKeyPacer()
        for (time in 0L..300L step 20L) {
            assertTrue(pacer.accept(1, nowMs = time, repeatCount = 0))
        }
    }

    @Test fun heldKeysHaveAConsistentRepeatInterval() {
        val pacer = TvKeyPacer()
        assertTrue(pacer.accept(1, nowMs = 0L, repeatCount = 0))
        for (repeat in 1..20) {
            val time = repeat * 120L
            assertFalse(pacer.accept(1, nowMs = time - 1L, repeatCount = repeat))
            assertTrue(pacer.accept(1, nowMs = time, repeatCount = repeat))
        }
    }

    @Test fun directionReversalRespondsImmediately() {
        val pacer = TvKeyPacer()
        assertTrue(pacer.accept(1, nowMs = 100L, repeatCount = 0))
        assertTrue(pacer.accept(-1, nowMs = 110L, repeatCount = 1))
    }

    @Test fun aNewPressAfterAHoldDoesNotInheritItsDelay() {
        val pacer = TvKeyPacer()
        assertTrue(pacer.accept(1, nowMs = 0L, repeatCount = 0))
        assertTrue(pacer.accept(1, nowMs = 120L, repeatCount = 1))
        assertTrue(pacer.accept(1, nowMs = 130L, repeatCount = 0))
        assertFalse(pacer.accept(1, nowMs = 140L, repeatCount = 1))
    }

    @Test fun anIdleRemoteCanResumeWithAMissingInitialDown() {
        val pacer = TvKeyPacer()
        assertTrue(pacer.accept(1, nowMs = 100L, repeatCount = 0))
        assertTrue(pacer.accept(1, nowMs = 700L, repeatCount = 4))
    }

    @Test fun olderCallersKeepTheirExistingPacing() {
        val pacer = TvKeyPacer()
        assertTrue(pacer.accept(1, nowMs = 0L))
        assertFalse(pacer.accept(1, nowMs = 84L))
        assertTrue(pacer.accept(1, nowMs = 85L))
        assertFalse(pacer.accept(1, nowMs = 219L))
        assertTrue(pacer.accept(1, nowMs = 220L))
    }
}
