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
}
