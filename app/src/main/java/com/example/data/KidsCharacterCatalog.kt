package com.example.data

import com.example.model.Movie
import com.example.model.catalogMediaKind
import java.net.URI

internal data class KidsCharacterEntry(
    val mediaKind: String,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val file: String? = null,
    val url: String? = null,
    val title: String? = null
)

/** Exact identity matching, with local cutouts preferred over explicitly registered URLs. */
class KidsCharacterCatalog internal constructor(
    private val files: Set<String>,
    entries: List<KidsCharacterEntry> = emptyList()
) {
    private val byTmdb = LinkedHashMap<String, String>()
    private val byImdb = LinkedHashMap<String, String>()
    private val imdbOnlyKinds = mutableSetOf<String>()
    private val artworkTitles = LinkedHashMap<String, Movie>()

    init {
        for (entry in entries) {
            if (entry.mediaKind != "movie" && entry.mediaKind != "tv") continue
            val url = entry.file?.takeIf { IMAGE_FILE.matches(it) && it in files }?.let(::assetUrl)
                ?: entry.url?.takeIf(::isHttpsUrl) ?: continue
            val tmdbId = entry.tmdbId?.toLongOrNull()?.takeIf { it > 0L }?.toString()
            val imdbId = entry.imdbId?.takeIf { IMDB_ID.matches(it) }
            if (tmdbId != null) byTmdb.putIfAbsent("${entry.mediaKind}:$tmdbId", url)
            if (tmdbId != null && !entry.title.isNullOrBlank()) {
                artworkTitles.putIfAbsent("${entry.mediaKind}:$tmdbId", Movie(
                    id = tmdbId, title = entry.title.orEmpty(), description = "", backdropUrl = "", posterUrl = "",
                    rating = "PG", year = "", type = if (entry.mediaKind == "tv") "Series" else "Animation",
                    duration = if (entry.mediaKind == "tv") "Series" else "", imdbId = imdbId
                ))
            }
            if (imdbId != null) {
                byImdb.putIfAbsent("${entry.mediaKind}:$imdbId", url)
                if (tmdbId == null) imdbOnlyKinds += entry.mediaKind
            }
        }
        for (file in files) {
            val match = IMDB_FILE.matchEntire(file) ?: continue
            val kind = match.groupValues[1]
            if (kind.isEmpty()) imdbOnlyKinds.addAll(listOf("movie", "tv"))
            else imdbOnlyKinds += kind
        }
    }

    fun urlFor(movie: Movie, imdbId: String? = movie.imdbId): String? {
        val kind = movie.catalogMediaKind()
        val tmdbId = movie.id.toLongOrNull()?.takeIf { it > 0L }?.toString()
        if (tmdbId != null) {
            localFile("${kind}_$tmdbId")?.let { return it }
            byTmdb["$kind:$tmdbId"]?.let { return it }
        }
        val externalId = imdbId?.takeIf { IMDB_ID.matches(it) } ?: return null
        return localFile("${kind}_$externalId") ?: localFile(externalId) ?: byImdb["$kind:$externalId"]
    }

    /** Titles with usable artwork, preserving their movie/TV namespace. */
    fun titleIdentities(): List<Pair<String, String>> {
        val local = files.mapNotNull { file ->
            Regex("""(movie|tv)_([0-9]+)\.(?:png|webp)""").matchEntire(file)?.let {
                it.groupValues[1] to it.groupValues[2]
            }
        }.sortedBy { "${it.first}:${it.second}" }
        return (byTmdb.keys.map { it.substringBefore(':') to it.substringAfter(':') } + local).distinct()
    }

    fun registeredTitles(): List<Movie> = artworkTitles.values.toList()

    /** Ordinary catalog entries never trigger a network lookup when no IMDb-only art exists. */
    fun needsImdbLookup(movie: Movie): Boolean =
        movie.catalogMediaKind() in imdbOnlyKinds &&
            movie.imdbId?.let { IMDB_ID.matches(it) } != true &&
            movie.id.toLongOrNull()?.let { it > 0L } == true && urlFor(movie) == null

    private fun localFile(stem: String): String? = when {
        "$stem.webp" in files -> assetUrl("$stem.webp")
        "$stem.png" in files -> assetUrl("$stem.png")
        else -> null
    }

    companion object {
        val EMPTY = KidsCharacterCatalog(emptySet())
        private val IMDB_ID = Regex("tt[0-9]{5,12}")
        private val IMDB_FILE = Regex("(?:(movie|tv)_)?tt[0-9]{5,12}\\.(?:webp|png)")
        private val IMAGE_FILE = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(?:webp|png)")
        private fun assetUrl(file: String) = "file:///android_asset/kids_characters/$file"
        private fun isHttpsUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}
