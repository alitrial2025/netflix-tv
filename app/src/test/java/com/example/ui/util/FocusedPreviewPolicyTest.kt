package com.example.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusedPreviewPolicyTest {
    @Test fun movingFocusCancelsBeforeAnyProviderRequest() = runTest {
        var requests = 0
        val preview = launch { FocusedPreviewPolicy.resolve { requests++; "stream" } }
        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(0, requests)
        preview.cancelAndJoin()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(0, requests)
    }

    @Test fun stableFocusResolvesOnceAndCanShowAFrameInsideTheTargetWindow() = runTest {
        var requests = 0
        val frame = CompletableDeferred<String?>()
        launch {
            frame.complete(FocusedPreviewPolicy.resolve {
                requests++
                delay(7_000L) // provider discovery
                delay(2_000L) // first frame buffering
                "visible"
            })
        }
        advanceTimeBy(1_499L)
        assertEquals(0, requests)
        advanceTimeBy(9_001L)
        runCurrent()
        assertEquals("visible", frame.await())
        assertEquals(1, requests)
        assertEquals(10_500L, testScheduler.currentTime)
    }

    @Test fun aHungPreviewIsCancelledAtTheDeadlineWithoutRetrying() = runTest {
        var requests = 0
        var cancelled = false
        val result = CompletableDeferred<String?>()
        launch {
            result.complete(FocusedPreviewPolicy.resolve {
                requests++
                try { delay(60_000L); "late" } finally { cancelled = true }
            })
        }
        advanceTimeBy(15_000L)
        runCurrent()
        assertNull(result.await())
        assertTrue(cancelled)
        assertEquals(1, requests)
    }

    @Test fun cleanupAndResolutionCannotResetTheFirstFrameDeadline() = runTest {
        val focusedAt = testScheduler.currentTime
        delay(4_000L) // previous preview cleanup
        val remaining = FocusedPreviewPolicy.remainingStartupMs(focusedAt, testScheduler.currentTime)
        assertEquals(11_000L, remaining)
        val result = FocusedPreviewPolicy.resolve(remaining) { delay(60_000L); "late" }
        assertNull(result)
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(0L, FocusedPreviewPolicy.remainingStartupMs(focusedAt, testScheduler.currentTime))
    }
}
