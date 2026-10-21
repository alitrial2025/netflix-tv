package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val HomeRowHeadingHeight = 36.dp
internal val HomeRowStripPadding = 4.dp

/** One geometry source for the moving rows and the stationary selection frame. */
@Immutable
data class HomeLayoutMetrics(
    val billboardHeight: Dp,
    val kidsHeroHeight: Dp,
    val cardHeight: Dp,
    val portraitWidth: Dp,
    val expandedWidth: Dp,
    val rowHeight: Dp,
    val categoriesHeight: Dp,
    val categoryWidth: Dp
)

@Composable
fun rememberHomeLayoutMetrics(): HomeLayoutMetrics {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val screenHeight = configuration.screenHeightDp.dp
        val cardHeight = (screenHeight * 0.48f).coerceIn(220.dp, 320.dp)
        HomeLayoutMetrics(
            billboardHeight = screenHeight * 0.76f,
            kidsHeroHeight = screenHeight * 0.74f,
            cardHeight = cardHeight,
            portraitWidth = cardHeight * (2f / 3f),
            expandedWidth = cardHeight * (16f / 9f),
            rowHeight = cardHeight + 128.dp,
            categoriesHeight = (screenHeight * 0.135f).coerceIn(64.dp, 96.dp),
            categoryWidth = ((configuration.screenWidthDp.dp - 32.dp) / 7.2f).coerceAtLeast(108.dp)
        )
    }
}

/** Distances are sums of rounded child heights, just as Compose places those children. */
internal fun homeScrollTarget(
    focusLevel: Int, kids: Boolean, home: Boolean, row: Float,
    billboard: Float, billboardGap: Float, kidsHero: Float, kidsGap: Float, categories: Float
): Float = when {
    focusLevel < 0 -> 0f
    kids && home -> -(kidsHero + kidsGap) - row * focusLevel
    kids -> -row * focusLevel
    home && focusLevel == 0 -> -(billboard + billboardGap)
    home -> -(billboard + billboardGap + categories) - row * (focusLevel - 1)
    else -> -(billboard + billboardGap) - row * focusLevel
}
