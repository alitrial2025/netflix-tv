package com.example.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test fun nextEpisodeDoesNotDebounceAnEqualPosition() {
        assertFalse(PlaybackProgressPolicy.shouldDebounce(12_000L, 1, 2, 12_000L, 1, 1, false))
    }

    @Test fun nextSeasonDoesNotDebounceAnEqualPosition() {
        assertFalse(PlaybackProgressPolicy.shouldDebounce(12_000L, 2, 1, 12_000L, 1, 1, false))
    }

    @Test fun completedEpisodeCanBeWatchedAgain() {
        assertFalse(PlaybackProgressPolicy.shouldDebounce(12_000L, 1, 1, 12_000L, 1, 1, true))
    }

    @Test fun sameEpisodeStillDebouncesFrequentUpdates() {
        assertTrue(PlaybackProgressPolicy.shouldDebounce(12_000L, 1, 1, 11_000L, 1, 1, false))
    }

    @Test fun cloudPositionsRequireTheSameEpisode() {
        assertFalse(PlaybackProgressPolicy.sameEpisode(1, 2, 1L, 1L, "s1_e1"))
        assertTrue(PlaybackProgressPolicy.sameEpisode(1, 2, 1L, 2L, "s1_e2"))
        assertFalse(PlaybackProgressPolicy.sameEpisode(1, 2, null, null, null))
    }

    @Test fun mobileAndTvEpisodeIdsAreRecognized() {
        assertTrue(PlaybackProgressPolicy.sameEpisode(2, 3, null, null, "ep_42_S2_3"))
        assertTrue(PlaybackProgressPolicy.sameEpisode(2, 3, null, null, "s2_e3"))
        assertFalse(PlaybackProgressPolicy.sameEpisode(2, 3, null, null, "ep_42_S1_3"))
    }

    @Test fun cloudPositionCannotExceedTheActualDuration() {
        assertEquals(60_000L, PlaybackProgressPolicy.resolvePosition(12_000L, 60_000L, 120_000L))
        assertEquals(0L, PlaybackProgressPolicy.resolvePosition(-1L, 60_000L, -1L))
    }
}
