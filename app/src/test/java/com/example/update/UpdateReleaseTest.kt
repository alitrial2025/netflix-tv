package com.example.update

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateReleaseTest {
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("channel", "tv")
        .put("packageName", "com.netflixprotv.apk").put("available", true).put("versionCode", 2)
        .put("versionName", "2").put("minSdk", 24).put("sizeBytes", 1024)
        .put("sha256", "a".repeat(64)).put("apkUrl", "/downloads/tv.apk")

    @Test fun relativeApkUsesTheManifestHttpsOrigin() {
        val release = UpdateRelease.parse(manifest().toString(), "https://localhost/updates/tv.json", "com.netflixprotv.apk")
        assertEquals("https://localhost/downloads/tv.apk", release?.apkUrl)
        assertEquals(2L, release?.versionCode)
    }

    @Test fun unavailableReleaseDoesNotShowGate() {
        assertNull(UpdateRelease.parse(manifest().put("available", false).toString(), "https://localhost/updates/tv.json", "com.netflixprotv.apk"))
    }

    @Test fun wrongPackageMalformedDigestAndInsecureApkAreRejected() {
        for (invalid in listOf(manifest().put("packageName", "com.netflixpro.apk"),
            manifest().put("sha256", "truncated"), manifest().put("apkUrl", "http://localhost/app.apk"))) {
            try { UpdateRelease.parse(invalid.toString(), "https://localhost/updates/tv.json", "com.netflixprotv.apk"); fail("Invalid update accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
