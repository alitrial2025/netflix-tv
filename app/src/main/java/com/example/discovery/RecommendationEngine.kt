package com.example.discovery

import kotlin.math.ln
import kotlin.math.pow

/** Only media identity and taste features; never account names or authentication data. */
data class RecommendationTitle(
    val key: String, val genres: Set<Int>, val popularity: Double = 0.0,
    val rating: Double = 0.0, val votes: Int = 0, val releaseDate: String? = null
)
data class TasteSignal(val key: String, val genres: Set<Int>, val watchedAt: Long,
    val watchedSeconds: Int, val durationSeconds: Int, val movie: Boolean)

object RecommendationEngine {
    fun normalizeGenre(id: Int): Int = when (id) { 10759 -> 28; 10765 -> 878; 10762 -> 10751; 10768 -> 10752; else -> id }
    fun genres(ids: Iterable<Int>): Set<Int> = ids.map(::normalizeGenre).toSet()
    private val names = mapOf("action" to 28, "adventure" to 12, "animation" to 16, "anime" to 16,
        "comedy" to 35, "crime" to 80, "documentary" to 99, "drama" to 18, "family" to 10751,
        "kids" to 10751, "fantasy" to 14, "horror" to 27, "mystery" to 9648, "romance" to 10749,
        "sci-fi" to 878, "science fiction" to 878, "thriller" to 53, "war" to 10752, "western" to 37)
    fun preferredGenres(labels: List<String>): Set<Int> = labels.flatMap { label ->
        names.filterKeys { label.lowercase(java.util.Locale.ROOT).contains(it) }.values
    }.map(::normalizeGenre).toSet()

    fun rank(titles: List<RecommendationTitle>, profileId: String, preferred: Set<Int> = emptySet(),
        history: List<TasteSignal> = emptyList(), ratings: Map<String, String> = emptyMap(),
        community: Map<String, Long> = emptyMap(), now: Long = System.currentTimeMillis(), limit: Int = 40): List<String> {
        if (limit <= 0) return emptyList()
        val today = ReleasePolicy.day(now)
        val pool = titles.distinctBy { it.key }.filter { ReleasePolicy.isPlayableDate(it.releaseDate, today) }
        val byKey = pool.associateBy { it.key }
        val taste = mutableMapOf<Int, Double>()
        preferred.forEach { taste[normalizeGenre(it)] = 1.0 }
        val completedMovies = mutableSetOf<String>()
        history.sortedByDescending { it.watchedAt }.distinctBy { it.key }.take(100).forEach { event ->
            if (event.watchedSeconds < 120 || event.durationSeconds <= 0) return@forEach
            val fraction = (event.watchedSeconds.toDouble() / event.durationSeconds).coerceIn(0.0, 1.0)
            val ageDays = (now - event.watchedAt).coerceAtLeast(0L) / 86_400_000.0
            val weight = (0.4 + fraction) * 0.5.pow(ageDays / 21.0)
            genres(event.genres.ifEmpty { byKey[event.key]?.genres.orEmpty() }).forEach { taste[it] = (taste[it] ?: 0.0) + weight }
            if (event.movie && fraction >= .95) completedMovies.add(event.key)
        }
        ratings.forEach { (key, value) ->
            val amount = when(value) { "DOUBLE_LIKE" -> 2.0; "LIKE" -> 1.0; else -> 0.0 }
            byKey[key]?.genres.orEmpty().forEach { taste[it] = (taste[it] ?: 0.0) + amount }
        }
        val maxTaste = taste.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val candidates = pool.filter { ratings[it.key] != "DISLIKE" && it.key !in completedMovies }.map { title ->
            val genreSet = genres(title.genres)
            val affinity = genreSet.sumOf { (taste[it] ?: 0.0) / maxTaste } / genreSet.size.coerceAtLeast(1)
            val votes = title.votes.coerceIn(0,10_000_000).toDouble()
            val quality = ((title.rating.coerceIn(0.0,10.0) * votes + 6.8 * 200) / (votes + 200))
            val pop = ln(1 + title.popularity.coerceAtLeast(0.0)).coerceAtMost(6.0)
            val crowds = ln(1 + (community[title.key] ?: 0).coerceAtLeast(0).toDouble()).coerceAtMost(6.0)
            val rotation = (("$profileId:$today:${title.key}".hashCode().toLong() and 0x7fffffff) % 1000) / 1000.0
            val fresh = if (ReleasePolicy.isNew(title.releaseDate, today)) 3.0 else 0.0
            title to (affinity * 24 + quality * 2 + pop * 1.5 + crowds * 1.3 + rotation * 5 + fresh)
        }.toMutableList()
        val genreCache = candidates.associate { it.first.key to genres(it.first.genres) }
        val picked = mutableListOf<RecommendationTitle>()
        while (picked.size < minOf(limit,60) && candidates.isNotEmpty()) {
            val recentGenres = picked.takeLast(3).flatMap { genreCache[it.key].orEmpty() }.groupingBy { it }.eachCount()
            val best = candidates.maxWithOrNull(compareBy<Pair<RecommendationTitle, Double>> {
                it.second - genreCache[it.first.key].orEmpty().sumOf { genre -> (recentGenres[genre] ?: 0) * 2.5 }
            }.thenBy { it.first.key })!!
            candidates.remove(best); picked.add(best.first)
        }
        return (picked.map { it.key } + candidates.sortedWith(compareByDescending<Pair<RecommendationTitle,Double>> { it.second }.thenBy { it.first.key }).map { it.first.key }).take(limit)
    }
}
