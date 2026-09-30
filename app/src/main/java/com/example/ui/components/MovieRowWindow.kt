package com.example.ui.components

/** Keep a bounded poster window around the rendered position, even during rapid input. */
internal fun movieRowWindow(
    animatedFloor: Int,
    posterSlots: Int,
    movieCount: Int,
    isInfinite: Boolean,
    includePrevious: Boolean = true
): IntRange {
    if (movieCount <= 0) return IntRange.EMPTY
    val start = animatedFloor - if (includePrevious) 1 else 0
    val end = animatedFloor + posterSlots.coerceAtLeast(1)
    return if (isInfinite) start..end
    else start.coerceAtLeast(0)..end.coerceAtMost(movieCount - 1)
}
