package com.example.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbImdbIdResolverTest {
    @Test fun concurrentCardsShareTheLookupButNotTheMovieTvNamespace() = runTest {
        val requests = mutableListOf<String>()
        val resolver = TmdbImdbIdResolver(lookup = { kind, id ->
            requests += "$kind:$id"
            delay(100)
            if (kind == "tv") "tt7678620" else "tt1679335"
        }, nowMs = { testScheduler.currentTime })
        val first = async { resolver.resolve("tv", "82728") }
        val second = async { resolver.resolve("tv", "82728") }
        assertEquals("tt7678620", first.await())
        assertEquals(first.await(), second.await())
        assertEquals(listOf("tv:82728"), requests)
        assertEquals("tt1679335", resolver.resolve("movie", "82728"))
        assertEquals(listOf("tv:82728", "movie:82728"), requests)
    }

    @Test fun failedLookupsBackOffAndRecoverWithoutClearingAppCache() = runTest {
        var requests = 0
        val resolver = TmdbImdbIdResolver(lookup = { _, _ ->
            requests++
            if (requests == 1) throw IOException("Offline")
            "tt7678620"
        }, nowMs = { testScheduler.currentTime })
        assertNull(resolver.resolve("tv", "82728"))
        assertNull(resolver.resolve("tv", "82728"))
        assertEquals(1, requests)
        advanceTimeBy(60_000)
        assertEquals("tt7678620", resolver.resolve("tv", "82728"))
        assertEquals(2, requests)
    }

    @Test fun cancelledScreenDoesNotCacheAFalseMissingIdentity() = runTest {
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val resolver = TmdbImdbIdResolver(lookup = { _, _ ->
            requests++
            if (requests == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            "tt7678620"
        }, nowMs = { testScheduler.currentTime })
        val job = launch { resolver.resolve("tv", "82728") }
        started.await()
        job.cancel()
        job.join()
        assertEquals("tt7678620", resolver.resolve("tv", "82728"))
        assertEquals(2, requests)
    }

    @Test fun optionalLookupIsBoundedAndDoesNotSwallowTheScreensTimeout() = runTest {
        var requests = 0
        val resolver = TmdbImdbIdResolver(lookup = { _, _ ->
            requests++
            awaitCancellation()
        }, nowMs = { testScheduler.currentTime })
        val lookup = async { resolver.resolve("tv", "82728") }
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(lookup.isCompleted)
        assertNull(lookup.await())
        assertNull(withTimeoutOrNull(100) { resolver.resolve("tv", "256953") })
        val retry = launch { resolver.resolve("tv", "256953") }
        runCurrent()
        assertEquals(3, requests)
        retry.cancel()
        retry.join()
    }
}
