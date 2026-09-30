package com.example.model

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/**
 * Maturity rating enum — single source of truth for content gating.
 *
 * `maxAge` is the inclusive upper age limit for the rating (e.g. PG13 = 13,
 * R = 17). A profile is allowed to see content with `content.maxAge <=
 * profile.maturityRating.maxAge`. The kids profile (maxAge = 12) and adult
 * (maxAge = 99) bracket the common boundaries.
 *
 * `displayLabel` is the human-readable string written to JSON / SharedPreferences
 * for backwards-compat with the previous String-typed field.
 */
enum class MaturityRating(val maxAge: Int, val displayLabel: String) {
    ALL(0, "All"),
    PG(7, "7+"),
    PG13(13, "13+"),
    R(17, "17+"),
    NC17(18, "18+");

    companion object {
        fun fromLabel(raw: String?): MaturityRating {
            if (raw.isNullOrBlank()) return NC17
            val trimmed = raw.trim()
            // Accept the legacy string forms the old code wrote ("7+", "13+", "18+",
            // "All Ages", etc.) and map to the enum.
            return when {
                trimmed.equals("All", true) || trimmed.equals("All Ages", true) || trimmed == "0+" -> ALL
                trimmed.startsWith("7") || trimmed.equals("PG", true) -> PG
                trimmed.startsWith("13") || trimmed.equals("PG13", true) || trimmed.equals("PG-13", true) -> PG13
                trimmed.startsWith("17") || trimmed.equals("R", true) -> R
                else -> NC17
            }
        }
    }
}

/**
 * Profile data class.
 *
 * Audit fixes (per spec):
 *   - Added `createdAt: Long` (epoch ms) and `lastUsedAt: Long` so the sync layer
 *     can resolve server-wins / client-wins conflicts deterministically.
 *   - `pin` is still stored on the model for backwards-compat with the
 *     SharedPreferences JSON blob, but **never** log it. New writes go through
 *     [hashPin] in the ViewModel so the in-memory + on-disk value is a SHA-256
 *     hex digest, not a plaintext 4-digit string.
 *   - Equality: `favoriteGenres` is a `List<String>` and Kotlin data-class
 *     equality uses `equals()` on the field, which is fine for an immutable
 *     `List<String>` (it falls back to `AbstractList.equals` content compare).
 *     We previously had a latent bug where `List<Profile>` was used as a key
 *     in some flows — avoid that.
 *   - The default `autoplayNext` / `autoplayPreviews` are `true` to match the
 *     shipped UX.
 */
data class Profile(
    val id: String,
    val name: String,
    val avatarColor: Color = Color(0xFFE50914),
    val isKid: Boolean = false,
    val avatarUrl: String? = null,
    /**
     * 4-digit PIN, or null if no PIN. For new writes this should be the
     * SHA-256 hex digest produced by [hashPin]; legacy plaintext PINs from
     * older installs are still accepted (the unlock path falls back to
     * direct equality for backward-compat). Never log this value.
     */
    val pin: String? = null,
    val language: String = "English",
    val autoplayNext: Boolean = true,
    val autoplayPreviews: Boolean = true,
    val maturityRating: String = "18+",
    val favoriteGenres: List<String> = emptyList(),
    val gameHandle: String? = null,
    /** When the profile was first created (epoch ms). Defaults to 0L = "unknown". */
    val createdAt: Long = 0L,
    /** Last time the user entered this profile (epoch ms). Defaults to 0L = "unknown". */
    val lastUsedAt: Long = 0L
)

/**
 * Resolve the maturity rating for a profile as a typed enum.
 */
val Profile.maturityRatingEnum: MaturityRating
    get() = MaturityRating.fromLabel(maturityRating)

// perf: @Stable lets Compose's strong-skipping mode treat Movie as a stable
// input. Without this annotation, every MovieCardItem and AnimatedContent
// targetState recomposition must re-evaluate the Movie even if all fields
// are structurally identical. The class is already immutable in practice —
// every field is `val` and we never mutate after construction.
@Stable
data class Movie(
    val id: String,
    val title: String,
    val description: String,
    val backdropUrl: String,
    val posterUrl: String,
    val rating: String = "18+",
    val year: String = "2024",
    val type: String = "Series",
    val duration: String = "3 Seasons",
    val logoUrl: String? = null,
    val isComingSoon: Boolean = false,
    val releaseDateBadge: String? = null,
    val isReminded: Boolean = false,
    // Per audit §4a: per-movie quality tags (HD, 4K, HDR, 5.1, CC, Atmos, etc.).
    // Empty list = no chips render, which is the safer failure mode than the
    // previous hardcoded "4K HDR" on every title.
    val quality: List<String> = emptyList(),
    // Per audit §9 (My List): epoch-ms when this movie was added to the user's
    // "My List". Used to sort the My List row in most-recently-added order
    // (descending). 0L = "unknown / never added" — the safer default than
    // -1L, which can collide with sentinel math elsewhere.
    val addedAt: Long = 0L,
    /** Optional external identity; artwork can resolve this through TMDB when absent. */
    val imdbId: String? = null
)

data class Episode(
    val id: Long,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val title: String,
    val description: String,
    val stillUrl: String,
    val runtime: String
)

fun isKidSafeMovie(movie: Movie): Boolean {
    val r = movie.rating.trim().uppercase()
    // Explicit adult ratings to restrict (13+, 14, 16, 18, R, TV-MA, NC-17)
    if (r.contains("18") || r.contains("16") || r.contains("14") || r.contains("TV-14") ||
        r.contains("R") || r.contains("TV-MA") || r.contains("NC-17") || r.contains("MATURE") ||
        r.contains("13")) {
        return false
    }

    val cleanNumber = r.filter { it.isDigit() }.toIntOrNull()
    if (cleanNumber != null) {
        return cleanNumber <= 12
    }

    val isExplicitKidRating = r == "G" || r == "PG" || r == "TV-Y" || r == "TV-Y7" || 
                              r == "TV-G" || r == "TV-PG" || r == "ALL" || r == "U" || 
                              r == "KIDS" || r.startsWith("7") || r.startsWith("10") || r.startsWith("12")
    val isAnimation = movie.type.equals("Animation", ignoreCase = true)
    
    return isExplicitKidRating || isAnimation
}
