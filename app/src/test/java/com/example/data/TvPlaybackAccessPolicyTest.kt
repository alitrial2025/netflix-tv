package com.example.data

import com.example.model.Movie
import com.example.model.UserSubscription
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TvPlaybackAccessPolicyTest {
    private val now = 1_800_000_000_000L
    private val previousClock = UserSubscription.clock
    private val movie = Movie("1399", "Premium Series", "", "", "", year = "2026")
    private val premium get() = UserSubscription("ACTIVE", "plan_premium", "Premium", expiresAt = now + RenewalPolicy.DAY_MS)

    @Before fun fixedClock() { UserSubscription.clock = { now } }
    @After fun restoreClock() { UserSubscription.clock = previousClock }

    @Test fun premiumLosingPlaybackProofRequiresVerificationWithoutAnUpgradePrompt() {
        assertEquals(TvPlaybackAccessPolicy.Decision.VERIFY,
            TvPlaybackAccessPolicy.decide(premium, movie, authenticated = true, leaseVerified = false))
        assertEquals(TvPlaybackAccessPolicy.Decision.ALLOW,
            TvPlaybackAccessPolicy.decide(premium, movie, authenticated = true, leaseVerified = true))
    }

    @Test fun aPreviousLeaseCannotAuthorizeAnAccountThatSignedOut() {
        assertEquals(TvPlaybackAccessPolicy.Decision.VERIFY,
            TvPlaybackAccessPolicy.decide(premium, movie, authenticated = false, leaseVerified = true))
    }

    @Test fun guestExpiredSuspendedAndMobileTvMembershipStillRequireAPlan() {
        for (subscription in listOf(UserSubscription(), premium.copy(status = "EXPIRED"),
            premium.copy(expiresAt = now - RenewalPolicy.GRACE_MS), premium.copy(status = "SUSPENDED"),
            premium.copy(planId = "plan_mobile", planName = "Mobile"))) {
            assertEquals(TvPlaybackAccessPolicy.Decision.UPGRADE,
                TvPlaybackAccessPolicy.decide(subscription, movie, authenticated = true, leaseVerified = true))
        }
    }

    @Test fun paidRenewalGraceKeepsThePlanAndRequiresAFreshLease() {
        val grace = premium.copy(status = "GRACE_PERIOD", expiresAt = now - RenewalPolicy.DAY_MS)
        assertEquals(TvPlaybackAccessPolicy.Decision.VERIFY,
            TvPlaybackAccessPolicy.decide(grace, movie, authenticated = true))
    }

    @Test fun transientFailuresShowARetryMessageWithoutExposingTransportDetails() {
        val message = TvPlaybackAccessPolicy.verificationMessage(IOException("https://private.example/?token=secret"))
        assertTrue(message.contains("Reconnect")); assertTrue(message.contains("retry"))
        assertFalse(message.contains("private.example")); assertFalse(message.contains("secret"))
        assertEquals(TvPlaybackAccessPolicy.Decision.VERIFY,
            TvPlaybackAccessPolicy.decide(premium, movie, authenticated = true))
    }

    @Test fun anActualDeviceOrScreenDenialKeepsItsDistinctReason() {
        val restriction = "All 4 screens on your plan are in use. Stop playback on another device."
        val error = IOException("Transaction failed", DeviceAccessException(restriction))
        assertEquals(restriction, TvPlaybackAccessPolicy.verificationMessage(error))
        assertEquals(TvPlaybackAccessPolicy.Decision.VERIFY,
            TvPlaybackAccessPolicy.decide(premium, movie, authenticated = true))
    }

    @Test fun theSuccessfulLeaseTierOverridesStalePremiumBeforeCatalogAuthorization() {
        val basic = premium.copy(planId = "plan_basic", planName = "Basic")
        val restricted = (1..100).map { movie.copy(id = it.toString()) }
            .first { basic.isMovieLocked(it.id, isTvDevice = true, movieTitle = it.title) }
        assertEquals(TvPlaybackAccessPolicy.Decision.ALLOW,
            TvPlaybackAccessPolicy.decide(premium, restricted, authenticated = true, leaseVerified = true))
        val verified = ScreenLease.VerifiedMembership("same-account", "plan_basic", "ACTIVE",
            now + 2 * RenewalPolicy.DAY_MS, "Basic")
        val confirmed = TvPlaybackAccessPolicy.confirmedSubscription(premium, "same-account", verified)!!
        assertEquals("plan_basic", confirmed.planId)
        assertEquals(verified.expiresAt, confirmed.expiresAt)
        assertEquals(TvPlaybackAccessPolicy.Decision.UPGRADE,
            TvPlaybackAccessPolicy.decide(confirmed, restricted, authenticated = true, leaseVerified = true))
    }

    @Test fun aLateLeaseResultCannotReplaceAnotherAccountsMembership() {
        val verified = ScreenLease.VerifiedMembership("old-account", "plan_basic", "ACTIVE",
            now + RenewalPolicy.DAY_MS, "Basic")
        assertNull(TvPlaybackAccessPolicy.confirmedSubscription(premium, "new-account", verified))
    }
}
