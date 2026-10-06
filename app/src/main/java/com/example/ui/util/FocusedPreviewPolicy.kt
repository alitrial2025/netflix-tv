package com.example.ui.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A short focus debounce is part of the total optional-preview startup budget. */
internal object FocusedPreviewPolicy {
    const val FOCUS_SETTLE_MS = 1_500L
    const val STARTUP_TIMEOUT_MS = 15_000L
    const val START_BUFFER_MS = 700

    fun remainingStartupMs(focusedAtMs: Long, nowMs: Long): Long =
        (STARTUP_TIMEOUT_MS - (nowMs - focusedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)

    suspend fun <T> resolve(startupBudgetMs: Long = STARTUP_TIMEOUT_MS, block: suspend () -> T): T? =
        withTimeoutOrNull(startupBudgetMs.coerceAtLeast(0L)) {
            delay(FOCUS_SETTLE_MS)
            block()
        }
}
