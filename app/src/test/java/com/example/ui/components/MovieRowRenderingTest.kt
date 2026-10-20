package com.example.ui.components

import android.app.Application
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.model.Movie
import com.example.ui.theme.NetflixProTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compare the actual rail's pixels and remote behavior through both viewport paths. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi", application = Application::class)
class MovieRowRenderingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val movies = (1..12).map {
        Movie("render_$it", "Title $it", "A story for the rendering fixture", "", "",
            type = "Movie", isComingSoon = it % 2 == 0)
    }

    private fun compare(width: Int) {
        rule.setContent {
            NetflixProTheme {
                Column(Modifier.width(width.dp)) {
                    NetflixMovieRow("Top 10", movies, Modifier.testTag("measured"), height = 300.dp,
                        continueWatchingProgressMap = mapOf("render_1" to 0.5f, "render_2" to 0.25f))
                    NetflixMovieRow("Top 10", movies, Modifier.testTag("reused"), height = 300.dp,
                        continueWatchingProgressMap = mapOf("render_1" to 0.5f, "render_2" to 0.25f),
                        viewportWidth = width.dp)
                }
            }
        }
        rule.waitForIdle()
        // Window PixelCopy needs a device draw loop; use Robolectric's native canvas.
        val output = File("build/reports/tv-home-review").apply { mkdirs() }
        fun capture(tag: String): android.graphics.Bitmap {
            val file = File(output, "rail-$width-$tag.png")
            if (file.exists()) check(file.delete())
            rule.onNodeWithTag(tag).captureRoboImage(file.path,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return requireNotNull(BitmapFactory.decodeFile(file.path))
        }
        val measured = capture("measured")
        val reused = capture("reused")
        assertEquals(measured.width, reused.width)
        assertEquals(measured.height, reused.height)
        val expected = IntArray(measured.width * measured.height)
        val actual = IntArray(expected.size)
        measured.getPixels(expected, 0, measured.width, 0, 0, measured.width, measured.height)
        reused.getPixels(actual, 0, reused.width, 0, 0, reused.width, reused.height)
        assertTrue("Reusing $width dp must preserve every rendered pixel", expected.contentEquals(actual))
        measured.recycle()
        reused.recycle()
    }

    @Test fun compactViewportKeepsTheSameArtworkAndBadges() = compare(640)
    @Test fun standardViewportKeepsTheSameArtworkAndBadges() = compare(960)
    @Test fun wideViewportKeepsTheSameArtworkAndBadges() = compare(1280)

    @Test fun reusedViewportKeepsRemoteNavigationAndSelectDuringMotion() {
        val selected = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NetflixProTheme {
                val focus = remember { FocusRequester() }
                NetflixMovieRow("Movies", movies, Modifier.width(960.dp), height = 300.dp,
                    viewportWidth = 960.dp, rowFocusRequester = focus,
                    onMovieClick = { selected += it.id })
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        fun key(code: Int) {
            rule.runOnUiThread {
                rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeByFrame()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeByFrame()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("render_3"), selected)
        rule.mainClock.advanceTimeBy(1_000)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.mainClock.advanceTimeBy(1_000)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("render_3", "render_2"), selected)
    }
}
