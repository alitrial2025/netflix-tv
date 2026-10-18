package com.example.ui.screens.details

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [34], application = Application::class)
class DetailsMetadataLineTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun metadataWrapsWithoutClippingBadgesAtNarrowWidthAndLargeFont() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.4f)) {
                Box(Modifier.width(320.dp)) {
                    DetailsMetadataLine("Series", "Drama & Fantasy", "2000", "12 Seasons", "TV-14", false,
                        Modifier.testTag("metadata"))
                }
            }
        }
        val parent = rule.onNodeWithTag("metadata").fetchSemanticsNode().boundsInRoot
        val labels = listOf("Series", "Drama & Fantasy", "2000", "12 Seasons", "TV-14", "AD)))", "CC")
        val bounds = labels.map { label ->
            val node = rule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label escapes available width", node.left >= parent.left && node.right <= parent.right)
            node
        }
        assertTrue("Badges should flow onto another line", bounds.last().top > bounds.first().top)
    }
}
