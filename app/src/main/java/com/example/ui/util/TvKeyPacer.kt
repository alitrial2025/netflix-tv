package com.example.ui.util

import android.os.SystemClock

/** First D-pad taps respond at once; a sustained hold gives each card time to appear. */
class TvKeyPacer {
    private var lastAcceptedMs = 0L
    private var lastDirection = 0
    private var burstLength = 0

    fun accept(
        direction: Int,
        nowMs: Long = SystemClock.uptimeMillis(),
        repeatCount: Int = -1
    ): Boolean {
        val continuing = direction == lastDirection && nowMs - lastAcceptedMs <= 500L
        val minimumGap = when {
            // Android reports a new physical press as repeatCount == 0.
            // Never drop fast taps; throttle only the repeats of a held key.
            repeatCount == 0 || !continuing -> 0L
            repeatCount > 0 -> 120L
            burstLength < 2 -> 85L
            burstLength < 4 -> 135L
            else -> 210L
        }
        if (continuing && nowMs - lastAcceptedMs < minimumGap) return false
        lastAcceptedMs = nowMs
        lastDirection = direction
        burstLength = if (continuing && repeatCount != 0) (burstLength + 1).coerceAtMost(5) else 1
        return true
    }
}
