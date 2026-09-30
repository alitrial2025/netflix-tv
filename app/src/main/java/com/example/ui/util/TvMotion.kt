package com.example.ui.util

import kotlin.math.roundToInt

/** A 1.6x motion duration; focus and input still update on the first frame. */
object TvMotion {
    private const val DurationScale = 1.6f

    fun duration(baseMillis: Int): Int = (baseMillis * DurationScale).roundToInt()

    // Spring duration follows 1 / sqrt(stiffness). Scaling stiffness this way
    // keeps a small glide while retaining velocity when the remote is held down.
    fun stiffness(baseStiffness: Float): Float = baseStiffness / (DurationScale * DurationScale)
}
