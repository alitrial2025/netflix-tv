package com.example.ui.components

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class TvCategoryNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tapsAndReversalSelectTheDestinationWhileTheSlowGlideContinues() {
        val focus = FocusRequester()
        val selected = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CategoriesBarSection(categoriesFocusRequester = focus, onCategoryClick = { selected += it })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        fun press(code: Int, repeat: Int = 0) {
            rule.runOnUiThread {
                val now = SystemClock.uptimeMillis()
                rule.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat))
                rule.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("Comedies", "Dramas"), selected)
    }
}
