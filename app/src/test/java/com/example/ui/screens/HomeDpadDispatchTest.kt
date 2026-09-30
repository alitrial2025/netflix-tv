package com.example.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose dispatch with the old child's focus deliberately retained. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeDpadDispatchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val requested = mutableIntStateOf(1)
    private var childDownEvents = 0

    private fun mount() {
        rule.setContent {
            val focus = remember { FocusRequester() }
            Box(Modifier.handleHomeVerticalNavigation(
                level = { requested.intValue }, rowCount = 4,
                hasCategories = true, hasBillboard = true,
                onMove = { requested.intValue = it }
            )) {
                Box(Modifier.size(20.dp).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) childDownEvents++
                    true
                }.focusRequester(focus).focusable())
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.waitForIdle()
    }

    private fun press(code: Int, repeat: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(KeyEvent(0, 120L * (repeat + 1), KeyEvent.ACTION_DOWN, code, repeat))
        }
        rule.waitForIdle()
    }

    @Test fun repeatedDownAdvancesEvenWhenDestinationFocusHasNotAttached() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 3)
        assertEquals(4, requested.intValue)
        assertEquals(0, childDownEvents)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(3, requested.intValue)
    }

    @Test fun horizontalAndSelectRemainOwnedByCarousel() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, requested.intValue)
        assertEquals(2, childDownEvents)
    }
}
