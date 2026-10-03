package com.example.update

import org.junit.Assert.*
import org.junit.Test

class UpdateGatePolicyTest {
    private fun release(code: Long, sdk: Int = 24) = UpdateRelease(
        "tv", "com.netflixprotv.apk", code, "v$code", sdk, 1024, "a".repeat(64),
        "https://npro-app.vercel.app/downloads/tv/$code.apk", "", "{}"
    )

    @Test fun latestInstalledReleaseUnlocks() {
        assertEquals(UpdateGatePolicy.Decision.Current, UpdateGatePolicy.decide(10, 34, release(10), release(9)))
    }

    @Test fun newerReleaseRequiresInstallation() {
        assertEquals(UpdateGatePolicy.Decision.Required(release(11), true), UpdateGatePolicy.decide(10, 34, release(11), null))
    }

    @Test fun failedCheckCannotDismissKnownRequiredRelease() {
        assertEquals(UpdateGatePolicy.Decision.Required(release(11), true), UpdateGatePolicy.decide(10, 34, null, release(11)))
    }

    @Test fun rolledBackManifestCannotDismissKnownRequiredRelease() {
        assertEquals(UpdateGatePolicy.Decision.Required(release(12), true), UpdateGatePolicy.decide(10, 34, release(11), release(12)))
    }

    @Test fun unavailableManifestRequiresRetryRatherThanOpeningTheApp() {
        assertEquals(UpdateGatePolicy.Decision.Unavailable, UpdateGatePolicy.decide(10, 34, null, release(9)))
    }

    @Test fun incompatibleNewReleaseCannotUnlockOldApp() {
        assertEquals(UpdateGatePolicy.Decision.Required(release(11, 35), false), UpdateGatePolicy.decide(10, 34, release(11, 35), null))
    }
}
