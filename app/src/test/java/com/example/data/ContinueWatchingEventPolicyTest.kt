package com.example.data

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingEventPolicyTest {
    @Test fun aLaterRewindOrEarlierEpisodeWinsWithoutComparingPositions() {
        assertTrue(ContinueWatchingEventPolicy.isNewer(201L, 200L))
        assertFalse(ContinueWatchingEventPolicy.isNewer(199L, 200L))
        assertFalse(ContinueWatchingEventPolicy.isNewer(200L, 200L))
        assertFalse(ContinueWatchingEventPolicy.isNewer(0L, null))
    }
    @Test fun shortVideosAreNotCompleteBeforePlaybackBegins() {
        assertFalse(ContinueWatchingEventPolicy.completed(0, 20_000))
        assertFalse(ContinueWatchingEventPolicy.completed(18_999, 20_000))
        assertTrue(ContinueWatchingEventPolicy.completed(19_000, 20_000))
        assertFalse(ContinueWatchingEventPolicy.completed(100, 0))
    }
    @Test fun mobileEpisodeIdentityPreservesTvCoordinatesAndUnderscores() {
        assertEquals("ep_mr_robot_S4_8", ContinueWatchingEventPolicy.mobileEpisodeId("mr_robot", 4, 8))
    }
}
