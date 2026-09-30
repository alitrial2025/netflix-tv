package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.TvKeyPacer
import com.example.ui.util.TvMotion
import com.example.ui.util.rememberTvAmbientColor
import com.example.ui.util.tvAmbientBackground
import com.example.ui.util.ambientTintForAccent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.model.Profile
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isSeriesContent
import com.example.ui.NetflixViewModel
import com.example.ui.components.*
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.KidsAccent

// ── Catalog bucket snapshot: one immutable holder replaces 3 independent mutableStateOf lists ──
private data class CatalogBuckets(
    val all: List<Movie> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Movie> = emptyList(),
    val isReady: Boolean = false
)

private fun getTabOrder(tab: String): Int = when (tab) {
    "Search" -> 0
    "Home" -> 1
    "Series" -> 2
    "Films" -> 3
    "My Netflix" -> 4
    else -> 1
}

private fun buildHomeTabRows(
    activeTab: String,
    catalogBuckets: CatalogBuckets,
    continueWatchingList: List<com.example.data.ContinueWatchingEntity>,
    watchHistoryMovies: List<Movie>,
    myListMovieIds: Set<String>,
    myListAddedAt: Map<String, Long>,
    categoryRows: List<Pair<String, List<Movie>>>,
    profileName: String,
    isKidProfile: Boolean
): List<Pair<String, List<Movie>>> {
    val continueMovies = continueWatchingList.map { it.toMovie() }.filter { !isKidProfile || com.example.model.isKidSafeMovie(it) }.distinctBy { it.id }
    val myListMovies = catalogBuckets.all.filter { myListMovieIds.contains(it.id) }.distinctBy { it.id }
    val seed = catalogBuckets.all.size + activeTab.hashCode() + profileName.hashCode()
    val baseRows = buildAlgorithmicRowsForTab(
        activeTab = activeTab,
        allCatalogMovies = catalogBuckets.all,
        allMoviesList = catalogBuckets.movies,
        allSeriesList = catalogBuckets.series,
        continueWatchingMovies = continueMovies,
        myListMovies = myListMovies,
        categoryRows = categoryRows,
        profileName = profileName,
        isKidProfile = isKidProfile,
        randomSeed = seed,
        myListAddedAt = myListAddedAt
    ).filter { it.second.isNotEmpty() }
    return if (activeTab == "My Netflix") {
        val watchedMovies = watchHistoryMovies.mapNotNull { historyMovie ->
            val catalogMovie = if (historyMovie.title == "Watched title") {
                catalogBuckets.all.singleOrNull { it.id == historyMovie.id }
            } else {
                com.example.model.findMovieByIdentity(
                    catalogBuckets.all,
                    historyMovie.id,
                    historyMovie.title,
                    historyMovie.catalogMediaKind()
                )
            }
            (catalogMovie ?: historyMovie)
                .takeIf { !isKidProfile || com.example.model.isKidSafeMovie(it) }
        }.distinctBy { "${it.catalogMediaKind()}:${it.id}" }
        if (watchedMovies.isNotEmpty()) {
            baseRows.toMutableList().apply {
                val continueRow = indexOfFirst { it.first.startsWith("Continue Watching") }
                add(if (continueRow >= 0) continueRow + 1 else 0,
                    "Recent Watch History for $profileName" to watchedMovies)
            }
        } else baseRows
    } else baseRows
}

// ── Static gradient brushes that never change ──
private val KidsNavGradientBrush = Brush.verticalGradient(
    colors = listOf(
        Color.Black.copy(alpha = 0.75f),
        Color.Black.copy(alpha = 0.35f),
        Color.Transparent
    )
)

private val KidsVignetteGradientBrush = Brush.verticalGradient(
    colors = listOf(Color.Black.copy(alpha = 0.80f), Color.Transparent)
)

private val KidsFeatherGradientBrush = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.38f to Color.Transparent,
    0.65f to Color.Black.copy(alpha = 0.24f),
    0.85f to Color.Black.copy(alpha = 0.42f),
    1.0f to Color.Black.copy(alpha = 0.56f)
)

private val CardOverlayGradient = Brush.verticalGradient(
    colors = listOf(
        Color.Transparent,
        Color.Black.copy(alpha = 0.2f),
        Color.Black.copy(alpha = 0.85f)
    ),
    startY = 100f
)

@Composable
fun HomeScreen(
    viewModel: NetflixViewModel,
    onPlayMovie: (Movie) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onCategoryClick: (String) -> Unit,
    onNavigateToProfiles: () -> Unit = {}
) {
    androidx.activity.compose.BackHandler {
        // Prevent exiting app on back press from root
    }

    val context = LocalContext.current
    val homeLayout = rememberHomeLayoutMetrics()
    val isLowMemoryDevice = remember(context) { TvImagePolicy.isLowMemoryDevice(context) }
    val categoryRows by viewModel.categoryRows.collectAsStateWithLifecycle()
    val isCatalogLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val continueWatchingList by viewModel.continueWatchingList.collectAsStateWithLifecycle()
    val watchHistoryMovies by viewModel.watchHistoryMovies.collectAsStateWithLifecycle()
    val myListMovieIds by viewModel.myListMovieIds.collectAsStateWithLifecycle()
    val myListAddedAt by viewModel.myListAddedAt.collectAsStateWithLifecycle()
    val remindedMovieIds by viewModel.remindedMovieIds.collectAsStateWithLifecycle()

    val profileName = selectedProfile?.name ?: "User"
    val homeArtworkReadyState = remember(selectedProfile?.id) { mutableStateOf(false) }
    var homeArtworkReady by homeArtworkReadyState
    val homeRowsReadyState = remember(selectedProfile?.id) { mutableStateOf(false) }
    var homeRowsReady by homeRowsReadyState
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isHomeResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, viewModel, selectedProfile?.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { owner, _ ->
            isHomeResumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!isHomeResumed) viewModel.markHomeHidden()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.markHomeHidden()
        }
    }
    // perf: compute the signature string once per list change (was being recomputed in the remember key)
    val continueWatchingProgressSignature = remember(continueWatchingList) {
        continueWatchingList.joinToString("|") { it.movieId + ":" + it.playbackPositionMs + "/" + it.durationMs }
    }
    val continueWatchingProgressMap = remember(
        continueWatchingList.size,
        continueWatchingProgressSignature
    ) {
        continueWatchingList.associate { entity ->
            val progress = if (entity.durationMs > 0L) {
                (entity.playbackPositionMs.toFloat() / entity.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            entity.movieId to if (progress.isNaN()) 0f else progress
        }
    }
    val continueWatchingRemainingMap = remember(continueWatchingProgressSignature) {
        continueWatchingList.associate { it.movieId to (it.durationMs - it.playbackPositionMs).coerceAtLeast(0L) }
    }

    val navBarTabState = remember { mutableStateOf("Home") }
    var navBarTab by navBarTabState
    val contentTabState = remember { mutableStateOf("Home") }
    var contentTab by contentTabState
    val pendingTabFocusState = remember { mutableStateOf<String?>(null) }
    var pendingTabFocus by pendingTabFocusState
    val enteringKidProfileState = remember { mutableStateOf<Profile?>(null) }
    var enteringKidProfile by enteringKidProfileState
    val lockedProfileState = remember { mutableStateOf<Profile?>(null) }

    // Profile-scoped state resets synchronously, without a first-frame clear/recompose.
    val tabFocusedMovies = remember(selectedProfile?.id) { mutableStateMapOf<String, Movie>() }

    // Debounced tab sync: NavBar cursor tracks instantly, but heavy content rebuild
    // only fires once the user settles on a tab or commits with D-pad DOWN.
    LaunchedEffect(navBarTabState, contentTabState) {
        // Observe the cursor outside composition, so fast header focus changes
        // do not recompose the catalogue or restart artwork layout on each key.
        snapshotFlow { navBarTabState.value }.collectLatest { requestedTab ->
            if (requestedTab != contentTabState.value) {
                kotlinx.coroutines.delay(220)
                if (navBarTabState.value == requestedTab) contentTabState.value = requestedTab
            }
        }
    }

    // One content layer and one reusable animation. If another tab is committed
    // mid-slide, its entry continues at the current offset instead of jumping.
    val tabSlide = remember(selectedProfile?.id) { Animatable(0f) }
    val tabSlideTabState = remember(selectedProfile?.id) { mutableStateOf(contentTab) }
    val tabSlideStart = remember(contentTab, selectedProfile?.id) {
        val previousTab = tabSlideTabState.value
        when {
            previousTab == contentTab -> 0f
            tabSlide.isRunning -> tabSlide.value
            else -> (getTabOrder(contentTab) - getTabOrder(previousTab)).coerceIn(-1, 1) * 24f
        }
    }
    LaunchedEffect(contentTab, tabSlide) {
        if (tabSlideTabState.value == contentTab) return@LaunchedEffect
        tabSlide.snapTo(tabSlideStart)
        tabSlideTabState.value = contentTab
        tabSlide.animateTo(0f, tween(TvMotion.duration(200), easing = LinearOutSlowInEasing))
    }

    // Dynamic focus level state machine:
    // -2: TopNavBar
    // -1: Billboard (Hero)
    //  0: Categories Bar
    //  1: Row 0
    //  2: Row 1 ... N: Row N-1
    val currentFocusLevelState = remember { mutableIntStateOf(-2) }
    var currentFocusLevel by currentFocusLevelState

    // Requesters for deterministic focus jumping
    val profileFocusRequester = remember { FocusRequester() }
    val searchIconFocusRequester = remember { FocusRequester() }
    val homeTabFocusRequester = remember { FocusRequester() }
    val seriesTabFocusRequester = remember { FocusRequester() }
    val filmsTabFocusRequester = remember { FocusRequester() }
    val myNetflixTabFocusRequester = remember { FocusRequester() }

    val searchFocusRequester = remember { FocusRequester() }
    val billboardFocusRequester = remember { FocusRequester() }
    val moreInfoFocusRequester = remember { FocusRequester() }
    val categoriesFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }

    fun requestNavBarFocus() {
        currentFocusLevel = -2
        try {
            when (navBarTab) {
                "Search" -> searchIconFocusRequester.requestFocus()
                "Home" -> homeTabFocusRequester.requestFocus()
                "Series" -> seriesTabFocusRequester.requestFocus()
                "Films" -> filmsTabFocusRequester.requestFocus()
                "My Netflix" -> myNetflixTabFocusRequester.requestFocus()
                else -> homeTabFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    val isKidProfile = selectedProfile?.isKid == true
    val isKidHome = isKidProfile && contentTab == "Home"

    // perf: consolidate 3 independent mutableStateOf lists into a single immutable Snapshot,
    // so catalog hydration is ONE assignment and downstream readers track one source of truth.
    var catalogBuckets by remember(isKidProfile) { mutableStateOf(CatalogBuckets()) }

    LaunchedEffect(homeArtworkReady, homeRowsReady, isHomeResumed, isCatalogLoading, catalogBuckets.isReady, catalogBuckets.all.isEmpty(), currentFocusLevel >= 0, isKidHome) {
        // Browsing rows may dispose an unfinished Kids backdrop request. The
        // visible rows are a usable first screen even without a hero image.
        // Kids filtering can produce an empty usable catalogue even when the
        // raw response has adult titles. Let discovery recover that case too.
        val emptyResult = !isCatalogLoading && catalogBuckets.isReady && catalogBuckets.all.isEmpty()
        if (isHomeResumed && ((homeRowsReady && (homeArtworkReady || currentFocusLevel >= 0 || (isKidProfile && !isKidHome))) || emptyResult)) {
            withFrameNanos { }
            withFrameNanos { }
            viewModel.markHomeReady()
        }
    }

    LaunchedEffect(selectedProfile?.id, isHomeResumed) {
        if (selectedProfile != null && isHomeResumed) viewModel.ensureStreamWarmed()
    }

    // Row generation depends on catalogue membership and each user's saved / in-progress
    // titles. Counts alone leave the rail cache stale when one item is swapped for another.
    val continueWatchingRowsSignature = remember(continueWatchingList) {
        continueWatchingList.joinToString(separator = "|") { it.movieId }
    }
    val watchHistoryRowsSignature = remember(watchHistoryMovies) {
        watchHistoryMovies.joinToString(separator = "|") {
            "${it.id}:${it.title}:${it.posterUrl}:${it.backdropUrl}:${it.rating}:${it.type}"
        }
    }
    val myListSignature = remember(myListMovieIds, myListAddedAt) {
        myListMovieIds.sorted().joinToString(separator = "|") { id ->
            "$id:${myListAddedAt[id] ?: 0L}"
        }
    }

    var kidsFeaturedTitles by remember(isKidProfile) { mutableStateOf<List<Movie>>(emptyList()) }
    LaunchedEffect(isKidProfile, categoryRows) {
        if (!isKidProfile) return@LaunchedEffect
        val art = com.example.data.KidsCharacterArtwork.load(context)
        val available = categoryRows.flatMap { it.second }.distinctBy { "${it.catalogMediaKind()}:${it.id}" }
        // Bundled characters appear immediately, including when metadata is offline.
        val seeds = art.registeredTitles().take(5).map { seed ->
            available.firstOrNull { it.id == seed.id && it.catalogMediaKind() == seed.catalogMediaKind() } ?: seed
        }
        kidsFeaturedTitles = seeds
        val featured = mutableListOf<Movie>()
        for ((kind, id) in art.titleIdentities().take(5)) {
            val title = available.firstOrNull { it.id == id && it.catalogMediaKind() == kind }
                ?: viewModel.fetchKidsArtworkTitle(kind, id)
                ?: seeds.firstOrNull { it.id == id && it.catalogMediaKind() == kind }
            if (title != null && art.urlFor(title) != null) {
                featured += title
                viewModel.cacheMovie(title)
            }
        }
        kidsFeaturedTitles = featured
    }

    var curatedBillboardCandidates by remember(isKidProfile) { mutableStateOf<Map<String, List<Movie>>>(emptyMap()) }

    LaunchedEffect(categoryRows, isKidProfile) {
        val (buckets, candidates) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val rawCatalog = categoryRows.flatMap { it.second }.distinctBy { it.id }
            val catalog = if (isKidProfile) {
                rawCatalog.filter { com.example.model.isKidSafeMovie(it) }
            } else {
                rawCatalog
            }
            val movies = catalog.filter { !it.isSeriesContent() }.ifEmpty { catalog }
            val series = catalog.filter { it.isSeriesContent() }.ifEmpty { catalog }

            val buckets = CatalogBuckets(all = catalog, movies = movies, series = series, isReady = true)

            // Prepare only the tab the user will see. Other tabs are ranked on demand.
            val tab = contentTab
            val source = when (tab) {
                "Series" -> series
                "Films" -> movies
                else -> catalog
            }
            val candidates = if (tab == "Search") emptyMap() else mapOf(
                tab to BillboardAlgorithm.rankBillboardMovies(source, tab, isKidProfile, 8)
            )
            buckets to candidates
        }
        catalogBuckets = buckets
        curatedBillboardCandidates = candidates
    }

    LaunchedEffect(contentTab, catalogBuckets, isKidProfile) {
        val tab = contentTab
        if (tab == "Search" || curatedBillboardCandidates.containsKey(tab)) return@LaunchedEffect
        val source = when (tab) {
            "Series" -> catalogBuckets.series
            "Films" -> catalogBuckets.movies
            else -> catalogBuckets.all
        }
        if (source.isEmpty()) return@LaunchedEffect
        val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            BillboardAlgorithm.rankBillboardMovies(source, tab, isKidProfile, 8)
        }
        curatedBillboardCandidates = curatedBillboardCandidates + (tab to candidates)
    }

    fun getDefaultMovieForTab(tab: String): Movie {
        val candidates = if (isKidProfile && tab == "Home" && kidsFeaturedTitles.isNotEmpty())
            kidsFeaturedTitles else curatedBillboardCandidates[tab].orEmpty()
        val candidate = when (tab) {
            "Series" -> {
                candidates.firstOrNull() ?: catalogBuckets.series.firstOrNull() ?: catalogBuckets.all.firstOrNull()
            }
            "Films" -> {
                candidates.firstOrNull() ?: catalogBuckets.movies.firstOrNull() ?: catalogBuckets.all.firstOrNull()
            }
            "My Netflix" -> {
                val continueMovie = continueWatchingList.map { it.toMovie() }.firstOrNull { !isKidProfile || com.example.model.isKidSafeMovie(it) }
                val myListItem = catalogBuckets.all.firstOrNull { myListMovieIds.contains(it.id) }
                continueMovie ?: myListItem ?: candidates.firstOrNull() ?: catalogBuckets.all.firstOrNull()
            }
            else -> { // "Home"
                candidates.firstOrNull() ?: catalogBuckets.all.firstOrNull()
            }
        }
        return candidate ?: Movie(
            id = "0",
            title = "Netflix Pro",
            description = "Explore trending movies and series on Netflix Pro.",
            backdropUrl = "",
            posterUrl = "",
            rating = "13+",
            year = "2026",
            type = "Movie"
        )
    }

    // Tab-specific Hero Movie: updates per tab and per focused card in that tab, independently of other tabs
    // perf: key on derived counts not full list reference to avoid invalidation on identity churn.
    val currentHeroMovie = remember(
        contentTab,
        tabFocusedMovies[contentTab],
        catalogBuckets,
        curatedBillboardCandidates,
        kidsFeaturedTitles,
        continueWatchingList,
        myListMovieIds,
        isKidProfile
    ) {
        tabFocusedMovies[contentTab] ?: getDefaultMovieForTab(contentTab)
    }

    // A new cache belongs to one immutable input snapshot. Cancelled background
    // builders can no longer publish old-profile or old-catalogue rows into it.
    val tabRowsGeneration = remember(
        catalogBuckets, selectedProfile?.id, profileName,
        continueWatchingRowsSignature, watchHistoryRowsSignature, myListSignature
    ) { Any() }
    val tabRowsCache = remember(tabRowsGeneration) {
        mutableStateMapOf<String, List<Pair<String, List<Movie>>>>()
    }

    LaunchedEffect(tabRowsGeneration, contentTab) {
        if (!catalogBuckets.isReady) return@LaunchedEffect
        suspend fun prepareRows(tab: String) {
            if (tab == "Search" || tabRowsCache.containsKey(tab)) return
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                buildHomeTabRows(tab, catalogBuckets, continueWatchingList, watchHistoryMovies,
                    myListMovieIds, myListAddedAt, categoryRows, profileName, isKidProfile)
            }
            // withContext checks cancellation before returning to the UI thread.
            if (!tabRowsCache.containsKey(tab)) tabRowsCache[tab] = rows
        }
        prepareRows(contentTab)
        // Warm only lists and ranking data after input is quiet. No offscreen
        // composables, artwork requests or players are created for these tabs.
        for (tab in listOf("Home", "Series", "Films", "My Netflix")) {
            viewModel.awaitHomeIdle()
            prepareRows(tab)
            if (!curatedBillboardCandidates.containsKey(tab)) {
                val source = when (tab) {
                    "Series" -> catalogBuckets.series
                    "Films" -> catalogBuckets.movies
                    else -> catalogBuckets.all
                }
                val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    BillboardAlgorithm.rankBillboardMovies(source, tab, isKidProfile, 8)
                }
                if (!curatedBillboardCandidates.containsKey(tab)) {
                    curatedBillboardCandidates = curatedBillboardCandidates + (tab to candidates)
                }
            }
        }
    }

    // A tab's focus target is attached only after its new content has been laid out.
    // Keep a pending request while asynchronous rows load on Kids tabs.
    LaunchedEffect(pendingTabFocus, contentTab, selectedProfile?.id, tabRowsCache[contentTab]) {
        val tab = pendingTabFocus ?: return@LaunchedEffect
        if (tab != contentTab || tab != navBarTab) return@LaunchedEffect
        if (isKidProfile && tab != "Home" && tab != "Search" && tabRowsCache[tab].isNullOrEmpty()) return@LaunchedEffect
        val requester = when {
            tab == "Search" -> searchFocusRequester
            isKidProfile && tab != "Home" -> firstRowFocusRequester
            else -> billboardFocusRequester
        }
        repeat(2) {
            withFrameNanos { }
            if (pendingTabFocus != tab || contentTab != tab || navBarTab != tab || currentFocusLevel != -2) return@LaunchedEffect
            if (runCatching { requester.requestFocus() }.isSuccess) {
                currentFocusLevel = if (isKidProfile && tab != "Home" && tab != "Search") 0 else -1
                pendingTabFocus = null
                return@LaunchedEffect
            }
        }
    }

    // Dynamic Palette Extraction for Screen Ambient Background
    var extractedMoodColor by remember(currentHeroMovie.id, currentHeroMovie.backdropUrl, currentHeroMovie.posterUrl) {
        mutableStateOf(PaletteExtractor.cachedColorFromMovie(currentHeroMovie) ?: PaletteExtractor.extractMovieMoodFallback(currentHeroMovie))
    }
    val selectedCategoryAccentState = remember(selectedProfile?.id) { mutableStateOf(getCategoryThemeColor("Dramas")) }
    var selectedCategoryAccent by selectedCategoryAccentState

    // Smoothly transition background from extracted poster mood color to NetflixBlack when on rows or in Search tab
    val isAtRows by remember(contentTab, isKidProfile) {
        derivedStateOf {
            if (contentTab == "Search" || navBarTabState.value == "Search") {
                true
            } else if (isKidProfile || contentTab != "Home") {
                currentFocusLevelState.intValue >= 0
            } else {
                currentFocusLevelState.intValue >= 1
            }
        }
    }

    val isAtCategories = !isKidProfile && contentTab == "Home" && currentFocusLevel == 0
    LaunchedEffect(currentHeroMovie.id, currentHeroMovie.backdropUrl, currentHeroMovie.posterUrl, isAtRows, isAtCategories) {
        // The metadata tint is already present on the first frame. Sampling the
        // artwork starts another image request and palette decode; on small TVs
        // keep the tint, and elsewhere wait until the initial row and session
        // work has passed before doing this optional refinement.
        if (isAtRows || isAtCategories || isLowMemoryDevice) return@LaunchedEffect
        try {
            kotlinx.coroutines.delay(4_000L)
            viewModel.awaitHomeIdle()
            extractedMoodColor = PaletteExtractor.extractColorFromMovie(context, currentHeroMovie)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The metadata-derived mood colour remains visible until artwork is ready.
        }
    }

    val targetBgColor = when {
        isAtRows -> NetflixBlack
        isAtCategories -> ambientTintForAccent(selectedCategoryAccent)
        else -> extractedMoodColor
    }
    val animatedBgColorState = rememberTvAmbientColor(
        target = targetBgColor,
        label = "homeAmbientColour"
    )

    val isBillboardFocused by remember { derivedStateOf { currentFocusLevelState.intValue < 0 } }
    // One interruptible motion drives the viewport and all its fades. Springs
    // retain momentum on repeated D-pad input and settle without overshooting.
    val focusTransition = key(selectedProfile?.id, contentTab) {
        updateTransition(targetState = currentFocusLevel, label = "homeFocus")
    }
    val homeFocusPosition = focusTransition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(430f), visibilityThreshold = 0.005f)
        },
        label = "homeFocusPosition"
    ) { it.toFloat() }

    // perf: wait for the first fully-laid-out frame before grabbing focus, instead of
    // guessing 180ms. awaitFrame() returns as soon as the frame is committed.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
        requestNavBarFocus()
    }

    // Stabilize lambda references to prevent child recomposition when parent recomposes
    val latestOnPlayMovie by rememberUpdatedState(onPlayMovie)
    val latestOnMovieClick by rememberUpdatedState(onMovieClick)
    val currentOnPlayMovie: (Movie) -> Unit = remember(viewModel) {
        { movie -> viewModel.stopHomePreviews(); latestOnPlayMovie(movie) }
    }
    val currentOnMovieClick: (Movie) -> Unit = remember(viewModel) {
        { movie -> viewModel.stopHomePreviews(); latestOnMovieClick(movie) }
    }
    val currentOnCategoryClick by rememberUpdatedState(onCategoryClick)

    HomeScene(
        HomeRenderScope(
            context = context,
            viewModel = viewModel,
            homeLayout = homeLayout,
            isLowMemoryDevice = isLowMemoryDevice,
            categoryRows = categoryRows,
            selectedProfile = selectedProfile,
            profiles = profiles,
            continueWatchingList = continueWatchingList,
            watchHistoryMovies = watchHistoryMovies,
            myListMovieIds = myListMovieIds,
            remindedMovieIds = remindedMovieIds,
            profileName = profileName,
            homeArtworkReadyState = homeArtworkReadyState,
            homeRowsReadyState = homeRowsReadyState,
            navBarTabState = navBarTabState,
            contentTabState = contentTabState,
            pendingTabFocusState = pendingTabFocusState,
            enteringKidProfileState = enteringKidProfileState,
            lockedProfileState = lockedProfileState,
            tabFocusedMovies = tabFocusedMovies,
            tabSlideStart = tabSlideStart,
            tabSlideTabState = tabSlideTabState,
            tabSlide = tabSlide,
            currentFocusLevelState = currentFocusLevelState,
            profileFocusRequester = profileFocusRequester,
            searchIconFocusRequester = searchIconFocusRequester,
            homeTabFocusRequester = homeTabFocusRequester,
            seriesTabFocusRequester = seriesTabFocusRequester,
            filmsTabFocusRequester = filmsTabFocusRequester,
            myNetflixTabFocusRequester = myNetflixTabFocusRequester,
            searchFocusRequester = searchFocusRequester,
            billboardFocusRequester = billboardFocusRequester,
            moreInfoFocusRequester = moreInfoFocusRequester,
            categoriesFocusRequester = categoriesFocusRequester,
            firstRowFocusRequester = firstRowFocusRequester,
            catalogBuckets = catalogBuckets,
            curatedBillboardCandidates = curatedBillboardCandidates,
            getDefaultMovieForTab = ::getDefaultMovieForTab,
            currentHeroMovie = currentHeroMovie,
            tabRowsGeneration = tabRowsGeneration,
            tabRowsCache = tabRowsCache,
            extractedMoodColor = extractedMoodColor,
            selectedCategoryAccentState = selectedCategoryAccentState,
            animatedBgColorState = animatedBgColorState,
            focusTransition = focusTransition,
            homeFocusPosition = homeFocusPosition,
            requestNavBarFocus = ::requestNavBarFocus,
            currentOnPlayMovie = currentOnPlayMovie,
            currentOnMovieClick = currentOnMovieClick,
            currentOnCategoryClick = currentOnCategoryClick,
            onNavigateToProfiles = onNavigateToProfiles,
            continueWatchingProgressMap = continueWatchingProgressMap,
            continueWatchingRemainingMap = continueWatchingRemainingMap,
            myListAddedAt = myListAddedAt,
            kidsFeaturedTitles = kidsFeaturedTitles
        )
    )
}


/** Separate generated methods keep Home's register allocation small on older TVs.
 * Every section shares the original State objects, focus targets and motion. */
private class HomeRenderScope(
    val context: android.content.Context,
    val viewModel: NetflixViewModel,
    val homeLayout: HomeLayoutMetrics,
    val isLowMemoryDevice: Boolean,
    val categoryRows: List<Pair<String, List<Movie>>>,
    val selectedProfile: Profile?,
    val profiles: List<Profile>,
    val continueWatchingList: List<com.example.data.ContinueWatchingEntity>,
    val watchHistoryMovies: List<Movie>,
    val myListMovieIds: Set<String>,
    val remindedMovieIds: Set<String>,
    val profileName: String,
    val homeArtworkReadyState: MutableState<Boolean>,
    val homeRowsReadyState: MutableState<Boolean>,
    val navBarTabState: MutableState<String>,
    val contentTabState: MutableState<String>,
    val pendingTabFocusState: MutableState<String?>,
    val enteringKidProfileState: MutableState<Profile?>,
    val lockedProfileState: MutableState<Profile?>,
    val tabFocusedMovies: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Movie>,
    val tabSlideStart: Float,
    val tabSlideTabState: MutableState<String>,
    val tabSlide: Animatable<Float, AnimationVector1D>,
    val currentFocusLevelState: MutableIntState,
    val profileFocusRequester: FocusRequester,
    val searchIconFocusRequester: FocusRequester,
    val homeTabFocusRequester: FocusRequester,
    val seriesTabFocusRequester: FocusRequester,
    val filmsTabFocusRequester: FocusRequester,
    val myNetflixTabFocusRequester: FocusRequester,
    val searchFocusRequester: FocusRequester,
    val billboardFocusRequester: FocusRequester,
    val moreInfoFocusRequester: FocusRequester,
    val categoriesFocusRequester: FocusRequester,
    val firstRowFocusRequester: FocusRequester,
    val catalogBuckets: CatalogBuckets,
    val curatedBillboardCandidates: Map<String, List<Movie>>,
    val getDefaultMovieForTab: (String) -> Movie,
    val currentHeroMovie: Movie,
    val tabRowsGeneration: Any,
    val tabRowsCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, List<Pair<String, List<Movie>>>>,
    val extractedMoodColor: Color,
    val selectedCategoryAccentState: MutableState<Color>,
    val animatedBgColorState: State<Color>,
    val focusTransition: Transition<Int>,
    val homeFocusPosition: State<Float>,
    val requestNavBarFocus: () -> Unit,
    val currentOnPlayMovie: (Movie) -> Unit,
    val currentOnMovieClick: (Movie) -> Unit,
    val currentOnCategoryClick: (String) -> Unit,
    val onNavigateToProfiles: () -> Unit,
    val continueWatchingProgressMap: Map<String, Float>,
    val continueWatchingRemainingMap: Map<String, Long>,
    val myListAddedAt: Map<String, Long>,
    val kidsFeaturedTitles: List<Movie>
) {
    var homeArtworkReady by homeArtworkReadyState
    var homeRowsReady by homeRowsReadyState
    var navBarTab by navBarTabState
    var contentTab by contentTabState
    var pendingTabFocus by pendingTabFocusState
    var enteringKidProfile by enteringKidProfileState
    var lockedProfile by lockedProfileState
    var selectedCategoryAccent by selectedCategoryAccentState
    var currentFocusLevel by currentFocusLevelState
    val isKidProfile: Boolean get() = selectedProfile?.isKid == true
    val isKidHome: Boolean get() = isKidProfile && contentTab == "Home"
    val isBillboardFocused: Boolean get() = currentFocusLevelState.intValue < 0
}

@Composable
private fun HomeScene(scope: HomeRenderScope): Unit = with(scope) {
    val isSearchTab = contentTab == "Search"
    val verticalKeyPacer = remember(selectedProfile?.id) { TvKeyPacer() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .tvAmbientBackground(animatedBgColorState)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val isVertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                // One gate across the header, hero, categories and rows. Accepted
                // events continue to the focused child; fresh taps always pass.
                event.type == KeyEventType.KeyDown && isVertical &&
                    !verticalKeyPacer.accept(code, repeatCount = event.nativeKeyEvent.repeatCount)
            }
    ) {
        if (isKidHome) HomeKidsBackdrop(scope)

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            HomeNavigationBar(scope, isSearchTab)

            // Small padding between TopNavBar and Billboard
            // Audit §5a: 5dp read as a seam on dark-mood tabs; 10dp feels like a margin.
            Spacer(modifier = Modifier.height(10.dp))

            // 2. Short tab reveal with a single active page
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val offsetDp = if (tabSlideTabState.value == contentTab) tabSlide.value else tabSlideStart
                        translationX = offsetDp.dp.toPx()
                    }
            ) {
                // Only one catalogue tree is alive during a tab change. The outer
                // layer provides the short reveal without decoding two sets of heroes.
                key(selectedProfile?.id, contentTab) {
                    val activeTab = contentTab
                    if (activeTab == "Search") {
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
                            withFrameNanos { }
                            homeArtworkReady = true
                            homeRowsReady = true
                        }
                        SearchSection(
                            viewModel = viewModel,
                            allMovies = catalogBuckets.all,
                            onPlayMovie = currentOnPlayMovie,
                            onMovieClick = currentOnMovieClick,
                            firstFocusRequester = searchFocusRequester,
                            navBarFocusRequester = searchIconFocusRequester,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    } else {
                        HomeBrowseTab(scope, activeTab)
                    }
                    }
                }
            }
        }

        // Cinematic Kids Profile Entrance Transition Overlay on profile switch
        HomeKidsProfileTransition(scope)
        HomeLockedProfilePrompt(scope)
}

@Composable
private fun HomeKidsBackdrop(scope: HomeRenderScope): Unit = with(scope) {
    // Fullscreen backdrop/poster for Kids Home with header directly above it
    val kidPosterUrl = currentHeroMovie.backdropUrl.ifBlank { currentHeroMovie.posterUrl }
    val isKidPosterVisible by remember { derivedStateOf { currentFocusLevelState.intValue < 0 } }
    val kidPosterAlphaState = remember(homeFocusPosition) {
        derivedStateOf { homeBillboardAlpha(homeFocusPosition.value) }
    }

    val keepKidBackdrop by remember(kidPosterAlphaState) {
        derivedStateOf { isKidPosterVisible || kidPosterAlphaState.value > 0f }
    }
    if (keepKidBackdrop) Box(
        modifier = Modifier
            .fillMaxSize()
            // perf: read the alpha State inside the graphicsLayer lambda so the
            // Box composition isn't invalidated on every animation tick.
            .graphicsLayer { alpha = kidPosterAlphaState.value }
    ) {
        val display = context.resources.displayMetrics
        val backdropSize = TvImagePolicy.backdropSize(display.widthPixels, display.heightPixels, isLowMemoryDevice)
        val artworkKind = if (currentHeroMovie.backdropUrl.isNotBlank()) TvArtworkKind.BACKDROP else TvArtworkKind.POSTER
        val request = remember(context, kidPosterUrl, backdropSize, artworkKind) {
            ImageRequest.Builder(context)
                .data(TvImagePolicy.artworkUrl(kidPosterUrl, backdropSize.first, artworkKind))
                .size(backdropSize.first, backdropSize.second)
                .precision(coil.size.Precision.EXACT)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                .allowRgb565(true)
                .crossfade(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
        ReadyArtwork(
            request = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            onReady = { if (currentHeroMovie.id != "0") homeArtworkReady = true }
        )

        // Top soft vignette so TopNavBar text remains clear
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(KidsVignetteGradientBrush)
        )

        // Bottom feather gradient until first row which transitions seamlessly into NetflixBlack
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KidsFeatherGradientBrush)
        )
    }
}

@Composable
private fun HomeNavigationBar(scope: HomeRenderScope, isSearchTab: Boolean): Unit = with(scope) {
    // 1. Fixed top navigation, with a background transition for Search
    val headerBgColorState = animateColorAsState(
        targetValue = if (isSearchTab || navBarTab == "Search") Color.Black else Color.Transparent,
        animationSpec = tween(TvMotion.duration(250), easing = FastOutSlowInEasing),
        label = "topNavHeaderBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .drawBehind { drawRect(headerBgColorState.value) }
    ) {
        TopNavBar(
            selectedTab = navBarTab,
            onTabSelected = {
                pendingTabFocus = null
                viewModel.onHomeInteraction()
                navBarTab = it
            },
            onTabActivated = {
                viewModel.onHomeInteraction()
                currentFocusLevel = -2
                navBarTab = it
                contentTab = it
                pendingTabFocus = it
            },
            profileFocusRequester = profileFocusRequester,
            searchIconFocusRequester = searchIconFocusRequester,
            homeTabFocusRequester = homeTabFocusRequester,
            seriesTabFocusRequester = seriesTabFocusRequester,
            filmsTabFocusRequester = filmsTabFocusRequester,
            myNetflixTabFocusRequester = myNetflixTabFocusRequester,
            selectedProfile = selectedProfile,
            profiles = profiles,
            onProfileSelected = { profile ->
                if (!profile.pin.isNullOrBlank() && selectedProfile?.id != profile.id) {
                    viewModel.stopHomePreviews()
                    lockedProfile = profile
                } else if (profile.isKid && selectedProfile?.id != profile.id) {
                    enteringKidProfile = profile
                } else {
                    viewModel.selectProfile(profile)
                }
            },
            onManageProfiles = onNavigateToProfiles,
            compactProfile = true,
            onFocused = {
                pendingTabFocus = null
                viewModel.onHomeInteraction()
                currentFocusLevel = -2
            },
            onDpadDown = {
                viewModel.onHomeInteraction()
                contentTab = navBarTab
                pendingTabFocus = navBarTab
                true
            },
            onDpadUp = {
                requestNavBarFocus()
                true
            }
        )
    }
}

@Composable
private fun HomeBrowseTab(scope: HomeRenderScope, activeTab: String): Unit = with(scope) {
    val activeTabMovie: Movie = tabFocusedMovies[activeTab] ?: getDefaultMovieForTab(activeTab)

    // Keep the current rows attached during a same-profile refresh.
    // Replacing them with a skeleton would discard focus and saved cards.
    var activeDisplayedCategoryRows by remember(activeTab, selectedProfile?.id, isKidProfile) {
        mutableStateOf(tabRowsCache[activeTab].orEmpty())
    }
    val firstRowPrepared = remember(activeTab, selectedProfile?.id) { mutableStateOf(false) }

    LaunchedEffect(activeTab, selectedProfile?.id, activeDisplayedCategoryRows.isNotEmpty()) {
        if (activeTab == "Home" && !isKidProfile && activeDisplayedCategoryRows.isNotEmpty()) {
            // Attach just the next D-pad target after the first Home frame is quiet.
            // Poster decodes then happen before, rather than during, the first scroll.
            viewModel.awaitHomeIdle()
            firstRowPrepared.value = true
        }
    }

    val preparedRows = tabRowsCache[activeTab]
    LaunchedEffect(activeTab, preparedRows) {
        val rows = preparedRows ?: return@LaunchedEffect
        if (rows == activeDisplayedCategoryRows) return@LaunchedEffect
        if (activeDisplayedCategoryRows.isNotEmpty()) viewModel.awaitBrowsingIdle()
        activeDisplayedCategoryRows = rows
    }

    LaunchedEffect(activeDisplayedCategoryRows.isNotEmpty()) {
        if (activeDisplayedCategoryRows.isNotEmpty()) {
            withFrameNanos { }
            withFrameNanos { }
            if (activeTab == contentTab) homeRowsReady = true
        }
    }

    val rowRequesterCache = remember(activeTab, selectedProfile?.id) {
        mutableMapOf<String, FocusRequester>()
    }
    val activeRowFocusRequesters = remember(activeDisplayedCategoryRows, rowRequesterCache, firstRowFocusRequester) {
        activeDisplayedCategoryRows.mapIndexed { index, row ->
            if (index == 0) firstRowFocusRequester
            else rowRequesterCache.getOrPut(row.first) { FocusRequester() }
        }
    }
    LaunchedEffect(currentFocusLevelState.intValue, activeRowFocusRequesters) {
        val requestedLevel = currentFocusLevelState.intValue
        val firstRowLevel = if (isKidProfile || activeTab != "Home") 0 else 1
        val requester = activeRowFocusRequesters.getOrNull(requestedLevel - firstRowLevel)
            ?: return@LaunchedEffect
        // The synchronous key handler handles attached rows immediately. Retry
        // after layout if the next row was still being composed, and never let
        // an older request steal focus after the user changes direction.
        repeat(2) {
            withFrameNanos { }
            if (currentFocusLevelState.intValue != requestedLevel) return@LaunchedEffect
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LaunchedEffect(activeDisplayedCategoryRows.size, activeTab) {
        if (activeDisplayedCategoryRows.isNotEmpty()) {
            val lastLevel = activeDisplayedCategoryRows.lastIndex +
                if (!isKidProfile && activeTab == "Home") 1 else 0
            if (currentFocusLevel > lastLevel) {
                currentFocusLevel = lastLevel
                withFrameNanos { }
                runCatching { activeRowFocusRequesters.lastOrNull()?.requestFocus() }
            }
        }
    }

    val rowHeight = homeLayout.rowHeight
    val selectionFrameTops = remember(activeTab, selectedProfile?.id) { mutableStateMapOf<String, Int>() }
    // Keep the hero's share of the TV viewport consistent. A fixed
    // 420dp hero looks shallow on TVs that expose a taller dp window.
    // Its minimum still leaves room for the copy and action buttons.
    // Scroll targets below use this same value to preserve focus alignment.
    val billboardHeight = homeLayout.billboardHeight
    val kidsHeroHeight = homeLayout.kidsHeroHeight
    val kidsRowSpacing = 24.dp
    val categoriesHeight = homeLayout.categoriesHeight
    val billboardSpacing = if (activeTab == "Home") 10.dp else 16.dp

    // ── Pixel-precise vertical scroll target (matches horizontal carousel's density math) ──
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowHeightPx = remember(density, rowHeight) { with(density) { rowHeight.toPx() } }
    val billboardHeightPx = remember(density, billboardHeight) { with(density) { billboardHeight.toPx() } }
    val kidsHeroHeightPx = remember(density, kidsHeroHeight) { with(density) { kidsHeroHeight.toPx() } }
    val kidsRowSpacingPx = remember(density) { with(density) { kidsRowSpacing.toPx() } }
    val categoriesHeightPx = remember(density, categoriesHeight) { with(density) { categoriesHeight.toPx() } }
    val billboardSpacingPx = remember(density) { with(density) { billboardSpacing.toPx() } }

    val calculateTargetScrollY = remember(
        isKidProfile, activeTab,
        kidsHeroHeightPx, kidsRowSpacingPx, rowHeightPx,
        billboardHeightPx, billboardSpacingPx, categoriesHeightPx
    ) {
        { focusLevel: Int ->
            if (isKidProfile) {
                if (activeTab == "Home") {
                    when {
                        focusLevel < 0 -> 0f
                        else -> -(kidsHeroHeightPx + kidsRowSpacingPx) - (rowHeightPx * focusLevel)
                    }
                } else {
                    when {
                        focusLevel <= 0 -> 0f
                        else -> -(rowHeightPx * focusLevel)
                    }
                }
            } else {
                if (activeTab == "Home") {
                    when {
                        focusLevel < 0 -> 0f
                        focusLevel == 0 -> -(billboardHeightPx + billboardSpacingPx)
                        else -> -(billboardHeightPx + billboardSpacingPx + categoriesHeightPx) - (rowHeightPx * (focusLevel - 1))
                    }
                } else {
                    when {
                        focusLevel < 0 -> 0f
                        else -> -(billboardHeightPx + billboardSpacingPx) - (rowHeightPx * focusLevel)
                    }
                }
            }
        }
    }

    // Animate physical distance, not the focus index. Header and billboard
    // share a zero offset: interpolating their indices created a dead interval
    // on rapid Down presses. Unequal section heights also changed speed at
    // each index boundary. A pixel spring moves immediately and keeps velocity.
    val scrollOffsetState = focusTransition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(430f), visibilityThreshold = 0.5f)
        },
        label = "homeScrollOffsetPx"
    ) { calculateTargetScrollY(it) }
    val coroutineScope = rememberCoroutineScope()
    val manualTouchOffsetAnim = remember(activeTab) { Animatable(0f) }
    val dragOffsetState = remember(activeTab) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val scrollOffsetProvider = remember(scrollOffsetState, manualTouchOffsetAnim, dragOffsetState) {
        { scrollOffsetState.value + manualTouchOffsetAnim.value + dragOffsetState.floatValue }
    }
    val animFocusLevelProvider = remember(homeFocusPosition) { { homeFocusPosition.value } }
    val ambientColorProvider = remember(animatedBgColorState) { { animatedBgColorState.value } }
    var isDragging by remember(activeTab) { mutableStateOf(false) }

    LaunchedEffect(focusTransition, manualTouchOffsetAnim) {
        snapshotFlow { isDragging || focusTransition.isRunning || manualTouchOffsetAnim.isRunning }
            .collect { viewModel.setHomeScrollInProgress(it) }
    }
    DisposableEffect(activeTab) {
        onDispose { viewModel.setHomeScrollInProgress(false) }
    }

    // perf: only the (isKidProfile, activeTab) tuple should restart the
    // gesture detectors; row count is read via state inside the lambdas
    // so a cache fill doesn't tear down & rebuild the pointer pipelines.
    val maxLevelProvider = remember(activeDisplayedCategoryRows.size, isKidProfile, activeTab) {
        derivedStateOf {
            val sz = activeDisplayedCategoryRows.size
            if (isKidProfile || activeTab != "Home") maxOf(0, sz - 1) else sz
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(isKidProfile, activeTab, maxLevelProvider) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        coroutineScope.launch {
                            manualTouchOffsetAnim.stop()
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        coroutineScope.launch {
                            val currentOffset = manualTouchOffsetAnim.value + dragOffsetState.floatValue
                            manualTouchOffsetAnim.snapTo(currentOffset)
                            dragOffsetState.floatValue = 0f
                            val rowHeightPxValue = if (rowHeightPx > 0f) rowHeightPx else 400f
                            val levelShift = (-currentOffset / rowHeightPxValue).roundToInt()
                            val newLevel = (currentFocusLevelState.intValue + levelShift).coerceIn(-2, maxLevelProvider.value)
                            currentFocusLevelState.intValue = newLevel
                            manualTouchOffsetAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        coroutineScope.launch {
                            manualTouchOffsetAnim.snapTo(manualTouchOffsetAnim.value + dragOffsetState.floatValue)
                            dragOffsetState.floatValue = 0f
                            manualTouchOffsetAnim.animateTo(0f, tween(TvMotion.duration(300)))
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetState.floatValue += dragAmount
                    }
                )
            }
            .pointerInput(isKidProfile, activeTab, maxLevelProvider) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (scrollDelta > 0f) {
                                if (currentFocusLevelState.intValue < maxLevelProvider.value) {
                                    currentFocusLevelState.intValue++
                                }
                            } else if (scrollDelta < 0f) {
                                if (currentFocusLevelState.intValue > -2) {
                                    currentFocusLevelState.intValue--
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val firstRowTopPx = when {
            isKidProfile && activeTab == "Home" -> kidsHeroHeightPx + kidsRowSpacingPx
            isKidProfile -> with(density) { 16.dp.toPx() }
            activeTab == "Home" -> billboardHeightPx + billboardSpacingPx + categoriesHeightPx
            else -> billboardHeightPx + billboardSpacingPx
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true, align = Alignment.Top)
                .graphicsLayer { translationY = scrollOffsetState.value + manualTouchOffsetAnim.value + dragOffsetState.floatValue }
                .padding(
                    top = if (isKidProfile && activeTab != "Home") 16.dp else 0.dp,
                    bottom = 120.dp
                )
        ) {
            if (isKidProfile) {
                if (activeTab == "Home") {
                    // Kids Hero Character Stage Carousel (Home tab only)
                    KidsHeroSection(
                        featuredMovies = kidsFeaturedTitles.ifEmpty { curatedBillboardCandidates[activeTab].orEmpty().ifEmpty { catalogBuckets.all } },
                        activeMovie = activeTabMovie,
                        height = kidsHeroHeight,
                        focusRequester = billboardFocusRequester,
                        viewModel = viewModel,
                        onFocused = { currentFocusLevel = -1 },
                        onMovieSelected = { movie ->
                            tabFocusedMovies[activeTab] = movie
                            viewModel.updateCurrentMovie(movie)
                        },
                        onPlayMovie = { movie ->
                            viewModel.cacheMovie(movie)
                            currentOnPlayMovie(movie)
                        },
                        onMovieClick = { movie ->
                            viewModel.cacheMovie(movie)
                            currentOnMovieClick(movie)
                        },
                        onDpadUp = {
                            requestNavBarFocus()
                            true
                        },
                        onDpadDown = {
                            if (activeDisplayedCategoryRows.isNotEmpty()) {
                                currentFocusLevel = 0
                                try { activeRowFocusRequesters.getOrNull(0)?.requestFocus() } catch (_: Exception) {}
                                true
                            } else false
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
                // On other tabs ("Series", "Films", "My Netflix"): NO billboard, NO categories bar, start directly with rows!
            } else {
                // 1. Featured billboard follows the same vertical motion as the rows
                val tabFeaturedList = curatedBillboardCandidates[activeTab] ?: emptyList()
                BillboardSection(
                    movie = activeTabMovie,
                    featuredMovies = tabFeaturedList,
                    height = billboardHeight,
                    alpha = { homeBillboardAlpha(homeFocusPosition.value) },
                    ambientColorProvider = ambientColorProvider,
                    playFocusRequester = billboardFocusRequester,
                    moreInfoFocusRequester = moreInfoFocusRequester,
                    isBillboardFocused = isBillboardFocused && activeTab == contentTab,
                    onArtworkReady = {
                        if (activeTab == contentTab && activeTabMovie.id != "0") homeArtworkReady = true
                    },
                    onPlayClick = {
                        viewModel.cacheMovie(activeTabMovie)
                        currentOnPlayMovie(activeTabMovie)
                    },
                    onInfoClick = {
                        viewModel.cacheMovie(activeTabMovie)
                        currentOnMovieClick(activeTabMovie)
                    },
                    onMovieSelected = { movie ->
                        tabFocusedMovies[activeTab] = movie
                        viewModel.updateCurrentMovie(movie)
                    },
                    viewModel = viewModel,
                    waitForHomeReadyBeforeLogo = true,
                    onFocused = {
                        currentFocusLevel = -1
                    },
                    modifier = Modifier
                        .padding(horizontal = 1.5.dp)
                        .handleTvDpadNavigation(
                            onDpadDown = {
                                if (activeTab == "Home") {
                                    firstRowPrepared.value = true
                                    currentFocusLevel = 0
                                    try { categoriesFocusRequester.requestFocus() } catch (_: Exception) {}
                                } else {
                                    if (activeDisplayedCategoryRows.isNotEmpty()) {
                                        currentFocusLevel = 0
                                        try { activeRowFocusRequesters.getOrNull(0)?.requestFocus() } catch (_: Exception) {}
                                    }
                                }
                                true
                            },
                            onDpadUp = {
                                requestNavBarFocus()
                                true
                            }
                        )
                )

                if (activeTab == "Home") {
                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Categories share the viewport motion (Home tab only)
                    CategoriesBarSection(
                        categoriesFocusRequester = categoriesFocusRequester,
                        height = categoriesHeight,
                        alpha = { homeCategoriesAlpha(homeFocusPosition.value) },
                        moodColor = extractedMoodColor,
                        onCategorySelected = { selectedCategoryAccent = getCategoryThemeColor(it) },
                        onCategoryClick = currentOnCategoryClick,
                        onFocused = {
                            firstRowPrepared.value = true
                            currentFocusLevel = 0
                        },
                        onDpadDown = {
                            if (activeDisplayedCategoryRows.isNotEmpty()) {
                                currentFocusLevel = 1
                                try { activeRowFocusRequesters.getOrNull(0)?.requestFocus() } catch (_: Exception) {}
                                true
                            } else false
                        },
                        onDpadUp = {
                            currentFocusLevel = -1
                            try { billboardFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        },
                        verticalRingOffsetProvider = {
                            calculateTargetScrollY(0) - scrollOffsetState.value
                        }
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 3. Content Movie Rows with Smooth Carousel Synchronized Glide Choreography
            // perf: Drop Crossfade during initial load to prevent laying out 23 rows (3 skeleton + 20 actual)
            // simultaneously. This skips massive layout thrash and GC pauses.
            if (activeDisplayedCategoryRows.isEmpty()) {
                val placeholderRows = kotlin.math.ceil(
                    (if (currentFocusLevel < 0) viewportHeightPx - firstRowTopPx else viewportHeightPx) / rowHeightPx
                ).toInt().coerceIn(1, 3)
                HomeScreenSkeleton(
                    visibleRowCount = placeholderRows,
                    showShimmer = false
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    activeDisplayedCategoryRows.forEachIndexed { index, (title, movies) ->
                            key(selectedProfile?.id, activeTab, title) {
                                HomeRowWrapper(
                                    index = index,
                                    firstRowTopPx = firstRowTopPx,
                                    rowHeightPx = rowHeightPx,
                                    viewportHeightPx = viewportHeightPx,
                                    scrollOffsetProvider = scrollOffsetProvider,
                                    title = title,
                                    movies = movies,
                                    activeTab = activeTab,
                                    isKidProfile = isKidProfile,
                                    currentFocusLevelState = currentFocusLevelState,
                                    firstRowPreparedState = firstRowPrepared,
                                    onUpdateFocusLevel = { currentFocusLevelState.intValue = it },
                                    activeRowFocusRequesters = activeRowFocusRequesters,
                                    activeDisplayedCategoryRowsSize = activeDisplayedCategoryRows.size,
                                    continueWatchingProgressMap = continueWatchingProgressMap,
                                    continueWatchingRemainingMap = continueWatchingRemainingMap,
                                    remindedMovieIds = remindedMovieIds,
                                    billboardFocusRequester = billboardFocusRequester,
                                    categoriesFocusRequester = categoriesFocusRequester,
                                    requestNavBarFocus = { requestNavBarFocus() },
                                    currentOnMovieClick = currentOnMovieClick,
                                    viewModel = viewModel,
                                    animFocusLevelProvider = animFocusLevelProvider,
                                    onSelectionFrameTopMeasured = { topPx ->
                                        if (selectionFrameTops[title] != topPx) selectionFrameTops[title] = topPx
                                    }
                                )
                            }
                        }
                    }
                }
            }
        // This frame belongs to the viewport, not a row's clipped
        // layer. Retargeting several rows cannot clip or fade it out.
        Box(
            Modifier
                .offset(x = 16.dp)
                .width(homeLayout.expandedWidth)
                .height(homeLayout.cardHeight)
                .graphicsLayer {
                    val firstRowLevel = if (isKidProfile || activeTab != "Home") 0 else 1
                    val rowIndex = currentFocusLevelState.intValue - firstRowLevel
                    val rowTitle = activeDisplayedCategoryRows.getOrNull(rowIndex)?.first
                    alpha = if (rowTitle != null && activeTab == navBarTab) 1f else 0f
                    val topPx = rowTitle?.let { selectionFrameTops[it] }?.toFloat() ?: 48.dp.toPx()
                    translationY = topPx + manualTouchOffsetAnim.value + dragOffsetState.floatValue +
                        (if (isKidProfile && activeTab != "Home") 16.dp.toPx() else 0f)
                }
                .border(2.5.dp, Color.White, RoundedCornerShape(8.dp))
                .zIndex(20f)
        )
        }
}

@Composable
private fun HomeLockedProfilePrompt(scope: HomeRenderScope): Unit = with(scope) {
    var promptWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(lockedProfile) {
        if (lockedProfile != null) promptWasOpen = true
        else if (promptWasOpen) {
            withFrameNanos { }
            runCatching { profileFocusRequester.requestFocus() }
            promptWasOpen = false
        }
    }
    lockedProfile?.let { profile ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { lockedProfile = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ProfilePinUnlockScreen(
                profile = profile,
                onBack = { lockedProfile = null },
                onVerifyPin = { entered -> viewModel.verifyPin(profile.id, entered) },
                onUnlockSuccess = {
                    lockedProfile = null
                    if (profile.isKid) enteringKidProfile = profile else viewModel.selectProfile(profile)
                }
            )
        }
    }
}

@Composable
private fun HomeKidsProfileTransition(scope: HomeRenderScope): Unit = with(scope) {
    enteringKidProfile?.let { kid ->
                var isCentered by remember { mutableStateOf(false) }

                LaunchedEffect(kid) {
                    isCentered = true
                    // Audit §5b: 1400ms read as a hang; 900ms reads as a transition.
                    kotlinx.coroutines.delay(900)
                    viewModel.selectProfile(kid)
                    enteringKidProfile = null
                }

                val avatarScale by animateFloatAsState(
                    targetValue = if (isCentered) 1.20f else 0.92f,
                animationSpec = tween(TvMotion.duration(450), easing = FastOutSlowInEasing),
                    label = "homeKidAvatarScale"
                )
                val avatarOffsetY by animateDpAsState(
                    targetValue = if (isCentered) 0.dp else 40.dp,
                animationSpec = tween(TvMotion.duration(450), easing = FastOutSlowInEasing),
                    label = "homeKidAvatarY"
                )
                val overlayAlpha by animateFloatAsState(
                    targetValue = if (isCentered) 1f else 0f,
                animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing),
                    label = "homeKidOverlayAlpha"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Audit §5b: 0.88f alpha lets the destination Kids hero bleed through,
                        // turning a freeze into a cross-fade the kid can read as progress.
                        .background(NetflixBlack.copy(alpha = 0.88f))
                        .graphicsLayer { alpha = overlayAlpha }
                        .zIndex(700f),
                    contentAlignment = Alignment.Center
                ) {
                    // Warm Amber Radial Ambient Glow
                    val glowBrush = remember {
                        Brush.radialGradient(
                            colors = listOf(
                                KidsAccent.copy(alpha = 0.55f),
                                KidsAccent.copy(alpha = 0.20f),
                                Color.Transparent
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(420.dp)
                            .drawBehind {
                                drawCircle(brush = glowBrush, radius = size.minDimension / 1.5f)
                            }
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.graphicsLayer {
                            translationY = avatarOffsetY.toPx()
                            scaleX = avatarScale
                            scaleY = avatarScale
                        }
                    ) {
                        // Kids Avatar Card
                        Box(
                            modifier = Modifier
                                .size(145.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(kid.avatarColor)
                                .border(3.dp, KidsAccent, RoundedCornerShape(14.dp))
                                .shadow(20.dp, RoundedCornerShape(14.dp))
                        ) {
                            if (!kid.avatarUrl.isNullOrBlank()) {
                                val context = LocalContext.current
                                // perf: DisposableEffect cancels the in-flight Coil request when
                                // the avatar URL changes or the overlay is dismissed. Without
                                // this, AsyncImage keeps decoding for an ImageRequest that's
                                // no longer rendered.
                                val avatarRequest = remember(kid.avatarUrl) {
                                    ImageRequest.Builder(context)
                                        .data(kid.avatarUrl)
                                        .crossfade(false)
                                        .build()
                                }
                                AsyncImage(
                                    model = avatarRequest,
                                    contentDescription = "Kids Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = kid.name.take(1).uppercase(),
                                        fontSize = 54.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            // Gold KIDS badge across bottom of avatar
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(KidsAccent)
                                .padding(vertical = 3.5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "KIDS",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        // Profile Name
                        Text(
                            text = kid.name,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Audit §5b: replace the pinwheel spinner with a narratable
                        // progress bar + label so the wait reads as a transition.
                        Text(
                            text = "Loading Kids Profile…",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .width(220.dp)
                                .padding(top = 4.dp),
                            color = KidsAccent
                        )
                    }
                }
            }
}

// ── Continuous row alpha matching the discrete step table with smooth interpolation ──
// Piecewise linear: at integer focus levels the values match the original table exactly,
// between integers the alpha glides smoothly — no per-row animateFloatAsState needed.
private fun computeRowAlpha(rowFocusLevel: Int, animFocus: Float): Float {
    val dist = rowFocusLevel.toFloat() - animFocus
    return when {
        dist <= -1f -> 0f
        dist <= 0f  -> dist + 1f                       // -1→0 : 0.0→1.0
        dist <= 1f  -> 1f   - 0.15f * dist             //  0→1 : 1.0 →0.85
        dist <= 2f  -> 0.85f - 0.20f * (dist - 1f)    //  1→2 : 0.85→0.65
        dist <= 3f  -> 0.65f - 0.20f * (dist - 2f)    //  2→3 : 0.65→0.45
        dist <= 4f  -> 0.45f - 0.20f * (dist - 3f)    //  3→4 : 0.45→0.25
        else        -> 0.25f
    }
}

@Composable
private fun HomeRowWrapper(
    index: Int,
    firstRowTopPx: Float,
    rowHeightPx: Float,
    viewportHeightPx: Float,
    scrollOffsetProvider: () -> Float,
    title: String,
    movies: List<Movie>,
    activeTab: String,
    isKidProfile: Boolean,
    currentFocusLevelState: State<Int>,
    firstRowPreparedState: State<Boolean>,
    onUpdateFocusLevel: (Int) -> Unit,
    activeRowFocusRequesters: List<FocusRequester>,
    activeDisplayedCategoryRowsSize: Int,
    continueWatchingProgressMap: Map<String, Float>?,
    continueWatchingRemainingMap: Map<String, Long>?,
    remindedMovieIds: Set<String>,
    billboardFocusRequester: FocusRequester,
    categoriesFocusRequester: FocusRequester,
    requestNavBarFocus: () -> Unit,
    currentOnMovieClick: (Movie) -> Unit,
    viewModel: NetflixViewModel,
    animFocusLevelProvider: () -> Float = { currentFocusLevelState.value.toFloat() },
    onSelectionFrameTopMeasured: (Int) -> Unit = {}
) {
    val homeLayout = rememberHomeLayoutMetrics()
    val rowFocusLevel = if (isKidProfile || activeTab != "Home") index else index + 1

    // Read continuous scroll state only through a boolean derived state: composition
    // changes when a row enters/leaves the viewport, never on every animation frame.
    val isRowVisible by remember(
        index, firstRowTopPx, rowHeightPx, viewportHeightPx, rowFocusLevel,
        scrollOffsetProvider, firstRowPreparedState, currentFocusLevelState, isKidProfile, activeTab
    ) {
        derivedStateOf {
            val focusLevel = currentFocusLevelState.value
            // Once Home has settled, keep the first D-pad target attached while
            // moving among the billboard, categories, and first row.
            val keepFirstRow = index == 0 && firstRowPreparedState.value &&
                focusLevel in -1..1 && !isKidProfile && activeTab == "Home"
            keepFirstRow || shouldComposeHomeRow(
                index, firstRowTopPx, rowHeightPx, scrollOffsetProvider(), viewportHeightPx,
                focusLevel - (rowFocusLevel - index),
                // Adjacent focused rows are already retained by shouldComposeHomeRow.
                // Extra viewport overscan mounted several poster rows on these boundaries.
                overscanRows = if (!isKidProfile && activeTab == "Home") 0
                    else if (focusLevel < 0) 0 else 1
            )
        }
    }
    val rowStateHolder = rememberSaveableStateHolder()
    if (!isRowVisible) {
        Spacer(Modifier.fillMaxWidth().height(homeLayout.rowHeight))
        return
    }

    val isMovieLockedLambda = remember(viewModel) { { movie: Movie -> viewModel.isMovieLocked(movie) } }
    val onToggleReminderLambda = remember(viewModel) { { movie: Movie -> viewModel.toggleReminder(movie.id) } }

    rowStateHolder.SaveableStateProvider("carousel") {
        NetflixMovieRow(
            title = title,
            movies = movies,
            isPortrait = true,
            rowFocusRequester = activeRowFocusRequesters.getOrNull(index),
            isMovieLocked = isMovieLockedLambda,
            height = homeLayout.rowHeight,
            alpha = { computeRowAlpha(rowFocusLevel, animFocusLevelProvider()) },
            isVisible = isRowVisible,
            continueWatchingProgressMap = continueWatchingProgressMap,
            continueWatchingRemainingMap = continueWatchingRemainingMap,
            isKids = isKidProfile,
            remindedMovieIds = remindedMovieIds,
            onToggleReminder = onToggleReminderLambda,
            onDpadDown = {
                if (index < activeDisplayedCategoryRowsSize - 1) {
                    val nextFocusLevel = if (isKidProfile || activeTab != "Home") index + 1 else index + 2
                    onUpdateFocusLevel(nextFocusLevel)
                    try { activeRowFocusRequesters.getOrNull(index + 1)?.requestFocus() } catch (_: Exception) {}
                }
                true
            },
            onDpadUp = {
                if (index > 0) {
                    val prevFocusLevel = if (isKidProfile || activeTab != "Home") index - 1 else index
                    onUpdateFocusLevel(prevFocusLevel)
                    try { activeRowFocusRequesters.getOrNull(index - 1)?.requestFocus() } catch (_: Exception) {}
                } else {
                    if (isKidProfile) {
                        if (activeTab == "Home") {
                            onUpdateFocusLevel(-1)
                            try { billboardFocusRequester.requestFocus() } catch (_: Exception) {}
                        } else {
                            requestNavBarFocus()
                        }
                    } else if (activeTab == "Home") {
                        onUpdateFocusLevel(0)
                        try { categoriesFocusRequester.requestFocus() } catch (_: Exception) {}
                    } else {
                        onUpdateFocusLevel(-1)
                        try { billboardFocusRequester.requestFocus() } catch (_: Exception) {}
                    }
                }
                true
            },
            onMovieFocused = { _ ->
                onUpdateFocusLevel(if (isKidProfile || activeTab != "Home") index else index + 1)
            },
            onRowFocused = {
                onUpdateFocusLevel(if (isKidProfile || activeTab != "Home") index else index + 1)
            },
            onMovieClick = { movie ->
                viewModel.cacheMovie(movie)
                currentOnMovieClick(movie)
            },
            viewModel = viewModel,
            showSelectionRing = false,
            onSelectionFrameTopMeasured = onSelectionFrameTopMeasured
        )
    }
}
