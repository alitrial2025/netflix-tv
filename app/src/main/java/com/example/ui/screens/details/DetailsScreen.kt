@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.screens.details

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.Caption
import com.example.model.Episode
import com.example.model.Movie
import com.example.model.isKidSafeMovie
import com.example.model.isSeriesContent
import com.example.model.catalogMediaKind
import com.example.model.playbackMediaId
import com.example.ui.NetflixViewModel
import com.example.ui.theme.NetflixBlack
import com.example.ui.util.TvMotion
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private class DetailsPreviewCookie {
    var value: String? = null
}

@Composable
fun DetailsScreen(
    movie: Movie,
    onBack: () -> Unit,
    onPlayMovie: (Movie, Int, Int, String) -> Unit,
    onPlayTrailer: (Movie, Int, Int, String) -> Unit,
    onNavigateToDetails: (Movie) -> Unit,
    viewModel: NetflixViewModel
) {
    val movieKey = movie.playbackMediaId(1, 1)
    DisposableEffect(viewModel) {
        viewModel.stopHomePreviews()
        onDispose { }
    }
    val categoryRows by viewModel.categoryRows.collectAsStateWithLifecycle()
    val continueWatchingList by viewModel.continueWatchingList.collectAsStateWithLifecycle()
    val myListMovieIds by viewModel.myListMovieIds.collectAsStateWithLifecycle()
    val likedMovieIds by viewModel.likedMovieIds.collectAsStateWithLifecycle()
    val userSubscription by viewModel.userSubscription.collectAsStateWithLifecycle()

    val isMyListAdded = remember(movie.id, myListMovieIds) { myListMovieIds.contains(movie.id) }
    val isLiked = remember(movie.id, likedMovieIds) { likedMovieIds.contains(movie.id) }

    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val isKidProfile = selectedProfile?.isKid == true
    val isKidContent = remember(movie.id, isKidProfile) { isKidProfile || isKidSafeMovie(movie) }

    val isTvSeries = remember(movie.id, movie.type, movie.duration) { movie.isSeriesContent() }

    val recommendations = remember(movie.id, categoryRows, isKidContent, isTvSeries) {
        val all = categoryRows.flatMap { it.second }.distinctBy { it.id }
        val pool = if (isKidContent) {
            all.filter { isKidSafeMovie(it) && it.id != movie.id }
        } else {
            all.filter { it.id != movie.id }
        }
        val sorted = if (isTvSeries) {
            pool.sortedByDescending { it.type.equals("Series", ignoreCase = true) || it.duration.contains("Season", ignoreCase = true) }
        } else {
            pool.sortedByDescending { !it.type.equals("Series", ignoreCase = true) && !it.duration.contains("Season", ignoreCase = true) }
        }
        sorted.take(12)
    }

    val context = LocalContext.current
    var detailsBillboardMeta by remember(movie.id) {
        mutableStateOf(
            viewModel.getCachedBillboardMeta(movie.id)
                ?: com.example.data.TmdbRepository.getCachedBillboardMeta(movie.id)
        )
    }
    val displayMovie = remember(movie, detailsBillboardMeta) {
        com.example.ui.components.BillboardCalloutGenerator.enrichMovieWithMeta(movie, detailsBillboardMeta)
    }
    val extraInfo = remember(displayMovie, detailsBillboardMeta) {
        getMovieExtraInfo(displayMovie, detailsBillboardMeta)
    }
    val calloutBadges = remember(
        displayMovie,
        detailsBillboardMeta,
        selectedProfile,
        likedMovieIds,
        myListMovieIds
    ) {
        com.example.ui.components.BillboardCalloutGenerator.resolveBadges(
            movie = displayMovie,
            meta = detailsBillboardMeta,
            favoriteGenres = selectedProfile?.favoriteGenres.orEmpty(),
            likedMovies = viewModel.getLikedMoviesSnapshot(),
            watchedMovies = viewModel.watchHistoryMovies.value,
            inMyList = isMyListAdded,
            rotationSlot = 0
        )
    }

    var activeTabName by remember(movie.id, isTvSeries) {
        mutableStateOf(if (isTvSeries) "Episodes" else "Details")
    }

    val initialCw = remember(movieKey) {
        continueWatchingList.find {
            it.movieId == movie.id && it.title.equals(movie.title, true) &&
                it.toMovie().catalogMediaKind() == movie.catalogMediaKind()
        }
    }
    var continueWatchingData by remember(movie.id) { mutableStateOf(initialCw) }
    var episodesList by remember(movie.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var currentSeason by remember(movie.id) {
        mutableIntStateOf(if (isTvSeries && initialCw != null && initialCw.season > 0) initialCw.season else 1)
    }
    var currentEpisode by remember(movie.id) {
        mutableIntStateOf(if (isTvSeries && initialCw != null && initialCw.episode > 0) initialCw.episode else 1)
    }
    var availableSeasons by remember(movie.id) { mutableStateOf(listOf(1)) }
    var appliedResumeEpisode by remember(movieKey) {
        mutableStateOf(initialCw?.let { it.season to it.episode })
    }
    var episodesLoading by remember(movieKey) { mutableStateOf(isTvSeries) }
    var episodesRetry by remember(movieKey) { mutableIntStateOf(0) }

    LaunchedEffect(movie.id, isTvSeries) {
        if (isTvSeries) {
            val tvId = movie.id.toLongOrNull()
            if (tvId != null && tvId > 0L) {
                val seasons = withContext(Dispatchers.IO) { viewModel.getTvSeasons(tvId) }
                ensureActive()
                if (seasons.isNotEmpty()) {
                    availableSeasons = seasons
                }
            }
        }
    }

    val selectedSubLang by viewModel.selectedSubtitleLanguage.collectAsStateWithLifecycle()
    var streamCaptions by remember(movie.id) { mutableStateOf<List<Caption>>(emptyList()) }
    var showUpgradeModal by remember(movie.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var currentDetailsLogoUrl by remember(movie.id) {
        mutableStateOf(movie.logoUrl ?: viewModel.getCachedLogo(movie.id))
    }

    val entranceProgress = remember(movie.id) { Animatable(0f) }
    LaunchedEffect(movie.id) {
        entranceProgress.snapTo(0f)
        entranceProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = TvMotion.duration(950), easing = FastOutSlowInEasing)
        )
    }
    // Read the reveal only in child graphics layers, keeping the screen's content
    // and video view out of composition during the entrance.
    val entranceProgressProvider = remember(entranceProgress) { { entranceProgress.value } }

    LaunchedEffect(movie.id) {
        if (currentDetailsLogoUrl.isNullOrBlank()) {
            val isTv = movie.isSeriesContent()
            val fetched = withContext(Dispatchers.IO) { viewModel.fetchLogoUrl(movie.id, isTv) }
            if (!fetched.isNullOrBlank()) {
                currentDetailsLogoUrl = fetched
            }
        }
        if (detailsBillboardMeta?.isEnriched != true) {
            val enriched = withContext(Dispatchers.IO) { viewModel.fetchBillboardMeta(movie) }
            if (enriched != null) {
                detailsBillboardMeta = enriched
            }
        }
    }

    LaunchedEffect(movieKey, currentSeason, episodesRetry) {
        if (!isTvSeries) return@LaunchedEffect
        episodesLoading = true
        episodesList = emptyList()
        try {
            val tvId = movie.id.toLongOrNull()
            val fetched = if (tvId != null && tvId > 0L)
                withContext(Dispatchers.IO) { viewModel.getEpisodes(tvId, currentSeason) } else emptyList()
            ensureActive()
            episodesList = fetched.filter { it.seasonNumber == currentSeason && it.episodeNumber > 0 }
        } finally {
            if (isActive) episodesLoading = false
        }
    }

    var showModalScreen by remember(movie.id) { mutableStateOf(false) }
    var lastModalCloseTime by remember(movie.id) { mutableLongStateOf(0L) }
    var lastFocusedSourceRequester by remember(movie.id) { mutableStateOf<FocusRequester?>(null) }

    val playButtonRequester = remember { FocusRequester() }
    val myListButtonRequester = remember { FocusRequester() }
    val likeButtonRequester = remember { FocusRequester() }
    val removeButtonRequester = remember { FocusRequester() }
    val tabRowRequester = remember { FocusRequester() }
    val modalUpRequester = remember { FocusRequester() }

    val exoPlayer = viewModel.sharedExoPlayer
    val playbackOwner = remember(movieKey) { "details:${java.util.UUID.randomUUID()}" }
    val sharedPlaybackOwner by viewModel.sharedPlaybackOwner.collectAsStateWithLifecycle()
    val ownsPlayback = sharedPlaybackOwner == playbackOwner
    val lifecycleOwner = LocalLifecycleOwner.current
    var isDetailsResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var isNavigatingToPlayer by remember(movieKey) { mutableStateOf(false) }
    LaunchedEffect(movieKey, continueWatchingList, isDetailsResumed) {
        if (!isDetailsResumed) return@LaunchedEffect
        val cw = withContext(Dispatchers.IO) {
            (continueWatchingList.find {
                it.movieId == movie.id && it.title.equals(movie.title, true) &&
                    it.toMovie().catalogMediaKind() == movie.catalogMediaKind()
            } ?: viewModel.getContinueWatching(movie.id))?.takeIf {
                it.title.equals(movie.title, true) && it.toMovie().catalogMediaKind() == movie.catalogMediaKind()
            }
        }
        ensureActive()
        continueWatchingData = cw
        val resumeEpisode = cw?.let { it.season.coerceAtLeast(1) to it.episode.coerceAtLeast(1) }
        if (isTvSeries && resumeEpisode != null && resumeEpisode != appliedResumeEpisode) {
            appliedResumeEpisode = resumeEpisode
            currentSeason = resumeEpisode.first
            currentEpisode = resumeEpisode.second
        }
    }
    val previewIsTrailer = !viewModel.isUserLoggedIn() || viewModel.isMovieLocked(movie)
    val targetMediaId = movie.playbackMediaId(currentSeason, currentEpisode) + if (previewIsTrailer) ":trailer" else ""
    var isStreamReady by remember(targetMediaId) { mutableStateOf(false) }
    var previewRecovery by remember(targetMediaId) { mutableIntStateOf(0) }
    val previewCookieHolder = remember(targetMediaId) { DetailsPreviewCookie() }
    val latestTargetMediaId by rememberUpdatedState(targetMediaId)
    val resetLatestPreview by rememberUpdatedState({ isStreamReady = false })
    val renderedMediaId by viewModel.sharedVideoFrameMediaId.collectAsStateWithLifecycle()
    LaunchedEffect(renderedMediaId, ownsPlayback, targetMediaId) {
        if (ownsPlayback) isStreamReady = renderedMediaId == targetMediaId
    }

    LaunchedEffect(targetMediaId, isDetailsResumed, isNavigatingToPlayer, previewRecovery, ownsPlayback) {
        if (!isDetailsResumed || isNavigatingToPlayer || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        val cached = if (!previewIsTrailer) viewModel.getCachedStream(movie, currentSeason, currentEpisode) else null
        if (exoPlayer.currentMediaItem?.mediaId == targetMediaId &&
            (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING)) {
            if (cached != null) {
                previewCookieHolder.value = cached.headers["Cookie"]
                streamCaptions = cached.captions
            }
            try { exoPlayer.volume = 0.25f } catch (_: Exception) {}
            try { exoPlayer.playWhenReady = true } catch (_: Exception) {}
            try { exoPlayer.play() } catch (_: Exception) {}
            return@LaunchedEffect
        }
        // A warm return resumes immediately. Only a new source waits for
        // the entrance/focus to settle before network and decoder work.
        delay(TvMotion.duration(950).toLong())
        com.example.ui.util.HomeStartupGate.awaitBrowsingIdle()
        isStreamReady = false
        try {
            val stream = resolveAndPlayStream(
                context = context,
                movie = movie,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                targetMediaId = targetMediaId,
                viewModel = viewModel,
                exoPlayer = exoPlayer,
                playbackOwner = playbackOwner,
                trailerOnly = previewIsTrailer,
                selectedSubLang = selectedSubLang,
                continueWatchingData = continueWatchingData,
                onCaptions = { streamCaptions = it }
            )
            previewCookieHolder.value = stream?.headers?.get("Cookie")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.e("DetailsScreen", "stream resolve/play failed for ${movie.id}", e)
        }
    }

    DisposableEffect(targetMediaId, playbackOwner) {
        fun ownsCurrentMedia(): Boolean = viewModel.ownsSharedPlayback(playbackOwner) &&
            exoPlayer.currentMediaItem?.mediaId == targetMediaId
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!previewIsTrailer && state == Player.STATE_READY && ownsCurrentMedia() &&
                    exoPlayer.duration in 535_000L..545_000L) {
                    viewModel.recordPlaybackRateLimit()
                    viewModel.evictCachedStream(movie, currentSeason, currentEpisode)
                    isStreamReady = false
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
            }
            override fun onRenderedFirstFrame() {
                if (ownsCurrentMedia()) {
                    isStreamReady = true
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!ownsCurrentMedia()) return
                isStreamReady = false
                val httpError = generateSequence<Throwable>(error) { it.cause }.take(16)
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val waitingVideo = generateSequence<Throwable>(error) { it.cause }.take(16)
                    .filterIsInstance<com.example.data.PlaybackRateLimitedException>().firstOrNull()
                if (httpError?.responseCode == 429 || waitingVideo != null) {
                    val retryAfter = httpError?.headerFields?.entries
                        ?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                    viewModel.recordPlaybackRateLimit(com.example.data.StreamSessionPolicy.retryAfterDelayMs(retryAfter, System.currentTimeMillis()))
                    viewModel.evictCachedStream(movie, currentSeason, currentEpisode)
                } else if (!previewIsTrailer && previewRecovery < 1 &&
                    (httpError?.responseCode == 401 || httpError?.responseCode == 403)) {
                    viewModel.reportBadSession(previewCookieHolder.value)
                    viewModel.invalidateStream(movie, currentSeason, currentEpisode)
                    previewRecovery += 1
                }
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(selectedSubLang, targetMediaId, ownsPlayback) {
        if (viewModel.ownsSharedPlayback(playbackOwner))
            com.example.ui.screens.player.updateExoPlayerTrackSelection(exoPlayer, selectedSubLang)
    }

    val posterAlphaState = animateFloatAsState(
        targetValue = if (isStreamReady) 0f else 1f,
        animationSpec = tween(TvMotion.duration(440), easing = FastOutSlowInEasing),
        label = "posterAlpha"
    )
    val showPoster by remember(posterAlphaState) { derivedStateOf { posterAlphaState.value > 0.01f } }

    var lastActivityTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isUiVisible by remember { mutableStateOf(true) }

    LaunchedEffect(lastActivityTime, isStreamReady, showModalScreen) {
        isUiVisible = true
        if (!isStreamReady || showModalScreen) return@LaunchedEffect
        delay(12000L)
        isUiVisible = false
    }

    val uiAlpha by animateFloatAsState(
        targetValue = if (isUiVisible) 1f else 0f,
        animationSpec = tween(TvMotion.duration(800), easing = FastOutSlowInEasing),
        label = "uiAlpha"
    )

    LaunchedEffect(showModalScreen) {
        if (showModalScreen) {
            delay(320)
            try { modalUpRequester.requestFocus() } catch (e: Exception) {
                android.util.Log.w("DetailsScreen", "modalUpRequester.requestFocus failed", e)
            }
        } else {
            delay(240)
            val toFocus = lastFocusedSourceRequester ?: playButtonRequester
            try { toFocus.requestFocus() } catch (e: Exception) {
                try { playButtonRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    }

    var focusSettleKey by remember(movie.id) { mutableIntStateOf(0) }
    LaunchedEffect(focusSettleKey) {
        if (focusSettleKey == 0) {
            try {
                delay(600)
                playButtonRequester.requestFocus()
            } catch (e: Exception) {
                android.util.Log.w("DetailsScreen", "initial playButtonRequester.requestFocus failed", e)
            }
        } else {
            try {
                androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
                playButtonRequester.requestFocus()
            } catch (e: Exception) {
                android.util.Log.w("DetailsScreen", "resume playButtonRequester.requestFocus failed", e)
            }
        }
    }

    DisposableEffect(lifecycleOwner, playbackOwner) {
        if (isDetailsResumed) viewModel.claimSharedPlayback(playbackOwner)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.claimSharedPlayback(playbackOwner)
                if (exoPlayer.currentMediaItem?.mediaId != latestTargetMediaId) resetLatestPreview()
                isDetailsResumed = true
                isNavigatingToPlayer = false
                focusSettleKey = focusSettleKey + 1
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isDetailsResumed = false
                if (!isNavigatingToPlayer && viewModel.ownsSharedPlayback(playbackOwner)) exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!isNavigatingToPlayer) viewModel.releaseSharedPlayback(playbackOwner)
        }
    }

    fun launchPlayer(selectedMovie: Movie, selectedSeason: Int, selectedEpisode: Int, name: String, trailer: Boolean = false) {
        if (isNavigatingToPlayer) return
        isNavigatingToPlayer = true
        if (trailer) onPlayTrailer(selectedMovie, selectedSeason, selectedEpisode, name)
        else onPlayMovie(selectedMovie, selectedSeason, selectedEpisode, name)
    }

    fun handleBack() {
        if (showUpgradeModal) {
            showUpgradeModal = false
        } else if (showModalScreen) {
            lastModalCloseTime = System.currentTimeMillis()
            showModalScreen = false
        } else if (!isUiVisible) {
            isUiVisible = true
            lastActivityTime = System.currentTimeMillis()
        } else {
            if (System.currentTimeMillis() - lastModalCloseTime > 450L) {
                viewModel.releaseSharedPlayback(playbackOwner)
                onBack()
            }
        }
    }

    BackHandler { handleBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    lastActivityTime = System.currentTimeMillis()
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    if (!isUiVisible) {
                        isUiVisible = true
                        return@onPreviewKeyEvent true
                    }
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        handleBack()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    lastActivityTime = System.currentTimeMillis()
                    isUiVisible = true
                }
            }
    ) {
        // 1. Live video stream playing in background
        if (isDetailsResumed && !isNavigatingToPlayer && ownsPlayback) AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.example.R.layout.media_player_view, null) as PlayerView
                view.apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    subtitleView?.visibility = android.view.View.GONE
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (ownsPlayback) viewModel.attachSharedPlaybackView(playbackOwner, playerView)
                else if (sharedPlaybackOwner == null) viewModel.detachSharedPlaybackView(playerView)
            },
            onRelease = { playerView ->
                viewModel.detachSharedPlaybackView(playerView)
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Poster layer
        if (showPoster) {
            val ctx = LocalContext.current
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val size = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
                TvImagePolicy.backdropSize(with(density) { configuration.screenWidthDp.dp.roundToPx() },
                    with(density) { configuration.screenHeightDp.dp.roundToPx() }, TvImagePolicy.isLowMemoryDevice(ctx))
            }
            val posterUrl = remember(movie.backdropUrl, movie.posterUrl) {
                movie.backdropUrl.ifBlank { movie.posterUrl }
            }
            val posterRequest = remember(ctx, posterUrl, size) {
                ImageRequest.Builder(ctx)
                    .data(TvImagePolicy.artworkUrl(posterUrl, size.first, TvArtworkKind.BACKDROP))
                    .size(size.first, size.second)
                    .crossfade(false)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = posterRequest,
                contentDescription = movie.title,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = posterAlphaState.value },
                contentScale = ContentScale.Crop
            )
        }

        // 3. Cinematic Multi-Layer Gradient Overlays for Details Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f)
                        ),
                        radius = 1800f
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = uiAlpha }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.00f to Color(0xFF040406).copy(alpha = 0.98f),
                                0.30f to Color(0xFF060608).copy(alpha = 0.94f),
                                0.48f to Color(0xFF08080C).copy(alpha = 0.84f),
                                0.62f to Color(0xFF0A0A10).copy(alpha = 0.58f),
                                0.78f to Color.Black.copy(alpha = 0.25f),
                                1.00f to Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.85f),
                                0.16f to Color.Black.copy(alpha = 0.40f),
                                0.35f to Color.Transparent,
                                0.55f to Color.Transparent,
                                0.75f to Color.Black.copy(alpha = 0.65f),
                                1.00f to Color(0xFF050507).copy(alpha = 0.98f)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to Color(0xFFE50914).copy(alpha = 0.09f),
                                0.28f to Color(0xFF630B12).copy(alpha = 0.05f),
                                0.55f to Color(0xFF14080B).copy(alpha = 0.02f),
                                1.00f to Color.Transparent
                            ),
                            center = Offset(140f, 200f),
                            radius = 950f
                        )
                    )
            )
        }

        // 4. Details Screen Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = uiAlpha }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                DetailsHeaderRow()

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DetailsLeftInfoColumn(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                            .padding(bottom = 12.dp),
                        movie = displayMovie,
                        extraInfo = extraInfo,
                        currentDetailsLogoUrl = currentDetailsLogoUrl,
                        isKidContent = isKidContent,
                        isTvSeries = isTvSeries,
                        continueWatchingData = continueWatchingData,
                        continueWatchingList = continueWatchingList,
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        isMyListAdded = isMyListAdded,
                        isLiked = isLiked,
                        entranceProgressProvider = entranceProgressProvider,
                        playButtonRequester = playButtonRequester,
                        myListButtonRequester = myListButtonRequester,
                        likeButtonRequester = likeButtonRequester,
                        removeButtonRequester = removeButtonRequester,
                        tabRowRequester = tabRowRequester,
                        exoPlayer = exoPlayer,
                        viewModel = viewModel,
                        onPlayClick = { playSeason, playEpisode, playEpName ->
                            launchPlayer(movie, playSeason, playEpisode, playEpName)
                        },
                        onTrailerClick = { s, e, name -> launchPlayer(movie, s, e, name, trailer = true) },
                        onClearProgress = {
                            continueWatchingData = null
                        },
                        onShowUpgradeModal = { showUpgradeModal = true }
                    )

                    DetailsRightTriviaColumn(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .padding(start = 32.dp, bottom = 20.dp),
                        extraInfo = extraInfo,
                        calloutBadges = calloutBadges,
                        entranceProgressProvider = entranceProgressProvider
                    )
                }

                DetailsBottomTabsRow(
                    isTvSeries = isTvSeries,
                    entranceProgressProvider = entranceProgressProvider,
                    tabRowRequester = tabRowRequester,
                    playButtonRequester = playButtonRequester,
                    onTabClick = { tabName ->
                        lastFocusedSourceRequester = tabRowRequester
                        activeTabName = tabName
                        showModalScreen = true
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = showModalScreen,
            enter = fadeIn(animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)) +
                    slideInVertically(
                        initialOffsetY = { it / 6 },
                        animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)
                    ),
            exit = fadeOut(animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)) +
                    slideOutVertically(
                        targetOffsetY = { it / 6 },
                        animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)
                    )
        ) {
            DetailsModalOverlay(
                movie = displayMovie,
                isTvSeries = isTvSeries,
                activeTabName = activeTabName,
                onActiveTabChange = { activeTabName = it },
                onCloseModal = {
                    lastModalCloseTime = System.currentTimeMillis()
                    showModalScreen = false
                },
                episodesList = episodesList,
                episodesLoading = episodesLoading,
                onRetryEpisodes = { episodesRetry += 1 },
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                availableSeasons = availableSeasons,
                continueWatchingData = continueWatchingData,
                onSeasonSelected = { newSeason ->
                    if (currentSeason != newSeason) {
                        currentSeason = newSeason
                        currentEpisode = 1
                    }
                },
                onEpisodeClick = { ep ->
                    launchPlayer(movie, currentSeason, ep.episodeNumber, ep.title)
                },
                extraInfo = extraInfo,
                recommendations = recommendations,
                onNavigateToDetails = onNavigateToDetails,
                exoPlayer = exoPlayer,
                streamCaptions = streamCaptions,
                selectedSubLang = selectedSubLang,
                onSetSelectedSubLang = { viewModel.setSelectedSubtitleLanguage(it) },
                onPlayTrailer = { selectedMovie, selectedSeason, selectedEpisode, selectedEpisodeName ->
                    launchPlayer(selectedMovie, selectedSeason, selectedEpisode, selectedEpisodeName, trailer = true)
                },
                modalUpRequester = modalUpRequester
            )
        }

        AnimatedVisibility(
            visible = showUpgradeModal,
            enter = fadeIn(animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)) +
                    slideInVertically(
                        initialOffsetY = { it / 8 },
                        animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)
                    ),
            exit = fadeOut(animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)) +
                    slideOutVertically(
                        targetOffsetY = { it / 8 },
                        animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)
                    )
        ) {
            UpgradePlanModal(
                currentPlanName = userSubscription.planName,
                lockReason = viewModel.getLockReason(movie),
                onDismiss = { showUpgradeModal = false },
                onUpgradeConfirm = { planId, planName ->
                    viewModel.upgradePlan(planId, planName)
                    showUpgradeModal = false
                },
                onWatchTrailer = {
                    showUpgradeModal = false
                    val playSeason = currentSeason
                    val cw = continueWatchingData
                    val playEpisode = if (isTvSeries && cw != null && cw.season == currentSeason) {
                        cw.episode
                    } else {
                        currentEpisode
                    }
                    val playEpName = if (cw != null && cw.season == currentSeason) cw.episodeName else ""
                    launchPlayer(movie, playSeason, playEpisode, playEpName, trailer = true)
                }
            )
        }
    }
}
