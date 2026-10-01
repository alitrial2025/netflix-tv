package com.example.ui.components

import com.example.discovery.recommendationTitle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import coil.compose.AsyncImage
import com.example.model.Movie
import com.example.model.isSeriesContent
import com.example.ui.theme.NetflixRed
import com.example.ui.theme.RatingDefaults
import com.example.ui.util.TvMotion
import com.example.ui.util.TvKeyPacer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive

import androidx.compose.ui.input.key.*
import android.view.KeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import coil.imageLoader
import coil.request.ImageRequest
import com.example.ui.NetflixViewModel
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.HomeStartupGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Reusable Netflix-style Horizontal Movie Carousel Component.
 * Features:
 * - Infinite horizontal wrap-around carousel loop
 * - Smooth interpolated index animation (FastOutSlowInEasing)
 * - Dynamic card expansion (2.3x focused width slot)
 * - Fixed/slid white selection frame/ring
 * - Metadata & synopsis details updated dynamically for focused movie
 */

private val CardOverlayGradient = Brush.verticalGradient(
    colors = listOf(
        Color.Transparent,
        Color.Black.copy(alpha = 0.15f),
        Color.Black.copy(alpha = 0.60f),
        Color.Black.copy(alpha = 0.95f)
    ),
    startY = 50f
)

private val CardCornerShape = RoundedCornerShape(8.dp)
private val BadgeShape3 = RoundedCornerShape(3.dp)
private val BadgeShape4 = RoundedCornerShape(4.dp)
private val BadgeShape12 = RoundedCornerShape(12.dp)
// perf: pre-built brush for the Netflix Pro badge. Was allocated inside the
// Row composable on every recomposition; with ~5 visible rows that's 5 brush
// allocations per focus change for a value that never changes.
private val ProBadgeBrush = Brush.horizontalGradient(
    listOf(NetflixRed, Color(0xFFB81D24))
)
private val cardLogoRepository = com.example.data.TmdbRepository()

private fun Movie.cardImageUrl(isPortrait: Boolean): String =
    if (isPortrait) posterUrl.ifBlank { backdropUrl } else backdropUrl.ifBlank { posterUrl }

private fun Movie.cardArtworkKind(isPortrait: Boolean): TvArtworkKind =
    if (if (isPortrait) posterUrl.isNotBlank() else backdropUrl.isBlank()) TvArtworkKind.POSTER
    else TvArtworkKind.BACKDROP

// Share request sizing/configuration with prefetch, so warming a neighboring hero
// never creates a different bitmap variant from the one that will be displayed.
private fun cardImageRequest(
    context: android.content.Context,
    imageUrl: String,
    isExpanded: Boolean,
    isPortrait: Boolean,
    artworkKind: TvArtworkKind
): ImageRequest {
    val widthPx = if (isExpanded) (if (isPortrait) 380 else 640) else 240
    val heightPx = if (isExpanded) (if (isPortrait) 540 else 360) else 340
    return ImageRequest.Builder(context)
        .data(TvImagePolicy.artworkUrl(imageUrl, widthPx, artworkKind))
        // The expanded slot remains visible on unfocused rows too. Avoid keeping
        // a four-byte photo copy for every such row; logos request alpha separately.
        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .crossfade(false)
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
        .size(widthPx, heightPx)
        .build()
}

@Composable
fun NetflixMovieRow(
    title: String,
    movies: List<Movie>,
    modifier: Modifier = Modifier,
    isPortrait: Boolean = true,
    rowFocusRequester: FocusRequester? = null,
    height: Dp = 375.dp,
    alpha: () -> Float = { 1f },
    isVisible: Boolean = true,
    isNavigationActive: () -> Boolean = { true },
    continueWatchingProgressMap: Map<String, Float>? = null,
    continueWatchingRemainingMap: Map<String, Long>? = null,
    isKids: Boolean = false,
    remindedMovieIds: Set<String> = emptySet(),
    isMovieLocked: ((Movie) -> Boolean)? = null,
    onToggleReminder: ((Movie) -> Unit)? = null,
    onMovieFocused: (Movie) -> Unit = {},
    onMovieClick: (Movie) -> Unit = {},
    onRowFocused: () -> Unit = {},
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false },
    viewModel: NetflixViewModel? = null,
    verticalRingOffsetProvider: () -> Float = { 0f },
    showSelectionRing: Boolean = true,
    onSelectionFrameTopMeasured: (Int) -> Unit = {}
) {
    if (movies.isEmpty() || height <= 2.dp) return

    if (!isVisible) {
        // Lightweight static skeleton: grey card outlines instead of black void.
        // No shimmer animation — pure static draw, near-zero GPU cost.
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
        ) {
            // Title bar placeholder
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 8.dp)
                    .width(150.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A1A))
            )
            // Card strip placeholder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .width(190.dp)
                            .height(270.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A1A1A))
                    )
                }
            }
        }
        return
    }

    // perf: hoist the title parsing out of the hot path. title.contains runs
    // on every recomposition; the result is constant for the lifetime of the
    // row's title.
    val isInfinite = remember(title, movies.size) { !title.contains("Continue Watching", ignoreCase = true) && movies.size >= 4 }
    val initialIndex = remember(title, isInfinite, movies.size) { if (isInfinite) 1000 * movies.size else 0 }
    var focusedIndex by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableIntStateOf(initialIndex) }
    val horizontalKeyPacer = remember { TvKeyPacer() }
    var savedMovieCount by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableIntStateOf(movies.size) }
    var isRowFocused by remember { mutableStateOf(false) }
    // Logical navigation changes before Android transfers the focus node.
    // Stop artwork/preview/metadata work immediately on a departing row.
    val isActiveRow = isRowFocused && isNavigationActive()

    val context = androidx.compose.ui.platform.LocalContext.current
    val isLowMemoryDevice = remember(context) { TvImagePolicy.isLowMemoryDevice(context) }
    val currentMovies by rememberUpdatedState(movies)
    val currentOnRowFocused by rememberUpdatedState(onRowFocused)

    // Visible posters already load themselves. Only warm the two possible next
    // heroes, sequentially, after focus settles. execute() belongs to this effect:
    // navigating away cancels in-flight work instead of leaving enqueued decodes.
    LaunchedEffect(focusedIndex, movies, isActiveRow, isPortrait, isInfinite, isLowMemoryDevice) {
        if (!isActiveRow || isLowMemoryDevice) return@LaunchedEffect
        delay(1_200)
        HomeStartupGate.awaitBrowsingIdle()
        for (offset in intArrayOf(1, -1)) {
            HomeStartupGate.awaitBrowsingIdle()
            val index = focusedIndex + offset
            if (!isInfinite && index !in movies.indices) continue
            val movie = movies[((index % movies.size) + movies.size) % movies.size]
            val imageUrl = movie.cardImageUrl(false)
            if (imageUrl.isNotBlank()) {
                context.imageLoader.execute(cardImageRequest(context, imageUrl, true, false, movie.cardArtworkKind(false)))
            }
        }
    }

    // Debounce notifying parent of focused movie change during rapid left/right sliding
    val currentOnMovieFocused by rememberUpdatedState(onMovieFocused)
    LaunchedEffect(focusedIndex, isActiveRow, movies) {
        if (isActiveRow && movies.isNotEmpty()) {
            kotlinx.coroutines.delay(180) // Settle before propagating full movie focus to billboard & palette
            val movieIndex = ((focusedIndex % movies.size) + movies.size) % movies.size
            movies.getOrNull(movieIndex)?.let { currentOnMovieFocused(it) }
        }
    }

    // Keep floating point motion near zero. The saved loop counter can be large
    // enough to quantize the last pixels of a glide; card identity stays integer.
    val animationOrigin = remember(title) { mutableIntStateOf(focusedIndex) }
    val animIndex = remember(title) { Animatable(0f) }
    val animatedFloor by remember(animIndex, animationOrigin) {
        derivedStateOf { animationOrigin.intValue + kotlin.math.floor(animIndex.value).toInt() }
    }
    val showIncomingHero by remember(animIndex) {
        derivedStateOf { animIndex.value - kotlin.math.floor(animIndex.value) > 0.01f }
    }
    val isHeroSettled by remember(animIndex) {
        derivedStateOf { kotlin.math.abs(animIndex.value - (focusedIndex - animationOrigin.intValue).toFloat()) < 0.01f }
    }
    val previewOwner = remember { "row:" + java.util.UUID.randomUUID().toString() }
    val selectedPreviewMovie = movies[((focusedIndex % movies.size) + movies.size) % movies.size]
    val preview by rememberHomePreview(
        owner = previewOwner, movie = selectedPreviewMovie,
        focused = isActiveRow && isHeroSettled, viewModel = viewModel,
        audible = true
    )

    LaunchedEffect(movies.size, isInfinite) {
        if (savedMovieCount != movies.size || (!isInfinite && focusedIndex !in movies.indices)) {
            val previousCount = savedMovieCount.coerceAtLeast(1)
            val movieIndex = (((focusedIndex % previousCount) + previousCount) % previousCount)
                .coerceAtMost(movies.lastIndex)
            focusedIndex = if (isInfinite) initialIndex + movieIndex else movieIndex
            savedMovieCount = movies.size
            animationOrigin.intValue = focusedIndex
            animIndex.snapTo(0f)
        }
    }
    LaunchedEffect(animIndex) {
        snapshotFlow { focusedIndex - animationOrigin.intValue }.collect { targetOffset ->
            // Start the new target before cancelling the old animation so
            // Animatable can preserve its velocity during D-pad repeats.
            launch {
                // Retarget even when the new destination equals the current
                // position: a just-started glide may still be heading elsewhere.
                animIndex.animateTo(
                    targetValue = targetOffset.toFloat(),
                    animationSpec = TvMotion.carouselSpring(0.005f)
                )
            }
        }
    }

    val cardHeight = (height - 128.dp).coerceAtLeast(180.dp)
    val baseWidth = cardHeight * (2f / 3f)
    val expandedWidth = cardHeight * (16f / 9f)
    val gap = 12.dp
    val itemSpacing = baseWidth + gap
    val ringStart = 16.dp

    val density = androidx.compose.ui.platform.LocalDensity.current
    val stripTopPaddingPx = with(density) { 4.dp.roundToPx() }
    val currentOnSelectionFrameTopMeasured by rememberUpdatedState(onSelectionFrameTopMeasured)
    val itemSpacingPx = remember(density, itemSpacing) { with(density) { itemSpacing.toPx() } }
    val ringStartPx = remember(density, ringStart) { with(density) { ringStart.toPx() } }
    val gapPx = remember(density, gap) { with(density) { gap.toPx() } }
    // Reduced hero slide distance: 35% of expanded card width (~150dp) for graceful, subtle glide
    val heroSlideDistancePx = remember(density, expandedWidth) { with(density) { (expandedWidth * 0.35f).toPx() } }
    val ringEnd = ringStart + expandedWidth
    val ringEndPx = remember(density, ringEnd) { with(density) { ringEnd.toPx() } }

    if (movies.isEmpty()) return

    val currentFocusedMovie = remember(focusedIndex, movies) {
        val movieIndex = ((focusedIndex % movies.size) + movies.size) % movies.size
        movies.getOrNull(movieIndex) ?: movies.first()
    }
    var metadataMovie by remember(title) { mutableStateOf(currentFocusedMovie) }
    LaunchedEffect(currentFocusedMovie, isActiveRow, isHeroSettled) {
        // Focus/Play use the destination immediately. Measure new synopsis text
        // after the glide, so a held key cannot stack outgoing text transitions.
        if (isActiveRow) {
            if (!isHeroSettled) return@LaunchedEffect
            delay(120)
        }
        metadataMovie = currentFocusedMovie
    }

    val actualRowFocusRequester = rowFocusRequester ?: remember { FocusRequester() }

    // perf: hoist these title checks into remember. The result depends only
    // on the row's title, but the original was recomputed on every recomposition.
    val isContinueWatchingRow = remember(title) {
        title.contains("Continue Watching", ignoreCase = true) || title.contains("Netflix Pro", ignoreCase = true)
    }
    val isComingSoonRowCached = remember(title) {
        title.contains("Coming Soon", ignoreCase = true) ||
            title.contains("Worth the Wait", ignoreCase = true) ||
            title.contains("Remind", ignoreCase = true)
    }
    val isTop10Row = remember(title) { title.contains("Top 10", ignoreCase = true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this@graphicsLayer.alpha = alpha() }
            .clipToBounds()
    ) {
        // A fixed heading baseline keeps the viewport's focus frame aligned.
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp)
                .onSizeChanged { currentOnSelectionFrameTopMeasured(it.height + stripTopPaddingPx) }
                .padding(start = 16.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 18.sp, lineHeight = 22.sp, color = Color.White,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight + 8.dp)
                .padding(top = 4.dp, bottom = 4.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (isRowFocused && keyEvent.type == KeyEventType.KeyDown) {
                        if (!isNavigationActive()) return@onPreviewKeyEvent true
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (movies.isEmpty()) return@onPreviewKeyEvent true
                                if (!isInfinite && focusedIndex >= movies.size - 1) return@onPreviewKeyEvent true
                                if (!horizontalKeyPacer.accept(1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)) return@onPreviewKeyEvent true
                                focusedIndex++
                                onRowFocused()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (movies.isEmpty()) return@onPreviewKeyEvent true
                                if (!isInfinite && focusedIndex <= 0) return@onPreviewKeyEvent true
                                if (!horizontalKeyPacer.accept(-1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)) return@onPreviewKeyEvent true
                                focusedIndex--
                                onRowFocused()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDown()
                            KeyEvent.KEYCODE_DPAD_UP -> onDpadUp()
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (keyEvent.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                                // perf: isComingSoonRow was being recomputed on
                                // every keypress lambda creation (i.e. every
                                // recomposition). It's constant for the row's
                                // lifetime; hoist to remember.
                                if ((currentFocusedMovie.isComingSoon || isComingSoonRowCached) && onToggleReminder != null) {
                                    onToggleReminder(currentFocusedMovie)
                                } else {
                                    onMovieClick(currentFocusedMovie)
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .onFocusChanged { isRowFocused = it.hasFocus }
                .focusProperties {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .focusRequester(actualRowFocusRequester)
                .focusable()
                .clipToBounds()
                .pointerInput(movies.size, isInfinite) {
                    var accumulatedHorizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { accumulatedHorizontalDrag = 0f },
                        onDragEnd = { accumulatedHorizontalDrag = 0f },
                        onDragCancel = { accumulatedHorizontalDrag = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedHorizontalDrag += dragAmount
                            val thresholdPx = 60f
                            if (accumulatedHorizontalDrag < -thresholdPx) {
                                if (movies.isNotEmpty() && (isInfinite || focusedIndex < movies.size - 1)) {
                                    focusedIndex++
                                    val movieIndex = ((focusedIndex % movies.size) + movies.size) % movies.size
                                    currentMovies.getOrNull(movieIndex)?.let { currentOnMovieFocused(it) }
                                    currentOnRowFocused()
                                }
                                accumulatedHorizontalDrag = 0f
                            } else if (accumulatedHorizontalDrag > thresholdPx) {
                                if (movies.isNotEmpty() && (isInfinite || focusedIndex > 0)) {
                                    focusedIndex--
                                    val movieIndex = ((focusedIndex % movies.size) + movies.size) % movies.size
                                    currentMovies.getOrNull(movieIndex)?.let { currentOnMovieFocused(it) }
                                    currentOnRowFocused()
                                }
                                accumulatedHorizontalDrag = 0f
                            }
                        }
                    )
                }
        ) {
            // Retain what is actually crossing the viewport. Fast taps can put
            // the destination several cards ahead of the spring's current position.
            val posterSlots = kotlin.math.ceil(
                ((maxWidth - ringEnd).coerceAtLeast(0.dp) / itemSpacing).toDouble()
            ).toInt() + if (isActiveRow || !isHeroSettled) 1 else 0
            val posterWindow = movieRowWindow(
                animatedFloor, posterSlots, movies.size, isInfinite,
                includePrevious = !isHeroSettled
            )
            val startWindow = posterWindow.first
            val endWindow = posterWindow.last

            // ── LAYER 1: Sliding portrait card strip (Zero recomposition on animation frames) ──
            // This calculation only runs at card/focus boundaries. Do not remember
            // lock answers: profile/rating state may change without the list size.
            val lockedMap = if (isMovieLocked == null) emptyMap() else {
                val out = HashMap<String, Boolean>(8)
                for (j in startWindow..endWindow) {
                    val movie = movies[((j % movies.size) + movies.size) % movies.size]
                    if (!out.containsKey(movie.id)) out[movie.id] = isMovieLocked(movie)
                }
                for (j in animatedFloor..animatedFloor + 1) {
                    val movie = movies[((j % movies.size) + movies.size) % movies.size]
                    if (!out.containsKey(movie.id)) out[movie.id] = isMovieLocked(movie)
                }
                out
            }
            for (i in startWindow..endWindow) {
                key("portrait_$i") {
                    val movieIndex = if (movies.isNotEmpty()) ((i % movies.size) + movies.size) % movies.size else 0
                    val movie = movies.getOrNull(movieIndex)

                    if (movie != null) {
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    val animVal = animIndex.value
                                    val offsetFloor = kotlin.math.floor(animVal).toInt()
                                    val f = animationOrigin.intValue + offsetFloor
                                    val frac = animVal - offsetFloor
                                    val offsetPx = when {
                                        i < f -> ringStartPx - itemSpacingPx * (f - i) - itemSpacingPx * frac
                                        i == f -> ringStartPx - itemSpacingPx * frac
                                        i == f + 1 -> {
                                            val startX = ringEndPx + gapPx
                                            val targetX = ringStartPx
                                            startX + (targetX - startX) * frac
                                        }
                                        else -> ringEndPx + gapPx + itemSpacingPx * (i - f - 1) - itemSpacingPx * frac
                                    }
                                    translationX = offsetPx
                                    // Subtle depth-of-field dimming for unfocused background cards
                                    this@graphicsLayer.alpha = 1f
                                }
                                .zIndex(1f)
                        ) {
                            // perf: skip composition/draw when completely hidden underneath LAYER 2's 437dp hero card
                            val isHiddenUnderHero = i == focusedIndex && isHeroSettled
                            if (!isHiddenUnderHero) {
                                MovieCardItem(
                                    movie = movie,
                                    isReminded = movie.recommendationTitle().key in remindedMovieIds,
                                    isComingSoonRow = isComingSoonRowCached,
                                    isPortrait = isPortrait,
                                    cardWidth = baseWidth,
                                    height = cardHeight,
                                    isExpanded = false,
                                    isFocused = false,
                                    progress = continueWatchingProgressMap?.get(movie.id),
                                    rank = if (isTop10Row) movieIndex + 1 else null,
                                    isLocked = lockedMap[movie.id] == true,
                                    onClick = {
                                        focusedIndex = i
                                        onMovieFocused(movie)
                                        onRowFocused()
                                        onMovieClick(movie)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── LAYER 2: Expanded hero content inside ring viewport (35% subtle slide + soft crossfade) ──
            Box(
                modifier = Modifier
                    .offset(x = ringStart)
                    .width(expandedWidth)
                    .height(cardHeight)
                    .graphicsLayer {
                        this@graphicsLayer.alpha = if (isActiveRow) 1f else 0.85f
                    }
                    .clip(CardCornerShape)
                    .zIndex(5f)
            ) {
                val animFloor = animatedFloor
                val heroEnd = if (showIncomingHero) animFloor + 1 else animFloor
                // A card keeps its image/composition as it moves from incoming to
                // outgoing. Fractions are read only by graphicsLayer, never by the
                // composition; the existing slide/crossfade now needs no per-frame
                // card, image-request, badge or text recomposition.
                for (heroIndex in animFloor..heroEnd) {
                    key("hero_$heroIndex") {
                        val movieIndex = ((heroIndex % movies.size) + movies.size) % movies.size
                        val movie = movies[movieIndex]
                        val isIncoming = heroIndex != animFloor
                        Box(
                            modifier = Modifier.graphicsLayer {
                                val fraction = (animIndex.value - (animFloor - animationOrigin.intValue)).coerceIn(0f, 1f)
                                translationX = if (isIncoming) {
                                    heroSlideDistancePx * (1f - fraction)
                                } else {
                                    -heroSlideDistancePx * fraction
                                }
                                this.alpha = if (isIncoming) fraction else 1f - fraction
                            }
                        ) {
                            MovieCardItem(
                                movie = movie,
                                isReminded = movie.recommendationTitle().key in remindedMovieIds,
                                isComingSoonRow = isComingSoonRowCached,
                                isPortrait = false,
                                cardWidth = expandedWidth,
                                height = cardHeight,
                                isExpanded = true,
                                isFocused = isActiveRow,
                                shouldFetchLogo = isActiveRow && isHeroSettled && heroIndex == focusedIndex,
                                progress = continueWatchingProgressMap?.get(movie.id),
                                rank = if (isTop10Row) movieIndex + 1 else null,
                                isLocked = lockedMap[movie.id] == true,
                                previewController = viewModel?.homePreviewController,
                                preview = if (viewModel != null && isActiveRow && isHeroSettled && heroIndex == focusedIndex) preview else null,
                                rotationSlot = movieIndex,
                                viewModel = viewModel,
                                onClick = { onMovieClick(movie) }
                            )
                        }
                    }
                }

            }

            // Home draws its shared frame above the moving viewport. Other callers
            // keep a local frame, fully visible as soon as the row takes focus.
            if (showSelectionRing) Box(
                modifier = Modifier
                    .offset(x = ringStart)
                    .width(expandedWidth)
                    .height(cardHeight)
                    .graphicsLayer {
                        this@graphicsLayer.alpha = if (isActiveRow) 1f else 0f
                        this@graphicsLayer.translationY = verticalRingOffsetProvider()
                    }
                    .border(1.5.dp, Color.White, CardCornerShape)
                    .zIndex(10f)
            )
        }

        // Active Movie Metadata & Synopsis below row — slides in matching card direction when row is focused
        if (isActiveRow) {
            AnimatedContent(
                targetState = metadataMovie,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .padding(start = 16.dp, top = 4.dp, end = 40.dp, bottom = 6.dp),
                transitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(TvMotion.duration(240), easing = LinearOutSlowInEasing),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() }
                    ) + fadeIn(animationSpec = tween(TvMotion.duration(240), easing = LinearOutSlowInEasing)))
                    .togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(TvMotion.duration(240), easing = LinearOutSlowInEasing),
                            targetOffsetX = { fullWidth -> (-fullWidth * 0.08f).toInt() }
                        ) + fadeOut(animationSpec = tween(TvMotion.duration(150), easing = FastOutSlowInEasing))
                    )
                },
                label = "rowMetadataSlide"
            ) { movie ->
                if (isContinueWatchingRow) {
                    val remaining = continueWatchingRemainingMap?.get(movie.id)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(movie.title, color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (remaining != null && remaining > 0L) {
                            Text("${(remaining + 59_999L) / 60_000L}m left", color = Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else MovieMetadataDetails(movie = movie, isKids = isKids, width = expandedWidth)
            }
        }
    }
}

@Composable
private fun MovieMetadataDetails(movie: Movie, isKids: Boolean, width: Dp) {
    val cachedMeta = com.example.data.TmdbRepository.getCachedBillboardMeta(movie.id)
    val enrichedMovie = remember(movie, cachedMeta) {
        BillboardCalloutGenerator.enrichMovieWithMeta(movie, cachedMeta)
    }
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TitleMetadata(enrichedMovie, isKids = isKids)
        Text(enrichedMovie.description, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun MovieCardItem(
    movie: Movie,
    isPortrait: Boolean,
    cardWidth: Dp,
    height: Dp = 270.dp,
    isExpanded: Boolean = false,
    isFocused: Boolean = false,
    shouldFetchLogo: Boolean = false,
    progress: Float? = null,
    rank: Int? = null,
    isComingSoonRow: Boolean = false,
    isReminded: Boolean = false,
    isLocked: Boolean = false,
    showArtwork: Boolean = true,
    previewController: com.example.ui.util.HomePreviewController? = null,
    preview: com.example.ui.util.HomePreviewState? = null,
    rotationSlot: Int = 0,
    viewModel: NetflixViewModel? = null,
    onClick: () -> Unit
) {
    val expandedProgress = if (isExpanded) 1f else 0f
    val isPreviewFirstFrameReady = preview?.firstFrameReady == true
    val prePreviewOverlayAlpha = animateFloatAsState(
        targetValue = if (isPreviewFirstFrameReady) 0f else 1f,
        animationSpec = tween(TvMotion.duration(240), easing = LinearOutSlowInEasing),
        label = "cardPrePreviewOverlayAlpha"
    )
    val cardScrimAlpha = animateFloatAsState(
        targetValue = if (isPreviewFirstFrameReady) 0.35f else 1f,
        animationSpec = tween(TvMotion.duration(320), easing = LinearOutSlowInEasing),
        label = "cardScrimAlpha"
    )

    val showPrePreviewOverlay by remember(prePreviewOverlayAlpha) {
        derivedStateOf { prePreviewOverlayAlpha.value > 0.01f }
    }

    // Netflix uses ONE consistent high-resolution image per card (seamlessly cropped across expansion)
    val imageUrl = remember(movie, isPortrait) {
        movie.cardImageUrl(isPortrait)
    }

    val cardCtx = androidx.compose.ui.platform.LocalContext.current
    val artworkKind = movie.cardArtworkKind(isPortrait)
    val imageRequest = remember(imageUrl, cardCtx, isExpanded, isPortrait, artworkKind) {
        cardImageRequest(cardCtx, imageUrl, isExpanded, isPortrait, artworkKind)
    }

    // Dynamic TMDB Logo & Billboard Callout Metadata for expanded/focused card
    var dynamicLogoUrl by remember(movie.id, movie.logoUrl, isExpanded) {
        mutableStateOf(if (isExpanded) (movie.logoUrl ?: com.example.data.TmdbRepository.getCachedLogo(movie.id)) else null)
    }
    var cardBillboardMeta by remember(movie.id, isExpanded) {
        mutableStateOf(
            if (isExpanded) {
                viewModel?.getCachedBillboardMeta(movie.id)
                    ?: com.example.data.TmdbRepository.getCachedBillboardMeta(movie.id)
            } else null
        )
    }

    if (isExpanded && shouldFetchLogo) {
        LaunchedEffect(movie.id, shouldFetchLogo) {
            // Avoid network work while the user is still moving through cards.
            delay(1_200)
            HomeStartupGate.awaitBrowsingIdle()
            if (dynamicLogoUrl.isNullOrBlank()) {
                val cached = com.example.data.TmdbRepository.getCachedLogo(movie.id)
                if (!cached.isNullOrBlank()) {
                    dynamicLogoUrl = cached
                } else {
                    val isTv = movie.isSeriesContent()
                    val fetched = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cardLogoRepository.fetchLogoUrl(movie.id.toLongOrNull() ?: 0L, isTv)
                    }
                    if (!fetched.isNullOrBlank()) {
                        dynamicLogoUrl = fetched
                    }
                }
            }
            if (cardBillboardMeta?.isEnriched != true) {
                val enriched = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    viewModel?.fetchBillboardMeta(movie)
                        ?: cardLogoRepository.fetchBillboardMeta(movie.id.toLongOrNull() ?: 0L, movie.isSeriesContent())
                }
                if (enriched != null) {
                    cardBillboardMeta = enriched
                }
            }
        }
    }

    val cardCalloutBadges = remember(movie, cardBillboardMeta, isExpanded, rotationSlot) {
        if (!isExpanded) {
            emptyList()
        } else {
            BillboardCalloutGenerator.resolveBadges(
                movie = movie,
                meta = cardBillboardMeta,
                favoriteGenres = viewModel?.selectedProfile?.value?.favoriteGenres.orEmpty(),
                likedMovies = viewModel?.getLikedMoviesSnapshot().orEmpty(),
                watchedMovies = viewModel?.watchHistoryMovies?.value.orEmpty(),
                inMyList = viewModel?.myListMovieIds?.value?.contains(movie.id) == true,
                rotationSlot = rotationSlot
            )
        }
    }

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(height)
            .clip(CardCornerShape)
            .background(if (showArtwork) Color(0xFF0D0D0D) else Color.Transparent)
            .clickable { onClick() }
    ) {
        // 1. Single Rock-Solid Image (No swapping/flickering)
        if (showArtwork) AsyncImage(
            model = imageRequest,
            contentDescription = movie.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )

        // perf: hoist the rank-in-1..10 check. Used 4x in the body (badge,
        // lock alignment, big number, title padding); with 10 cards in LAYER 1
        // and 2 in LAYER 2 that's 60 redundant checks per focus change.
        val isTop10Rank = rank != null && rank in 1..10

        // Top 10 Badge
        if (isTop10Rank) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(NetflixRed, BadgeShape3)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "TOP 10",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Lock Badge Overlay
        if (isLocked) {
            Box(
                modifier = Modifier
                    .align(if (isTop10Rank) Alignment.TopStart else Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0xFF1A1A1A), BadgeShape4)
                    .border(1.dp, Color(0xFFCC9A06), BadgeShape4)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked Title",
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "LOCK",
                        color = Color(0xFFFFC107),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }


        // Bottom Gradient Overlay (Only on Expanded Hero Card; softens during preview like Billboard)
        if (expandedProgress > 0.02f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = expandedProgress * cardScrimAlpha.value }
                    .background(CardOverlayGradient)
            )
        }

        // Giant Stylized Netflix Top 10 Outline Number in Bottom-Left
        if (isTop10Rank && showPrePreviewOverlay) {
            Top10RankNumber(
                rank = rank!!,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 6.dp,
                        bottom = if (isLocked) 20.dp else 4.dp
                    )
                    .graphicsLayer { alpha = prePreviewOverlayAlpha.value }
                    .zIndex(4f)
            )
        }

        val titlePaddingStart = if (isTop10Rank && expandedProgress < 0.2f) 52.dp else 12.dp
        val bottomContentPadding = if (progress != null && progress > 0f) 20.dp else 12.dp

        if (previewController != null && preview?.player != null) {
            HomePreviewSurface(
                controller = previewController,
                preview = preview,
                placeholderRequest = imageRequest,
                modifier = Modifier.fillMaxSize(),
                captionAlignment = Alignment.BottomEnd,
                captionBottomInset = bottomContentPadding,
                captionHorizontalPadding = 12.dp,
                captionMaxWidth = cardWidth * 0.52f
            )
        }

        // Expanded Hero Card Title / Logo at Bottom-Left (fades out when preview starts like Billboard)
        if (isExpanded && expandedProgress > 0.01f && showPrePreviewOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = titlePaddingStart, end = 12.dp, bottom = bottomContentPadding)
                    .widthIn(max = cardWidth * 0.42f)
                    .zIndex(5f)
                    .graphicsLayer {
                        alpha = expandedProgress * prePreviewOverlayAlpha.value
                        translationY = (1f - expandedProgress) * 10f
                    }
            ) {
                if (!dynamicLogoUrl.isNullOrBlank()) {
                    val logoCtx = androidx.compose.ui.platform.LocalContext.current
                    val logoImageRequest = remember(dynamicLogoUrl, logoCtx) {
                        coil.request.ImageRequest.Builder(logoCtx)
                            .data(TvImagePolicy.artworkUrl(dynamicLogoUrl.orEmpty(), 210, TvArtworkKind.LOGO))
                            .crossfade(false)
                            .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                            .allowRgb565(false)
                            .size(210, 42)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                    }
                    AsyncImage(
                        model = logoImageRequest,
                        contentDescription = movie.title,
                        modifier = Modifier
                            .height(36.dp)
                            .widthIn(max = 195.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else {
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color(0xFF0D0D0D),
                                blurRadius = 8f
                            )
                        )
                    )
                }

            }
        }

        // Remind Me / Reminded / Release Date Badge Overlay OR Dynamic TMDB Callout Badges on Bottom-Right
        val showBellBadge = (movie.isComingSoon || isComingSoonRow || isReminded || movie.releaseDateBadge != null) && !isPreviewFirstFrameReady
        if (showBellBadge) {
            val badgeText = when {
                isReminded -> "Reminded"
                movie.releaseDateBadge != null -> movie.releaseDateBadge!!
                else -> "Remind Me"
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .zIndex(6f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(BadgeShape12)
                        .background(if (isReminded) NetflixRed else Color(0xFF262626))
                        .border(
                            width = if (isReminded) 1.5.dp else 1.dp,
                            color = Color(0xFFA0A0A0),
                            shape = BadgeShape12
                        )
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = if (isReminded) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                            contentDescription = badgeText,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = badgeText,
                            color = Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (isExpanded && cardCalloutBadges.isNotEmpty() && showPrePreviewOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = bottomContentPadding)
                    .zIndex(6f)
                    .graphicsLayer {
                        alpha = expandedProgress * prePreviewOverlayAlpha.value
                        translationY = (1f - expandedProgress) * 10f
                    }
            ) {
                BillboardCalloutBadgesRow(
                    badges = cardCalloutBadges,
                    darkenedMoodColor = Color(0xFF10141E),
                    compact = true
                )
            }
        }

        if (progress != null && progress > 0f) {
            // Elegant Netflix Continue Watching progress bar with dark track and red indicator
            val progressHeight = if (isFocused || isExpanded) 5.dp else 3.5.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(progressHeight)
                    .background(Color(0x80000000))
                    .align(Alignment.BottomCenter)
            ) {
                // Inactive dark track
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x40FFFFFF))
                )
                // Active vibrant red progress
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0.04f, 1f))
                        .background(NetflixRed, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                )
            }
        }
    }
}

@Composable
fun Top10RankNumber(
    rank: Int,
    modifier: Modifier = Modifier
) {
    val rankText = rank.toString()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        // Deep shadow layer for high contrast over any poster art
        Text(
            text = rankText,
            fontSize = 90.sp,
            fontWeight = FontWeight.Black,
            color = Color.Black.copy(alpha = 0.95f),
            letterSpacing = (-6).sp,
            modifier = Modifier.offset(x = 2.dp, y = 2.dp)
        )
        // Dark Obsidian Body Fill
        Text(
            text = rankText,
            fontSize = 90.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF141416),
            letterSpacing = (-6).sp
        )
        // Authentic Silver-White Outline Stroke (Netflix Style)
        Text(
            text = rankText,
            fontSize = 90.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFFF5F5F5),
            letterSpacing = (-6).sp,
            style = androidx.compose.ui.text.TextStyle(
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 4.5f,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        )
    }
}
