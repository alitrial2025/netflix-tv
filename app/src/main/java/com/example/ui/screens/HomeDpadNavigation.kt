package com.example.ui.screens

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Move from the requested section even while its focus node is being attached. */
internal fun homeVerticalTarget(
    level: Int,
    direction: Int,
    rowCount: Int,
    hasCategories: Boolean,
    hasBillboard: Boolean
): Int {
    val firstRow = if (hasCategories) 1 else 0
    val lastLevel = if (rowCount > 0) firstRow + rowCount - 1
        else if (hasCategories) 0 else if (hasBillboard) -1 else -2
    val next = (level + direction).coerceIn(-2, lastLevel)
    return if (!hasBillboard && next == -1) {
        if (direction < 0) -2 else if (rowCount > 0) 0 else -2
    } else next
}

internal fun Modifier.handleHomeVerticalNavigation(
    level: () -> Int,
    rowCount: Int,
    hasCategories: Boolean,
    hasBillboard: Boolean,
    onMove: (Int) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val current = level()
    // The navigation bar has its own tab/profile handling.
    if (event.type != KeyEventType.KeyDown || current < -1) return@onPreviewKeyEvent false
    val direction = when (event.nativeKeyEvent.keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN -> 1
        KeyEvent.KEYCODE_DPAD_UP -> -1
        else -> return@onPreviewKeyEvent false
    }
    val next = homeVerticalTarget(current, direction, rowCount, hasCategories, hasBillboard)
    if (next != current) onMove(next)
    true // Consume section edges as well, preventing default focus from escaping.
}
