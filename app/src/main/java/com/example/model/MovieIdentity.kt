package com.example.model

import java.util.Locale

/** TMDB movie and TV IDs occupy separate namespaces, even when their digits match. */
fun Movie.isSeriesContent(): Boolean =
    type.equals("Series", ignoreCase = true) ||
        type.equals("TV", ignoreCase = true) ||
        duration.equals("Series", ignoreCase = true) ||
        duration.contains("Season", ignoreCase = true)

fun Movie.catalogMediaKind(): String = if (isSeriesContent()) "tv" else "movie"

/** Shared player IDs must include the title namespace, not only TMDB's number. */
fun Movie.playbackMediaId(season: Int, episode: Int): String =
    "${catalogMediaKind()}:$id:${title.trim().lowercase(Locale.ROOT)}:$season:$episode"

/** A Play Next card must resolve the same title and media kind it advertised. */
fun findMovieByIdentity(
    candidates: Iterable<Movie>,
    id: String,
    expectedTitle: String? = null,
    expectedMediaKind: String? = null
): Movie? {
    val matches = candidates.filter { it.id == id }
    val title = expectedTitle?.trim()?.takeIf { it.isNotEmpty() }
    val kind = expectedMediaKind?.lowercase(Locale.ROOT)?.takeIf { it == "tv" || it == "movie" }
    return when {
        title != null && kind != null -> matches.firstOrNull {
            it.title.equals(title, ignoreCase = true) && it.catalogMediaKind() == kind
        }
        title != null -> matches.firstOrNull { it.title.equals(title, ignoreCase = true) }
        kind != null -> matches.firstOrNull { it.catalogMediaKind() == kind }
        else -> matches.firstOrNull()
    }
}
