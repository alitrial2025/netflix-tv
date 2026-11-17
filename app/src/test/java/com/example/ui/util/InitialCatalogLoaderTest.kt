package com.example.ui.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialCatalogLoaderTest {
    @Test fun firstUsefulFeedDoesNotWaitForSlowOrEmptyFeeds() = runTest {
        val publications = mutableListOf<Pair<Long, List<Pair<String, List<Int>>>>>()
        var finalRows: List<Pair<String, List<Int>>>? = null
        launch {
            finalRows = loadInitialCatalog(listOf(
                "slow" to { delay(20_000); listOf(1) },
                "empty" to { delay(100); emptyList() },
                "fast" to { delay(200); listOf(3) }
            ), 2) { publications += testScheduler.currentTime to it }
        }
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(300L to listOf("fast" to listOf(3))), publications)
        assertNull(finalRows)
        advanceUntilIdle()
        assertEquals(listOf("slow" to listOf(1), "fast" to listOf(3)), finalRows)
        assertEquals(1, publications.size)
    }

    @Test fun cancellationStopsPendingRequestsAndDoesNotPublishLateResults() = runTest {
        var active = 0
        var maximum = 0
        var published = false
        val job = launch {
            loadInitialCatalog((1..4).map { n -> "$n" to suspend {
                active++; maximum = maxOf(maximum, active)
                try { awaitCancellation() } finally { active-- }
                @Suppress("UNREACHABLE_CODE") listOf(n)
            } }, 2) { published = true }
        }
        runCurrent()
        assertEquals(2, maximum)
        job.cancel(); runCurrent()
        assertEquals(0, active)
        assertFalse(published)
    }
}
