package com.example.data

/** Share provider backoff across preview, Details, Player and session warmup. */
class PlaybackRequestCooldown(private val nowMs: () -> Long) {
    private var blockedUntilMs = 0L
    private var strikes = 0
    private var lastLimitMs = 0L

    @Synchronized fun remainingMs(): Long = (blockedUntilMs - nowMs()).coerceAtLeast(0L)

    @Synchronized fun onRateLimited(retryAfterMs: Long? = null): Long {
        val now = nowMs()
        // Concurrent Player/preview failures describe one block, not several strikes.
        if (blockedUntilMs > now) {
            blockedUntilMs = maxOf(blockedUntilMs, now + (retryAfterMs?.coerceIn(0L, 86_400_000L) ?: 0L))
            return blockedUntilMs - now
        }
        if (now - lastLimitMs > 10 * 60_000L) strikes = 0
        strikes = (strikes + 1).coerceAtMost(4)
        lastLimitMs = now
        val delay = maxOf((60_000L shl (strikes - 1)).coerceAtMost(300_000L),
            retryAfterMs?.coerceIn(0L, 86_400_000L) ?: 0L)
        blockedUntilMs = maxOf(blockedUntilMs, now + delay)
        return (blockedUntilMs - now).coerceAtLeast(0L)
    }
}
