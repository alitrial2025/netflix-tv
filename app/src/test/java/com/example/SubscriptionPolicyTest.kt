package com.example

import com.example.model.SubscriptionPlans
import com.example.model.UserSubscription
import com.example.data.MonotonicSubscriptionClock
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SubscriptionPolicyTest {
    @Test fun tierBenefitsIncreaseAndExpiredOrUnknownPlansLoseEveryPaidFeature() {
        val previousClock = UserSubscription.clock
        val now = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        UserSubscription.clock = { now }
        try {
            val plans = SubscriptionPlans.PLANS
            assertEquals(listOf(150, 550, 950, 1350), plans.map { it.priceKes })
            assertEquals(listOf(480, 720, 1080, 2160), plans.map { it.maxVideoHeight })
            assertEquals(listOf(3, 5, 25, 999), plans.map { it.maxDownloads })
            assertTrue(plans.all { it.durationDays == 30 })
            for (plan in plans) {
                val active = UserSubscription(status = "ACTIVE", planId = plan.id, expiresAt = now + 1)
                assertTrue(active.isActive)
                assertEquals(plan.id != "plan_mobile", active.isTvAllowed)
                assertEquals(plan.maxVideoHeight, active.maxVideoHeight)
                assertEquals(plan.maxDownloads, active.maxDownloads)
                assertEquals(plan.smartNextEpisode, active.isSmartNextEpisodeAllowed)
                assertEquals(plan.downloadsForYou, active.isDownloadsForYouAllowed)
                assertEquals(plan.spatialAudio, active.isSpatialAudioAllowed)
                val expired = active.copy(expiresAt = now - com.example.data.RenewalPolicy.GRACE_MS)
                assertFalse(expired.isActive)
                assertEquals(0, expired.maxVideoHeight)
                assertEquals(0, expired.maxDownloads)
                assertFalse(expired.isSmartNextEpisodeAllowed)
                assertFalse(expired.isDownloadsForYouAllowed)
                assertFalse(expired.isSpatialAudioAllowed)
            }
            assertFalse(UserSubscription(status = "ACTIVE", planId = "plan_admin", expiresAt = Long.MAX_VALUE).isActive)
            assertFalse(UserSubscription(status = "APPROVED", planId = "plan_premium", expiresAt = Long.MAX_VALUE).isActive)
        } finally { UserSubscription.clock = previousClock }
    }

    @Test fun renewalReminderAndCutoffHaveFixedBoundaries() {
        val previous = UserSubscription.clock
        val expiry = 1_800_000_000_000L
        var now = expiry
        UserSubscription.clock = { now }
        try {
            val sub = UserSubscription(status = "ACTIVE", planId = "plan_premium", expiresAt = expiry)
            assertTrue(sub.isActive)
            assertTrue(sub.isInRenewalGrace)
            assertEquals(0, sub.daysRemaining)
            assertFalse(sub.renewalReminderDue)
            now += com.example.data.RenewalPolicy.DAY_MS
            assertTrue(sub.renewalReminderDue)
            assertTrue(sub.isActive)
            assertFalse(sub.copy(status = "SUSPENDED").isActive)
            assertFalse(sub.copy(status = "EXPIRED").isActive)
            now += com.example.data.RenewalPolicy.DAY_MS
            assertFalse(sub.isActive)
            assertFalse(sub.renewalReminderDue)
            assertFalse(sub.copy(status = "GRACE_PERIOD").isActive)
            assertFalse(sub.copy(expiresAt = 0L).isActive)
        } finally { UserSubscription.clock = previous }
    }

    @Test fun rollingBackThePhoneClockDoesNotAddMembershipTimeAndCheckpointSurvivesRestart() {
        var wall = 1_000_000L
        var elapsed = 5_000L
        var saved = 0L
        val clock = MonotonicSubscriptionClock(wallTime = { wall }, elapsedTime = { elapsed }, persist = { saved = it })
        clock.synchronize(wall)
        wall -= 86_400_000L
        elapsed += 120_000L
        assertEquals(1_120_000L, clock.now())
        val restarted = MonotonicSubscriptionClock(saved, { wall }, { 0L })
        assertEquals(1_120_000L, restarted.now())
        elapsed += 1_000L
        clock.observeServerTimestamp(1_100_000L) // an older cloud record cannot rewind time
        assertEquals(1_121_000L, clock.now())
    }
}
