package com.example.api

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class TmdbResponse(
    val results: List<TmdbItemDto> = emptyList()
)

data class TmdbItemDto(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "popularity") val popularity: Double? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Long> = emptyList()
)

interface TmdbApiService {
    @GET("discover/movie")
    suspend fun discoverReleaseMovies(@retrofit2.http.QueryMap parameters: Map<String, String>): TmdbResponse

    @GET("discover/tv")
    suspend fun discoverReleaseTv(@retrofit2.http.QueryMap parameters: Map<String, String>): TmdbResponse

    @GET("movie/{id}")
    suspend fun getMovieArtworkTitle(@Path("id") id: Long, @Query("api_key") apiKey: String): TmdbItemDto

    @GET("tv/{id}")
    suspend fun getTvArtworkTitle(@Path("id") id: Long, @Query("api_key") apiKey: String): TmdbItemDto

    @GET("movie/{movie_id}")
    suspend fun getMovieDetailsWithKeywords(
        @Path("movie_id") movieId: Long,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "keywords"
    ): TmdbMovieDetailsDto

    @GET("tv/{tv_id}")
    suspend fun getTvDetailsWithKeywords(
        @Path("tv_id") tvId: Long,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "keywords"
    ): TmdbTvFullDetailsDto

    @GET("collection/{collection_id}")
    suspend fun getCollectionDetails(
        @Path("collection_id") collectionId: Long,
        @Query("api_key") apiKey: String
    ): TmdbCollectionDetailsDto

    @GET("trending/all/week")
    suspend fun getTrending(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("tv/popular")
    suspend fun getPopularTvShows(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("movie/now_playing")
    suspend fun getNowPlayingMovies(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("tv/top_rated")
    suspend fun getTopRatedTvShows(
        @Query("api_key") apiKey: String
    ): TmdbResponse

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: Long,
        @Query("api_key") apiKey: String
    ): TmdbVideoResponse

    @GET("movie/{movie_id}/images")
    suspend fun getMovieImages(
        @Path("movie_id") movieId: Long,
        @Query("api_key") apiKey: String
    ): TmdbImagesResponse

    @GET("tv/{tv_id}/images")
    suspend fun getTvImages(
        @Path("tv_id") tvId: Long,
        @Query("api_key") apiKey: String
    ): TmdbImagesResponse

    @GET("movie/{movie_id}/external_ids")
    suspend fun getMovieExternalIds(
        @Path("movie_id") movieId: Long,
        @Query("api_key") apiKey: String
    ): TmdbExternalIdsDto

    @GET("tv/{tv_id}/external_ids")
    suspend fun getTvExternalIds(
        @Path("tv_id") tvId: Long,
        @Query("api_key") apiKey: String
    ): TmdbExternalIdsDto

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeason(
        @Path("tv_id") tvId: Long,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonResponse

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("genre/movie/list")
    suspend fun getMovieGenres(
        @Query("api_key") apiKey: String
    ): TmdbGenresResponse

    @GET("genre/tv/list")
    suspend fun getTvGenres(
        @Query("api_key") apiKey: String
    ): TmdbGenresResponse

    @GET("discover/movie")
    suspend fun discoverMoviesByGenre(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: Long,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("discover/movie")
    suspend fun discoverMoviesAdvanced(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("discover/tv")
    suspend fun discoverTvByGenre(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: Long,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("discover/tv")
    suspend fun discoverTvAdvanced(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("discover/movie")
    suspend fun discoverKidsMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "10751,16",
        @Query("certification_country") certificationCountry: String = "US",
        @Query("certification.lte") certificationLte: String = "PG",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("discover/tv")
    suspend fun discoverKidsTv(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "10762,10751,16",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Long,
        @Query("api_key") apiKey: String
    ): TmdbTvDetailsDto
}

data class TmdbTvDetailsDto(
    val id: Long,
    val name: String? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = 1,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    val seasons: List<TmdbSeasonInfoDto> = emptyList()
)

data class TmdbCollectionRefDto(
    val id: Long,
    val name: String? = null
)

data class TmdbCollectionDetailsDto(
    val id: Long,
    val name: String? = null,
    val parts: List<TmdbItemDto> = emptyList()
)

data class TmdbKeywordDto(
    val id: Long,
    val name: String = ""
)

data class TmdbKeywordsContainerDto(
    val keywords: List<TmdbKeywordDto> = emptyList(),
    val results: List<TmdbKeywordDto> = emptyList()
) {
    fun allKeywords(): List<String> = (keywords + results).mapNotNull { it.name.takeIf(String::isNotBlank) }
}

data class TmdbAirEpisodeDto(
    val id: Long? = null,
    val name: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null
)

data class TmdbMovieDetailsDto(
    val id: Long,
    val title: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    val status: String? = null,
    val tagline: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    @Json(name = "belongs_to_collection") val belongsToCollection: TmdbCollectionRefDto? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val keywords: TmdbKeywordsContainerDto? = null
)

data class TmdbTvFullDetailsDto(
    val id: Long,
    val name: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "last_air_date") val lastAirDate: String? = null,
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: TmdbAirEpisodeDto? = null,
    @Json(name = "last_episode_to_air") val lastEpisodeToAir: TmdbAirEpisodeDto? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = 1,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    @Json(name = "in_production") val inProduction: Boolean? = null,
    val status: String? = null,
    val type: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    val genres: List<TmdbGenreDto> = emptyList(),
    val keywords: TmdbKeywordsContainerDto? = null
)

data class TmdbSeasonInfoDto(
    val id: Long,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = 0
)

data class TmdbGenresResponse(
    val genres: List<TmdbGenreDto> = emptyList()
)

data class TmdbGenreDto(
    val id: Long,
    val name: String
)

data class TmdbImagesResponse(
    val logos: List<TmdbLogoDto> = emptyList()
)

data class TmdbExternalIdsDto(
    val id: Long,
    @Json(name = "imdb_id") val imdbId: String? = null
)

data class TmdbLogoDto(
    @Json(name = "file_path") val filePath: String,
    @Json(name = "iso_639_1") val iso: String? = null
)

data class TmdbSeasonResponse(
    val episodes: List<TmdbEpisodeDto> = emptyList()
)

data class TmdbEpisodeDto(
    val id: Long,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    val runtime: Int? = null,
    @Json(name = "still_path") val stillPath: String? = null
)

data class TmdbVideoResponse(
    val results: List<TmdbVideoDto> = emptyList()
)

data class TmdbVideoDto(
    val key: String,
    val site: String,
    val type: String
)

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .build()

    val instance: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }
}
