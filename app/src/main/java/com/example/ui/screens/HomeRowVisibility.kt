package com.example.ui.screens

/** Keep the viewport, optional overscan, and adjacent D-pad targets composed. */
internal fun shouldComposeHomeRow(
    index: Int,
    firstRowTopPx: Float,
    rowHeightPx: Float,
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
    focusedRowIndex: Int,
    overscanRows: Int = 1
): Boolean {
    if (rowHeightPx <= 0f || index < 0) return false
    if (focusedRowIndex >= 0 && index in (focusedRowIndex - 1)..(focusedRowIndex + 1)) return true
    val top = firstRowTopPx + index * rowHeightPx + scrollOffsetPx
    val overscanPx = rowHeightPx * overscanRows.coerceAtLeast(0)
    return top < viewportHeightPx + overscanPx && top + rowHeightPx > -overscanPx
}

/** Retain only the first Home rail across header/hero/category focus, without mounting deep rows. */
internal fun shouldRetainHomeEntryRow(index: Int, focusLevel: Int, prepared: Boolean, isKidProfile: Boolean, activeTab: String): Boolean =
    index == 0 && prepared && focusLevel in -2..1 && !isKidProfile && activeTab == "Home"
