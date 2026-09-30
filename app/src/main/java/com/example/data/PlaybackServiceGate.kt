package com.example.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One cancellable queue for provider metadata, shared by all resolver instances. */
internal object PlaybackServiceGate {
    private val cooldown = PlaybackRequestCooldown(android.os.SystemClock::elapsedRealtime)
    private val requests = Mutex()
    private var lastRequestAt = 0L
    private val limitRevision = java.util.concurrent.atomic.AtomicLong(0)
    val sourceRevision: Long get() = limitRevision.get()
    fun remainingMs(): Long = cooldown.remainingMs()
    @Synchronized fun recordLimit(retryAfterMs: Long? = null) {
        if (cooldown.remainingMs() == 0L) limitRevision.incrementAndGet()
        cooldown.onRateLimited(retryAfterMs)
    }
    fun check() {
        val remaining = remainingMs()
        if (remaining > 0L) throw PlaybackRateLimitedException(remaining)
    }
    fun checkResponse(code: Int, body: String, retryAfter: String?, url: String) {
        if (StreamSessionPolicy.isRateLimited(code, body) || StreamSessionPolicy.isWaitingVideo(url)) {
            val serverDelay = StreamSessionPolicy.retryAfterDelayMs(retryAfter, System.currentTimeMillis())
            recordLimit(serverDelay ?: if (StreamSessionPolicy.isWaitingVideo(body) ||
                StreamSessionPolicy.isWaitingVideo(url)) 9 * 60_000L else null)
            throw PlaybackRateLimitedException(remainingMs())
        }
    }
    suspend fun <T> request(preview: Boolean = false, block: suspend () -> T): T = requests.withLock {
        check()
        val wait = (if (preview) 1_000L else 400L) -
            (android.os.SystemClock.elapsedRealtime() - lastRequestAt)
        if (wait > 0L) delay(wait)
        currentCoroutineContext().ensureActive()
        check()
        lastRequestAt = android.os.SystemClock.elapsedRealtime()
        block()
    }
}
