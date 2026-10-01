package com.example.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.theme.NetflixProTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class EditProfileLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun footerLabelsAreCenteredInsideTheirButtonsAndActionsStillWork() {
        var saves = 0
        var deletes = 0
        rule.setContent {
            NetflixProTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    EditProfileMainView("Home", "English", "", null, Color.Red, false, "", null, true,
                        {}, {}, {}, {}, {}, {}, { saves++ }, { deletes++ })
                }
            }
        }
        // Settle the screen's delayed initial Name focus before moving to its footer.
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        for (label in listOf("Done", "Delete Profile")) {
            val button = rule.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(button.center.y.toDouble(), text.center.y.toDouble(), 1.0)
            assertEquals(button.center.x.toDouble(), text.center.x.toDouble(), 1.0)
        }
        val output = File("build/reports/tv-profile-review").apply { mkdirs() }
        rule.onRoot().captureRoboImage(File(output, "edit-profile.png").path)
        for (label in listOf("Done", "Delete Profile")) {
            rule.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            rule.waitForIdle()
            rule.onNodeWithContentDescription(label).assertIsFocused()
            rule.runOnUiThread {
                rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            }
            rule.waitForIdle()
            assertEquals(1, if (label == "Done") saves else deletes)
        }
        assertEquals(1, saves)
        assertEquals(1, deletes)
    }
}
