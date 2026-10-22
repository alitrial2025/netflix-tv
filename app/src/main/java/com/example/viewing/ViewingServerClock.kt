package com.example.viewing

/** Server time advances on a monotonic clock, independent of changes to the device's wall clock. */
internal class ViewingServerClock(private val elapsedMs: () -> Long, private val wallMs: () -> Long = System::currentTimeMillis) {
    private data class Anchor(val serverMs: Long, val elapsedMs: Long)
    @Volatile private var anchor: Anchor? = null
    fun nowMs(): Long = anchor?.let { it.serverMs + (elapsedMs() - it.elapsedMs).coerceAtLeast(0) } ?: wallMs()
    @Synchronized fun observe(serverMs: Long, sent: Long, received: Long) {
        if (serverMs <= 0 || received < sent || received - sent > 10_000) return
        val midpoint = sent + (received - sent) / 2
        val previous = anchor
        if (previous != null && midpoint < previous.elapsedMs) return
        val minimum = previous?.let { it.serverMs + (midpoint - it.elapsedMs).coerceAtLeast(0) } ?: serverMs
        anchor = Anchor(maxOf(serverMs, minimum), midpoint)
    }
}
