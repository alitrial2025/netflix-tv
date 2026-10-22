package com.example.viewing

import org.junit.Assert.*
import org.junit.Test

class PartySyncPolicyTest {
    private val now = 1_800_000_000_000L
    private val target = ViewingTarget("tv", "4604", "Smallville", 6, 22)
    private fun room() = WatchParty("AABBCCDDEE", 3, target, 12_000, 2_400_000, true, false,
        now - 1_000, now, now + 60_000, false, false, listOf(PartyMember("host", "Host", true)))
    private fun local() = LocalViewingPlayback(target, 13_000, 2_400_000, true, false)
    @Test fun matchingPlaybackDoesNotSeekOrToggleThePlayer() {
        assertEquals(PartyAdjustment(), PartySyncPolicy.adjustment(room(), local(), now, true))
    }
    @Test fun hostIsTheOnlyAuthorityAndUnreadyOrDifferentTitlesAreNotMutated() {
        assertEquals(PartyAdjustment(), PartySyncPolicy.adjustment(room().copy(isHost = true), local().copy(positionMs = 0), now, true))
        assertEquals(PartyAdjustment(), PartySyncPolicy.adjustment(room(), local().copy(ready = false), now, true))
        assertEquals(PartyAdjustment(), PartySyncPolicy.adjustment(room(), local().copy(target = target.copy(episode = 23)), now, true))
        assertEquals(PartyAdjustment(), PartySyncPolicy.adjustment(room(), local().copy(target = target.copy(kind = "movie")), now, true))
    }
    @Test fun hostPauseAndBufferingPauseTheGuestWithoutLosingPosition() {
        val paused = PartySyncPolicy.adjustment(room().copy(playing = false, positionMs = 13_000), local(), now, true)
        assertEquals(false, paused.playing);assertNull(paused.seekMs)
        val buffering = PartySyncPolicy.adjustment(room().copy(buffering = true, positionMs = 13_000), local(), now, true)
        assertEquals(false, buffering.playing);assertNull(buffering.seekMs)
    }
    @Test fun resumingHostResumesTheGuestAndLargeDriftSeeksToTheServerTimeline() {
        val result = PartySyncPolicy.adjustment(room(), local().copy(positionMs = 0, playing = false), now, true)
        assertEquals(13_000L, result.seekMs);assertEquals(true, result.playing)
    }
    @Test fun driftWithinToleranceAndSeekThrottlePreventRepeatedCorrections() {
        assertNull(PartySyncPolicy.adjustment(room(), local().copy(positionMs = 11_200), now, true).seekMs)
        assertEquals(13_000L, PartySyncPolicy.adjustment(room(), local().copy(positionMs = 11_199), now, true).seekMs)
        assertNull(PartySyncPolicy.adjustment(room(), local().copy(positionMs = 0), now, false).seekMs)
    }
    @Test fun disconnectedExpiredEndedAndStaleHostRoomsPauseInsteadOfRunningAhead() {
        for (room in listOf(room().copy(connected = false), room().copy(expiresMs = now), room().copy(ended = true),
            room().copy(hostSeenMs = now - 45_001), room().copy(hostSeenMs = now + 5_001))) {
            val result = PartySyncPolicy.adjustment(room, local(), now, true)
            assertEquals(false, result.playing);assertTrue(result.waitingForHost);assertNull(result.seekMs)
        }
    }
    @Test fun incompatibleCutsPauseInsteadOfSeekingIntoDifferentScenes() {
        val result = PartySyncPolicy.adjustment(room(), local().copy(durationMs = 2_410_001), now, true)
        assertEquals(false, result.playing);assertTrue(result.incompatibleEdition);assertNull(result.seekMs)
    }
    @Test fun serverTimelineAdvancesOnlyWhenPlayingAndNeverPastRuntime() {
        assertEquals(13_000L, room().targetPosition(now))
        assertEquals(12_000L, room().copy(playing = false).targetPosition(now))
        assertEquals(12_000L, room().copy(buffering = true).targetPosition(now))
        assertEquals(12_000L, room().targetPosition(now - 2_000))
        assertEquals(57_000L, room().targetPosition(now + 100_000))
        assertEquals(2_400_000L, room().copy(positionMs = 2_399_999).targetPosition(now))
    }
}
