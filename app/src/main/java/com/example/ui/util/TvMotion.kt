package com.example.ui.util

import kotlin.math.roundToInt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Decorative durations stay unchanged; browsing has a shared, unhurried glide. */
object TvMotion {
    private const val DurationScale = 1.6f

    fun duration(baseMillis: Int): Int = (baseMillis * DurationScale).roundToInt()

    // Spring duration follows 1 / sqrt(stiffness). Scaling stiffness this way
    // keeps a small glide while retaining velocity when the remote is held down.
    fun stiffness(baseStiffness: Float): Float = baseStiffness / (DurationScale * DurationScale)

    /** Home sections and card rows use the same response when the remote is held. */
    fun carouselStiffness(): Float = stiffness(430f)

    /** The focus border retains its existing pace while the content glides behind it. */
    fun focusRingStiffness(): Float = stiffness(600f)

    fun <T> carouselSpring(visibilityThreshold: T): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = carouselStiffness(),
        visibilityThreshold = visibilityThreshold
    )

    fun <T> focusRingSpring(visibilityThreshold: T): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = focusRingStiffness(),
        visibilityThreshold = visibilityThreshold
    )
}
