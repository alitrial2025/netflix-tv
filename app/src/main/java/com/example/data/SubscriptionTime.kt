package com.example.data

import android.content.Context
import android.os.SystemClock

/** Stops a device clock rollback from adding paid time during normal app use. */
class MonotonicSubscriptionClock(
    initialCheckpoint: Long = 0L,
    private val wallTime: () -> Long = { System.currentTimeMillis() },
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() },
    private val persist: (Long) -> Unit = {}
) {
    private var checkpoint = maxOf(0L, initialCheckpoint)
    private var anchor = maxOf(wallTime(), checkpoint)
    private var anchorElapsed = elapsedTime()
    private var persisted = checkpoint

    @Synchronized fun now(): Long {
        val elapsed = (elapsedTime() - anchorElapsed).coerceAtLeast(0L)
        checkpoint = maxOf(checkpoint, wallTime(), anchor + elapsed)
        if (checkpoint - persisted >= 60_000L) { persist(checkpoint); persisted = checkpoint }
        return checkpoint
    }

    /** A TLS-authenticated gateway Date is current trusted time, unlike a cached record. */
    @Synchronized fun synchronize(serverNow: Long) {
        require(serverNow > 0)
        checkpoint = serverNow
        anchor = serverNow
        anchorElapsed = elapsedTime()
        persist(checkpoint)
        persisted = checkpoint
    }

    /** A Firestore server timestamp is a lower bound; it is not necessarily today's time. */
    @Synchronized fun observeServerTimestamp(timestamp: Long) {
        if (timestamp <= checkpoint) return
        checkpoint = timestamp
        anchor = maxOf(now(), timestamp)
        anchorElapsed = elapsedTime()
        persist(checkpoint)
        persisted = checkpoint
    }
}

object SubscriptionTime {
    @Volatile private var clock = MonotonicSubscriptionClock()
    @Volatile private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences("subscription_time", Context.MODE_PRIVATE)
        clock = MonotonicSubscriptionClock(prefs.getLong("checkpoint", 0L),
            persist = { prefs.edit().putLong("checkpoint", it).apply() })
        initialized = true
    }
    fun now() = clock.now()
    fun synchronize(serverNow: Long) = clock.synchronize(serverNow)
    fun observeServerTimestamp(timestamp: Long) = clock.observeServerTimestamp(timestamp)
}
