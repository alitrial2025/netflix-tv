package com.example.ui.components

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.example.model.Movie
import com.example.ui.util.HomeStartupGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class TvCarouselNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val active = mutableStateOf(true)
    private val selected = mutableListOf<String>()
    private val movies = (0..2).map { Movie("$it", "Title $it", "", "", "") }

    @Before fun pauseOptionalNetworkWork() { HomeStartupGate.setScrolling(true) }
    @After fun releaseInputGate() { HomeStartupGate.setScrolling(false) }

    private fun mount() {
        val focus = FocusRequester()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NetflixMovieRow(title = "Continue Watching", movies = movies,
                rowFocusRequester = focus, isNavigationActive = { active.value },
                onMovieClick = { selected += it.id })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun press(code: Int, repeat: Int = 0) {
        rule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat))
            rule.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test fun fastTapsSelectDestinationBeforeGlideFinishesAndRespectEdges() {
        mount()
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("2", "1", "0"), selected)
    }

    @Test fun departingRowCannotConsumeSelectionOrChangeItsSavedPosition() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.runOnIdle { active.value = false }
        rule.mainClock.advanceTimeByFrame()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(emptyList<String>(), selected)
        rule.runOnIdle { active.value = true }
        rule.mainClock.advanceTimeByFrame()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("1"), selected)
    }
    @Test fun immediateReversalSettlesBackOnTheSelectedHero() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("A cancelled rightward glide must not leave the next hero on screen",
            rule.onAllNodesWithText("Title 1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("0"), selected)
    }

}
