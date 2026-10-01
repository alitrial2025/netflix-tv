package com.example.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDemandTest {
    @Test fun playPromotesTheHandshakeAlreadyHoldingTheSessionLock() = runTest {
        val demand = SessionDemand()
        val sessionLock = Mutex()
        val nativeFinished = CompletableDeferred<Unit>()
        var handshakes = 0
        var browserAttempts = 0
        var storedCookie: String? = null
        val warmup = launch {
            sessionLock.withLock {
                handshakes++
                nativeFinished.await()
                storedCookie = demand.whileRequested { browserAttempts++; "complete-cookie" }
            }
        }
        runCurrent()
        val play = async { demand.withRequest { sessionLock.withLock { storedCookie } } }
        runCurrent()
        nativeFinished.complete(Unit)
        assertEquals("complete-cookie", play.await())
        warmup.join()
        assertEquals(1, handshakes)
        assertEquals(1, browserAttempts)
        assertFalse(demand.isRequested)
    }

    @Test fun backgroundAloneCannotStartBrowserWork() = runTest {
        val demand = SessionDemand()
        var opened = false
        assertNull(demand.whileRequested { opened = true; "cookie" })
        assertFalse(opened)
    }

    @Test fun leavingPlaybackCancelsPromotedBrowserAndRunsCleanup() = runTest {
        val demand = SessionDemand()
        val foreground = launch { demand.withRequest { awaitCancellation() } }
        runCurrent()
        var cleaned = false
        val background = async {
            demand.whileRequested {
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        runCurrent()
        foreground.cancel()
        foreground.join()
        assertNull(background.await())
        assertTrue(cleaned)
        assertFalse(demand.isRequested)
    }

    @Test fun oneCancelledViewerDoesNotAbandonAnotherViewer() = runTest {
        val demand = SessionDemand()
        val first = launch { demand.withRequest { awaitCancellation() } }
        val second = launch { demand.withRequest { awaitCancellation() } }
        runCurrent()
        first.cancel(); first.join()
        assertTrue(demand.isRequested)
        second.cancel(); second.join()
        assertFalse(demand.isRequested)
    }

    @Test fun deadlineReleasesDemandAndPropagatesCancellation() = runTest {
        val demand = SessionDemand()
        assertNull(withTimeoutOrNull(1_000) { demand.withRequest { awaitCancellation() } })
        assertFalse(demand.isRequested)
    }
}
