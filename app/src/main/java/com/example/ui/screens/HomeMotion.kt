package com.example.ui.screens

import kotlin.math.floor

/** The viewport, row opacity, and focus ring all follow the same animated focus position. */
internal fun homeScrollOffset(focusPosition: Float, targetForLevel: (Int) -> Float): Float {
    val lower = floor(focusPosition).toInt()
    val fraction = focusPosition - lower
    val start = targetForLevel(lower)
    return start + (targetForLevel(lower + 1) - start) * fraction
}

internal fun homeBillboardAlpha(focusPosition: Float): Float = (-focusPosition).coerceIn(0f, 1f)

internal fun homeCategoriesAlpha(focusPosition: Float): Float = when {
    focusPosition < 0f -> 0.65f + 0.35f * (focusPosition + 1f).coerceIn(0f, 1f)
    else -> (1f - focusPosition).coerceIn(0f, 1f)
}
