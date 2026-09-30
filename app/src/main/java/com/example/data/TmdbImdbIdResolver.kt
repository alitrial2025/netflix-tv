package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Small, serialized optional lookups: a missing or offline title cannot block browsing. */
internal class TmdbImdbIdResolver(
    private val lookup: suspend (mediaKind: String, tmdbId: Long) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 64
) {
    private data class CachedId(val value: String?, val expiresAtMs: Long)
    private val cache = LinkedHashMap<String, CachedId>(16, 0.75f, true)
    private val mutex = Mutex()

    suspend fun resolve(mediaKind: String, tmdbId: String): String? {
        if (mediaKind != "tv" && mediaKind != "movie") return null
        val id = tmdbId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val key = "$mediaKind:$id"
        return mutex.withLock {
            cache[key]?.takeIf { it.expiresAtMs > nowMs() }?.let { return@withLock it.value }
            var lifetimeMs = 86_400_000L
            val value = try {
                withTimeout(4_000L) { lookup(mediaKind, id) }
                    ?.takeIf { IMDB_ID.matches(it) }
                    ?.also { lifetimeMs = 604_800_000L }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                lifetimeMs = 60_000L
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lifetimeMs = 60_000L
                null
            }
            cache[key] = CachedId(value, nowMs() + lifetimeMs)
            while (cache.size > maxEntries.coerceAtLeast(1)) {
                cache.remove(cache.keys.first())
            }
            value
        }
    }

    private companion object {
        val IMDB_ID = Regex("tt[0-9]{5,12}")
    }
}
