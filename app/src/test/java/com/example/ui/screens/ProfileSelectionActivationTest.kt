package com.example.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.model.Profile
import com.example.ui.theme.NetflixProTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class ProfileSelectionActivationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun oneHeldOkPressOpensTheEditorExactlyOnce() {
        val pencil = FocusRequester()
        val avatar = FocusRequester()
        var opens = 0
        rule.setContent {
            NetflixProTheme {
                ProfileSelectionRow(Profile("test", "Home", Color.Red), 0, true, true, avatar, pencil,
                    null, null, {}, {}, {}, { opens++ })
                LaunchedEffect(Unit) { pencil.requestFocus() }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            rule.activity.dispatchKeyEvent(KeyEvent(0, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            rule.activity.dispatchKeyEvent(KeyEvent(0, 200, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        rule.waitForIdle()
        assertEquals(1, opens)
    }

    @Test fun enterOnAvatarSelectsOnlyOnce() {
        val pencil = FocusRequester()
        val avatar = FocusRequester()
        var selections = 0
        rule.setContent {
            NetflixProTheme {
                ProfileSelectionRow(Profile("test", "Home", Color.Red), 0, true, false, avatar, pencil,
                    null, null, {}, {}, { selections++ }, {})
                LaunchedEffect(Unit) { avatar.requestFocus() }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        rule.waitForIdle()
        assertEquals(1, selections)
    }
}
