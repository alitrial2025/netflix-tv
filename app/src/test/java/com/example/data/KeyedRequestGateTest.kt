package com.example.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KeyedRequestGateTest {
    @Test fun identicalCallsShareCachedWorkButDifferentKeysProceed() = runTest {
        val gate = KeyedRequestGate()
        val ready = CompletableDeferred<Unit>()
        val cache = mutableMapOf<String, Int>()
        var requests = 0
        suspend fun resolve(key: String) = gate.withKey(key) {
            cache[key] ?: run {
                requests++
                if (key == "one") ready.await()
                7.also { cache[key] = it }
            }
        }
        val first = async { resolve("one") }
        val duplicate = async { resolve("one") }
        val other = async { resolve("two") }
        assertEquals(7, other.await())
        ready.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(7, duplicate.await())
        assertEquals(2, requests)
    }
    @Test fun cancellingOwnerAndWaiterDoesNotPoisonNextRequest() = runTest {
        val gate = KeyedRequestGate()
        val started = CompletableDeferred<Unit>()
        val owner = launch { gate.withKey("one") { started.complete(Unit); awaitCancellation() } }
        started.await()
        val waiter = launch { gate.withKey("one") { fail("Cancelled waiter ran") } }
        yield()
        waiter.cancelAndJoin()
        owner.cancelAndJoin()
        assertEquals(9, gate.withKey("one") { 9 })
    }
}
