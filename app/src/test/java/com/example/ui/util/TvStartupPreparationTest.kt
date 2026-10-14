package com.example.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvStartupPreparationTest {
    @Test fun coldPreparationRunsDataAndHandshakeInParallelAndWaitsForBoth() = runTest {
        var result: TvStartupResult? = null
        launch { result = prepareTvStartup(prepareData = { delay(30_000) }, warmSession = { delay(38_000); true }) }
        advanceTimeBy(37_999); runCurrent()
        assertNull(result)
        advanceTimeBy(1); runCurrent()
        assertEquals(TvStartupResult(true, true), result)
        assertEquals(38_000L, testScheduler.currentTime)
    }
    @Test fun cachedAndGuestStartupDoNotForceAFortyFiveSecondWait() = runTest {
        assertEquals(TvStartupResult(true, true), prepareTvStartup(prepareData = {}, warmSession = { true }))
        assertEquals(TvStartupResult(true, false), prepareTvStartup(prepareData = {}, warmSession = { false }))
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun deadlineCancelsAnUnfinishedHandshakeAndReleasesStartup() = runTest {
        var cancelled = false
        var result: TvStartupResult? = null
        launch { result = prepareTvStartup(prepareData = {}, warmSession = { try { awaitCancellation() } finally { cancelled = true } }) }
        advanceTimeBy(44_999); runCurrent(); assertNull(result)
        advanceTimeBy(1); runCurrent()
        assertEquals(TvStartupResult(false, false), result)
        assertTrue(cancelled)
    }
    @Test fun networkFailureDoesNotPreventOfflineBrowsing() = runTest {
        assertEquals(TvStartupResult(true, false), prepareTvStartup(prepareData = {}, warmSession = { error("offline") }))
    }
    @Test fun leavingTheOwnerScopeCancelsBothBranchesWithoutSwallowingCancellation() = runTest {
        var dataCancelled = false
        var warmCancelled = false
        var returned = false
        val preparation = launch {
            prepareTvStartup(prepareData = { try { awaitCancellation() } finally { dataCancelled = true } },
                warmSession = { try { awaitCancellation() } finally { warmCancelled = true } })
            returned = true
        }
        runCurrent(); preparation.cancel(); runCurrent()
        assertTrue(dataCancelled); assertTrue(warmCancelled); assertFalse(returned)
    }
    @Test fun entryAdmissionFinishesBeforeDataOrProviderWorkStarts() = runTest {
        val events = mutableListOf<String>()
        val result = prepareTvStartup(
            admitSession = { events += "entry-start"; delay(5_000); events += "entry-ready" },
            prepareData = { assertEquals("entry-ready", events.last()); events += "data" },
            warmSession = { assertTrue("entry-ready" in events); events += "provider"; true }
        )
        assertEquals(TvStartupResult(true, true), result)
        assertEquals(listOf("entry-start", "entry-ready"), events.take(2))
    }

    @Test fun failedEntryNeverStartsProviderRequestsAndCanBeRetried() = runTest {
        var providerCalls = 0
        assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                prepareTvStartup(admitSession = { throw java.io.IOException("Offline") },
                    prepareData = {}, warmSession = { providerCalls++; true })
            }
        }
        assertEquals(0, providerCalls)
        assertEquals(TvStartupResult(true, true), prepareTvStartup(admitSession = {},
            prepareData = {}, warmSession = { providerCalls++; true }))
        assertEquals(1, providerCalls)
    }

}
