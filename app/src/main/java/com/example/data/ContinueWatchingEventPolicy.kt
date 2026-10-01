package com.example.data

/** The latest watch event wins, including rewinds and zero-position episode transitions. */
object ContinueWatchingEventPolicy {
    private val lastEvent = java.util.concurrent.atomic.AtomicLong(0L)
    fun newTimestamp(): Long = lastEvent.updateAndGet { maxOf(System.currentTimeMillis(), it + 1L) }
    fun isNewer(incoming: Long, existing: Long?): Boolean = incoming > 0L && (existing == null || incoming > existing)
    fun completed(positionMs: Long, durationMs: Long): Boolean = durationMs > 0L && positionMs >= durationMs * .95
    fun mobileEpisodeId(mediaId: String, season: Int, episode: Int): String = "ep_${mediaId}_S${season.coerceAtLeast(1)}_${episode.coerceAtLeast(1)}"
}
