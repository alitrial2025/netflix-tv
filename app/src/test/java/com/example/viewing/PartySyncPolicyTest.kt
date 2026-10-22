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
        assertEquals(PartyAdjustment(speed = 1f), PartySyncPolicy.adjustment(room(), local(), now, true))
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
        assertNull(PartySyncPolicy.adjustment(room(), local().copy(positionMs = 11_800), now, true).seekMs)
        assertEquals(13_000L, PartySyncPolicy.adjustment(room(), local().copy(positionMs = 11_799), now, true).seekMs)
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
    @Test fun modestPlayingDriftUsesBoundedSpeedAndAlignmentRestoresNormalSpeed() {
        assertEquals(1.02f, PartySyncPolicy.adjustment(room(), local().copy(positionMs = 12_400), now, true).speed!!, .001f)
        assertEquals(.98f, PartySyncPolicy.adjustment(room(), local().copy(positionMs = 13_600), now, true).speed!!, .001f)
        assertEquals(1f, PartySyncPolicy.adjustment(room(), local(), now, true).speed!!, .001f)
        assertEquals(1.03f, PartySyncPolicy.adjustment(room(), local().copy(positionMs = 0), now, false).speed!!, .001f)
    }
    @Test fun pausedGuestsCorrectSmallPositionErrorsExactlyWithoutChangingSpeed() {
        val paused = room().copy(playing = false, positionMs = 13_000)
        val result = PartySyncPolicy.adjustment(paused, local().copy(positionMs = 12_800), now, true)
        assertEquals(13_000L, result.seekMs); assertEquals(false, result.playing); assertEquals(1f, result.speed!!, .001f)
    }
    @Test fun delayedChatOrPlaybackRepliesCannotReplaceANewerSnapshot() {
        val current = room().copy(revision = 4, eventRevision = 10)
        assertFalse(PartySnapshotPolicy.accepts(current, current.copy(revision = 3, eventRevision = 11)))
        assertFalse(PartySnapshotPolicy.accepts(current, current.copy(eventRevision = 9)))
        assertTrue(PartySnapshotPolicy.accepts(current, current.copy(eventRevision = 11)))
        assertTrue(PartySnapshotPolicy.accepts(current, current.copy(revision = 5, eventRevision = 12)))
    }

    @Test fun aBufferingGuestIsNotRepeatedlySeekedButStillObeysHostPause() {
        val buffering = local().copy(positionMs = 0, buffering = true)
        val waiting = PartySyncPolicy.adjustment(room(), buffering, now, true)
        assertNull(waiting.seekMs); assertNull(waiting.playing); assertEquals(1f, waiting.speed!!, .001f)
        val paused = PartySyncPolicy.adjustment(room().copy(playing = false), buffering, now, true)
        assertNull(paused.seekMs); assertEquals(false, paused.playing); assertEquals(1f, paused.speed!!, .001f)
        assertEquals(13_000L, PartySyncPolicy.adjustment(room(), buffering.copy(buffering = false), now, true).seekMs)
    }

}
