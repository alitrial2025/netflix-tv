package com.example.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [UserSubscription], [SubscriptionStatus], and [SubscriptionTier].
 *
 * The `clock` companion field on [UserSubscription] is a [Volatile] injection point;
 * tests install a fixed clock (2026-09-01T12:00:00Z) in [setUp] and restore the
 * production clock in [tearDown] so each test starts from a deterministic "now".
 */
class UserSubscriptionTest {

    private val fixedNowMillis: Long =
        Instant.parse("2026-09-01T12:00:00Z").toEpochMilli()

    private val oneDayMs: Long = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        // Install a deterministic clock so `nowMillis()` is reproducible.
        UserSubscription.clock = { fixedNowMillis }
    }

    @After
    fun tearDown() {
        // Restore the production clock so subsequent test classes are unaffected.
        UserSubscription.clock = { System.currentTimeMillis() }
    }

    // -----------------------------------------------------------------------
    // 1. isActive for every SubscriptionStatus
    // -----------------------------------------------------------------------

    @Test
    fun isActive_trueForActiveStatusWithFutureExpiry() {
        val sub = UserSubscription(status = "ACTIVE", planId = "plan_standard", expiresAt = fixedNowMillis + oneDayMs)
        assertTrue(sub.isActive)
    }

    @Test
    fun activeStatusWithoutExpiryDoesNotGrantMembership() {
        assertFalse(UserSubscription(status = "ACTIVE", planId = "plan_standard").isActive)
    }

    @Test
    fun isActive_falseForGuest() {
        val sub = UserSubscription(status = "ACTIVE", planId = "plan_guest")
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForTrialWhenNotExpired() {
        val sub = UserSubscription(
            status = "TRIAL",
            planId = "plan_standard",
            trialEndsAt = fixedNowMillis + oneDayMs
        )
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForGracePeriodWhenNotExpired() {
        val sub = UserSubscription(
            status = "GRACE_PERIOD",
            planId = "plan_standard",
            gracePeriodEndsAt = fixedNowMillis + oneDayMs
        )
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForPastDue() {
        val sub = UserSubscription(status = "PAST_DUE", planId = "plan_standard")
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForCancelledWhenExpiresAtIsInFuture() {
        val sub = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForExpired() {
        val sub = UserSubscription(
            status = "EXPIRED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForSuspended() {
        val sub = UserSubscription(status = "SUSPENDED", planId = "plan_standard")
        assertFalse(sub.isActive)
    }

    // -----------------------------------------------------------------------
    // 11/12. PENDING_ACTIVATION gated on lastVerifiedAt
    // -----------------------------------------------------------------------

    @Test
    fun isActive_falseForPendingActivationWhenLastVerifiedAtIsNull() {
        val sub = UserSubscription(
            status = "PENDING_ACTIVATION",
            planId = "plan_standard",
            lastVerifiedAt = null
        )
        assertFalse(sub.isActive)
    }

    @Test
    fun isActive_falseForPendingActivationWhenLastVerifiedAtIsNotNull() {
        val sub = UserSubscription(
            status = "PENDING_ACTIVATION",
            planId = "plan_standard",
            lastVerifiedAt = fixedNowMillis - oneDayMs
        )
        assertFalse(sub.isActive)
    }

    // -----------------------------------------------------------------------
    // 2. isInGracePeriod
    // -----------------------------------------------------------------------

    @Test
    fun isInGracePeriod_trueWhenStatusIsGracePeriod() {
        val sub = UserSubscription(
            status = "GRACE_PERIOD",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs,
            gracePeriodEndsAt = fixedNowMillis + oneDayMs
        )
        assertTrue(sub.isInGracePeriod)
    }

    @Test
    fun isInGracePeriod_falseForAnyOtherStatus() {
        val active = UserSubscription(status = "ACTIVE", planId = "plan_standard")
        val trial = UserSubscription(
            status = "TRIAL",
            planId = "plan_standard",
            trialEndsAt = fixedNowMillis + oneDayMs
        )
        val pastDue = UserSubscription(status = "PAST_DUE", planId = "plan_standard")
        val cancelled = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        val expired = UserSubscription(
            status = "EXPIRED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        val suspended = UserSubscription(status = "SUSPENDED", planId = "plan_standard")
        val pending = UserSubscription(
            status = "PENDING_ACTIVATION",
            planId = "plan_standard",
            lastVerifiedAt = fixedNowMillis
        )

        assertFalse(active.isInGracePeriod)
        assertFalse(trial.isInGracePeriod)
        assertFalse(pastDue.isInGracePeriod)
        assertFalse(cancelled.isInGracePeriod)
        assertFalse(expired.isInGracePeriod)
        assertFalse(suspended.isInGracePeriod)
        assertFalse(pending.isInGracePeriod)
    }

    // -----------------------------------------------------------------------
    // 3. isInTrial
    // -----------------------------------------------------------------------

    @Test
    fun isInTrial_trueWhenStatusIsTrial() {
        val sub = UserSubscription(
            status = "TRIAL",
            planId = "plan_standard",
            trialEndsAt = fixedNowMillis + oneDayMs
        )
        assertTrue(sub.isInTrial)
    }

    @Test
    fun isInTrial_falseForAnyOtherStatus() {
        val active = UserSubscription(status = "ACTIVE", planId = "plan_standard")
        val grace = UserSubscription(
            status = "GRACE_PERIOD",
            planId = "plan_standard",
            gracePeriodEndsAt = fixedNowMillis + oneDayMs
        )
        val pastDue = UserSubscription(status = "PAST_DUE", planId = "plan_standard")
        val cancelled = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        val expired = UserSubscription(
            status = "EXPIRED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        val suspended = UserSubscription(status = "SUSPENDED", planId = "plan_standard")
        val pending = UserSubscription(
            status = "PENDING_ACTIVATION",
            planId = "plan_standard",
            lastVerifiedAt = fixedNowMillis
        )

        assertFalse(active.isInTrial)
        assertFalse(grace.isInTrial)
        assertFalse(pastDue.isInTrial)
        assertFalse(cancelled.isInTrial)
        assertFalse(expired.isInTrial)
        assertFalse(suspended.isInTrial)
        assertFalse(pending.isInTrial)
    }

    // -----------------------------------------------------------------------
    // 4. isPastDue
    // -----------------------------------------------------------------------

    @Test
    fun isPastDue_trueWhenStatusIsPastDue() {
        val sub = UserSubscription(status = "PAST_DUE", planId = "plan_standard")
        assertTrue(sub.isPastDue)
    }

    @Test
    fun isPastDue_falseForAnyOtherStatus() {
        val active = UserSubscription(status = "ACTIVE", planId = "plan_standard")
        val trial = UserSubscription(
            status = "TRIAL",
            planId = "plan_standard",
            trialEndsAt = fixedNowMillis + oneDayMs
        )
        val grace = UserSubscription(
            status = "GRACE_PERIOD",
            planId = "plan_standard",
            gracePeriodEndsAt = fixedNowMillis + oneDayMs
        )
        val cancelled = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        val expired = UserSubscription(
            status = "EXPIRED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        val suspended = UserSubscription(status = "SUSPENDED", planId = "plan_standard")
        val pending = UserSubscription(
            status = "PENDING_ACTIVATION",
            planId = "plan_standard",
            lastVerifiedAt = fixedNowMillis
        )

        assertFalse(active.isPastDue)
        assertFalse(trial.isPastDue)
        assertFalse(grace.isPastDue)
        assertFalse(cancelled.isPastDue)
        assertFalse(expired.isPastDue)
        assertFalse(suspended.isPastDue)
        assertFalse(pending.isPastDue)
    }

    // -----------------------------------------------------------------------
    // 5. isCancelledButNotExpired
    // -----------------------------------------------------------------------

    @Test
    fun isCancelledButNotExpired_trueWhenCancelledAndExpiresAtInFuture() {
        val sub = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        assertTrue(sub.isCancelledButNotExpired)
    }

    @Test
    fun isCancelledButNotExpired_falseWhenStatusNotCancelled() {
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + oneDayMs
        )
        assertFalse(sub.isCancelledButNotExpired)
    }

    @Test
    fun isCancelledButNotExpired_falseWhenExpiresAtInPast() {
        val sub = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        assertFalse(sub.isCancelledButNotExpired)
    }

    @Test
    fun isCancelledButNotExpired_falseWhenExpiresAtIsZero() {
        val sub = UserSubscription(
            status = "CANCELLED",
            planId = "plan_standard",
            expiresAt = 0L
        )
        assertFalse(sub.isCancelledButNotExpired)
    }

    // -----------------------------------------------------------------------
    // 6. tier getter
    // -----------------------------------------------------------------------

    @Test
    fun tier_resolvesFromPlanId() {
        assertEquals(SubscriptionTier.GUEST, UserSubscription(planId = "plan_guest").tier)
        assertEquals(SubscriptionTier.MOBILE, UserSubscription(planId = "plan_mobile").tier)
        assertEquals(SubscriptionTier.BASIC, UserSubscription(planId = "plan_basic").tier)
        assertEquals(SubscriptionTier.STANDARD, UserSubscription(planId = "plan_standard").tier)
        assertEquals(SubscriptionTier.PREMIUM, UserSubscription(planId = "plan_premium").tier)
    }

    @Test
    fun tier_defaultsToGuestForUnknownPlanId() {
        assertEquals(SubscriptionTier.GUEST, UserSubscription(planId = "plan_unknown").tier)
        assertEquals(SubscriptionTier.GUEST, UserSubscription(planId = "").tier)
    }

    // -----------------------------------------------------------------------
    // 7. daysRemaining — ceiling division correctness
    // -----------------------------------------------------------------------

    @Test
    fun daysRemaining_zeroWhenExpiresAtEqualsNow() {
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis
        )
        assertEquals(0, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_zeroWhenExpiresAtInPast() {
        val sub = UserSubscription(
            status = "EXPIRED",
            planId = "plan_standard",
            expiresAt = fixedNowMillis - oneDayMs
        )
        assertEquals(0, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_oneWhenOneMillisecondRemaining() {
        // Audit fix: previously this returned 0 then `coerceAtLeast(1)` masked it as 1.
        // The new ceiling division should report 1 for any positive remainder.
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + 1L
        )
        assertEquals(1, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_oneWhenLessThanADayRemaining() {
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + (oneDayMs - 1L)
        )
        assertEquals(1, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_exactDaysForFullDaysRemaining() {
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + (5L * oneDayMs)
        )
        assertEquals(5, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_ceilingRoundingForDaysAndPartialDay() {
        // 3 days + 1 ms remaining should round up to 4, not down to 3.
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + (3L * oneDayMs) + 1L
        )
        assertEquals(4, sub.daysRemaining)
    }

    @Test
    fun daysRemaining_largeValueHandledCorrectly() {
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis + (365L * oneDayMs)
        )
        assertEquals(365, sub.daysRemaining)
    }

    // -----------------------------------------------------------------------
    // 8. daysRemaining with null/zero renewsAt — should return 0
    // -----------------------------------------------------------------------

    @Test
    fun daysRemaining_zeroWhenExpiresAtIsZero() {
        // renewsAt is null AND expiresAt is the default 0L: should be 0, no crash.
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            renewsAt = null,
            expiresAt = 0L
        )
        assertEquals(0, sub.daysRemaining)
    }

    // -----------------------------------------------------------------------
    // 9. SubscriptionStatus.fromString — exhaustive case coverage
    // -----------------------------------------------------------------------

    @Test
    fun fromString_recognisesAllValidStatusesUppercase() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.fromString("ACTIVE"))
        assertEquals(SubscriptionStatus.TRIAL, SubscriptionStatus.fromString("TRIAL"))
        assertEquals(
            SubscriptionStatus.GRACE_PERIOD,
            SubscriptionStatus.fromString("GRACE_PERIOD")
        )
        assertEquals(SubscriptionStatus.PAST_DUE, SubscriptionStatus.fromString("PAST_DUE"))
        assertEquals(SubscriptionStatus.CANCELLED, SubscriptionStatus.fromString("CANCELLED"))
        assertEquals(SubscriptionStatus.EXPIRED, SubscriptionStatus.fromString("EXPIRED"))
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("PENDING_ACTIVATION")
        )
        assertEquals(SubscriptionStatus.SUSPENDED, SubscriptionStatus.fromString("SUSPENDED"))
    }

    @Test
    fun fromString_recognisesLowercaseInput() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.fromString("active"))
        assertEquals(SubscriptionStatus.EXPIRED, SubscriptionStatus.fromString("expired"))
    }

    @Test
    fun fromString_recognisesMixedCaseInput() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.fromString("Active"))
        assertEquals(
            SubscriptionStatus.GRACE_PERIOD,
            SubscriptionStatus.fromString("Grace_Period")
        )
    }

    @Test
    fun fromString_recognisesAliases() {
        // FREE_TRIAL maps to TRIAL
        assertEquals(SubscriptionStatus.TRIAL, SubscriptionStatus.fromString("FREE_TRIAL"))
        // GRACE maps to GRACE_PERIOD
        assertEquals(
            SubscriptionStatus.GRACE_PERIOD,
            SubscriptionStatus.fromString("GRACE")
        )
        // PASTDUE + PAYMENT_FAILED map to PAST_DUE
        assertEquals(SubscriptionStatus.PAST_DUE, SubscriptionStatus.fromString("PASTDUE"))
        assertEquals(
            SubscriptionStatus.PAST_DUE,
            SubscriptionStatus.fromString("PAYMENT_FAILED")
        )
        // CANCELED maps to CANCELLED
        assertEquals(
            SubscriptionStatus.CANCELLED,
            SubscriptionStatus.fromString("CANCELED")
        )
        // PENDING maps to PENDING_ACTIVATION
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("PENDING")
        )
        // FRAUD maps to SUSPENDED
        assertEquals(SubscriptionStatus.SUSPENDED, SubscriptionStatus.fromString("FRAUD"))
    }

    @Test
    fun fromString_trimsWhitespace() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.fromString("  ACTIVE  "))
        assertEquals(
            SubscriptionStatus.EXPIRED,
            SubscriptionStatus.fromString("\tEXPIRED\n")
        )
    }

    // -----------------------------------------------------------------------
    // 10. SubscriptionStatus.fromString — security (blank / unknown → PENDING_ACTIVATION)
    // -----------------------------------------------------------------------

    @Test
    fun fromString_nullDefaultsToPendingActivation() {
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString(null)
        )
    }

    @Test
    fun fromString_emptyStringDefaultsToPendingActivation() {
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("")
        )
    }

    @Test
    fun fromString_blankStringDefaultsToPendingActivation() {
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("   ")
        )
    }

    @Test
    fun fromString_unknownValueDefaultsToPendingActivation() {
        // Audit: a malicious or buggy write like `status = "🐛"` must NOT
        // resolve to ACTIVE — that would silently grant full access.
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("🐛")
        )
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("not_a_real_status")
        )
        assertEquals(
            SubscriptionStatus.PENDING_ACTIVATION,
            SubscriptionStatus.fromString("admin_override")
        )
    }

    // -----------------------------------------------------------------------
    // 13. SubscriptionTier comparison — priority ordering
    // -----------------------------------------------------------------------

    @Test
    fun tierPriority_isOrderedFromLowToHigh() {
        val ordered = listOf(
            SubscriptionTier.GUEST,
            SubscriptionTier.MOBILE,
            SubscriptionTier.BASIC,
            SubscriptionTier.STANDARD,
            SubscriptionTier.PREMIUM
        )
        for (i in 0 until ordered.size - 1) {
            assertTrue(
                "Expected ${ordered[i].name} (${ordered[i].priority}) < ${ordered[i + 1].name} (${ordered[i + 1].priority})",
                ordered[i].priority < ordered[i + 1].priority
            )
        }
    }

    @Test
    fun tierPriority_premiumIsGreaterThanStandardEtc() {
        assertTrue(SubscriptionTier.PREMIUM.priority > SubscriptionTier.STANDARD.priority)
        assertTrue(SubscriptionTier.STANDARD.priority > SubscriptionTier.BASIC.priority)
        assertTrue(SubscriptionTier.BASIC.priority > SubscriptionTier.MOBILE.priority)
        assertTrue(SubscriptionTier.MOBILE.priority > SubscriptionTier.GUEST.priority)
    }

    // -----------------------------------------------------------------------
    // Bonus: clock injection sanity check — the injected clock is what
    // `nowMillis()` reads, not the real wall clock.
    // -----------------------------------------------------------------------

    @Test
    fun clockInjection_isHonoredByNowMillis() {
        // Sanity: the @Before setUp installed fixedNowMillis, and a sub
        // that expires at exactly that instant should report 0 days remaining.
        // This guards against someone accidentally capturing the clock in a
        // companion-object `val` instead of reading it on every call.
        val sub = UserSubscription(
            status = "ACTIVE",
            planId = "plan_standard",
            expiresAt = fixedNowMillis
        )
        assertEquals(0, sub.daysRemaining)
    }

    @Test
    fun clockIsReadableAsJavaTimeClock() {
        // Confirms we can round-trip the same instant through java.time.Clock
        // — useful if a future test wants to assert against a wall-clock
        // helper (ZonedDateTime.ofInstant etc.).
        val clock = Clock.fixed(Instant.ofEpochMilli(fixedNowMillis), ZoneOffset.UTC)
        assertEquals(fixedNowMillis, clock.instant().toEpochMilli())
    }
}
