package com.example.ui.util

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max
import kotlin.math.roundToInt

enum class TvArtworkKind { POSTER, BACKDROP, LOGO }

/** Shared limits for artwork; leave heap space for the catalog, Compose and video decoders. */
object TvImagePolicy {
    private const val MEBIBYTE = 1024 * 1024
    private val tmdbImage = Regex(
        "^(https?://image\\.tmdb\\.org/t/p/)(original|w[1-9][0-9]*)(/[^?#]+)([?#].*)?$",
        RegexOption.IGNORE_CASE
    )
    private val posterWidths = intArrayOf(92, 154, 185, 342, 500, 780)
    private val backdropWidths = intArrayOf(300, 780, 1280)
    private val logoWidths = intArrayOf(45, 92, 154, 185, 300, 500)

    fun isLowMemoryDevice(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager?.isLowRamDevice == true || (manager?.memoryClass ?: 128) <= 128
    }

    fun memoryCacheBytes(maxHeapBytes: Long, isLowRamDevice: Boolean): Int {
        val fraction = if (isLowRamDevice) 0.12 else 0.18
        val ceiling = (if (isLowRamDevice) 16 else 32) * MEBIBYTE
        return (maxHeapBytes.coerceAtLeast(0L) * fraction)
            .coerceAtMost(ceiling.toDouble())
            .toInt()
    }

    /**
     * Bound the compressed source as well as the decoded bitmap. Request.size alone
     * still downloads and scans TMDB originals, often several megabytes per card.
     * Only rewrite TMDB raster URLs; signed mirrors, custom paths and SVGs are left
     * byte-for-byte intact. Alpha is preserved by the requesting logo decoder.
     */
    fun artworkUrl(url: String, widthPx: Int, kind: TvArtworkKind): String {
        val match = tmdbImage.matchEntire(url) ?: return url
        val path = match.groupValues[3]
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in listOf("jpg", "jpeg", "png", "webp")) return url
        val widths = when (kind) {
            TvArtworkKind.POSTER -> posterWidths
            TvArtworkKind.BACKDROP -> backdropWidths
            TvArtworkKind.LOGO -> logoWidths
        }
        val requestedWidth = widthPx.coerceAtLeast(1)
        val sourceWidth = widths.firstOrNull { it >= requestedWidth } ?: widths.last()
        return match.groupValues[1] + "w" + sourceWidth + path + match.groupValues[4]
    }

    /** Use display pixels, not dp or the source image's original (often 4K) dimensions. */
    fun backdropSize(widthPx: Int, heightPx: Int, isLowMemoryDevice: Boolean = false): Pair<Int, Int> {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val maxEdge = if (isLowMemoryDevice) 1280.0 else 1920.0
        val scale = (maxEdge / max(width, height)).coerceAtMost(1.0)
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}
