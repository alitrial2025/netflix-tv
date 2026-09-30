package com.example.ui.screens.details

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.example.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class EpisodeGlideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val season = mutableIntStateOf(1)
    private val currentEpisode = mutableIntStateOf(1)
    private val selected = mutableListOf<Pair<Int, Int>>()
    private val seasons = (1..2).associateWith { number ->
        (1..10).map { Episode(number * 100L + it, it, number, "Episode $it", "", "", "45m") }
    }

    private fun mount(legacy: Boolean = false) {
        val focus = FocusRequester()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val click: (Episode) -> Unit = { selected += it.seasonNumber to it.episodeNumber }
            if (legacy) {
                com.example.ui.screens.EpisodesRowSection(seasons.getValue(season.intValue),
                    season.intValue, currentEpisode.intValue, availableSeasons = listOf(1, 2),
                    continueWatchingData = null, onEpisodeClick = click, episodesFocusRequester = focus)
            } else {
                EpisodesRowSection(seasons.getValue(season.intValue), season.intValue, currentEpisode.intValue,
                    availableSeasons = listOf(1, 2), continueWatchingData = null,
                    onEpisodeClick = click, episodesFocusRequester = focus)
            }
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

    @Test fun rapidTapsKeepOutgoingPostersMountedAndSelectRequestedEpisode() {
        mount()
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue("The ongoing glide must retain its outgoing episode rather than snap to episode 7",
            rule.onAllNodesWithText("EP 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1 to 7, 1 to 6), selected)
    }

    @Test fun seasonChangeStartsAtItsOwnEpisodeWithoutTraversingPreviousSeason() {
        mount()
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        rule.runOnIdle { season.intValue = 2; currentEpisode.intValue = 3 }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("EP 3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(2 to 3), selected)
    }

    @Test fun legacyEntryUsesTheSameGlideAndPreservesFiniteEdges() {
        mount(legacy = true)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(12) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1 to 1, 1 to 10), selected)
    }
}
