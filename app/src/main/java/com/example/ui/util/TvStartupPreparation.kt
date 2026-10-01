package com.example.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class TvStartupResult(val completed: Boolean, val sessionWarmed: Boolean)

/** One overall deadline; a provider failure never prevents signing in or browsing. */
internal suspend fun prepareTvStartup(
    timeoutMs: Long = 45_000L,
    prepareData: suspend () -> Unit,
    warmSession: suspend () -> Boolean
): TvStartupResult = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val data = async { prepareData() }
        val warm = async {
            try { warmSession() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        data.await()
        TvStartupResult(completed = true, sessionWarmed = warm.await())
    }
} ?: TvStartupResult(completed = false, sessionWarmed = false)
