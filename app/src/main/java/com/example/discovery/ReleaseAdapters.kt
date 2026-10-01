package com.example.discovery

import android.content.Context
import com.example.BuildConfig
import com.example.api.TmdbClient
import com.example.model.Movie
import com.example.model.catalogMediaKind

fun createReleaseDiscovery(context: Context): LiveReleaseDiscovery {
    val cache = ReleaseDiskCache(java.io.File(context.filesDir, "release-discovery-v1.json"))
    return LiveReleaseDiscovery(fetch = { query, page ->
        com.example.ui.util.HomeStartupGate.awaitIdle()
        val parameters = query.parameters + mapOf("api_key" to BuildConfig.TMDB_API_KEY, "page" to page.toString())
        val result = if(query.kind == "movie") TmdbClient.instance.discoverReleaseMovies(parameters)
            else TmdbClient.instance.discoverReleaseTv(parameters)
        result.results.map { item -> ReleaseTitle(item.id.toString(),query.kind,item.title ?: item.name ?: "",item.overview.orEmpty(),
            item.posterPath,item.backdropPath,item.releaseDate ?: item.firstAirDate,item.genreIds.map { it.toInt() },
            item.voteAverage ?: 0.0,item.voteCount ?: 0,item.popularity ?: 0.0,query.majorOtt) }
    }, readCache = { cache.read() }, writeCache = { cache.write(it) },
        parallelism = if (com.example.ui.util.TvImagePolicy.isLowMemoryDevice(context)) 1 else 2)
}
fun ReleaseTitle.toDiscoveryMovie(upcoming: Boolean): Movie = Movie(
    id = id, title = title, description = overview,
    backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }.orEmpty(),
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty(),
    year = date?.take(4).orEmpty(), type = if(kind == "tv") "Series" else "Movie", duration = if(kind == "tv") "Series" else "Feature",
    isComingSoon = upcoming, releaseDateBadge = if (upcoming) date?.let(ReleasePolicy::badge) else null,
    releaseDate = date, genreIds = genreIds, voteAverage = voteAverage, voteCount = voteCount, popularity = popularity)
fun Movie.recommendationTitle(): RecommendationTitle = RecommendationTitle(
    "${catalogMediaKind()}:$id",RecommendationEngine.genres(genreIds),popularity,voteAverage,voteCount,releaseDate)
