package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TmdbBillboardMeta
import com.example.data.TmdbRepository
import com.example.model.Movie
import com.example.model.isSeriesContent
import kotlin.math.abs

enum class BillboardBadgeCategory {
    SEQUEL_FRANCHISE,
    RELEASE_FRESHNESS,
    AWARD_PRESTIGE,
    PERSONAL_AFFINITY,
    ADAPTATION_ORIGIN,
    TRENDING_POPULARITY
}

@Immutable
data class BillboardCalloutBadge(
    val emoji: String,
    val label: String,
    val category: BillboardBadgeCategory
)

object BillboardCalloutGenerator {

    private val NumberedSequelRegex = Regex(
        """^(.+?)\s+(?:Part\s+(?:Two|II|2|Three|III|3|Four|IV|4)|Chapter\s+(?:Two|II|2|Three|III|3|Four|IV|4)|2|3|4|5|II|III|IV)(?:\b|:.*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val KnownSubtitleSequels = listOf(
        "infinity castle", "mugen train", "swordsmith village", "hashira training",
        "the way of water", "fire and ash", "the last dance", "let there be carnage",
        "folie à deux", "folie a deux", "ride or die", "dead reckoning", "final reckoning",
        "across the spider-verse", "beyond the spider-verse", "no way home", "far from home",
        "wakanda forever", "multiverse of madness", "love and thunder", "quantumania",
        "the winter soldier", "civil war", "brave new world", "ragnarok",
        "resurrections", "reloaded", "revolutions", "romulus", "covenant",
        "a new empire", "king of the monsters", "fallen kingdom", "dominion", "rebirth",
        "kingdom of the planet of the apes", "war for the planet of the apes", "dawn of the planet of the apes",
        "furiosa", "fury road", "glass onion", "wake up dead man", "beetlejuice beetlejuice",
        "daryl dixon", "dead city", "the ones who live", "nocturne", "valhalla", "shippuden"
    )

    /**
     * Resolves a clean predecessor or franchise title when the movie/series is a sequel,
     * prioritizing TMDB `belongs_to_collection` / `collection/{id}` data, then keywords and title patterns.
     */
    fun inferSequelTitle(movie: Movie, meta: TmdbBillboardMeta?): String? {
        meta?.sequelOfTitle?.trim()?.takeIf {
            it.isNotBlank() && !it.equals(movie.title.trim(), ignoreCase = true)
        }?.let { return it }

        val cleanCollection = TmdbRepository.cleanFranchiseCollectionName(meta?.collectionName)
        if (!cleanCollection.isNullOrBlank() &&
            meta?.isFirstInCollection != true &&
            !cleanCollection.equals(movie.title.trim(), ignoreCase = true)
        ) {
            return cleanCollection
        }

        val trimmedTitle = movie.title.trim()
        val numberedMatch = NumberedSequelRegex.matchEntire(trimmedTitle)
        if (numberedMatch != null) {
            val base = numberedMatch.groupValues[1].trim().removeSuffix(":").removeSuffix("-").trim()
            if (base.length in 2..26 && !base.equals(trimmedTitle, ignoreCase = true)) {
                return base
            }
        }

        if (trimmedTitle.equals("Beetlejuice Beetlejuice", ignoreCase = true)) {
            return "Beetlejuice"
        }
        if (trimmedTitle.contains("Deadpool & Wolverine", ignoreCase = true)) {
            return "Deadpool 2"
        }

        val keywordsLower = meta?.keywords.orEmpty().map { it.lowercase() }
        val descLower = movie.description.lowercase()
        val titleLower = trimmedTitle.lowercase()
        val hasSequelSignal = keywordsLower.any { it.contains("sequel") || it.contains("part two") } ||
            KnownSubtitleSequels.any { titleLower.contains(it) } ||
            descLower.contains("sequel") || descLower.contains("returns") || descLower.contains("final battle")

        if (hasSequelSignal) {
            if (trimmedTitle.contains(":")) {
                val prefix = trimmedTitle.substringBefore(":").trim()
                if (prefix.length in 3..24 && !prefix.equals(trimmedTitle, ignoreCase = true)) {
                    return prefix
                }
            }
            if (trimmedTitle.contains(" - ")) {
                val prefix = trimmedTitle.substringBefore(" - ").trim()
                if (prefix.length in 3..24 && !prefix.equals(trimmedTitle, ignoreCase = true)) {
                    return prefix
                }
            }
        }

        return null
    }

    /**
     * Enriches generic duration strings ("Feature" / "Series") with real TMDB runtime or season count
     * when available (e.g., 154 -> "2h 34m", 3 seasons -> "3 Seasons").
     */
    fun enrichMovieWithMeta(movie: Movie, meta: TmdbBillboardMeta?): Movie {
        if (meta == null) return movie
        val currentDuration = movie.duration.trim()
        val isGenericDuration = currentDuration.isBlank() ||
            currentDuration.equals("Feature", ignoreCase = true) ||
            currentDuration.equals("Series", ignoreCase = true) ||
            currentDuration.equals("Movie", ignoreCase = true)

        if (!isGenericDuration) return movie

        val formattedDuration = if (movie.isSeriesContent()) {
            val seasons = meta.numberOfSeasons ?: 0
            if (seasons > 0) {
                if (seasons == 1) "1 Season" else "$seasons Seasons"
            } else null
        } else {
            val mins = meta.runtimeMinutes ?: 0
            if (mins > 0) {
                val h = mins / 60
                val m = mins % 60
                when {
                    h > 0 && m > 0 -> "${h}h ${m}m"
                    h > 0 -> "${h}h"
                    else -> "${m}m"
                }
            } else null
        }

        return if (!formattedDuration.isNullOrBlank()) {
            movie.copy(duration = formattedDuration)
        } else {
            movie
        }
    }

    /**
     * Generates 2 dynamic, non-repetitive callout badges for the billboard bottom-right corner
     * before preview playback begins, driven by TMDB metadata and user profile signals.
     */
    fun resolveBadges(
        movie: Movie,
        meta: TmdbBillboardMeta? = null,
        favoriteGenres: List<String> = emptyList(),
        likedMovies: List<Movie> = emptyList(),
        watchedMovies: List<Movie> = emptyList(),
        inMyList: Boolean = false,
        rotationSlot: Int = 0
    ): List<BillboardCalloutBadge> {
        if (movie.id == "0" || movie.title.isBlank()) return emptyList()

        val isTv = movie.isSeriesContent() || meta?.isTv == true
        val yearInt = (meta?.releaseDate?.take(4) ?: movie.year.filter { it.isDigit() }.take(4)).toIntOrNull() ?: 2025
        val lastAirYearInt = meta?.lastAirDate?.take(4)?.toIntOrNull() ?: yearInt
        val voteAvg = meta?.voteAverage ?: when {
            movie.rating.contains("18") -> 8.1
            movie.rating.contains("16") -> 7.6
            else -> 7.3
        }
        val voteCount = meta?.voteCount ?: 600
        val popularity = meta?.popularity ?: 120.0
        val keywordsLower = meta?.keywords.orEmpty().map { it.lowercase() }
        val feeds = meta?.sourceFeeds.orEmpty()
        val genreIds = meta?.genreIds.orEmpty()
        val genreNamesLower = meta?.genreNames.orEmpty().map { it.lowercase() }
        val titleAndDescLower = "${movie.title} ${movie.description}".lowercase()
        val movieHash = abs(movie.id.hashCode() * 31 + movie.title.hashCode())

        val candidatesByCategory = LinkedHashMap<BillboardBadgeCategory, MutableList<BillboardCalloutBadge>>()
        fun addCandidate(badge: BillboardCalloutBadge) {
            val list = candidatesByCategory.getOrPut(badge.category) { mutableListOf() }
            if (list.none { it.label.equals(badge.label, ignoreCase = true) }) {
                list.add(badge)
            }
        }

        // 1. SEQUEL / FRANCHISE (from TMDB belongs_to_collection / collection parts / title)
        val sequelTitle = inferSequelTitle(movie, meta)
        if (!sequelTitle.isNullOrBlank()) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🎬",
                    label = "Sequel of $sequelTitle",
                    category = BillboardBadgeCategory.SEQUEL_FRANCHISE
                )
            )
        } else if (meta?.isFirstInCollection == true) {
            val cleanCol = TmdbRepository.cleanFranchiseCollectionName(meta.collectionName)
            if (!cleanCol.isNullOrBlank()) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🎬",
                        label = "$cleanCol Franchise",
                        category = BillboardBadgeCategory.SEQUEL_FRANCHISE
                    )
                )
            }
        } else if (keywordsLower.any { it.contains("spin off") || it.contains("spinoff") || it.contains("prequel") }) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🎬",
                    label = "Prequel & Spin-Off",
                    category = BillboardBadgeCategory.SEQUEL_FRANCHISE
                )
            )
        }

        // 2. RELEASE / FRESHNESS (Recently added, New Season, Coming Soon)
        if (movie.isComingSoon || !movie.releaseDateBadge.isNullOrBlank()) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🗓️",
                    label = movie.releaseDateBadge ?: "Coming Soon",
                    category = BillboardBadgeCategory.RELEASE_FRESHNESS
                )
            )
        } else {
            val isRecentlyAdded = yearInt >= 2024 ||
                lastAirYearInt >= 2025 ||
                feeds.contains("now_playing") ||
                feeds.contains("trending")
            if (isRecentlyAdded) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "📢",
                        label = "Recently added",
                        category = BillboardBadgeCategory.RELEASE_FRESHNESS
                    )
                )
            }
            if (isTv && ((meta?.numberOfSeasons ?: 0) >= 2 || movie.duration.contains("Season", ignoreCase = true)) &&
                (lastAirYearInt >= 2024 || meta?.tvStatus.equals("Returning Series", ignoreCase = true))
            ) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🔥",
                        label = "New Season",
                        category = BillboardBadgeCategory.RELEASE_FRESHNESS
                    )
                )
            }
            if (meta?.hasUpcomingEpisode == true) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🗓️",
                        label = "New Episode Weekly",
                        category = BillboardBadgeCategory.RELEASE_FRESHNESS
                    )
                )
            }
        }

        // 3. AWARDS & CRITICAL PRESTIGE (Golden Globe Nominee, Emmy Nominee, Oscar, BAFTA, Critics' Pick)
        val hasEmmyKeyword = keywordsLower.any { it.contains("emmy") }
        val hasGoldenGlobeKeyword = keywordsLower.any { it.contains("golden globe") }
        val hasOscarKeyword = keywordsLower.any { it.contains("oscar") || it.contains("academy award") }
        val isBritish = meta?.originCountry?.contains("GB") == true
        val isAcclaimed = voteAvg >= 7.1 || feeds.contains("top_rated") || feeds.contains("award_winning")

        if (isTv) {
            if (hasEmmyKeyword || isAcclaimed) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🏆",
                        label = "Emmy Nominee",
                        category = BillboardBadgeCategory.AWARD_PRESTIGE
                    )
                )
            }
            if (hasGoldenGlobeKeyword || voteAvg >= 7.3 || feeds.contains("award_winning")) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🏆",
                        label = "Golden Globe Nominee",
                        category = BillboardBadgeCategory.AWARD_PRESTIGE
                    )
                )
            }
        } else {
            if (hasGoldenGlobeKeyword || isAcclaimed || genreIds.contains(16L) || genreIds.contains(18L)) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🏆",
                        label = "Golden Globe Nominee",
                        category = BillboardBadgeCategory.AWARD_PRESTIGE
                    )
                )
            }
            if (hasOscarKeyword || (voteAvg >= 7.9 && voteCount >= 700) || feeds.contains("award_winning")) {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🏆",
                        label = "Oscar Nominee",
                        category = BillboardBadgeCategory.AWARD_PRESTIGE
                    )
                )
            }
        }
        if (isBritish && voteAvg >= 7.3) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🏆",
                    label = "BAFTA Nominee",
                    category = BillboardBadgeCategory.AWARD_PRESTIGE
                )
            )
        }
        if (voteAvg >= 7.8) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🌟",
                    label = "Critics' Pick",
                    category = BillboardBadgeCategory.AWARD_PRESTIGE
                )
            )
        }

        // 4. PERSONALIZED AFFINITY ("We thought you'll like this", "Because you liked ...")
        val relatedLiked = likedMovies.firstOrNull { liked ->
            liked.id != movie.id && (
                liked.type.equals(movie.type, ignoreCase = true) ||
                sharesThemeWords(liked, movie)
            )
        }
        val matchesFavGenre = favoriteGenres.any { fav ->
            val f = fav.lowercase()
            genreNamesLower.any { it.contains(f) } || titleAndDescLower.contains(f)
        }
        val relatedWatched = watchedMovies.firstOrNull { watched ->
            watched.id != movie.id && sharesThemeWords(watched, movie)
        }

        // Primary affinity badge requested by user
        if (matchesFavGenre || relatedLiked != null || relatedWatched != null || voteAvg >= 6.8 || popularity >= 60.0) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "👍",
                    label = "We thought you'll like this",
                    category = BillboardBadgeCategory.PERSONAL_AFFINITY
                )
            )
        }
        if (relatedLiked != null && relatedLiked.title.length <= 20) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "❤️",
                    label = "Because you liked ${relatedLiked.title}",
                    category = BillboardBadgeCategory.PERSONAL_AFFINITY
                )
            )
        } else if (inMyList) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "📌",
                    label = "In Your List",
                    category = BillboardBadgeCategory.PERSONAL_AFFINITY
                )
            )
        }

        // 5. ADAPTATION & ORIGIN (Manga, Book, True Story, Game, Comic, K-Drama, Limited Series)
        val isAnime = genreIds.contains(16L) && (meta?.originalLanguage == "ja" || feeds.contains("anime") ||
            titleAndDescLower.contains("anime") || titleAndDescLower.contains("hashira") || titleAndDescLower.contains("demon"))
        when {
            keywordsLower.any { it.contains("manga") || it.contains("shonen") } || isAnime -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "📖",
                        label = "Based on Hit Manga",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            keywordsLower.any { it.contains("based on novel") || it.contains("based on book") || it.contains("based on young adult") } -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "📚",
                        label = "Based on a Bestseller",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            keywordsLower.any { it.contains("true story") || it.contains("biography") } || genreIds.contains(36L) -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "📜",
                        label = "Based on a True Story",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            keywordsLower.any { it.contains("video game") } -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🎮",
                        label = "Based on the Hit Game",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            keywordsLower.any { it.contains("based on comic") || it.contains("superhero") || it.contains("marvel") || it.contains("dc comics") } -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🦸",
                        label = "Based on the Iconic Comic",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            meta?.tvType.equals("Miniseries", ignoreCase = true) -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "📺",
                        label = "Limited Series",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            meta?.originalLanguage == "ko" -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🇰🇷",
                        label = "K-Drama Sensation",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
            meta?.originalLanguage != null && meta.originalLanguage != "en" && popularity >= 110.0 -> {
                addCandidate(
                    BillboardCalloutBadge(
                        emoji = "🌍",
                        label = "Global Phenomenon",
                        category = BillboardBadgeCategory.ADAPTATION_ORIGIN
                    )
                )
            }
        }

        // 6. TRENDING & POPULARITY
        val rank = meta?.trendingRank
        if (rank != null && rank in 1..10) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🔥",
                    label = "#$rank in Trending Today",
                    category = BillboardBadgeCategory.TRENDING_POPULARITY
                )
            )
        } else if (voteCount >= 1800 || popularity >= 180.0 || feeds.contains("popular")) {
            addCandidate(
                BillboardCalloutBadge(
                    emoji = "🍿",
                    label = "Fan Favorite",
                    category = BillboardBadgeCategory.TRENDING_POPULARITY
                )
            )
        }

        // Select 2 distinct-category badges using a deterministic per-movie + slot rotation
        // so consecutive billboards never show the exact same pair.
        fun pickFromCategory(cat: BillboardBadgeCategory): BillboardCalloutBadge? {
            val list = candidatesByCategory[cat].orEmpty()
            if (list.isEmpty()) return null
            return list[(movieHash + rotationSlot) % list.size]
        }

        val categoryOrderTemplates: List<List<BillboardBadgeCategory>> = listOf(
            // Template 0: Recently added + Golden Globe / Emmy Nominee (matches screenshot)
            listOf(
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.TRENDING_POPULARITY,
                BillboardBadgeCategory.ADAPTATION_ORIGIN
            ),
            // Template 1: Sequel of ... + We thought you'll like this / Award
            listOf(
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.ADAPTATION_ORIGIN,
                BillboardBadgeCategory.TRENDING_POPULARITY
            ),
            // Template 2: Emmy / Golden Globe Nominee + We thought you'll like this
            listOf(
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.TRENDING_POPULARITY,
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.ADAPTATION_ORIGIN
            ),
            // Template 3: Sequel / Adaptation + Recently added
            listOf(
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.ADAPTATION_ORIGIN,
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.TRENDING_POPULARITY
            ),
            // Template 4: We thought you'll like this + Recently added / Trending
            listOf(
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.TRENDING_POPULARITY,
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.ADAPTATION_ORIGIN
            ),
            // Template 5: Trending / Popularity + Award / Sequel
            listOf(
                BillboardBadgeCategory.TRENDING_POPULARITY,
                BillboardBadgeCategory.AWARD_PRESTIGE,
                BillboardBadgeCategory.SEQUEL_FRANCHISE,
                BillboardBadgeCategory.PERSONAL_AFFINITY,
                BillboardBadgeCategory.RELEASE_FRESHNESS,
                BillboardBadgeCategory.ADAPTATION_ORIGIN
            )
        )

        val selected = mutableListOf<BillboardCalloutBadge>()
        val usedCategories = mutableSetOf<BillboardBadgeCategory>()

        // If this title has an explicit "Sequel of ..." badge, prioritize showing it on Sequel-eligible slots
        val sequelBadge = candidatesByCategory[BillboardBadgeCategory.SEQUEL_FRANCHISE]
            ?.firstOrNull { it.label.startsWith("Sequel of", ignoreCase = true) }
        if (sequelBadge != null && (movieHash + rotationSlot) % 3 != 0) {
            selected.add(sequelBadge)
            usedCategories.add(BillboardBadgeCategory.SEQUEL_FRANCHISE)
        }

        val templateIdx = ((movieHash % categoryOrderTemplates.size) + abs(rotationSlot)) % categoryOrderTemplates.size
        val orderedCategories = categoryOrderTemplates[templateIdx]

        for (cat in orderedCategories) {
            if (selected.size >= 2) break
            if (cat in usedCategories) continue
            val picked = pickFromCategory(cat)
            if (picked != null) {
                selected.add(picked)
                usedCategories.add(cat)
            }
        }

        // Fallback if fewer than 2 badges were found
        if (selected.isEmpty()) {
            selected.add(
                BillboardCalloutBadge(
                    emoji = "👍",
                    label = "We thought you'll like this",
                    category = BillboardBadgeCategory.PERSONAL_AFFINITY
                )
            )
        }

        return selected.take(2)
    }

    private fun sharesThemeWords(a: Movie, b: Movie): Boolean {
        val wordsA = a.title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val wordsB = b.title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        return wordsA.intersect(wordsB).isNotEmpty()
    }
}

@Composable
fun BillboardCalloutBadgesRow(
    badges: List<BillboardCalloutBadge>,
    darkenedMoodColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (badges.isEmpty()) return

    val pillBackground = remember(darkenedMoodColor) {
        lerp(Color(0xFF0A0C10), darkenedMoodColor, 0.22f).copy(alpha = 0.84f)
    }
    val pillBorder = remember { Color.White.copy(alpha = 0.12f) }
    val pillShape = remember(compact) { RoundedCornerShape(if (compact) 6.dp else 8.dp) }
    val rowSpacing = if (compact) 5.dp else 8.dp
    val horizontalPad = if (compact) 8.dp else 12.dp
    val verticalPad = if (compact) 4.5.dp else 7.dp
    val innerSpacing = if (compact) 4.dp else 6.dp
    val emojiSize = if (compact) 11.sp else 13.sp
    val textSize = if (compact) 10.5.sp else 13.sp
    val lineHeight = if (compact) 13.sp else 16.sp
    val maxPillTextWidth = if (compact) 128.dp else 220.dp

    Row(
        modifier = modifier.testTag("billboard_callout_badges"),
        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEachIndexed { index, badge ->
            Box(
                modifier = Modifier
                    .testTag("billboard_callout_badge_$index")
                    .clip(pillShape)
                    .background(pillBackground, pillShape)
                    .border(0.8.dp, pillBorder, pillShape)
                    .padding(horizontal = horizontalPad, vertical = verticalPad)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(innerSpacing)
                ) {
                    Text(
                        text = badge.emoji,
                        fontSize = emojiSize,
                        lineHeight = lineHeight,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = badge.label,
                        color = Color.White,
                        fontSize = textSize,
                        lineHeight = lineHeight,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = maxPillTextWidth),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}
