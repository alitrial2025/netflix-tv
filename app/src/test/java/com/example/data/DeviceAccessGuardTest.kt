package com.example.data

import org.junit.Assert.*
import org.junit.Test

class DeviceAccessGuardTest {
    @Test fun verifiedMembershipSurvivesRenewalDisplayTransitionButRejectsSuspension() {
        val expiry = 100_000L
        val grace = expiry + RenewalPolicy.DAY_MS
        assertTrue(DeviceAccessPolicy.confirmationStatusMatches("ACTIVE", "GRACE_PERIOD", expiry, grace))
        assertFalse(DeviceAccessPolicy.confirmationStatusMatches("ACTIVE", "SUSPENDED", expiry, grace))
        assertFalse(DeviceAccessPolicy.confirmationStatusMatches("ACTIVE", "GRACE_PERIOD", expiry, expiry + RenewalPolicy.GRACE_MS))
        assertFalse(DeviceAccessPolicy.confirmationStatusMatches("NONE", "ACTIVE", expiry, grace))
    }
    @Test fun singleDevicePlansRejectSecondDeviceEvenAfterSignOut() {
        for (plan in listOf("plan_mobile", "plan_basic")) {
            assertTrue(DeviceAccessPolicy.permits(plan, true, false, null, "phone-a"))
            assertTrue(DeviceAccessPolicy.permits(plan, true, false, "phone-a", "phone-a"))
            assertFalse(DeviceAccessPolicy.permits(plan, true, false, "phone-a", "phone-b"))
        }
        assertFalse(DeviceAccessPolicy.permits("plan_basic", true, true, "phone-a", "tv-a"))
        assertTrue(DeviceAccessPolicy.permits("plan_basic", true, true, "tv-a", "tv-a"))
    }
    @Test fun mobileCannotUseTvButUnpaidAccountsCanBrowseTrailers() {
        assertFalse(DeviceAccessPolicy.permits("plan_mobile", true, true, null, "tv-a"))
        assertFalse(DeviceAccessPolicy.permits("plan_mobile", true, true, "tv-a", "tv-a"))
        assertTrue(DeviceAccessPolicy.permits("plan_mobile", false, true, "phone-a", "tv-a"))
        assertTrue(DeviceAccessPolicy.permits("plan_guest", false, false, "phone-a", "phone-b"))
        assertTrue(DeviceAccessPolicy.permits("plan_standard", true, true, "phone-a", "tv-a"))
    }
    @Test fun concurrentSlotsKeepExistingDeviceAndRejectFullPlans() {
        assertEquals(1, ScreenLease.availableSlot(listOf("a", "b"),listOf(100_000,100_000),listOf(false,false),"b",110_000))
        assertNull(ScreenLease.availableSlot(listOf("a", "b"),listOf(100_000,100_000),listOf(false,false),"c",110_000))
        assertEquals(0, ScreenLease.availableSlot(listOf("a", "b"),listOf(100_000,100_000),listOf(false,false),"c",145_000))
        assertEquals(1, ScreenLease.availableSlot(listOf("a", "b"),listOf(100_000,100_000),listOf(false,true),"c",110_000))
        assertEquals(listOf(1,1,2,4,0),listOf("plan_mobile","plan_basic","plan_standard","plan_premium","unknown").map(DeviceAccessPolicy::screenCount))
    }
}
