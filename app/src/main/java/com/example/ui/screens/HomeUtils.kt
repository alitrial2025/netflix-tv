package com.example.ui.screens

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.model.Movie
import com.example.ui.theme.NetflixRed

/**
 * Builds a moving horizontal gradient brush used to render skeleton/shimmer placeholders.
 *
 * The brush is only animated when [showShimmer] is `true`; otherwise a static solid color
 * is returned so the caller does not pay the cost of the infinite transition when the
 * skeleton is no longer visible.
 *
 * @param showShimmer when `false` the function returns a static `SolidColor` and skips
 *  the rememberInfiniteTransition allocation.
 * @param targetValue horizontal pixel offset for the moving gradient end-point.
 * @return a [Brush] suitable for use as a `Modifier.then(shimmer)`.
 */
@Composable
fun shimmerBrush(
    showShimmer: Boolean = true,
    targetValue: Float = 1000f
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            Color(0xFF0F0F0F),
            Color(0xFF1C1C1C),
            Color(0xFF0F0F0F)
        )

        val transition = rememberInfiniteTransition(label = "shimmerTransition")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerTranslate"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation.value, y = translateAnimation.value)
        )
    } else {
        SolidColor(Color(0xFF0F0F0F))
    }
}

@Composable
private fun shimmerBackground(showShimmer: Boolean = true): Modifier {
    if (!showShimmer) return Modifier.background(Color(0xFF171717))
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val offset = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonShimmerOffset"
    )
    val colors = remember { listOf(Color(0xFF0F0F0F), Color(0xFF1C1C1C), Color(0xFF0F0F0F)) }
    return Modifier.drawBehind {
        drawRect(Brush.linearGradient(colors, Offset.Zero, Offset(offset.value, offset.value)))
    }
}

@Composable
fun ShimmerBillboard(
    height: Dp = 435.dp,
    modifier: Modifier = Modifier
) {
    val shimmer = shimmerBackground()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141414))
        ) {
            // Shimmer Backdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(shimmer)
            )

            // Dark vignette gradients matching real billboard for true aesthetic parity
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Transparent
                                ),
                                endX = size.width * 0.65f
                            )
                        )
                        drawRect(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.3f),
                                    Color.Black.copy(alpha = 0.95f)
                                )
                            )
                        )
                    }
            )

            // Content Overlay (Bottom-Left) matching BillboardSection
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 36.dp, bottom = 28.dp)
                    .widthIn(max = 540.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Title / Logo Placeholder
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .height(52.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(shimmer)
                )

                // Subtitle / Overview 2-line Description Placeholder
                Column(
                    modifier = Modifier.padding(top = 6.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.60f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Metadata Details Row Placeholder
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                    Text(text = "•", color = Color.Gray, fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                    Text(text = "•", color = Color.Gray, fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                    Text(text = "•", color = Color.Gray, fontSize = 14.sp)

                    // Age Rating Badge Placeholder
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NetflixRed.copy(alpha = 0.8f))
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons Row (Play & More Info) Placeholder matching BillboardButton
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play Button Placeholder
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.Black.copy(alpha = 0.7f))
                            )
                        }
                    }

                    // More Info Button Placeholder
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4B5563).copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .width(62.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.8f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShimmerCategoriesBar(
    height: Dp = 116.dp,
    modifier: Modifier = Modifier
) {
    val shimmer = shimmerBackground()
    val chipWidths = remember { listOf(230.dp, 180.dp, 180.dp, 180.dp, 180.dp, 180.dp) }
    val titleWidths = remember { listOf(90.dp, 75.dp, 85.dp, 70.dp, 80.dp, 75.dp) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            chipWidths.forEachIndexed { index, width ->
                val textW = titleWidths.getOrElse(index) { 80.dp }
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF343434),
                                    Color(0xFF262626)
                                )
                            )
                        )
                        .then(shimmer),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(textW)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreenSkeleton(visibleRowCount: Int = 1, showShimmer: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 120.dp)
    ) {
        repeat(visibleRowCount.coerceIn(1, 3)) { index ->
            ShimmerMovieRow(
                titleWidth = if (index == 0) 200.dp else if (index == 1) 160.dp else 220.dp,
                showShimmer = showShimmer
            )
        }
    }
}

@Composable
fun ShimmerMovieRow(
    height: Dp = 410.dp,
    titleWidth: Dp = 180.dp,
    modifier: Modifier = Modifier,
    showShimmer: Boolean = true
) {
    val shimmer = shimmerBackground(showShimmer)
    val baseWidth = 190.dp
    val cardHeight = 270.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 4.dp)
    ) {
        // Row Title Placeholder
        Box(
            modifier = Modifier
                .padding(start = 16.dp, bottom = 8.dp)
                .width(titleWidth)
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(shimmer)
        )

        // Movie Poster Cards Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(6) { index ->
                val cardW = if (index == 0) baseWidth * 2.3f else baseWidth
                Box(
                    modifier = Modifier
                        .width(cardW)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D0D0D))
                        .then(shimmer)
                ) {
                    // Dark bottom gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                                    startY = 120f
                                )
                            )
                    )
                    // Card title / logo shimmer bar at bottom
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .width(if (index == 0) 140.dp else 100.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .then(shimmer)
                    )
                }
            }
        }

        // Active Movie Metadata & Synopsis Placeholder below row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(start = 40.dp, top = 6.dp, end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Metadata Details Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .then(shimmer)
                )
                Text(text = "•", color = Color.Gray, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .then(shimmer)
                )
                Text(text = "•", color = Color.Gray, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .then(shimmer)
                )
                Text(text = "•", color = Color.Gray, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NetflixRed.copy(alpha = 0.8f))
                )
            }

            // Synopsis text lines
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .then(shimmer)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.40f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .then(shimmer)
            )
        }
    }
}

object PaletteExtractor {
    private val colorCache = android.util.LruCache<String, Color>(128)

    /** A return visit starts from its real tint, avoiding a placeholder-colour flash. */
    fun cachedColorFromMovie(movie: Movie?): Color? {
        val url = movie?.let { it.backdropUrl.ifBlank { it.posterUrl } } ?: return null
        return if (url.isBlank()) null else colorCache.get(url)
    }

    // Pre-allocated fallback palette — avoids allocating a new List<Color> for every
    // mood-color lookup, which happens once per recomposition of the billboard hero.
    private val MoodFallbackPalette: List<Color> = listOf(
        Color(0xFF121E3D), // Deep Cobalt Night
        Color(0xFF2E0A14), // Deep Wine Noir
        Color(0xFF0C2731), // Deep Steel Cyan
        Color(0xFF2C1C0B), // Deep Amber Smoke
        Color(0xFF210D33), // Deep Royal Velvet
        Color(0xFF0D2820), // Deep Forest Obsidian
        Color(0xFF0D203B), // Deep Midnight Sapphire
        Color(0xFF2A101A)  // Deep Bordeaux
    )

    /**
     * Extracts a dominant ambient color from [movie]'s backdrop (or poster) for use as
     * a background tint. Results are cached in [colorCache] keyed by image URL, so
     * repeated lookups for the same movie are O(1) and never re-decode the bitmap.
     *
     * @param context Android context for Coil's [Coil.imageLoader].
     * @param movie the movie whose backdrop/poster will be sampled; may be `null`.
     * @return a tuned ambient color (saturation/lightness clamped to Netflix mood
     *  range). Always non-null.
     */
    suspend fun extractColorFromMovie(context: Context, movie: Movie?): Color {
        if (movie == null) return Color(0xFF162034)
        val imageUrl = movie.backdropUrl.ifBlank { movie.posterUrl }
        if (imageUrl.isBlank()) return extractMovieMoodFallback(movie)

        colorCache.get(imageUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val loader = Coil.imageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(TvImagePolicy.artworkUrl(imageUrl, 300,
                        if (movie.backdropUrl.isNotBlank()) TvArtworkKind.BACKDROP else TvArtworkKind.POSTER))
                    .allowHardware(false)
                    .size(100, 100)
                    .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap

                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val swatch = palette.darkVibrantSwatch
                        ?: palette.vibrantSwatch
                        ?: palette.dominantSwatch
                        ?: palette.darkMutedSwatch
                        ?: palette.mutedSwatch

                    val baseColor = if (swatch != null) Color(swatch.rgb) else extractMovieMoodFallback(movie)

                    // Fine-tune tone for rich, visible ambient Netflix mood glow
                    // Fine-tune tone for rich, non-overdone cinematic Netflix mood glow
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(
                        android.graphics.Color.rgb(
                            (baseColor.red * 255).toInt(),
                            (baseColor.green * 255).toInt(),
                            (baseColor.blue * 255).toInt()
                        ),
                        hsv
                    )
                    hsv[1] = (hsv[1] * 1.12f).coerceIn(0.32f, 0.68f) // Refined, natural saturation (never neon/overdone)
                    hsv[2] = (hsv[2] * 0.80f).coerceIn(0.18f, 0.36f) // Deep, atmospheric ambient darkness

                    val finalColor = Color(android.graphics.Color.HSVToColor(hsv))
                    colorCache.put(imageUrl, finalColor)
                    finalColor
                } else {
                    val fallback = extractMovieMoodFallback(movie)
                    colorCache.put(imageUrl, fallback)
                    fallback
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val fallback = extractMovieMoodFallback(movie)
                colorCache.put(imageUrl, fallback)
                fallback
            }
        }
    }

    /**
     * Pure (non-suspending) fallback that derives a mood color from a movie's metadata
     * keywords. Used both as a pre-image-decode placeholder and as the cache-miss
     * fallback inside [extractColorFromMovie].
     *
     * @param movie the movie whose title/type/description will be keyword-matched.
     * @return a tuned ambient color drawn from [MoodFallbackPalette].
     */
    fun extractMovieMoodFallback(movie: Movie?): Color {
        if (movie == null) return Color(0xFF101B34)
        val text = (movie.title + " " + movie.id + " " + movie.type + " " + movie.description).lowercase()

        return when {
            text.contains("jujutsu") || text.contains("anime") -> Color(0xFF101B38) // Deep Twilight Cobalt
            text.contains("stranger") || text.contains("horror") || text.contains("fist") -> Color(0xFF330C14) // Deep Velvet Crimson
            text.contains("dune") || text.contains("sand") || text.contains("gold") -> Color(0xFF30200B) // Deep Espresso Amber
            text.contains("arcane") || text.contains("cyber") -> Color(0xFF0B2434) // Deep Obsidian Cyan
            text.contains("oppenheimer") || text.contains("war") -> Color(0xFF142416) // Deep Charcoal Pine
            text.contains("series") || text.contains("tv") -> Color(0xFF220F33) // Deep Plum Night
            text.contains("movie") || text.contains("film") -> Color(0xFF0F262D) // Deep Midnight Teal
            else -> {
                val index = (movie.title.hashCode() and Int.MAX_VALUE) % MoodFallbackPalette.size
                MoodFallbackPalette[index]
            }
        }
    }
}

/**
 * Convenience wrapper around [PaletteExtractor.extractMovieMoodFallback].
 *
 * @param movie the movie to derive the mood color from.
 * @return the derived ambient color.
 */
fun extractMovieMoodColor(movie: Movie?): Color = PaletteExtractor.extractMovieMoodFallback(movie)

/**
 * Modifier that intercepts D-pad key events on a focusable Compose element. Each
 * handler returns `true` to mark the event as consumed (and stop propagation).
 *
 * @param onDpadUp invoked on [KeyEvent.KEYCODE_DPAD_UP] KeyDown; return `true` to consume.
 * @param onDpadDown invoked on [KeyEvent.KEYCODE_DPAD_DOWN] KeyDown; return `true` to consume.
 * @param onDpadLeft invoked on [KeyEvent.KEYCODE_DPAD_LEFT] KeyDown; return `true` to consume.
 * @param onDpadRight invoked on [KeyEvent.KEYCODE_DPAD_RIGHT] KeyDown; return `true` to consume.
 */
fun Modifier.handleTvDpadNavigation(
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false },
    onDpadLeft: () -> Boolean = { false },
    onDpadRight: () -> Boolean = { false }
): Modifier = this.onPreviewKeyEvent { keyEvent ->
    if (keyEvent.type == KeyEventType.KeyDown) {
        when (keyEvent.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDown()
            KeyEvent.KEYCODE_DPAD_UP -> onDpadUp()
            KeyEvent.KEYCODE_DPAD_LEFT -> onDpadLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> onDpadRight()
            else -> false
        }
    } else false
}

// Smart Netflix Recommendation Engine Helpers

private val NonAlphaNumericRegex = Regex("[^a-zA-Z0-9]+")
private val StopWords = setOf("with", "that", "this", "from", "they", "their", "into", "when", "after", "where", "about")

/**
 * Returns `true` if any of the given [keywords] appears in the movie's title,
 * description, type, or rating (case-insensitive).
 *
 * @param keywords space-insensitive tokens to search for.
 */
fun Movie.matchesThemes(vararg keywords: String): Boolean {
    val text = "$title $description $type $rating".lowercase()
    return keywords.any { kw -> text.contains(kw.lowercase()) }
}

/**
 * Builds a list of up to [limit] movies from [catalog] that are most similar to
 * [seedMovie], using a cheap keyword-overlap + same-type boost scoring heuristic.
 *
 * @param seedMovie the movie whose keywords seed the scoring.
 * @param catalog the candidate pool to rank.
 * @param limit maximum number of results returned.
 * @return the top-scoring movies, ordered by descending score, then catalog order.
 */
fun findSimilarMovies(seedMovie: Movie, catalog: List<Movie>, limit: Int = 10): List<Movie> {
    val seedKeywords = ("${seedMovie.title} ${seedMovie.description} ${seedMovie.type}")
        .lowercase()
        .split(NonAlphaNumericRegex)
        .filter { it.length > 3 && it !in StopWords }
        .toSet()

    return catalog
        .asSequence()
        .filter { it.id != seedMovie.id }
        .map { candidate ->
            val candidateText = "${candidate.title} ${candidate.description} ${candidate.type}".lowercase()
            var score = 0
            if (candidate.type == seedMovie.type) score += 2
            seedKeywords.forEach { kw ->
                if (candidateText.contains(kw)) score += 3
            }
            candidate to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit)
        .toList()
}

/**
 * Builds a "Coming Soon" rail by reusing movies already flagged as
 * [Movie.isComingSoon] and, if fewer than 4 are present, padding the list with
 * shuffled picks from [allCatalogMovies] tagged with synthetic release-date badges.
 *
 * @param allCatalogMovies the source pool of movies.
 * @param seed deterministic seed for the random shuffle; pass a stable value to get
 *  the same padding across recompositions / profile switches.
 * @return at most 10 movies with `isComingSoon = true`.
 */
fun buildComingSoonList(allCatalogMovies: List<Movie>, seed: Int = 42): List<Movie> {
    val rng = kotlin.random.Random(seed)
    val existing = allCatalogMovies.filter { it.isComingSoon }
    if (existing.size >= 4) return existing

    val releaseDates = listOf("Coming Nov 15", "Coming Dec 2", "Coming Friday", "Season 2 Coming Soon", "Coming Oct 30", "Coming Dec 20", "Coming Soon", "Jan 12")
    val candidates = allCatalogMovies.shuffled(rng).take(10)
    return candidates.mapIndexed { index, movie ->
        movie.copy(
            isComingSoon = true,
            releaseDateBadge = releaseDates[index % releaseDates.size]
        )
    }
}

/**
 * Builds the list of `(row title, row movies)` pairs displayed on the home screen
 * for the given [activeTab] and [profileName]. The output is content-driven
 * (different per tab and per kid/adult profile) and safe to recompose because it
 * is deterministic when [randomSeed] is fixed.
 *
 * @param activeTab one of `"Home"`, `"Series"`, `"Films"`, `"My Netflix"`.
 * @param allCatalogMovies full catalog (movies + series + kids-safe items).
 * @param allMoviesList filtered list of Movie-type items.
 * @param allSeriesList filtered list of Series-type items.
 * @param continueWatchingMovies the user's continue-watching rail.
 * @param myListMovies the user's saved-for-later list.
 *  (Ordering is overridden by [myListAddedAt] when provided, so the caller
 *  does not need to pre-sort.)
 * @param categoryRows pre-computed category rows (currently unused; kept for API
 *  compatibility with older callers).
 * @param profileName used to personalize row titles.
 * @param isKidProfile when `true`, filters the catalog with [com.example.model.isKidSafeMovie]
 *  and switches to the kid-targeted row set.
 * @param randomSeed seed for deterministic shuffling; pass the same value to get
 *  the same row ordering across recompositions.
 * @param myListAddedAt per-movie addedAt (epoch ms) used to sort the My List
 *  row in "most recently added first" order. Optional; when null/empty the
 *  function falls back to the input order so older callers keep working.
 */
fun buildAlgorithmicRowsForTab(
    activeTab: String,
    allCatalogMovies: List<Movie>,
    allMoviesList: List<Movie>,
    allSeriesList: List<Movie>,
    continueWatchingMovies: List<Movie>,
    myListMovies: List<Movie>,
    categoryRows: List<Pair<String, List<Movie>>>,
    profileName: String,
    isKidProfile: Boolean,
    randomSeed: Int,
    myListAddedAt: Map<String, Long> = emptyMap()
): List<Pair<String, List<Movie>>> {
    // Reuse normalized metadata across the theme scans in this one generation.
    // The cache dies with the build, so it cannot retain an old catalogue/profile.
    val themeText = HashMap<String, String>(allCatalogMovies.size)
    fun Movie.matchesThemes(vararg keywords: String): Boolean {
        val text = themeText.getOrPut(id) { "$title $description $type $rating".lowercase() }
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
    val rng = kotlin.random.Random(randomSeed)
    val rows = mutableListOf<Pair<String, List<Movie>>>()
    // perf: sort the My List list once, up front, so every branch that
    // appends `myListMovies` to a row gets the "most recently added first"
    // ordering for free. Previously the row was emitted in catalog-id order
    // (whichever order the catalog filter produced), which is the wrong UX
    // for a "My List" rail — the user expects the newest save at the front.
    val sortedMyListMovies: List<Movie> = if (myListAddedAt.isNotEmpty() && myListMovies.isNotEmpty()) {
        myListMovies.sortedByDescending { myListAddedAt[it.id] ?: 0L }
    } else {
        myListMovies
    }

    if (isKidProfile) {
        val safeCatalog = allCatalogMovies.filter { com.example.model.isKidSafeMovie(it) }.ifEmpty { allCatalogMovies }
        val safeSeries = safeCatalog.filter { it.type.equals("Series", ignoreCase = true) || it.type.equals("TV", ignoreCase = true) || it.duration.contains("Season", ignoreCase = true) }.ifEmpty { safeCatalog }
        val safeFilms = safeCatalog.filter { it.type.equals("Movie", ignoreCase = true) || it.type.equals("Animation", ignoreCase = true) || !it.duration.contains("Season", ignoreCase = true) }.ifEmpty { safeCatalog }
        val safeAnimations = safeCatalog.filter { it.type == "Animation" || it.matchesThemes("cartoon", "anime", "animated", "magic") }.ifEmpty { safeCatalog }

        when (activeTab) {
            "Series" -> {
                // 1. Continue Watching Kids Series
                val continueSeries = continueWatchingMovies.filter { it.type.equals("Series", ignoreCase = true) || it.duration.contains("Season", ignoreCase = true) }
                if (continueSeries.isNotEmpty()) {
                    rows.add("Continue Watching Kids Shows for $profileName" to continueSeries)
                }
                // 2. Top 10 Kids TV Shows Today
                val top10Series = safeSeries.take(10)
                if (top10Series.isNotEmpty()) {
                    rows.add("Top 10 Kids TV Shows Today" to top10Series)
                }
                // 3. Because You Watched
                val lastSeries = continueSeries.firstOrNull() ?: safeSeries.firstOrNull()
                if (lastSeries != null) {
                    val similar = findSimilarMovies(lastSeries, safeSeries, 10)
                    if (similar.isNotEmpty()) {
                        rows.add("Because You Watched ${lastSeries.title}" to similar)
                    }
                }
                // 4. Animated TV Series & Cartoons
                rows.add("Animated TV Series & Cartoons" to safeSeries.filter { it.matchesThemes("animation", "cartoon", "anime", "pokemon") }.ifEmpty { safeSeries.shuffled(rng) }.take(10))
                // 5. Action, Quests & Superhero Series
                rows.add("Action Heroes & Quests TV Series" to safeSeries.filter { it.matchesThemes("action", "hero", "quest", "power", "dragon") }.ifEmpty { safeSeries.shuffled(rng) }.take(10))
                // 6. Funny & Laugh-Out-Loud Cartoons
                rows.add("Funny & Laugh-Out-Loud Cartoons" to safeSeries.filter { it.matchesThemes("comedy", "funny", "sponge", "laugh") }.ifEmpty { safeSeries.reversed() }.take(10))
                // 7. Anime & Fantasy Series for Kids
                rows.add("Magical & Anime TV Series for Kids" to safeSeries.filter { it.matchesThemes("anime", "magic", "fantasy", "pokemon", "one piece") }.ifEmpty { safeSeries.shuffled(rng) }.take(10))
                // 8. Binge-Worthy Family TV Adventures
                rows.add("Binge-Worthy Family TV Adventures" to safeSeries.takeLast(10).reversed())
                // 9. Short & Sweet: Quick Episodes
                rows.add("Short & Sweet: Quick Episodes" to safeSeries.shuffled(rng).take(10))
                // 10. Popular Kids Shows on Netflix
                rows.add("Popular Kids Shows on Netflix" to safeSeries.take(10))
                return rows
            }
            "Films" -> {
                // 1. Continue Watching Kids Movies
                val continueFilms = continueWatchingMovies.filter { !it.type.equals("Series", ignoreCase = true) && !it.duration.contains("Season", ignoreCase = true) }
                if (continueFilms.isNotEmpty()) {
                    rows.add("Continue Watching Movies for $profileName" to continueFilms)
                }
                // 2. Top 10 Kids Movies Today
                val top10Films = safeFilms.take(10)
                if (top10Films.isNotEmpty()) {
                    rows.add("Top 10 Kids Movies Today" to top10Films)
                }
                // 3. Because You Watched
                val lastFilm = continueFilms.firstOrNull() ?: safeFilms.firstOrNull()
                if (lastFilm != null) {
                    val similar = findSimilarMovies(lastFilm, safeFilms, 10)
                    if (similar.isNotEmpty()) {
                        rows.add("Because You Watched ${lastFilm.title}" to similar)
                    }
                }
                // 4. Blockbuster Animated Movies
                rows.add("Blockbuster Animated Movies" to safeFilms.filter { it.type == "Animation" || it.matchesThemes("mario", "spider", "despicable", "kung fu") }.ifEmpty { safeFilms.shuffled(rng) }.take(10))
                // 5. Family Movie Night Favorites
                rows.add("Family Movie Night Favorites" to safeFilms.filter { it.matchesThemes("family", "adventure", "fun") }.ifEmpty { safeFilms.reversed() }.take(10))
                // 6. Magical Worlds & Fantasy Movies
                rows.add("Magical Worlds & Fantasy Movies" to safeFilms.filter { it.matchesThemes("magic", "fantasy", "dragon", "spirit") }.ifEmpty { safeFilms.shuffled(rng) }.take(10))
                // 7. Animal Tales & Cute Creatures Cinema
                rows.add("Animal Tales & Cute Creatures Cinema" to safeFilms.filter { it.matchesThemes("animal", "dog", "cat", "panda", "wild") }.ifEmpty { safeFilms.takeLast(10) }.take(10))
                // 8. Laugh-Out-Loud Kids Comedies
                rows.add("Laugh-Out-Loud Kids Comedies" to safeFilms.filter { it.matchesThemes("comedy", "funny", "laugh", "minion") }.ifEmpty { safeFilms.shuffled(rng) }.take(10))
                // 9. Action-Packed Kids Cinema
                rows.add("Action-Packed Kids Cinema" to safeFilms.filter { it.matchesThemes("action", "hero", "quest") }.ifEmpty { safeFilms.take(10) })
                // 10. Sing-Along & Musical Feature Films
                rows.add("Sing-Along & Musical Feature Films" to safeFilms.shuffled(rng).take(10))
                return rows
            }
            "My Netflix" -> {
                // 1. Continue Watching
                if (continueWatchingMovies.isNotEmpty()) {
                    rows.add("Continue Watching for $profileName" to continueWatchingMovies)
                }
                // 2. My List (sorted by addedAt descending — most recent first)
                if (sortedMyListMovies.isNotEmpty()) {
                    rows.add("My List ($profileName)" to sortedMyListMovies)
                }
                // 3. Recommended for $profileName
                rows.add("Recommended for $profileName" to safeCatalog.shuffled(rng).take(10))
                // 4. Popular on Kids Netflix
                rows.add("Popular on Kids Netflix" to safeCatalog.take(10))
                // 5. Favorite Kids Animated Movies
                rows.add("Favorite Animated Movies" to safeFilms.take(10))
                // 7. Favorite Kids TV Series
                rows.add("Favorite Kids TV Shows" to safeSeries.take(10))
                return rows
            }
            else -> { // "Home"
                // 1. Continue Watching
                if (continueWatchingMovies.isNotEmpty()) {
                    rows.add("Continue Watching for $profileName" to continueWatchingMovies)
                }
                // 2. Top 10 for Kids Today
                val top10 = safeCatalog.take(10)
                if (top10.isNotEmpty()) rows.add("Top 10 for Kids Today" to top10)

                // 3. Because You Watched
                val recentKidMovie = continueWatchingMovies.firstOrNull() ?: sortedMyListMovies.firstOrNull() ?: safeCatalog.firstOrNull()
                if (recentKidMovie != null) {
                    val similar = findSimilarMovies(recentKidMovie, safeCatalog, 10)
                    if (similar.isNotEmpty()) {
                        rows.add("Because You Watched ${recentKidMovie.title}" to similar)
                    }
                }

                // 4. Watch with the Family Tonight (Films)
                rows.add("Family Movie Night Favorites" to safeFilms.filter { it.matchesThemes("family", "adventure", "mario", "spider", "disney", "fun") }.ifEmpty { safeFilms.shuffled(rng) }.take(10))

                // 5. Kids TV Shows & Cartoons (Series)
                rows.add("Kids TV Shows & Cartoons" to safeSeries.shuffled(rng).take(10))

                // 6. Animated Adventures & Magical Worlds (Films & Series)
                rows.add("Animated Adventures & Magical Worlds" to safeAnimations.shuffled(rng).take(10))

                // 7. Fun & Laughs for Family Night
                rows.add("Fun & Laughs for Family Night" to safeCatalog.filter { it.matchesThemes("comedy", "funny", "laugh", "minion", "mario", "shrek") }.ifEmpty { safeCatalog.reversed() }.take(10))

                // 8. Superheroes & Action Packed
                rows.add("Action Heroes & Epic Quests" to safeCatalog.filter { it.matchesThemes("hero", "spider", "batman", "action", "quest", "power") }.ifEmpty { safeCatalog.shuffled(rng) }.take(10))

                // 9. Animal Tales & Nature Friends
                rows.add("Animal Tales & Nature Friends" to safeCatalog.filter { it.matchesThemes("animal", "dog", "cat", "panda", "dragon", "wild") }.ifEmpty { safeCatalog.takeLast(10) }.take(10))

                // 10. Sing-Along, Musical & Dance Favorites
                rows.add("Sing-Along & Cheerful Favorites" to safeCatalog.shuffled(rng).take(10))

                // 11. Bedtime Tales & Cozy Cartoons
                rows.add("Bedtime Tales & Cozy Cartoons" to safeCatalog.reversed().take(10))

                return rows
            }
        }
    }

    when (activeTab) {
        "Series" -> {
            // 1. Continue Watching Series
            val continueSeries = continueWatchingMovies.filter { it.type.contains("Series", ignoreCase = true) || it.type.contains("TV", ignoreCase = true) }
            if (continueSeries.isNotEmpty()) {
                rows.add("Continue Watching TV Shows for $profileName" to continueSeries)
            }

            // 2. Top 10 TV Shows Today
            val top10Series = allSeriesList.take(10)
            if (top10Series.isNotEmpty()) {
                rows.add("Top 10 TV Shows in Your Country Today" to top10Series)
            }

            // 2b. Worth the Wait / Coming Soon Shows
            val comingSoonSeries = buildComingSoonList(allSeriesList, randomSeed)
            if (comingSoonSeries.isNotEmpty()) {
                rows.add("Worth the Wait / Coming Soon Shows" to comingSoonSeries)
            }

            // 3. Because You Watched [Recent Series]
            val lastSeries = continueSeries.firstOrNull() ?: allSeriesList.firstOrNull()
            if (lastSeries != null) {
                val similar = findSimilarMovies(lastSeries, allSeriesList, 10)
                if (similar.isNotEmpty()) {
                    rows.add("Because You Watched ${lastSeries.title}" to similar)
                }
            }

            // 4. Binge-Worthy TV Dramas & Mysteries
            val bingeDramas = allSeriesList.filter { it.matchesThemes("drama", "mystery", "crime", "stranger", "detective") }.ifEmpty { allSeriesList.shuffled(rng) }
            rows.add("Binge-Worthy TV Dramas & Mysteries" to bingeDramas.take(10))

            // 5. Edge-of-Your-Seat Sci-Fi & Supernatural
            val sciFiSeries = allSeriesList.filter { it.matchesThemes("sci-fi", "alien", "supernatural", "future", "magic", "stranger", "dark") }.ifEmpty { allSeriesList.shuffled(rng) }
            rows.add("Edge-of-Your-Seat Sci-Fi & Supernatural" to sciFiSeries.take(10))

            // 6. Anime & Animated Series Spectacles
            val animeSeries = allSeriesList.filter { it.matchesThemes("anime", "animation", "jujutsu", "arcane", "demon", "attack") }.ifEmpty { allCatalogMovies.filter { it.type == "Animation" } }
            if (animeSeries.isNotEmpty()) {
                rows.add("Visually Stunning Anime & Animated Epics" to animeSeries.take(10))
            }

            // 7. Dark, Gritty & Intense Crime Dramas
            val crimeSeries = allSeriesList.filter { it.matchesThemes("crime", "thriller", "heist", "police", "money", "peaky", "breaking") }.ifEmpty { allSeriesList.reversed() }
            rows.add("Dark, Gritty & Psychological Crime TV" to crimeSeries.take(10))

            // 8. Critically Acclaimed Peak TV Masterpieces
            rows.add("Critically Acclaimed Peak TV Masterpieces" to allSeriesList.shuffled(rng).take(10))

            // 9. Quick Bites & Limited Miniseries
            rows.add("Watch in One Weekend: Limited Series" to allSeriesList.takeLast(10).reversed())

            // 10. Global TV Sensations & International Dramas
            rows.add("Global TV Sensations & International Dramas" to allSeriesList.shuffled(rng).take(10))
        }

        "Films" -> {
            // 1. Continue Watching Movies
            val continueFilms = continueWatchingMovies.filter { it.type.contains("Movie", ignoreCase = true) || it.type == "Animation" }
            if (continueFilms.isNotEmpty()) {
                rows.add("Continue Watching Movies for $profileName" to continueFilms)
            }

            // 2. Top 10 Movies Today
            val top10Films = allMoviesList.take(10)
            if (top10Films.isNotEmpty()) {
                rows.add("Top 10 Movies in Your Country Today" to top10Films)
            }

            // 2b. Worth the Wait / Coming Soon Movies
            val comingSoonFilms = buildComingSoonList(allMoviesList, randomSeed + 1)
            if (comingSoonFilms.isNotEmpty()) {
                rows.add("Worth the Wait / Coming Soon Movies" to comingSoonFilms)
            }

            // 3. Because You Watched [Recent Movie]
            val lastFilm = continueFilms.firstOrNull() ?: allMoviesList.firstOrNull()
            if (lastFilm != null) {
                val similar = findSimilarMovies(lastFilm, allMoviesList, 10)
                if (similar.isNotEmpty()) {
                    rows.add("Because You Watched ${lastFilm.title}" to similar)
                }
            }

            // 4. Blockbuster Hollywood Cinema & Box Office Hits
            val blockbusters = allMoviesList.filter { it.matchesThemes("action", "adventure", "blockbuster", "dune", "oppenheimer", "batman", "avatar") }.ifEmpty { allMoviesList.shuffled(rng) }
            rows.add("Blockbuster Hollywood Cinema & Box Office Hits" to blockbusters.take(10))

            // 5. Mind-Bending Sci-Fi & Dystopian Worlds
            val sciFiFilms = allMoviesList.filter { it.matchesThemes("sci-fi", "future", "space", "dune", "interstellar", "matrix", "blade") }.ifEmpty { allMoviesList.shuffled(rng) }
            rows.add("Mind-Bending Sci-Fi & Dystopian Futures" to sciFiFilms.take(10))

            // 6. Adrenaline Rush: High-Octane Action & Heists
            val actionFilms = allMoviesList.filter { it.matchesThemes("action", "fight", "war", "heist", "chase", "mission", "fast", "john") }.ifEmpty { allMoviesList.shuffled(rng) }
            rows.add("Adrenaline Rush: High-Octane Action & Heists" to actionFilms.take(10))

            // 7. Award-Winning & Critically Acclaimed Masterpieces
            rows.add("Award-Winning & Oscar-Nominated Masterpieces" to allMoviesList.take(10))

            // 8. Psychological Thrillers & Dark Mysteries
            val thrillerFilms = allMoviesList.filter { it.matchesThemes("thriller", "mystery", "psychological", "dark", "crime", "detective", "killer") }.ifEmpty { allMoviesList.reversed() }
            rows.add("Psychological Thrillers & Dark Mysteries" to thrillerFilms.take(10))

            // 9. Crowd-Pleasing Comedies & Feel-Good Movies
            val comedyFilms = allMoviesList.filter { it.matchesThemes("comedy", "romance", "laugh", "fun", "friend", "happy") }.ifEmpty { allMoviesList.shuffled(rng) }
            rows.add("Feel-Good Comedies & Lighthearted Favorites" to comedyFilms.take(10))

            // 10. Animated Feature Masterpieces
            val animatedFilms = allCatalogMovies.filter { it.type == "Animation" || it.matchesThemes("animated", "spider", "mario", "ghibli") }.ifEmpty { allMoviesList.takeLast(10) }
            rows.add("Animated Feature Masterpieces for All Ages" to animatedFilms.take(10))
        }

        "My Netflix" -> {
            // 1. Continue Watching
            if (continueWatchingMovies.isNotEmpty()) {
                rows.add("Continue Watching for $profileName" to continueWatchingMovies)
            }

            // 2. My List (sorted by addedAt descending — most recent first)
            if (sortedMyListMovies.isNotEmpty()) {
                rows.add("My List ($profileName)" to sortedMyListMovies)
            }

            // 3. Top Picks Based on Your Watch History
            val watchedSeed = continueWatchingMovies.firstOrNull() ?: sortedMyListMovies.firstOrNull() ?: allCatalogMovies.firstOrNull()
            if (watchedSeed != null) {
                val similar = findSimilarMovies(watchedSeed, allCatalogMovies, 10)
                if (similar.isNotEmpty()) {
                    rows.add("Top Picks Based on Your Activity" to similar)
                }
            }

            // 4. Because You Saved [First Bookmarked Item]
            val savedSeed = sortedMyListMovies.firstOrNull()
            if (savedSeed != null) {
                val similarSaved = findSimilarMovies(savedSeed, allCatalogMovies, 10)
                if (similarSaved.isNotEmpty()) {
                    rows.add("Because You Saved ${savedSeed.title}" to similarSaved)
                }
            }

            // 5. Trending in Your Favorite Genres
            rows.add("Trending in Your Favorite Genres" to allCatalogMovies.shuffled(rng).take(10))

            // 7. Critically Acclaimed You Might Like
            rows.add("Critically Acclaimed Titles for $profileName" to allCatalogMovies.take(10))

            // 8. New Releases Tailored for You
            rows.add("New Releases Matching Your Taste" to allCatalogMovies.takeLast(10).reversed())

            // 9. Quick Weekend Binges for $profileName
            rows.add("Quick Weekend Binges for $profileName" to allSeriesList.take(10))

            // 10. Popular on Netflix Pro Worldwide
            rows.add("Popular on Netflix Pro Worldwide" to allCatalogMovies.shuffled(rng).take(10))
        }

        else -> { // "Home"
            // 1. Continue Watching
            if (continueWatchingMovies.isNotEmpty()) {
                rows.add("Continue Watching for $profileName" to continueWatchingMovies)
            }

            // 2. Top 10 Movies & TV Shows Today
            val top10All = allCatalogMovies.take(10)
            if (top10All.isNotEmpty()) {
                rows.add("Top 10 Movies & TV Shows in Your Country Today" to top10All)
            }

            // 2b. Worth the Wait / Coming Soon
            val comingSoonAll = buildComingSoonList(allCatalogMovies, randomSeed + 2)
            if (comingSoonAll.isNotEmpty()) {
                rows.add("Worth the Wait / Coming Soon" to comingSoonAll)
            }

            // 3. Because You Watched [Recent Movie/Series]
            val lastWatched = continueWatchingMovies.firstOrNull() ?: sortedMyListMovies.firstOrNull() ?: allCatalogMovies.firstOrNull()
            if (lastWatched != null) {
                val similar = findSimilarMovies(lastWatched, allCatalogMovies, 10)
                if (similar.isNotEmpty()) {
                    rows.add("Because You Watched ${lastWatched.title}" to similar)
                }
            }

            // 4. Blockbuster Action & Adrenaline Surge
            val actionAll = allCatalogMovies.filter { it.matchesThemes("action", "adventure", "hero", "fight", "chase", "dune", "oppenheimer", "matrix") }.ifEmpty { allCatalogMovies.shuffled(rng) }
            rows.add("Adrenaline Rush: Blockbuster Action & Sci-Fi" to actionAll.take(10))

            // 5. Binge-Worthy TV Dramas & Deep Mysteries
            val dramasAll = allSeriesList.filter { it.matchesThemes("drama", "mystery", "crime", "stranger", "detective") }.ifEmpty { allSeriesList.shuffled(rng) }
            rows.add("Binge-Worthy TV Dramas & Deep Mysteries" to dramasAll.take(10))

            // 6. Award-Winning Cinema & Must-Watch Masterpieces
            rows.add("Award-Winning Cinema & Must-Watch Masterpieces" to allCatalogMovies.take(10))

            // 7. Visually Stunning Anime & Dark Fantasy
            val animeAll = allCatalogMovies.filter { it.matchesThemes("anime", "animation", "jujutsu", "arcane", "fantasy", "magic", "spirit") }.ifEmpty { allCatalogMovies.shuffled(rng) }
            rows.add("Visually Stunning Anime & Dark Fantasy" to animeAll.take(10))

            // 8. Dark, Gritty & Psychological Thrillers
            val thrillersAll = allCatalogMovies.filter { it.matchesThemes("thriller", "crime", "psychological", "dark", "killer", "mind") }.ifEmpty { allCatalogMovies.reversed() }
            rows.add("Dark, Gritty & Psychological Thrillers" to thrillersAll.take(10))

            // 9. Crowd-Pleasing Comedies & Feel-Good Stories
            val comediesAll = allCatalogMovies.filter { it.matchesThemes("comedy", "romance", "funny", "laugh", "friend", "family") }.ifEmpty { allCatalogMovies.shuffled(rng) }
            rows.add("Feel-Good Comedies & Lighthearted Favorites" to comediesAll.take(10))

            // 10. Hidden Gems & International Sensations
            rows.add("Hidden Gems & International Sensations" to allCatalogMovies.takeLast(10).reversed())

            // 11. Watch in One Weekend: Miniseries & Quick Watches
            rows.add("Watch in One Weekend: Miniseries & Quick Watches" to allSeriesList.shuffled(rng).take(10))
        }
    }

    return rows
}




