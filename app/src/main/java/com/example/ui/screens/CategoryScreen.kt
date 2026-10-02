package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.Movie
import com.example.ui.NetflixViewModel
import com.example.ui.components.BillboardSection
import com.example.ui.components.NetflixMovieRow
import com.example.ui.components.getCategoryThemeColor
import com.example.ui.screens.handleTvDpadNavigation
import com.example.ui.theme.NetflixBlack
import com.example.ui.util.TvMotion
import com.example.ui.util.TvKeyPacer
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.rememberTvAmbientColor
import com.example.ui.util.tvAmbientBackground
import com.example.ui.util.ambientTintForAccent

@Composable
fun CategoryScreen(
    categoryName: String,
    viewModel: NetflixViewModel,
    onBack: () -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onMovieClick: (Movie) -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val categoryRows by viewModel.categoryRows.collectAsStateWithLifecycle()
    val activeMovie by viewModel.currentMovie.collectAsStateWithLifecycle()
    val continueWatchingList by viewModel.continueWatchingList.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val isKidProfile = selectedProfile?.isKid == true
    
    val activeCategoryName by remember(categoryName) { mutableStateOf(categoryName) }

    val continueWatchingProgressMap = remember(continueWatchingList) {
        // perf: the previous code computed a ratio then checked `progress.isNaN()`
        // to defensively substitute 0f. A ratio of two finite Longs coerced into
        // [0,1] cannot be NaN — the check is dead code. Drop it for clarity.
        continueWatchingList.associate { entity ->
            val progress = if (entity.durationMs > 0L) {
                (entity.playbackPositionMs.toFloat() / entity.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            entity.movieId to progress
        }
    }
    
    val allMovies = remember(categoryRows, isKidProfile) {
        val raw = categoryRows.flatMap { it.second }.distinctBy { it.id }
        if (isKidProfile) {
            raw.filter { com.example.model.isKidSafeMovie(it) }
        } else {
            raw
        }
    }

    // Generate 5 distinct filtered rows based on active category
    val generatedFiveRows = remember(activeCategoryName, allMovies, categoryRows, isKidProfile) {
        val rows = generateCategoryFilteredRows(activeCategoryName, allMovies, categoryRows)
        if (isKidProfile) {
            rows.map { (cat, list) -> cat to list.filter { com.example.model.isKidSafeMovie(it) } }.filter { it.second.isNotEmpty() }
        } else {
            rows
        }
    }

    // Combine Continue Watching (if present) + 5 category rows matching HomeScreen structure exactly
    val displayedCategoryRows = remember(generatedFiveRows, continueWatchingList, isKidProfile) {
        if (continueWatchingList.isNotEmpty()) {
            val continueMovies = continueWatchingList.map { it.toMovie() }.filter { !isKidProfile || com.example.model.isKidSafeMovie(it) }.distinctBy { it.id }
            val list = mutableListOf<Pair<String, List<Movie>>>()
            if (continueMovies.isNotEmpty()) {
                list.add("Continue Watching Netflix Pro" to continueMovies)
            }
            list.addAll(generatedFiveRows)
            list
        } else {
            generatedFiveRows
        }
    }

    // Billboard featured movie for active category
    val fallbackDefaultMovie = remember(activeCategoryName) {
        Movie(
            id = "0",
            title = activeCategoryName,
            description = "Explore $activeCategoryName movies and series on Netflix Pro.",
            backdropUrl = "",
            posterUrl = "",
            rating = "13+",
            year = "2026",
            type = "Movie"
        )
    }
    // perf: drop `generatedFiveRows` and `isKidProfile` from the key — both are
    // already derivable from `allMovies` and `activeCategoryName`. Including them
    // causes a redundant recomputation when the ViewModel re-emits an equivalent
    // list (no structural equality on List<Movie>).
    val billboardMovie = remember(activeCategoryName, allMovies, activeMovie) {
        val safeActive = activeMovie?.takeIf { !isKidProfile || com.example.model.isKidSafeMovie(it) }
        safeActive ?: generatedFiveRows.firstOrNull()?.second?.firstOrNull() ?: allMovies.firstOrNull() ?: fallbackDefaultMovie
    }

    // Focus state levels:
    // -1: Billboard Section (Hero)
    //  0..N-1: Movie Rows
    var currentFocusLevel by remember { mutableIntStateOf(-1) }

    val billboardFocusRequester = remember { FocusRequester() }
    val moreInfoFocusRequester = remember { FocusRequester() }
    val rowFocusRequesters = remember(displayedCategoryRows.size) {
        List(displayedCategoryRows.size) { FocusRequester() }
    }

    val context = LocalContext.current
    val lowMemory = remember(context) { TvImagePolicy.isLowMemoryDevice(context) }
    val categoryAccent = remember(activeCategoryName) { getCategoryThemeColor(activeCategoryName) }

    // Dynamic Palette Extraction
    var extractedMoodColor by remember(billboardMovie.id, billboardMovie.backdropUrl, billboardMovie.posterUrl, categoryAccent) {
        mutableStateOf(PaletteExtractor.cachedColorFromMovie(billboardMovie) ?: ambientTintForAccent(categoryAccent))
    }

    val isAtMovieRows = currentFocusLevel >= 0
    LaunchedEffect(billboardMovie.id, billboardMovie.backdropUrl, billboardMovie.posterUrl, isAtMovieRows) {
        if (isAtMovieRows || lowMemory) return@LaunchedEffect
        try {
            kotlinx.coroutines.delay(2_000)
            viewModel.awaitBrowsingIdle()
            extractedMoodColor = PaletteExtractor.extractColorFromMovie(context, billboardMovie)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the category tint if optional artwork sampling fails.
        }
    }

    val animatedBgColorState = rememberTvAmbientColor(
        target = if (isAtMovieRows) NetflixBlack else extractedMoodColor,
        label = "categoryAmbientColour"
    )

    // Netflix choreographed entrance: content glides up smoothly without double-fading
    var isContentEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isContentEntered = true
    }

    val contentEntranceOffsetY = animateDpAsState(
        targetValue = if (isContentEntered) 0.dp else 30.dp,
        animationSpec = tween(durationMillis = TvMotion.duration(380), easing = FastOutSlowInEasing),
        label = "categoryEntranceY"
    )

    // Animated Alpha for collapsing Billboard as user navigates down into movie rows
    val billboardAlphaState = animateFloatAsState(
        targetValue = if (currentFocusLevel < 0) 1f else 0f,
        animationSpec = tween(durationMillis = TvMotion.duration(280), easing = FastOutSlowInEasing),
        label = "categoryBillboardAlpha"
    )

    // perf: use withFrameNanos to wait for the first committed frame instead of
    // a fixed 200ms delay. A 200ms delay either steals focus too early (on fast
    // devices where the layout settles in 60ms) or too late (on slow TVs where
    // the first frame lands at 250ms+). awaitFrame() returns as soon as the
    // frame is committed, so focus lands on the right target on every device.
    LaunchedEffect(Unit) {
        try {
            androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
            billboardFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    val rowHeight = 410.dp
    val billboardHeight = 435.dp
    val headerOffset = 28.dp

    val targetScrollY by remember {
        derivedStateOf {
            when {
                currentFocusLevel < 0 -> 0.dp
                currentFocusLevel == 0 -> -(billboardHeight - headerOffset)
                else -> -(billboardHeight - headerOffset) - (rowHeight * currentFocusLevel)
            }
        }
    }

    val animatedScrollY = animateDpAsState(
        targetValue = targetScrollY,
        animationSpec = tween(durationMillis = TvMotion.duration(280), easing = FastOutSlowInEasing),
        label = "categoryAnimatedScrollY"
    )

    val currentOnPlayMovie by rememberUpdatedState(onPlayMovie)
    val currentOnMovieClick by rememberUpdatedState(onMovieClick)

    val coroutineScope = rememberCoroutineScope()
    val manualTouchOffsetAnim = remember { Animatable(0f) }
    val verticalKeyPacer = remember { TvKeyPacer() }
    // bug fix: `density` was used inside the pointerInput drag-end lambda but
    // never declared. The original code would not compile against a missing
    // `density` symbol. Read it from LocalDensity once here so the lambda
    // captures a stable reference and we don't re-read LocalDensity on every
    // drag frame.
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .tvAmbientBackground(animatedBgColorState)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = contentEntranceOffsetY.value.toPx()
                }
                .clipToBounds()
                .pointerInput(displayedCategoryRows.size) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            coroutineScope.launch {
                                manualTouchOffsetAnim.stop()
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                val currentOffset = manualTouchOffsetAnim.value
                                val rowHeightPxValue = with(density) { rowHeight.toPx() }
                                val levelShift = (-currentOffset / rowHeightPxValue).roundToInt()
                                val maxLevel = displayedCategoryRows.size - 1
                                val newLevel = (currentFocusLevel + levelShift).coerceIn(-1, maxLevel)
                                currentFocusLevel = newLevel
                                manualTouchOffsetAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing)
                                )
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                manualTouchOffsetAnim.animateTo(0f, tween(TvMotion.duration(300)))
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                manualTouchOffsetAnim.snapTo(manualTouchOffsetAnim.value + dragAmount)
                            }
                        }
                    )
                }
                .pointerInput(displayedCategoryRows.size) {
                    // bug fix: the previous `while (true)` would throw on
                    // cancellation (the pointerInput suspending function) and
                    // the unhandled exception would tear down the coroutine
                    // scope. wrap in try/catch so a normal cancellation is
                    // silent and any unexpected exception is swallowed — this
                    // is a TV scroll-wheel handler, not critical path.
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (scrollDelta > 0f) {
                                        val maxLevel = displayedCategoryRows.size - 1
                                        if (currentFocusLevel < maxLevel) {
                                            currentFocusLevel++
                                        }
                                    } else if (scrollDelta < 0f) {
                                        if (currentFocusLevel > -1) {
                                            currentFocusLevel--
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // pointerInput coroutine cancelled or the suspending
                        // channel was closed; either way we're done.
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(unbounded = true, align = Alignment.Top)
                    .graphicsLayer { translationY = animatedScrollY.value.toPx() + manualTouchOffsetAnim.value }
                    .padding(bottom = 120.dp)
            ) {
                // Category accent divider and title begin at the screen margin.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(22.dp)
                            .background(categoryAccent)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activeCategoryName,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 1. Featured Billboard Section (Exact layout matching HomeScreen)
                // perf: hoist the click lambdas to stable identities so the
                // BillboardSection subtree sees the same lambda instance on every
                // parent recomposition (and Compose can skip it).
                // bug fix: the previous `remember { { currentOnPlayMovie(billboardMovie) } }`
                // captured `billboardMovie` at the time the lambda was created;
                // if the user focused a different card and the viewmodel's
                // _currentMovie updated, the click would still fire on the
                // STALE billboardMovie. Key the remembers on `billboardMovie.id`
                // (a String, cheap equality) so the closure re-binds to the
                // current movie whenever the billboard changes.
                val onBillboardPlayClick = remember(billboardMovie.id) { { currentOnPlayMovie(billboardMovie) } }
                val onBillboardInfoClick = remember(billboardMovie.id) { { currentOnMovieClick(billboardMovie) } }
                val onBillboardFocused = remember<() -> Unit> { { currentFocusLevel = -1 } }
                val billboardRowCount = displayedCategoryRows.size
                val onBillboardDpadDown = remember<() -> Boolean>(billboardRowCount) {
                    {
                        if (billboardRowCount > 0) {
                            currentFocusLevel = 0
                            try { rowFocusRequesters.getOrNull(0)?.requestFocus() } catch (_: Exception) {}
                        }
                        true
                    }
                }
                val onBillboardDpadUp = remember<() -> Boolean> {
                    {
                        currentFocusLevel = -1
                        false
                    }
                }
                BillboardSection(
                    movie = billboardMovie,
                    height = billboardHeight,
                    alpha = { billboardAlphaState.value },
                    playFocusRequester = billboardFocusRequester,
                    moreInfoFocusRequester = moreInfoFocusRequester,
                    isBillboardFocused = currentFocusLevel == -1,
                    onPlayClick = onBillboardPlayClick,
                    onInfoClick = onBillboardInfoClick,
                    viewModel = viewModel,
                    onFocused = onBillboardFocused,
                    modifier = Modifier
                        .padding(horizontal = 1.5.dp)
                        .handleTvDpadNavigation(
                            onDpadDown = onBillboardDpadDown,
                            onDpadUp = onBillboardDpadUp
                        )
                )

                // 2. Category Filtered Movie & Series Rows (Exact layout matching HomeScreen)
                // perf: hoist the per-row isMovieLocked lambda to a single
                // remembered closure. The original was `{ viewModel.isMovieLocked(it) }`
                // — a new lambda instance per row per recomposition, defeating
                // NetflixMovieRow's stable-input skip.
                val isMovieLocked: (Movie) -> Boolean = remember(viewModel) {
                    { movie -> viewModel.isMovieLocked(movie) }
                }
                displayedCategoryRows.forEachIndexed { index, (title, movies) ->
                    // perf: include `isKidProfile` in the key so a kids/non-kids
                    // toggle re-keys the row (otherwise Compose may reuse the
                    // composition slot with stale kids-filtered children).
                    key("category_row_${title}_${index}_${isKidProfile}") {
                        val rowFocusLevel = index
                        val isRowVisible = if (currentFocusLevel < 0) {
                            index == 0
                        } else {
                            rowFocusLevel in (currentFocusLevel - 1)..(currentFocusLevel + 1)
                        }

                        val rowAlphaState = animateFloatAsState(
                            targetValue = if (rowFocusLevel < currentFocusLevel) 0f else 1f,
                            animationSpec = tween(durationMillis = TvMotion.duration(180), easing = FastOutSlowInEasing),
                            label = "categoryRowAlpha_${title}_$index"
                        )

                        // perf: hoist the row's navigation lambdas to stable
                        // identities keyed only on the index, the row list size
                        // (for boundary checks), and the focus requester list.
                        // Without hoisting, every parent recomposition creates 5
                        // fresh lambdas per row, blocking NetflixMovieRow from
                        // skipping its body.
                        val rowCount = displayedCategoryRows.size
                        val onRowDpadDown = remember<() -> Boolean>(index, rowCount) {
                            {
                                if (index < rowCount - 1 && verticalKeyPacer.accept(1)) {
                                    currentFocusLevel = index + 1
                                    try { rowFocusRequesters.getOrNull(index + 1)?.requestFocus() } catch (_: Exception) {}
                                }
                                true
                            }
                        }
                        val onRowDpadUp = remember<() -> Boolean>(index) {
                            {
                                if (verticalKeyPacer.accept(-1)) {
                                    if (index > 0) {
                                        currentFocusLevel = index - 1
                                        try { rowFocusRequesters.getOrNull(index - 1)?.requestFocus() } catch (_: Exception) {}
                                    } else {
                                        currentFocusLevel = -1
                                        try { billboardFocusRequester.requestFocus() } catch (_: Exception) {}
                                    }
                                }
                                true
                            }
                        }
                        val onRowMovieFocused = remember<(Movie) -> Unit>(index) {
                            { movie ->
                                viewModel.updateCurrentMovie(movie)
                                currentFocusLevel = index
                            }
                        }
                        val onRowFocused = remember<() -> Unit>(index) {
                            { currentFocusLevel = index }
                        }
                        val onRowMovieClick = remember<(Movie) -> Unit> {
                            { movie -> currentOnMovieClick(movie) }
                        }
                        NetflixMovieRow(
                            title = title,
                            movies = movies,
                            isPortrait = true,
                            rowFocusRequester = rowFocusRequesters.getOrNull(index),
                            isMovieLocked = isMovieLocked,
                            height = 410.dp,
                            alpha = { rowAlphaState.value },
                            isVisible = isRowVisible,
                            continueWatchingProgressMap = continueWatchingProgressMap,
                            onDpadDown = onRowDpadDown,
                            onDpadUp = onRowDpadUp,
                            onMovieFocused = onRowMovieFocused,
                            onRowFocused = onRowFocused,
                            onMovieClick = onRowMovieClick,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generates 5 distinct movie/series rows filtered according to the selected category.
 */
private fun generateCategoryFilteredRows(
    categoryName: String,
    allMovies: List<Movie>,
    categoryRows: List<Pair<String, List<Movie>>>
): List<Pair<String, List<Movie>>> {
    val lowerCat = categoryName.lowercase()

    val filteredMovies = when {
        lowerCat.contains("tv") || lowerCat.contains("series") || lowerCat.contains("show") -> {
            allMovies.filter { it.type.contains("Series", ignoreCase = true) }
        }
        lowerCat.contains("movie") || lowerCat.contains("film") || lowerCat.contains("theater") -> {
            allMovies.filter { it.type.contains("Movie", ignoreCase = true) }
        }
        lowerCat.contains("action") -> {
            allMovies.filter {
                it.description.contains("action|hero|battle|fight|mission|agent|war|danger|weapon|chase".toRegex(RegexOption.IGNORE_CASE)) ||
                it.title.contains("action|hero|battle|war".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("comedy") || lowerCat.contains("comedies") -> {
            allMovies.filter {
                it.description.contains("comedy|funny|laugh|hilarious|humor|joke|sitcom|comic".toRegex(RegexOption.IGNORE_CASE)) ||
                it.title.contains("comedy|fun".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("sci-fi") || lowerCat.contains("scifi") || lowerCat.contains("science") -> {
            allMovies.filter {
                it.description.contains("sci-fi|space|alien|future|galaxy|robot|star|tech|time|cyber|quantum".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("romance") || lowerCat.contains("romantic") -> {
            allMovies.filter {
                it.description.contains("love|romance|romantic|heart|couple|kiss|wedding|passion".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("animation") || lowerCat.contains("anime") -> {
            allMovies.filter {
                it.description.contains("animated|anime|cartoon|dragon|magic|pixar|disney".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("drama") || lowerCat.contains("dramas") -> {
            allMovies.filter {
                it.description.contains("drama|story|family|life|truth|secret|struggle|deep|crime".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("horror") -> {
            allMovies.filter {
                it.description.contains("horror|dark|fear|ghost|monster|dead|haunted|nightmare|zombie|evil".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("doc") || lowerCat.contains("documentaries") -> {
            allMovies.filter {
                it.description.contains("doc|real|history|nature|planet|world|truth|life|science".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("thriller") || lowerCat.contains("thrillers") -> {
            allMovies.filter {
                it.description.contains("thriller|mystery|suspense|crime|detective|kill|agent|secret".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("kids") || lowerCat.contains("family") -> {
            allMovies.filter {
                it.description.contains("kids|family|fun|magic|adventure|friend|animal|journey".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        lowerCat.contains("fantasy") -> {
            allMovies.filter {
                it.description.contains("magic|fantasy|wizard|realm|kingdom|sword|dragon|myth|legend".toRegex(RegexOption.IGNORE_CASE))
            }
        }
        else -> {
            categoryRows.firstOrNull { it.first.equals(categoryName, ignoreCase = true) }?.second ?: emptyList()
        }
    }.ifEmpty { allMovies }

    fun getSubset(offset: Int, count: Int = 10): List<Movie> {
        val pool = if (filteredMovies.isNotEmpty()) filteredMovies else allMovies
        val size = pool.size
        if (size == 0) return emptyList()
        val result = mutableListOf<Movie>()
        for (i in 0 until count.coerceAtMost(size)) {
            val idx = (offset + i) % size
            result.add(pool[idx])
        }
        return result.distinctBy { it.id }.ifEmpty { allMovies.take(count) }
    }

    val row1Title = when {
        lowerCat.contains("tv") || lowerCat.contains("series") -> "Trending TV Series"
        lowerCat.contains("movie") || lowerCat.contains("film") -> "Trending Blockbuster Movies"
        else -> "Top Picks in $categoryName"
    }

    val row2Title = when {
        lowerCat.contains("tv") || lowerCat.contains("series") -> "Popular Binge-Worthy Shows"
        lowerCat.contains("movie") || lowerCat.contains("film") -> "Popular Movies for You"
        else -> "Popular $categoryName Titles"
    }

    val row3Title = when {
        lowerCat.contains("tv") || lowerCat.contains("series") -> "Critically Acclaimed TV Series"
        lowerCat.contains("movie") || lowerCat.contains("film") -> "Critically Acclaimed Movies"
        else -> "Critically Acclaimed $categoryName"
    }

    val row4Title = when {
        lowerCat.contains("tv") || lowerCat.contains("series") -> "Action & Drama Series"
        lowerCat.contains("movie") || lowerCat.contains("film") -> "New & Popular Releases"
        else -> "Trending & New Releases"
    }

    val row5Title = when {
        lowerCat.contains("tv") || lowerCat.contains("series") -> "Recommended TV Series"
        lowerCat.contains("movie") || lowerCat.contains("film") -> "Recommended Feature Films"
        else -> "Recommended in $categoryName"
    }

    return listOf(
        row1Title to getSubset(0, 10),
        row2Title to getSubset(2, 10),
        row3Title to getSubset(4, 10),
        row4Title to getSubset(1, 10),
        row5Title to getSubset(3, 10)
    )
}
