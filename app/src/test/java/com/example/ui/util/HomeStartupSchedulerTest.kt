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
}
