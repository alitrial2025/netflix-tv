package com.example.ui.util

/** Keep decoder and surface allocations within the budget of older TV chipsets. */
internal object TvPlaybackPolicy {
    fun needsCompatibilityMode(lowMemory: Boolean, sdkInt: Int): Boolean = lowMemory || sdkInt <= 25

    data class VideoLimits(val width: Int, val height: Int, val bitrate: Int, val frameRate: Int)

    fun videoLimits(compatibilityMode: Boolean, planHeight: Int, trailer: Boolean = false): VideoLimits {
        val allowedHeight = if (trailer) 1080 else planHeight.coerceAtLeast(480)
        return if (compatibilityMode) VideoLimits(1280, minOf(720, allowedHeight), 3_000_000, 30)
        else VideoLimits(Int.MAX_VALUE, allowedHeight, Int.MAX_VALUE, Int.MAX_VALUE)
    }
}
