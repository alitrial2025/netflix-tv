package com.example.ui.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Colour keeps its current velocity when focus changes again during a blend. */
@Composable
internal fun rememberTvAmbientColor(target: Color, label: String): State<Color> =
    animateColorAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = TvMotion.stiffness(100f)
        ),
        label = label
    )

/** Keep genre accents rich enough to see, but comfortably behind white TV text. */
internal fun ambientTintForAccent(accent: Color): Color = Color(
    red = accent.red * 0.31f,
    green = accent.green * 0.31f,
    blue = accent.blue * 0.31f
)

/** The shader depends on viewport size, never on the animated colour. */
@Composable
internal fun Modifier.tvAmbientBackground(colour: State<Color>): Modifier {
    val background = remember(colour) {
        Modifier.drawWithCache {
            val shading = Brush.verticalGradient(
                0f to Color.Transparent,
                0.35f to Color.Black.copy(alpha = 0.12f),
                0.72f to Color.Black.copy(alpha = 0.28f),
                1f to Color.Black.copy(alpha = 0.44f),
                startY = 0f,
                endY = size.height.coerceAtLeast(1f)
            )
            onDrawBehind {
                val current = colour.value
                drawRect(current)
                // Once browsing reaches the black rows, the idle background is one fill.
                if (current != Color.Black) drawRect(shading)
            }
        }
    }
    return then(background)
}
