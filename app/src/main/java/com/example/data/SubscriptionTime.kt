package com.example.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Elapsed time, rather than an editable wall clock, advances verified paid time. */
class MonotonicSubscriptionClock(
    initialCheckpoint: Long = 0L,
    private val wallTime: () -> Long = { System.currentTimeMillis() },
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() },
    private val persist: (Long) -> Unit = {},
    restoredElapsed: Long? = null,
    initiallyTrusted: Boolean = false,
    private val persistState: (Long, Long, Boolean) -> Unit = { _, _, _ -> }
) {
    private var checkpoint = maxOf(0L, initialCheckpoint)
    private val restored = restoredElapsed?.takeIf { it >= 0L && it <= elapsedTime() && checkpoint > 0L }
    private var trusted = initiallyTrusted && restored != null
    private var anchor = if (restored != null) checkpoint else maxOf(wallTime(), checkpoint)
    private var anchorElapsed = restored ?: elapsedTime()
    private var persisted = checkpoint

    private fun save(elapsed: Long) {
        persist(checkpoint)
        persistState(checkpoint, elapsed, trusted)
        persisted = checkpoint
    }

    @Synchronized fun now(): Long {
        val currentElapsed = elapsedTime()
        val elapsed = (currentElapsed - anchorElapsed).coerceAtLeast(0L)
        checkpoint = maxOf(checkpoint, anchor + elapsed, if (trusted) 0L else wallTime())
        if (checkpoint - persisted >= 60_000L) save(currentElapsed)
        return checkpoint
    }

    /** Only current TLS server evidence may correct an incorrect local date. */
    @Synchronized fun synchronize(serverNow: Long) {
        require(serverNow in 1L..8_640_000_000_000_000L)
        checkpoint = serverNow
        anchor = serverNow
        anchorElapsed = elapsedTime()
        trusted = true
        save(anchorElapsed)
    }

    /** A record timestamp is a lower bound, not necessarily today's time. */
    @Synchronized fun observeServerTimestamp(timestamp: Long) {
        if (timestamp <= now()) return
        checkpoint = timestamp
        anchor = timestamp
        anchorElapsed = elapsedTime()
        save(anchorElapsed)
    }
}

object SubscriptionTime {
    @Volatile private var clock = MonotonicSubscriptionClock()
    @Volatile private var initialized = false
    private val refreshMutex = Mutex()
    private var retryAfterElapsed = Long.MIN_VALUE
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences("subscription_time", Context.MODE_PRIVATE)
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val sameBoot = boot >= 0 && prefs.getInt("boot", -2) == boot
        clock = MonotonicSubscriptionClock(prefs.getLong("checkpoint", 0L),
            restoredElapsed = if (sameBoot && prefs.contains("elapsed")) prefs.getLong("elapsed", 0L) else null,
            initiallyTrusted = sameBoot && prefs.getBoolean("trusted", false),
            persistState = { time, elapsed, trusted -> prefs.edit().putLong("checkpoint", time)
                .putLong("elapsed", elapsed).putInt("boot", boot).putBoolean("trusted", trusted).apply() })
        initialized = true
    }
    fun now() = clock.now()
    fun synchronize(serverNow: Long) = clock.synchronize(serverNow)
    fun observeServerTimestamp(timestamp: Long) = clock.observeServerTimestamp(timestamp)

    /** Optional entry-time synchronization; transport failure does not revoke offline access. */
    suspend fun refreshFromServer(context: Context) = refreshMutex.withLock {
        initialize(context)
        if (SystemClock.elapsedRealtime() < retryAfterElapsed) return@withLock
        retryAfterElapsed = SystemClock.elapsedRealtime() + 60_000L
        try {
            val nonce = UUID.randomUUID().toString()
            val time = withContext(Dispatchers.IO) {
                val request = Request.Builder().url("https://npro-app.vercel.app/api/time?nonce=$nonce")
                    .header("Cache-Control", "no-cache").build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful)
                    val json = JSONObject(response.body?.string().orEmpty())
                    require(json.optString("nonce") == nonce)
                    json.getLong("serverNow").also { require(it in 1L..8_640_000_000_000_000L) }
                }
            }
            synchronize(time)
            retryAfterElapsed = SystemClock.elapsedRealtime() + 300_000L
        } catch (cancelled: CancellationException) {
            retryAfterElapsed = Long.MIN_VALUE
            throw cancelled
        }
        catch (_: Exception) { /* Keep the existing clock and verified membership. */ }
    }
}
