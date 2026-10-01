package com.example.ui.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStartupSchedulerTest {
    @Test fun discoveryWaitsForRenderedHomeAndQuietInput() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        var started = false
        launch { gate.awaitIdle(); started = true }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(started)
        gate.markHomeReady()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(started)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(started)
    }

    @Test fun remoteRepeatAndScrollKeepPostponingBackgroundWork() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        gate.markHomeReady()
        var started = false
        launch { gate.awaitIdle(); started = true }
        advanceTimeBy(800)
        gate.onInteraction()
        advanceTimeBy(800)
        gate.setScrolling(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(started)
        gate.setScrolling(false)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(started)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(started)
    }

    @Test fun leavingHomeBlocksDiscoveryButNotCategoryPreviews() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        gate.markHomeReady()
        var discoveryStarted = false
        val discovery = launch { gate.awaitIdle(); discoveryStarted = true }
        gate.markHomeHidden()
        var previewReady = false
        launch { gate.awaitBrowsingIdle(); previewReady = true }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(discoveryStarted)
        assertTrue(previewReady)
        discovery.cancel()
    }

    @Test fun leavingDuringQuietCountdownRequiresTheNextHomeFrame() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        gate.markHomeReady()
        var started = false
        val warmup = launch { gate.awaitIdle(); started = true }
        advanceTimeBy(900)
        gate.markHomeHidden()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(started)
        gate.markHomeReady()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(started)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(started)
        warmup.join()
    }

    @Test fun cancelledBackgroundWaitDoesNotHoldTheForegroundGate() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        val background = launch { gate.awaitIdle(); error("Home never became ready") }
        runCurrent()
        background.cancel()
        background.join()
        var foregroundReady = false
        launch { gate.awaitBrowsingIdle(); foregroundReady = true }
        advanceTimeBy(999)
        runCurrent()
        assertFalse(foregroundReady)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(foregroundReady)
    }
    @Test fun splashPreparationAllowsDiscoveryButFinishingItRequiresRenderedHomeAgain() = runTest {
        val gate = HomeStartupScheduler({ testScheduler.currentTime })
        gate.setPreparing(true)
        var duringSplash = false
        launch { gate.awaitIdle(); duringSplash = true }
        runCurrent()
        assertTrue(duringSplash)
        gate.setPreparing(false)
        var afterSplash = false
        val waiting = launch { gate.awaitIdle(); afterSplash = true }
        advanceTimeBy(5_000); runCurrent()
        assertFalse(afterSplash)
        gate.markHomeReady()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(afterSplash)
        waiting.cancel()
    }

}
