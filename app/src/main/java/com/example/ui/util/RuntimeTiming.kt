package com.example.ui.util

import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig

/** Debug-only monotonic markers. Never include account data or stream URLs. */
object RuntimeTiming {
    fun start(): Long = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L

    fun elapsed(stage: String, startedAt: Long) {
        if (BuildConfig.DEBUG && startedAt > 0L) {
            Log.d("RuntimeTiming", "$stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }
}
