package com.example.ui.screens.player

import com.example.ui.screens.ThumbnailCue

/** VTT intervals are half open: the next frame owns its exact start time. */
internal fun seekPreviewIndex(cues: List<ThumbnailCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (cues[mid].startMs <= timeMs) low = mid + 1 else high = mid - 1
    }
    return high.coerceIn(0, cues.lastIndex)
}

internal fun seekPreviewWindowStart(index: Float, count: Int): Float =
    (index - 1f).coerceIn(0f, (count - 3).coerceAtLeast(0).toFloat())
