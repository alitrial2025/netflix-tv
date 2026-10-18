package com.example.data

import com.example.model.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A next episode exists only when it is present in the series catalog. */
internal class VerifiedNextEpisodeLookup(
    private val episodes: suspend (Long, Int) -> List<Episode>,
    private val seasons: suspend (Long) -> List<Int>,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val lookup = Mutex()
    private val verified = LinkedHashMap<String, Pair<Int, Int>>()
    private val misses = LinkedHashMap<String, Long>()

    suspend fun find(movieId: String, season: Int, episode: Int): Pair<Int, Int>? {
        val id = movieId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        if (season < 1 || episode < 1) return null
        return lookup.withLock {
            val key = "$id:$season:$episode"
            verified[key]?.let { return@withLock it }
            val now = nowMs()
            misses[key]?.let { if (now >= it && now - it < 60_000L) return@withLock null }
            try {
                val catalogSeason = SeriesEpisodeOrder.displaySeason(movieId, season, episode)
                val catalog = episodes(id, catalogSeason).filter { it.id > 0 && SeriesEpisodeOrder.displaySeason(movieId, it.seasonNumber, it.episodeNumber) == catalogSeason && it.episodeNumber > 0 }
                currentCoroutineContext().ensureActive()
                // An empty catalog can mean offline. It cannot prove that the
                // watched episode is the season finale or authorize a jump.
                if (catalog.none { it.episodeNumber == episode }) return@withLock miss(key, now)
                val remaining = catalog.filter { it.episodeNumber > episode }.minByOrNull { it.episodeNumber }
                val result = if (remaining != null) remaining.seasonNumber to remaining.episodeNumber else {
                    val nextSeason = seasons(id).filter { it > catalogSeason }.minOrNull()
                    currentCoroutineContext().ensureActive()
                    val next = nextSeason?.let { number ->
                        episodes(id, number).filter { it.id > 0 && SeriesEpisodeOrder.displaySeason(movieId, it.seasonNumber, it.episodeNumber) == number && it.episodeNumber > 0 }
                            .minByOrNull { it.episodeNumber }
                    }
                    currentCoroutineContext().ensureActive()
                    if (nextSeason != null && next != null) next.seasonNumber to next.episodeNumber else null
                }
                if (result == null) miss(key, now) else result.also {
                    verified[key] = it
                    while (verified.size > 64) verified.remove(verified.keys.first())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { miss(key, now) }
        }
    }

    private fun miss(key: String, now: Long): Pair<Int, Int>? {
        misses[key] = now
        while (misses.size > 64) misses.remove(misses.keys.first())
        return null
    }
}
