package com.example.ui.util

import kotlin.random.Random

/** Shared by every preview surface; navigating to another row cannot reset the budget. */
internal class HomePreviewRequestBudget(private val nowMs: () -> Long) {
    private var nextRequestAtMs = 0L
    private var blockedUntilMs = 0L
    private var consecutiveLimits = 0

    fun waitMillis(): Long = (maxOf(nextRequestAtMs, blockedUntilMs) - nowMs()).coerceAtLeast(0L)
    fun cooldownMillis(): Long = (blockedUntilMs - nowMs()).coerceAtLeast(0L)

    fun onResolutionStarted() { nextRequestAtMs = nowMs() + 10_000L }
    fun onSuccess() { consecutiveLimits = 0 }
    fun onFailure() { nextRequestAtMs = maxOf(nextRequestAtMs, nowMs() + 15_000L) }

    fun onRateLimited(retryAfterMs: Long? = null) {
        consecutiveLimits = (consecutiveLimits + 1).coerceAtMost(4)
        val backoffMs = (60_000L * (1L shl (consecutiveLimits - 1))).coerceAtMost(300_000L)
        blockedUntilMs = maxOf(blockedUntilMs, nowMs() + maxOf(backoffMs, retryAfterMs ?: 0L))
    }
}

/** Leave a full minute before the end; short clips can only start at zero. */
internal fun homePreviewStartMs(durationMs: Long, cachedStartMs: Long?, random: Random = Random.Default): Long {
    val latestStartMs = (durationMs - 60_000L).coerceAtLeast(0L)
    cachedStartMs?.let { return it.coerceIn(0L, latestStartMs) }
    val lastMinute = latestStartMs / 60_000L
    if (lastMinute < 1L) return 0L
    return random.nextLong(1L, lastMinute + 1L) * 60_000L
}
