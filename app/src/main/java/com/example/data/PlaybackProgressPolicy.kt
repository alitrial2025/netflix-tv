package com.example.data

/** A position belongs to an episode, never to the whole series. */
internal object PlaybackProgressPolicy {
    fun sameEpisode(season: Int, episode: Int, remoteSeason: Long?, remoteEpisode: Long?, remoteId: String?): Boolean {
        if (remoteSeason != null && remoteEpisode != null) {
            return remoteSeason == season.toLong() && remoteEpisode == episode.toLong()
        }
        return remoteId.equals("s${season}_e$episode", ignoreCase = true) ||
            remoteId?.endsWith("_S${season}_$episode", ignoreCase = true) == true
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolvePosition(positionMs: Long, durationMs: Long, remotePositionMs: Long): Long =
        positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun shouldDebounce(
        positionMs: Long, season: Int, episode: Int,
        previousPositionMs: Long, previousSeason: Int, previousEpisode: Int, previousCompleted: Boolean
    ): Boolean = !previousCompleted && season == previousSeason && episode == previousEpisode &&
        kotlin.math.abs(previousPositionMs - positionMs) < 2_000L
}
