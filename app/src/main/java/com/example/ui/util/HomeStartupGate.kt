package com.example.ui.util

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Keeps optional startup work out of the first Home layout and remote-driven scrolling. */
object HomeStartupGate {
    private val scheduler = HomeStartupScheduler(SystemClock::uptimeMillis)
    fun configureLowMemory(lowMemory: Boolean) = scheduler.setEntryQuietMs(if (lowMemory) 4_000L else 1_000L)
    fun setPreparing(preparing: Boolean) = scheduler.setPreparing(preparing)
    fun markHomeReady() = scheduler.markHomeReady()
    fun markHomeHidden() = scheduler.markHomeHidden()
    fun onInteraction() = scheduler.onInteraction()
    fun setScrolling(scrolling: Boolean) = scheduler.setScrolling(scrolling)
    suspend fun awaitIdle() = scheduler.awaitIdle()
    suspend fun awaitBrowsingIdle() = scheduler.awaitBrowsingIdle()
}

/** Clock injection lets scheduling be checked without a device or real-time sleeps. */
internal class HomeStartupScheduler(
    private val nowMs: () -> Long,
    private val inputQuietMs: Long = 1_000L,
    private val initialEntryQuietMs: Long = inputQuietMs
) {
    private data class State(
        val homeReady: Boolean = false,
        val preparing: Boolean = false,
        val scrolling: Boolean = false,
        val lastInteractionMs: Long = 0L,
        val homeReadyAtMs: Long = 0L,
        val entryQuietMs: Long = 1_000L
    )

    private val lock = Any()
    private val state = MutableStateFlow(State(lastInteractionMs = nowMs(), entryQuietMs = initialEntryQuietMs))

    fun setEntryQuietMs(quietMs: Long) = synchronized(lock) {
        state.value = state.value.copy(entryQuietMs = quietMs.coerceAtLeast(inputQuietMs))
    }

    fun setPreparing(preparing: Boolean) = synchronized(lock) {
        state.value = state.value.copy(preparing = preparing, lastInteractionMs = nowMs())
    }

    fun markHomeReady() = synchronized(lock) {
        if (!state.value.homeReady) {
            state.value = state.value.copy(homeReady = true, lastInteractionMs = nowMs(), homeReadyAtMs = nowMs())
        }
    }

    fun markHomeHidden() = synchronized(lock) {
        state.value = state.value.copy(homeReady = false, scrolling = false)
    }

    fun onInteraction() = synchronized(lock) {
        state.value = state.value.copy(lastInteractionMs = nowMs())
    }

    fun setScrolling(scrolling: Boolean) = synchronized(lock) {
        if (state.value.scrolling != scrolling) {
            state.value = state.value.copy(scrolling = scrolling, lastInteractionMs = nowMs())
        }
    }

    /** Suspends while Home is absent, rendering its first frame, or accepting continuous input. */
    suspend fun awaitIdle() = awaitQuiet(requireHome = true)

    /** Category previews also use the input gate, without needing a Home screen underneath. */
    suspend fun awaitBrowsingIdle() = awaitQuiet(requireHome = false)

    private suspend fun awaitQuiet(requireHome: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val candidate = state.first { (!requireHome || it.homeReady || it.preparing) && !it.scrolling }
            val remaining = if (candidate.preparing) 0L else maxOf(
                inputQuietMs - (nowMs() - candidate.lastInteractionMs),
                if (requireHome) candidate.entryQuietMs - (nowMs() - candidate.homeReadyAtMs) else 0L
            )
            if (remaining > 0L) {
                delay(remaining)
                continue
            }
            // Input or navigation can arrive between the flow read and this check.
            if (state.value == candidate) return
        }
    }
}
