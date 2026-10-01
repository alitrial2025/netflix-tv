package com.example.data

import androidx.compose.runtime.Stable
import com.example.BuildConfig
import com.example.api.TmdbClient
import com.example.api.TmdbItemDto
import com.example.model.Movie
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Stable
data class TmdbBillboardMeta(
    val itemId: String,
    val isTv: Boolean = false,
    val releaseDate: String? = null,
    val lastAirDate: String? = null,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val popularity: Double? = null,
    val genreIds: List<Long> = emptyList(),
    val genreNames: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val originCountry: List<String> = emptyList(),
    val sourceFeeds: Set<String> = emptySet(),
    val trendingRank: Int? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val sequelOfTitle: String? = null,
    val isFirstInCollection: Boolean = false,
    val keywords: List<String> = emptyList(),
    val numberOfSeasons: Int? = null,
    val hasUpcomingEpisode: Boolean = false,
    val tvType: String? = null,
    val tvStatus: String? = null,
    val runtimeMinutes: Int? = null,
    val isEnriched: Boolean = false
)

class TmdbRepository {
    private val apiKey: String
        get() = try {
            BuildConfig.TMDB_API_KEY.ifEmpty { "8baba8ab6b8bbe247645bcae7df63d0d" }
        } catch (e: Exception) {
            "8baba8ab6b8bbe247645bcae7df63d0d"
        }

    private val imdbIds = TmdbImdbIdResolver(lookup = { kind, id ->
        val response = if (kind == "tv") {
            TmdbClient.instance.getTvExternalIds(id, apiKey)
        } else {
            TmdbClient.instance.getMovieExternalIds(id, apiKey)
        }
        response.imdbId.takeIf { response.id == id }
    })

    suspend fun fetchImdbId(itemId: String, mediaKind: String): String? =
        imdbIds.resolve(mediaKind, itemId)

    companion object {
        private val logoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val billboardMetaCache = java.util.concurrent.ConcurrentHashMap<String, TmdbBillboardMeta>()

        fun getCachedLogo(itemId: String): String? = logoCache[itemId]
        fun putCachedLogo(itemId: String, url: String) { logoCache[itemId] = url }

        fun getCachedBillboardMeta(itemId: String): TmdbBillboardMeta? = billboardMetaCache[itemId]
        fun putCachedBillboardMeta(itemId: String, meta: TmdbBillboardMeta) {
            billboardMetaCache[itemId] = meta
        }

        internal fun cleanFranchiseCollectionName(rawCollectionName: String?): String? {
            if (rawCollectionName.isNullOrBlank()) return null
            val stripped = rawCollectionName
                .replace(Regex("\\s*(Collection|Series|Anthology|Saga|Trilogy|Universe)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (stripped.isBlank()) return null
            return if (stripped.length > 24 && stripped.contains(":")) {
                stripped.substringBefore(":").trim().ifBlank { stripped.take(24).trim() }
            } else {
                stripped
            }
        }

        internal fun formatPredecessorTitleForBadge(
            immediatePrevTitle: String?,
            firstPartTitle: String?,
            rawCollectionName: String?,
            currentTitle: String
        ): String? {
            val cleanCollection = cleanFranchiseCollectionName(rawCollectionName)
            val candidates = listOfNotNull(
                immediatePrevTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(currentTitle, ignoreCase = true) },
                firstPartTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(currentTitle, ignoreCase = true) },
                cleanCollection?.takeIf { !it.equals(currentTitle, ignoreCase = true) }
            )
            val best = candidates.firstOrNull { it.length <= 24 }
                ?: cleanCollection?.takeIf { it.length <= 26 && !it.equals(currentTitle, ignoreCase = true) }
                ?: candidates.firstOrNull()?.let { raw ->
                    when {
                        raw.contains(": ") && raw.substringBefore(": ").length in 3..22 &&
                            !raw.substringBefore(": ").equals(currentTitle, ignoreCase = true) ->
                            raw.substringBefore(": ").trim()
                        raw.contains(" - ") && raw.substringBefore(" - ").length in 3..22 ->
                            raw.substringBefore(" - ").trim()
                        else -> raw.take(24).trim()
                    }
                }
            return best?.takeIf { it.isNotBlank() }
        }
    }

    fun getCachedLogo(itemId: String): String? = logoCache[itemId]
    fun getCachedBillboardMeta(itemId: String): TmdbBillboardMeta? = billboardMetaCache[itemId]

    private val kidsArtworkTitles = java.util.concurrent.ConcurrentHashMap<String, Movie>()

    suspend fun fetchKidsArtworkTitle(kind: String, id: String): Movie? {
        if (kind != "movie" && kind != "tv") return null
        val key = "$kind:$id"
        kidsArtworkTitles[key]?.let { return it }
        val numericId = id.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return try {
            val item = if (kind == "tv") TmdbClient.instance.getTvArtworkTitle(numericId, apiKey)
                else TmdbClient.instance.getMovieArtworkTitle(numericId, apiKey)
            if (item.id != numericId) return null
            // Only registered Kids artwork identities reach this method.
            item.copy(mediaType = kind, genreIds = listOf(16L, 10751L)).toMovie().also {
                kidsArtworkTitles[key] = it
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }

    private fun TmdbItemDto.toMovie(logoUrl: String? = null): Movie {
        val imageBase = "https://image.tmdb.org/t/p/"
        val backdrop = backdropPath?.let { 
            val cleanPath = if (it.startsWith("/")) it else "/$it"
            "${imageBase}original$cleanPath"
        } ?: posterPath?.let { 
            val cleanPath = if (it.startsWith("/")) it else "/$it"
            "${imageBase}w780$cleanPath"
        } ?: ""

        val poster = posterPath?.let { 
            val cleanPath = if (it.startsWith("/")) it else "/$it"
            "${imageBase}w780$cleanPath"
        } ?: backdropPath?.let { 
            val cleanPath = if (it.startsWith("/")) it else "/$it"
            "${imageBase}w780$cleanPath"
        } ?: ""

        val itemTitle = title ?: name ?: "Untitled"
        val releaseYear = releaseDate?.take(4) ?: firstAirDate?.take(4) ?: "2025"
        val isTv = mediaType == "tv" || name != null
        val isAnimation = genreIds.contains(16L)
        val isFamily = genreIds.contains(10751L)
        val isKidsTv = genreIds.contains(10762L)
        val isKidFriendlyGenre = isAnimation || isFamily || isKidsTv
        // A TV animation is still a series for episode lookup and playback.
        val itemType = if (isTv) "Series" else if (isAnimation) "Animation" else "Movie"
        
        val ageRating = when {
            isKidFriendlyGenre -> {
                when {
                    isKidsTv -> "TV-Y7"
                    isAnimation -> "PG"
                    isFamily -> "PG"
                    else -> "12"
                }
            }
            voteAverage != null && voteAverage >= 8.3 -> "18"
            voteAverage != null && voteAverage >= 7.2 -> "16"
            voteAverage != null && voteAverage >= 6.0 -> "13"
            else -> "13"
        }

        return Movie(
            id = id.toString(),
            title = itemTitle,
            description = overview?.ifBlank { "A thrilling title on Netflix Pro." } ?: "A thrilling title on Netflix Pro.",
            backdropUrl = backdrop,
            posterUrl = poster,
            rating = ageRating,
            year = releaseYear,
            type = itemType,
            duration = if (isTv) "Series" else "Feature",
            logoUrl = logoUrl ?: logoCache[id.toString()],
            releaseDate = releaseDate ?: firstAirDate,
            genreIds = genreIds.map { it.toInt() }, voteAverage = voteAverage ?: 0.0,
            voteCount = voteCount ?: 0, popularity = popularity ?: 0.0
        )
    }

    suspend fun fetchLogoUrl(itemId: Long, isTv: Boolean): String? {
        val key = itemId.toString()
        logoCache[key]?.let { return it }
        return try {
            val response = if (isTv) {
                TmdbClient.instance.getTvImages(itemId, apiKey)
            } else {
                TmdbClient.instance.getMovieImages(itemId, apiKey)
            }
            val logo = response.logos.firstOrNull { it.iso == "en" } ?: response.logos.firstOrNull()
            logo?.filePath?.let {
                val cleanPath = if (it.startsWith("/")) it else "/$it"
                val url = "https://image.tmdb.org/t/p/w500$cleanPath"
                logoCache[key] = url
                url
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun recordSeedBillboardMeta(item: TmdbItemDto, sourceFeed: String?, index: Int) {
        val key = item.id.toString()
        val isTv = item.mediaType == "tv" || item.name != null
        val existing = billboardMetaCache[key]
        val feeds = buildSet {
            existing?.sourceFeeds?.let(::addAll)
            if (!sourceFeed.isNullOrBlank()) add(sourceFeed)
        }
        val trendingRank = when {
            sourceFeed == "trending" && index in 0..9 -> index + 1
            else -> existing?.trendingRank
        }
        val updated = (existing ?: TmdbBillboardMeta(itemId = key, isTv = isTv)).copy(
            isTv = isTv,
            releaseDate = item.releaseDate ?: item.firstAirDate ?: existing?.releaseDate,
            voteAverage = item.voteAverage ?: existing?.voteAverage,
            voteCount = item.voteCount ?: existing?.voteCount,
            popularity = item.popularity ?: existing?.popularity,
            genreIds = if (item.genreIds.isNotEmpty()) item.genreIds else existing?.genreIds.orEmpty(),
            originalLanguage = item.originalLanguage ?: existing?.originalLanguage,
            originCountry = if (item.originCountry.isNotEmpty()) item.originCountry else existing?.originCountry.orEmpty(),
            sourceFeeds = feeds,
            trendingRank = trendingRank
        )
        billboardMetaCache[key] = updated
    }

    suspend fun fetchBillboardMeta(itemId: Long, isTv: Boolean, currentTitle: String = ""): TmdbBillboardMeta? {
        if (itemId <= 0L) return null
        val key = itemId.toString()
        val existing = billboardMetaCache[key]
        if (existing?.isEnriched == true) return existing

        return try {
            val enriched = if (isTv) {
                val details = TmdbClient.instance.getTvDetailsWithKeywords(itemId, apiKey)
                val kw = details.keywords?.allKeywords().orEmpty()
                val gIds = details.genres.map { it.id }.ifEmpty { existing?.genreIds.orEmpty() }
                val gNames = details.genres.map { it.name }
                (existing ?: TmdbBillboardMeta(itemId = key, isTv = true)).copy(
                    isTv = true,
                    releaseDate = details.firstAirDate ?: existing?.releaseDate,
                    lastAirDate = details.lastAirDate ?: existing?.lastAirDate,
                    voteAverage = details.voteAverage ?: existing?.voteAverage,
                    voteCount = details.voteCount ?: existing?.voteCount,
                    popularity = details.popularity ?: existing?.popularity,
                    genreIds = gIds,
                    genreNames = gNames,
                    originalLanguage = details.originalLanguage ?: existing?.originalLanguage,
                    originCountry = details.originCountry.ifEmpty { existing?.originCountry.orEmpty() },
                    keywords = kw,
                    numberOfSeasons = details.numberOfSeasons,
                    hasUpcomingEpisode = details.nextEpisodeToAir != null,
                    tvType = details.type,
                    tvStatus = details.status,
                    isEnriched = true
                )
            } else {
                val details = TmdbClient.instance.getMovieDetailsWithKeywords(itemId, apiKey)
                val kw = details.keywords?.allKeywords().orEmpty()
                val gIds = details.genres.map { it.id }.ifEmpty { existing?.genreIds.orEmpty() }
                val gNames = details.genres.map { it.name }
                val collectionRef = details.belongsToCollection
                var sequelTitle: String? = null
                var isFirstInCollection = false
                if (collectionRef != null && collectionRef.id > 0L) {
                    val collectionDetails = runCatching {
                        TmdbClient.instance.getCollectionDetails(collectionRef.id, apiKey)
                    }.getOrNull()
                    val sortedParts = collectionDetails?.parts
                        .orEmpty()
                        .filter { !(it.title ?: it.name).isNullOrBlank() }
                        .sortedWith(
                            compareBy<TmdbItemDto> {
                                (it.releaseDate ?: it.firstAirDate).orEmpty().ifBlank { "9999-99-99" }
                            }.thenBy { it.id }
                        )
                    val idx = sortedParts.indexOfFirst { it.id == itemId }
                    val effectiveTitle = currentTitle.ifBlank { details.title.orEmpty() }
                    if (idx > 0) {
                        val prevPart = sortedParts[idx - 1]
                        val firstPart = sortedParts.first()
                        sequelTitle = formatPredecessorTitleForBadge(
                            immediatePrevTitle = prevPart.title ?: prevPart.name,
                            firstPartTitle = firstPart.title ?: firstPart.name,
                            rawCollectionName = collectionRef.name,
                            currentTitle = effectiveTitle
                        )
                    } else if (idx == 0 && sortedParts.size > 1) {
                        isFirstInCollection = true
                    } else {
                        sequelTitle = formatPredecessorTitleForBadge(
                            immediatePrevTitle = null,
                            firstPartTitle = null,
                            rawCollectionName = collectionRef.name,
                            currentTitle = effectiveTitle
                        )
                    }
                }
                (existing ?: TmdbBillboardMeta(itemId = key, isTv = false)).copy(
                    isTv = false,
                    releaseDate = details.releaseDate ?: existing?.releaseDate,
                    voteAverage = details.voteAverage ?: existing?.voteAverage,
                    voteCount = details.voteCount ?: existing?.voteCount,
                    popularity = details.popularity ?: existing?.popularity,
                    genreIds = gIds,
                    genreNames = gNames,
                    originalLanguage = details.originalLanguage ?: existing?.originalLanguage,
                    originCountry = details.originCountry.ifEmpty { existing?.originCountry.orEmpty() },
                    collectionId = collectionRef?.id,
                    collectionName = collectionRef?.name,
                    sequelOfTitle = sequelTitle,
                    isFirstInCollection = isFirstInCollection,
                    keywords = kw,
                    runtimeMinutes = details.runtime,
                    isEnriched = true
                )
            }
            billboardMetaCache[key] = enriched
            enriched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            existing
        }
    }

    private fun mapToMovies(items: List<TmdbItemDto>, sourceFeed: String? = null): List<Movie> {
        return items.take(20).mapIndexed { index, item ->
            recordSeedBillboardMeta(item, sourceFeed, index)
            item.toMovie(null)
        }.filter { it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank() }
    }

    suspend fun getTrending(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getTrending(apiKey).results
            mapToMovies(rawResults, "trending")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getPopularMovies(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getPopularMovies(apiKey).results
            mapToMovies(rawResults, "popular")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getPopularTvShows(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getPopularTvShows(apiKey).results
            mapToMovies(rawResults, "popular")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTopRatedMovies(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getTopRatedMovies(apiKey).results
            mapToMovies(rawResults, "top_rated")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getNowPlayingMovies(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getNowPlayingMovies(apiKey).results
            mapToMovies(rawResults, "now_playing")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTopRatedTvShows(fetchLogos: Boolean = false): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.getTopRatedTvShows(apiKey).results
            mapToMovies(rawResults, "top_rated")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getKidsMovies(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverKidsMovies(apiKey).results
            mapToMovies(rawResults, "kids")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getKidsTvShows(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverKidsTv(apiKey).results
            mapToMovies(rawResults, "kids")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getActionMovies(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "28,12", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "action")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getSciFiMovies(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "878", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "sci_fi")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getCrimeThrillers(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "80,53", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "crime")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getAnime(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverTvAdvanced(apiKey, withGenres = "16", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "anime")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getComedies(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "35", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "comedy")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getHorror(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "27", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "horror")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDramas(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "18", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "drama")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getAwardWinning(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, sortBy = "vote_average.desc", voteAverageGte = 8.0, voteCountGte = 500).results
            mapToMovies(rawResults, "award_winning")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDocumentaries(): List<Movie> {
        return try {
            val rawResults = TmdbClient.instance.discoverMoviesAdvanced(apiKey, withGenres = "99", sortBy = "popularity.desc").results
            mapToMovies(rawResults, "documentary")
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTvSeasonEpisodes(tvId: Long, seasonNumber: Int = 1): List<com.example.model.Episode> {
        return try {
            val response = TmdbClient.instance.getTvSeason(tvId, seasonNumber, apiKey)
            response.episodes.map { ep ->
                val still = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
                val runtimeStr = ep.runtime?.let { "${it}m" } ?: "45m"
                com.example.model.Episode(
                    id = ep.id,
                    episodeNumber = ep.episodeNumber ?: 1,
                    seasonNumber = ep.seasonNumber ?: seasonNumber,
                    title = ep.name ?: "Episode ${ep.episodeNumber}",
                    description = ep.overview ?: "No description available.",
                    stillUrl = still,
                    runtime = runtimeStr
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTvSeasons(tvId: Long): List<Int> {
        return try {
            val details = TmdbClient.instance.getTvDetails(tvId, apiKey)
            val seasonNumbers = details.seasons
                .map { it.seasonNumber }
                .filter { it > 0 }
                .distinct()
                .sorted()
            if (seasonNumbers.isNotEmpty()) seasonNumbers
            else {
                val num = details.numberOfSeasons ?: 1
                (1..num.coerceAtLeast(1)).toList()
            }
        } catch (e: Exception) {
            listOf(1)
        }
    }

    suspend fun searchMulti(query: String): List<Movie> {
        if (query.isBlank()) return emptyList()
        return try {
            val rawResults = TmdbClient.instance.searchMulti(apiKey, query).results
            mapToMovies(rawResults)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getGenres(): List<com.example.api.TmdbGenreDto> {
        return try {
            val movies = TmdbClient.instance.getMovieGenres(apiKey).genres
            val tv = TmdbClient.instance.getTvGenres(apiKey).genres
            (movies + tv).distinctBy { it.id }.sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun discoverByGenre(genreId: Long): List<Movie> {
        return try {
            // We just fetch movies for simplicity, or we can fetch both and mix
            val movieResults = TmdbClient.instance.discoverMoviesByGenre(apiKey, genreId).results
            val tvResults = TmdbClient.instance.discoverTvByGenre(apiKey, genreId).results
            val mixed = (movieResults + tvResults).shuffled()
            mapToMovies(mixed)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
