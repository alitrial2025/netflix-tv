package com.example.data

import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AppSessionAdmissionTest {
    @Test fun transientRefreshDoesNotRevokeAnAdmittedPremiumSession() {
        val admission = AppSessionAdmission().apply { admit("premium-account") }
        for (failure in listOf(IOException("Network interrupted"), MembershipCheckException(),
            FirebaseFirestoreException("Unavailable", FirebaseFirestoreException.Code.UNAVAILABLE))) {
            if (MembershipFailure.isDefinitive(failure)) admission.clear()
            assertTrue(admission.allows("premium-account", entitled = true))
        }
    }

    @Test fun explicitDenialAndSignOutRequireFreshAdmission() {
        for (failure in listOf(DeviceAccessException("All screens are in use"), MembershipRevokedException(),
            MembershipCheckException(FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)))) {
            val admission = AppSessionAdmission().apply { admit("account-a") }
            assertTrue(MembershipFailure.isDefinitive(failure))
            if (MembershipFailure.isDefinitive(failure)) admission.clear()
            assertFalse(admission.allows("account-a", entitled = true))
        }
        val admission = AppSessionAdmission().apply { admit("account-a") }
        admission.clear()
        assertFalse(admission.allows("account-a", entitled = true))
    }

    @Test fun admissionCannotCrossAccountsOrAuthorizeAnExpiredPlan() {
        val admission = AppSessionAdmission().apply { admit("account-a") }
        assertFalse(admission.allows("account-b", entitled = true))
        assertFalse(admission.allows(null, entitled = true))
        assertFalse(admission.allows("account-a", entitled = false))
        admission.admit("account-b")
        assertFalse(admission.allows("account-a", entitled = true))
        assertTrue(admission.allows("account-b", entitled = true))
    }

    @Test fun unavailableStartupCannotCreateAnAdmission() {
        val admission = AppSessionAdmission()
        assertFalse(MembershipFailure.isDefinitive(MembershipCheckException(IOException("Offline"))))
        assertFalse(admission.allows("premium-account", entitled = true))
        assertThrows(IllegalArgumentException::class.java) { admission.admit(" ") }
    }
}
