package com.example.ui.util

import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig

/** Monotonic stage timings retained in diagnostics, including production builds. */
object RuntimeTiming {
    fun start(): Long = SystemClock.elapsedRealtime()

    fun elapsed(stage: String, startedAt: Long) {
        if (startedAt > 0L) {
            val runtime = Runtime.getRuntime()
            val message = "$stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt}" +
                " javaHeapKiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024}" +
                " nativeHeapKiB=${android.os.Debug.getNativeHeapAllocatedSize() / 1024}"
            AppDiagnosticsLogger.event("Timing", message)
            if (BuildConfig.DEBUG) Log.d("RuntimeTiming", message)
        }
    }
}
