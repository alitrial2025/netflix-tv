package com.example.ui.screens.player

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.example.ui.screens.ThumbnailCue
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class SeekFilmstripAnimationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun ringMovesThroughIntermediatePositionsAndEndsOnTheNextFrame() {
        val sprite = File(rule.activity.cacheDir, "seek-preview-test.png")
        val bitmap = Bitmap.createBitmap(960, 90, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(0xffb03030.toInt(), 0xff308050.toInt(), 0xff3060b0.toInt(), 0xffc09030.toInt(), 0xff8050b0.toInt(), 0xff309090.toInt())
        for (x in 0 until 960) for (y in 0 until 90) bitmap.setPixel(x, y, colors[x / 160])
        sprite.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val cues = (0..5).map { ThumbnailCue(it * 10_000L, (it + 1) * 10_000L, sprite.toURI().toString(), it * 160, 0, 160, 90) }
        val time = mutableLongStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                ThumbnailSeekingBand(time.longValue, 60, cues)
            }
        }
        rule.mainClock.advanceTimeBy(32)
        val initial = rule.onNodeWithTag("seek_thumbnail_ring").fetchSemanticsNode().boundsInRoot
        rule.runOnUiThread { time.longValue = 10_000L }
        rule.mainClock.advanceTimeBy(80)
        val middle = rule.onNodeWithTag("seek_thumbnail_ring").fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(200)
        val end = rule.onNodeWithTag("seek_thumbnail_ring").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.left > initial.left)
        assertTrue(middle.left < end.left)
        assertEquals(208f, end.left - initial.left, 1f)
        assertEquals(initial.width, end.width, 0f)
        val output = File("build/reports/tv-player-review").apply { mkdirs() }
        rule.onRoot().captureRoboImage(File(output, "seek-filmstrip.png").path)
    }
}
