package com.example.update

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h720dp-mdpi", sdk = [34], application = Application::class)
class UpdateGateScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val release = UpdateRelease("tv", "com.netflixprotv.apk", 29092028, "Local update test",
        24, 30L * 1024 * 1024, "a".repeat(64), "https://localhost/downloads/tv.apk", "Playback fixes", "{}")

    private fun show(phase: UpdatePhase, name: String) {
        rule.setContent {
            UpdateGateScreen(UpdateGateState(phase, release, .37f, message = "Local update test"), {}, {}, {}, {})
        }
        rule.onNodeWithText("Update required").assertExists()
        rule.onNodeWithText("Later").assertDoesNotExist()
        rule.onNodeWithText("Continue").assertDoesNotExist()
        rule.onNodeWithText("Exit app").assertExists()
        val folder = File(System.getProperty("screenshot.output", "build/reports/update-screenshots")).apply { mkdirs() }
        rule.onRoot().captureRoboImage(File(folder, "$name.png").path)
    }

    @Test fun downloading() = show(UpdatePhase.DOWNLOADING, "downloading")
    @Test fun installPermission() = show(UpdatePhase.PERMISSION, "permission")
    @Test fun androidApproval() = show(UpdatePhase.APPROVAL, "approval")

    @Test fun exitDoesNotOfferAnAppBypass() {
        var exited = false
        rule.setContent {
            UpdateGateScreen(UpdateGateState(UpdatePhase.ERROR, release), { exited = true }, {}, {}, {})
        }
        rule.onNodeWithText("Later").assertDoesNotExist()
        rule.onNodeWithText("Exit app").performClick()
        assertTrue(exited)
    }

    @Test fun retryInvokesRecoveryAction() {
        var retried = false
        rule.setContent {
            UpdateGateScreen(UpdateGateState(UpdatePhase.ERROR, release, message = "Download interrupted"), {}, { retried = true }, {}, {})
        }
        rule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
