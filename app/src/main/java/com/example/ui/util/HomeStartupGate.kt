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
    fun setPreparing(preparing: Boolean) = scheduler.setPreparing(preparing)
    fun markHomeReady() = scheduler.markHomeReady()
    fun markHomeHidden() = scheduler.markHomeHidden()
    fun onInteraction() = scheduler.onInteraction()
    fun setScrolling(scrolling: Boolean) = scheduler.setScrolling(scrolling)
    fun setTabTransitioning(transitioning: Boolean) = scheduler.setTabTransitioning(transitioning)
    suspend fun awaitIdle() = scheduler.awaitIdle()
    suspend fun awaitBrowsingIdle() = scheduler.awaitBrowsingIdle()
}

/** Clock injection lets scheduling be checked without a device or real-time sleeps. */
internal class HomeStartupScheduler(
    private val nowMs: () -> Long,
    private val inputQuietMs: Long = 1_000L
) {
    private data class State(
        val homeReady: Boolean = false,
        val preparing: Boolean = false,
        val scrolling: Boolean = false,
        val tabTransitioning: Boolean = false,
        val lastInteractionMs: Long = 0L
    )

    private val lock = Any()
    private val state = MutableStateFlow(State(lastInteractionMs = nowMs()))

    fun setPreparing(preparing: Boolean) = synchronized(lock) {
        state.value = state.value.copy(preparing = preparing, lastInteractionMs = nowMs())
    }

    fun markHomeReady() = synchronized(lock) {
        if (!state.value.homeReady) {
            state.value = state.value.copy(homeReady = true, lastInteractionMs = nowMs())
        }
    }

    fun markHomeHidden() = synchronized(lock) {
        state.value = state.value.copy(homeReady = false, scrolling = false, tabTransitioning = false)
    }

    fun onInteraction() = synchronized(lock) {
        state.value = state.value.copy(lastInteractionMs = nowMs())
    }

    fun setScrolling(scrolling: Boolean) = synchronized(lock) {
        if (state.value.scrolling != scrolling) {
            state.value = state.value.copy(scrolling = scrolling, lastInteractionMs = nowMs())
        }
    }

    // A departing rail can finish its scroll while the new tab is still sliding.
    // Separate motion sources prevent either one from unblocking optional work.
    fun setTabTransitioning(transitioning: Boolean) = synchronized(lock) {
        if (state.value.tabTransitioning != transitioning) {
            state.value = state.value.copy(tabTransitioning = transitioning, lastInteractionMs = nowMs())
        }
    }

    /** Suspends while Home is absent, rendering its first frame, or accepting continuous input. */
    suspend fun awaitIdle() = awaitQuiet(requireHome = true)

    /** Category previews also use the input gate, without needing a Home screen underneath. */
    suspend fun awaitBrowsingIdle() = awaitQuiet(requireHome = false)

    private suspend fun awaitQuiet(requireHome: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val candidate = state.first { (!requireHome || it.homeReady || it.preparing) && !it.scrolling && !it.tabTransitioning }
            val remaining = if (candidate.preparing) 0L else inputQuietMs - (nowMs() - candidate.lastInteractionMs)
            if (remaining > 0L) {
                delay(remaining)
                continue
            }
            // Input or navigation can arrive between the flow read and this check.
            if (state.value == candidate) return
        }
    }
}
