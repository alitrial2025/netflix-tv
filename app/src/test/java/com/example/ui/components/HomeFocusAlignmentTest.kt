package com.example.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.model.Movie
import com.example.ui.theme.NetflixProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi", application = Application::class)
class HomeFocusAlignmentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun checkAlignment(scale: Float) {
        var top = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                NetflixProTheme {
                    val density = LocalDensity.current
                    val row = 375.25.dp
                    val billboard = 410.4.dp
                    val gap = 10.dp
                    val categories = 79.2.dp
                    val target = with(density) {
                        homeScrollTarget(32, false, true, row.roundToPx().toFloat(),
                            billboard.roundToPx().toFloat(), gap.roundToPx().toFloat(), 0f, 0f,
                            categories.roundToPx().toFloat())
                    }
                    Box(Modifier.fillMaxSize()) {
                        // The viewport uses bounded composition: previous rails occupy fixed spacers.
                        Column(Modifier.wrapContentHeight(unbounded = true, align = androidx.compose.ui.Alignment.Top).graphicsLayer { translationY = target }) {
                            Spacer(Modifier.height(billboard))
                            Spacer(Modifier.height(gap))
                            Spacer(Modifier.height(categories))
                            repeat(31) { Spacer(Modifier.height(row)) }
                            NetflixMovieRow("Movies", remember { listOf(Movie("fixture", "Title", "", "", "")) },
                                Modifier.testTag("focused-row"), height = row, viewportWidth = 900.dp,
                                onSelectionFrameTopMeasured = { top = it })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("focused-row").fetchSemanticsNode().boundsInRoot
        assertEquals("Deep rails must land at the stationary viewport anchor", 0f, bounds.top, 0.5f)
        val expectedTop = with(Density(scale)) { HomeRowHeadingHeight.roundToPx() + HomeRowStripPadding.roundToPx() }
        assertEquals("Ring fallback must match the real measured strip", expectedTop, top)
    }
    @Test fun fractionalTvDensityDoesNotAccumulateDriftAfterThirtyTwoRows() = checkAlignment(213f / 160)
    @Test fun hdpiDensityDoesNotAccumulateDriftAfterThirtyTwoRows() = checkAlignment(1.5f)
    @Test fun normalDensityKeepsTheSameAlignment() = checkAlignment(1f)

    @Test fun allTabLayoutsUseTheSameRoundedDistances() {
        assertEquals(-1530f, homeScrollTarget(3, true, true, 375f, 410f, 10f, 381f, 24f, 79f), 0f)
        assertEquals(-1125f, homeScrollTarget(3, true, false, 375f, 410f, 10f, 381f, 24f, 79f), 0f)
        assertEquals(-1249f, homeScrollTarget(3, false, true, 375f, 410f, 10f, 381f, 24f, 79f), 0f)
        assertEquals(-1545f, homeScrollTarget(3, false, false, 375f, 410f, 10f, 381f, 24f, 79f), 0f)
    }
}
