package com.example.ui.screens

import com.example.data.TmdbRepository
import com.example.model.Movie
import com.example.model.isKidSafeMovie

/**
 * High-Performance Billboard Ranking & Curation Engine for Netflix Pro TV.
 *
 * Evaluates catalogue items based on visual quality, release era (2015+ priority),
 * cinematic prestige, description completeness, quality badges, and logo availability.
 */
object BillboardAlgorithm {

    private val blockbusterKeywords = listOf(
        "stranger", "witcher", "crown", "dune", "avatar", "batman", "oppenheimer",
        "spider", "marvel", "avengers", "cyberpunk", "arcane", "squid", "peaky",
        "blade", "matrix", "interstellar", "inception", "dark knight", "godfather",
        "breaking", "better call", "game of", "house of", "dragon", "star wars",
        "mandalorian", "gladiator", "top gun", "mission", "john wick", "fast",
        "deadpool", "wolverine", "godzilla", "kong", "alien", "predator", "fallout"
    )

    /**
     * Scores a movie candidate for showcase in the Billboard hero header.
     * Scale: 0 to 250+ points.
     */
    fun calculateBillboardScore(movie: Movie, tab: String = "Home"): Int {
        var score = 0

        // 1. Mandatory Visual Requirement: High-Res Backdrop
        if (movie.backdropUrl.isBlank()) {
            return -1000 // Ineligible for hero billboard if no backdrop exists
        }
        score += 50

        // Extra points for true original/w1280 wide backdrops
        if (movie.backdropUrl.contains("original") || movie.backdropUrl.contains("w1280") || movie.backdropUrl.contains("w780")) {
            score += 20
        }

        // 2. Release Year Tier (2015 onwards boost per user directive)
        val yearInt = movie.year.filter { it.isDigit() }.toIntOrNull() ?: 2024
        when {
            yearInt >= 2024 -> score += 40 // Latest cutting-edge releases
            yearInt >= 2020 -> score += 32 // Recent hits (2020-2023)
            yearInt >= 2015 -> score += 24 // Modern era classics (2015-2019)
            yearInt >= 2008 -> score += 12 // Late 2000s blockbusters
            else -> score += 5             // Classic vintage
        }

        // 3. Official Logo Availability (Gives stunning transparent SVG/PNG branding)
        if (!movie.logoUrl.isNullOrBlank() || TmdbRepository.getCachedLogo(movie.id) != null) {
            score += 35
        }

        // 4. Description & Synopsis Completeness
        val descLength = movie.description.trim().length
        if (descLength >= 80 && !movie.description.startsWith("A thrilling title") && !movie.description.startsWith("Explore trending")) {
            score += 20
        } else if (descLength >= 30) {
            score += 10
        }

        // 5. Age Rating & Prestige
        val ratingUpper = movie.rating.uppercase()
        when {
            ratingUpper.contains("18") || ratingUpper.contains("TV-MA") || ratingUpper.contains("R") -> score += 15
            ratingUpper.contains("16") || ratingUpper.contains("14") || ratingUpper.contains("TV-14") -> score += 15
            ratingUpper.contains("13") || ratingUpper.contains("PG-13") -> score += 14
            ratingUpper.contains("PG") || ratingUpper.contains("TV-Y7") || ratingUpper.contains("ALL") -> score += 12
        }

        // 6. Quality Chips (4K, Ultra HD, HDR, 5.1)
        if (movie.quality.isNotEmpty()) {
            score += 10
            if (movie.quality.any { it.contains("4K") || it.contains("UHD") || it.contains("HDR") }) {
                score += 10
            }
        }

        // 7. Cinematic Blockbuster Title Recognition & Genre Boost
        val titleLower = movie.title.lowercase()
        val descLower = movie.description.lowercase()
        if (blockbusterKeywords.any { titleLower.contains(it) || descLower.contains(it) }) {
            score += 30
        }

        // 8. Tab-Specific Relevance
        when (tab) {
            "Series" -> {
                if (movie.type.equals("Series", ignoreCase = true) || movie.duration.contains("Season", ignoreCase = true)) {
                    score += 25
                } else {
                    score -= 40
                }
            }
            "Films" -> {
                if (!movie.type.equals("Series", ignoreCase = true) && !movie.duration.contains("Season", ignoreCase = true)) {
                    score += 25
                } else {
                    score -= 40
                }
            }
            else -> {
                // Home: balanced mix
                score += 10
            }
        }

        return score
    }

    /**
     * Curates and ranks the best billboard candidates for the given tab.
     * Returns top [limit] distinct movies sorted by score descending.
     */
    fun rankBillboardMovies(
        catalog: List<Movie>,
        tab: String = "Home",
        isKidProfile: Boolean = false,
        limit: Int = 8
    ): List<Movie> {
        if (catalog.isEmpty()) return emptyList()

        val filtered = if (isKidProfile) {
            catalog.filter { isKidSafeMovie(it) }
        } else {
            catalog
        }

        return filtered
            .filter { !it.isComingSoon && com.example.discovery.ReleasePolicy.isPlayableDate(it.releaseDate) && it.backdropUrl.isNotBlank() }
            .map { movie -> movie to calculateBillboardScore(movie, tab) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
            .take(limit)
    }
}
