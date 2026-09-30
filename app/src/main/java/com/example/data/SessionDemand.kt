package com.example.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** A foreground media request can promote an existing native session handshake. */
internal class SessionDemand {
    private val lock = Any()
    private val users = MutableStateFlow(0)
    val isRequested: Boolean get() = users.value > 0

    suspend fun <T> withRequest(block: suspend () -> T): T {
        synchronized(lock) { users.value += 1 }
        try {
            return block()
        } finally {
            synchronized(lock) { users.value -= 1 }
        }
    }

    /** A promoted background WebView must stop when its last foreground viewer leaves. */
    suspend fun <T> whileRequested(block: suspend () -> T): T? = coroutineScope {
        if (!isRequested) return@coroutineScope null
        val work = async { block() }
        val abandoned = async { users.first { it == 0 } }
        try {
            select {
                work.onAwait { it }
                abandoned.onAwait { null }
            }
        } finally {
            work.cancel()
            abandoned.cancel()
        }
    }
}
