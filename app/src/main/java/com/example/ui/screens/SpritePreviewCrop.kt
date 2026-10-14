package com.example.ui.screens

internal data class SpritePreviewCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/** Center-crop the original tile instead of stretching faces to fit the preview. */
internal fun spritePreviewCrop(
    cue: ThumbnailCue, sheetWidth: Int, sheetHeight: Int, targetWidth: Float, targetHeight: Float
): SpritePreviewCrop? {
    if (cue.x < 0 || cue.y < 0 || cue.w < 0 || cue.h < 0 || targetWidth <= 0 || targetHeight <= 0) return null
    // A VTT cue without #xywh refers to the entire image.
    val wholeImage = cue.x == 0 && cue.y == 0 && cue.w == 0 && cue.h == 0
    if (!wholeImage && (cue.w == 0 || cue.h == 0)) return null
    val width = if (wholeImage) sheetWidth else cue.w.coerceAtMost(sheetWidth - cue.x)
    val height = if (wholeImage) sheetHeight else cue.h.coerceAtMost(sheetHeight - cue.y)
    if (width <= 0 || height <= 0) return null
    val aspect = targetWidth / targetHeight
    val cropWidth = minOf(width, (height * aspect).toInt()).coerceAtLeast(1)
    val cropHeight = minOf(height, (width / aspect).toInt()).coerceAtLeast(1)
    return SpritePreviewCrop(cue.x + (width - cropWidth) / 2, cue.y + (height - cropHeight) / 2, cropWidth, cropHeight)
}
