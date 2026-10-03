@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.screens.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isSeriesContent
import com.example.model.playbackMediaId
import com.example.ui.NetflixViewModel
import com.example.ui.screens.PlaybackMarkers
import com.example.ui.screens.SubtitleMarkerDetector
import com.example.ui.screens.ThumbnailCue
import com.example.ui.screens.ThumbnailParser
import com.example.ui.theme.NetflixBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.toNetMirrorStream
import com.example.ui.util.playbackMediaSource
import com.example.ui.util.TvMotion

private class PlayerJobHolder {
    var job: Job? = null
}


private fun Throwable.playbackHttpResponseCode(): Int? {
    var current: Throwable? = this
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (current != null && visited.add(current)) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}


@Composable
fun PlayerScreen(
    movie: Movie,
    season: Int = 1,
    episode: Int = 1,
    episodeName: String = "",
    initialPositionMs: Long = 0L,
    trailerOnly: Boolean = false,
    onBack: () -> Unit,
    viewModel: NetflixViewModel
) {
    DisposableEffect(viewModel) {
        viewModel.stopHomePreviews()
        onDispose { }
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLoggedIn = viewModel.isUserLoggedIn()
    val userSubscription by viewModel.userSubscription.collectAsStateWithLifecycle()
    val playbackAccessError by viewModel.playbackAccessError.collectAsStateWithLifecycle()
    var trailerChosen by remember(movie.id) { mutableStateOf(false) }
    val isTrailerPlayback = trailerOnly || !isLoggedIn || trailerChosen
    val playbackAccessLocked = remember(movie, userSubscription, isTrailerPlayback) {
        !isTrailerPlayback && viewModel.isMovieLocked(movie)
    }
    if (playbackAccessLocked) {
        com.example.ui.screens.details.UpgradePlanModal(
            currentPlanName = userSubscription.planName,
            lockReason = viewModel.getLockReason(movie),
            onDismiss = onBack,
            onUpgradeConfirm = { planId, planName -> viewModel.upgradePlan(planId, planName) },
            onWatchTrailer = { trailerChosen = true }
        )
        BackHandler(onBack = onBack)
        return
    }
    DisposableEffect(movie.id, isTrailerPlayback) {
        if (!isTrailerPlayback) viewModel.startStreamHeartbeat(movie.title, movie)
        onDispose { if (!isTrailerPlayback) viewModel.stopStreamHeartbeat() }
    }
    val playbackOwner = remember(movie.id, movie.title, movie.type) { "player:${java.util.UUID.randomUUID()}" }
    val sharedPlaybackOwner by viewModel.sharedPlaybackOwner.collectAsStateWithLifecycle()
    val ownsPlayback = sharedPlaybackOwner == playbackOwner
    val lifecycleOwner = LocalLifecycleOwner.current
    var playbackActive by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(playbackOwner) {
        viewModel.claimSharedPlayback(playbackOwner)
        onDispose { }
    }

    var currentSeason by remember(movie.id, season) { mutableIntStateOf(season) }
    var currentEpisode by remember(movie.id, season, episode) { mutableIntStateOf(episode) }
    var currentEpisodeName by remember(movie.id, season, episode, episodeName) { mutableStateOf(episodeName) }

    var playbackMarkers by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(PlaybackMarkers()) }
    var thumbnailCues by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(emptyList<ThumbnailCue>()) }

    var isPlaying by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(true) }
    var showControls by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(true) }
    var lastActivityTime by remember(movie.id, currentSeason, currentEpisode) { mutableLongStateOf(System.currentTimeMillis()) }

    val exoPlayer = viewModel.sharedExoPlayer
    val initialTargetMediaId = movie.playbackMediaId(currentSeason, currentEpisode) + if (isTrailerPlayback) ":trailer" else ""
    val isAlreadyWarm = remember(movie.id, currentSeason, currentEpisode) {
        try {
            val currId = exoPlayer.currentMediaItem?.mediaId
            val state = exoPlayer.playbackState
            currId == initialTargetMediaId &&
                    (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        } catch (_: Exception) {
            false
        }
    }

    var isLoading by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(!isAlreadyWarm) }
    var isBuffering by remember(movie.id, currentSeason, currentEpisode) {
        mutableStateOf(try { exoPlayer.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false })
    }
    var playbackError by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(movie.id, currentSeason, currentEpisode) { mutableIntStateOf(0) }
    val playbackStartedAt = remember(movie.id, currentSeason, currentEpisode, loadAttempt) {
        com.example.ui.util.RuntimeTiming.start()
    }
    var firstFrameReported by remember(movie.id, currentSeason, currentEpisode, loadAttempt) { mutableStateOf(false) }
    var sessionRecoveryAttempts by remember(movie.id, currentSeason, currentEpisode) { mutableIntStateOf(0) }
    val renderedMediaId by viewModel.sharedVideoFrameMediaId.collectAsStateWithLifecycle()
    var hasVideoFrame by remember(initialTargetMediaId) {
        mutableStateOf(viewModel.sharedVideoFrameMediaId.value == initialTargetMediaId)
    }
    LaunchedEffect(renderedMediaId, ownsPlayback, initialTargetMediaId) {
        if (ownsPlayback) hasVideoFrame = renderedMediaId == initialTargetMediaId
    }
    val loadingPosterAlpha = animateFloatAsState(
        targetValue = if (hasVideoFrame) 0f else 1f,
        animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing),
        label = "playerFirstFrame"
    )
    val showLoadingPoster by remember(loadingPosterAlpha) { derivedStateOf { loadingPosterAlpha.value > 0f } }
    var pendingResumePositionMs by remember(movie.id, movie.title, currentSeason, currentEpisode, initialPositionMs) {
        val previewStart = viewModel.takeDetailsPlaybackStart(initialTargetMediaId)
        mutableStateOf(if (isTrailerPlayback) null else
            initialPositionMs.takeIf { it > 0L && currentSeason == season && currentEpisode == episode }
                ?: previewStart?.takeUnless { isAlreadyWarm })
    }
    val fallbackJobHolder = remember(movie.id, currentSeason, currentEpisode) {
        PlayerJobHolder()
    }
    val resolutionJobHolder = remember(initialTargetMediaId) { PlayerJobHolder() }

    LaunchedEffect(playbackAccessError, ownsPlayback, isTrailerPlayback) {
        val error = playbackAccessError
        if (error != null && !isTrailerPlayback && viewModel.ownsSharedPlayback(playbackOwner)) {
            resolutionJobHolder.job?.cancel()
            fallbackJobHolder.job?.cancel()
            isLoading = false
            isBuffering = false
            playbackError = error
        }
    }

    var showSubtitleModal by remember { mutableStateOf(false) }
    val selectedSubLang by viewModel.selectedSubtitleLanguage.collectAsStateWithLifecycle()
    var activeStream by remember(movie.id, currentSeason, currentEpisode) {
        mutableStateOf(if (isTrailerPlayback) null else viewModel.getCachedStream(movie, currentSeason, currentEpisode))
    }
    var logoUrl by remember(movie) { mutableStateOf(movie.logoUrl) }

    var currentSubtitleCues by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(emptyList<SubtitleCue>()) }
    var exoPlayerSubtitleText by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf("") }
    var playerTracks by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }

    fun restartPlaybackResolution() {
        if (!viewModel.ownsSharedPlayback(playbackOwner)) return
        fallbackJobHolder.job?.cancel()
        fallbackJobHolder.job = null
        val positionMs = runCatching { exoPlayer.currentPosition }.getOrDefault(0L)
        if (positionMs > 0L) pendingResumePositionMs = positionMs
        try { exoPlayer.stop() } catch (_: Exception) {}
        try { exoPlayer.clearMediaItems() } catch (_: Exception) {}
        activeStream = null
        currentSubtitleCues = emptyList()
        exoPlayerSubtitleText = ""
        hasVideoFrame = false
        playbackError = null
        isBuffering = false
        isLoading = true
        loadAttempt += 1
    }

    val mainPlayerRequester = remember { FocusRequester() }
    val playButtonRequester = remember { FocusRequester() }
    val progressRequester = remember { FocusRequester() }

    var isExitProgressSaved by remember { mutableStateOf(false) }
    var startedEpisodeSaved by remember(initialTargetMediaId) { mutableStateOf(false) }
    fun saveCurrentProgress(forceSync: Boolean) {
        if (isTrailerPlayback || !viewModel.ownsSharedPlayback(playbackOwner) ||
            exoPlayer.currentMediaItem?.mediaId != initialTargetMediaId) return
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration
        if (duration > 0L && duration != C.TIME_UNSET) {
            viewModel.savePlaybackProgress(
                movie = movie, positionMs = position, durationMs = duration,
                season = currentSeason, episode = currentEpisode, episodeName = currentEpisodeName,
                forceFirestoreSync = forceSync
            )
        }
    }
    val saveLatestProgress by rememberUpdatedState<(Boolean) -> Unit> { forceSync -> saveCurrentProgress(forceSync) }
    fun safeSaveAndExit() {
        if (isExitProgressSaved) return
        isExitProgressSaved = true
        saveCurrentProgress(forceSync = true)
        if (viewModel.ownsSharedPlayback(playbackOwner)) {
            exoPlayer.pause()
            (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        viewModel.handoffSharedPlayback(playbackOwner)
        onBack()
    }

    val nextEpisodeJobHolder = remember(movie.id) { PlayerJobHolder() }
    fun playNextEpisode() {
        if (isTrailerPlayback || !viewModel.ownsSharedPlayback(playbackOwner)) return
        val movieIdLong = movie.id.toLongOrNull() ?: 0L
        if (nextEpisodeJobHolder.job?.isActive == true) return
        nextEpisodeJobHolder.job = coroutineScope.launch {
            isLoading = true
            val (positionMs, durationMs) = try {
                exoPlayer.currentPosition to exoPlayer.duration
            } catch (_: Exception) {
                0L to 0L
            }
            if (durationMs > 0L) {
                viewModel.savePlaybackProgress(
                    movie = movie,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    season = currentSeason,
                    episode = currentEpisode,
                    episodeName = currentEpisodeName,
                    forceFirestoreSync = true
                )
            }
            currentSubtitleCues = emptyList()
            exoPlayerSubtitleText = ""
            activeStream = null
            isLoading = true

            val nextEpNum = currentEpisode + 1
            if (movieIdLong > 0L) {
                val episodesList = withContext(Dispatchers.IO) { viewModel.getEpisodes(movieIdLong, currentSeason) }
                ensureActive()
                if (!viewModel.ownsSharedPlayback(playbackOwner)) return@launch
                val nextEp = episodesList.find { it.episodeNumber == nextEpNum }
                if (nextEp != null) {
                    currentEpisode = nextEpNum
                    currentEpisodeName = nextEp.title
                } else {
                    val nextSeasonNum = currentSeason + 1
                    val nextSeasonEps = withContext(Dispatchers.IO) { viewModel.getEpisodes(movieIdLong, nextSeasonNum) }
                    ensureActive()
                    if (!viewModel.ownsSharedPlayback(playbackOwner)) return@launch
                    val firstEp = nextSeasonEps.find { it.episodeNumber == 1 }
                    if (firstEp != null) {
                        currentSeason = nextSeasonNum
                        currentEpisode = 1
                        currentEpisodeName = firstEp.title
                    } else {
                        safeSaveAndExit()
                    }
                }
            } else {
                currentEpisode = nextEpNum
                currentEpisodeName = "Episode $nextEpNum"
            }
        }
    }

    fun userActivity() {
        showControls = true
        lastActivityTime = System.currentTimeMillis()
    }

    LaunchedEffect(movie.id) {
        if (logoUrl.isNullOrBlank()) {
            val isTv = movie.isSeriesContent()
            val fetchedLogo = withContext(Dispatchers.IO) { viewModel.fetchLogoUrl(movie.id, isTv) }
            if (!fetchedLogo.isNullOrBlank()) {
                logoUrl = fetchedLogo
            }
        }
    }

    DisposableEffect(exoPlayer, movie.id, currentSeason, currentEpisode, selectedSubLang, loadAttempt) {
        val listenerSeason = currentSeason
        val listenerEpisode = currentEpisode
        val listenerMediaId = movie.playbackMediaId(listenerSeason, listenerEpisode) + if (isTrailerPlayback) ":trailer" else ""
        fun ownsCurrentMedia(): Boolean {
            if (!viewModel.ownsSharedPlayback(playbackOwner)) return false
            if (currentSeason != listenerSeason || currentEpisode != listenerEpisode) return false
            val mediaId = runCatching { exoPlayer.currentMediaItem?.mediaId }.getOrNull()
            return mediaId == listenerMediaId
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!ownsCurrentMedia()) return
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    isLoading = false

                }
            }
            override fun onRenderedFirstFrame() {
                if (ownsCurrentMedia()) {
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        com.example.ui.util.RuntimeTiming.elapsed("player_first_frame", playbackStartedAt)
                    }
                    hasVideoFrame = true
                    // Persist the newly started episode immediately, even at position zero.
                    if (!startedEpisodeSaved) {
                        saveCurrentProgress(forceSync = true)
                        startedEpisodeSaved = true
                    }
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (ownsCurrentMedia()) isPlaying = playWhenReady
            }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (!ownsCurrentMedia()) return
                if (!selectedSubLang.equals("Off", ignoreCase = true)) {
                    val cues = cueGroup.cues
                    if (cues.isNotEmpty()) {
                        val text = cues.mapNotNull { it.text?.toString() }
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        exoPlayerSubtitleText = text
                    } else {
                        exoPlayerSubtitleText = ""
                    }
                } else {
                    exoPlayerSubtitleText = ""
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!ownsCurrentMedia()) return
                playerTracks = tracks
                updateExoPlayerTrackSelection(exoPlayer, selectedSubLang)
                var hasSelectedAudio = false
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected) {
                        hasSelectedAudio = true
                        break
                    }
                }
                if (!hasSelectedAudio) {
                    for (group in tracks.groups) {
                        if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSupported) {
                            for (i in 0 until group.length) {
                                if (group.isTrackSupported(i)) {
                                    try {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                            .build()
                                    } catch (_: Exception) {}
                                    return
                                }
                            }
                        }
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!ownsCurrentMedia()) return
                isLoading = false
                isBuffering = false
                hasVideoFrame = false
                android.util.Log.e("PlayerScreen", "Playback failed: ${error.errorCodeName} (${error.errorCode})", error)
                fallbackJobHolder.job?.cancel()
                val responseCode = error.playbackHttpResponseCode()
                val waitingVideo = generateSequence<Throwable>(error) { it.cause }.take(16)
                    .filterIsInstance<com.example.data.PlaybackRateLimitedException>().firstOrNull()
                if (responseCode == 429 || waitingVideo != null) {
                    val httpError = generateSequence<Throwable>(error) { it.cause }
                        .take(16).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val retryAfter = httpError?.headerFields?.entries
                        ?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                    viewModel.recordPlaybackRateLimit(com.example.data.StreamSessionPolicy.retryAfterDelayMs(retryAfter, System.currentTimeMillis()))
                    // Rate limits require waiting; refreshing authentication would
                    // turn one playback request into another rejected request.
                    viewModel.evictCachedStream(movie, listenerSeason, listenerEpisode)
                    pendingResumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    try { exoPlayer.stop() } catch (_: Exception) {}
                    try { exoPlayer.clearMediaItems() } catch (_: Exception) {}
                    activeStream = null
                    playbackError = "Playback is temporarily rate limited.\nPlease wait and press Retry."
                    return
                }
                if (responseCode == 401 || responseCode == 403) {
                    if (isTrailerPlayback) {
                        playbackError = "The trailer could not be played."
                        return
                    }
                    // A CDN signature can fail while the provider cookie remains
                    // valid. Refresh this route; renew a cookie only when one was
                    // actually sent and rejected.
                    viewModel.reportBadSession(activeStream?.headers?.get("Cookie"))
                    viewModel.invalidateStream(movie, listenerSeason, listenerEpisode)
                    if (sessionRecoveryAttempts < 1) {
                        sessionRecoveryAttempts += 1
                        restartPlaybackResolution()
                    } else {
                        try { exoPlayer.stop() } catch (_: Exception) {}
                        try { exoPlayer.clearMediaItems() } catch (_: Exception) {}
                        activeStream = null
                        playbackError = "This title could not be played."
                    }
                    return
                }
                val failedStream = activeStream
                fallbackJobHolder.job = coroutineScope.launch {
                    if (!ownsCurrentMedia()) return@launch
                    val rawUrl = failedStream?.rawVideoUrl
                    val currUri = try { exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() } catch (_: Exception) { null }
                    if (!rawUrl.isNullOrBlank() && currUri != rawUrl) {
                        android.util.Log.w("PlayerScreen", "Retrying the same title with its original video track")
                        try {
                            val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                            exoPlayer.setMediaSource(playbackMediaSource(context, requireNotNull(failedStream),
                                listenerMediaId, selectedSubLang, rawUrl), positionMs)
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                            isPlaying = true
                            playbackError = null
                            return@launch
                        } catch (e: Exception) {
                            android.util.Log.w("PlayerScreen", "Raw stream fallback failed: ${e.message}")
                        }
                    }

                    playbackError = "This title could not be played."
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            fallbackJobHolder.job?.cancel()
            fallbackJobHolder.job = null
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(initialTargetMediaId, loadAttempt, playbackActive, playbackAccessLocked, ownsPlayback) {
        if (!playbackActive || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        val thisAttempt = kotlinx.coroutines.currentCoroutineContext()[Job]
        resolutionJobHolder.job = thisAttempt
        val targetMediaId = initialTargetMediaId
        val warm = loadAttempt == 0 && exoPlayer.currentMediaItem?.mediaId == targetMediaId &&
            (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING)
        playbackError = null
        try {
            if (playbackAccessLocked) {
                playbackError = "This title requires an active plan. Return to Details to unlock it."
                return@LaunchedEffect
            }
            if (warm && !isTrailerPlayback && !viewModel.confirmPlaybackAccess(movie)) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                playbackError = "Reconnect to verify your membership before watching."
                return@LaunchedEffect
            }
            // Details uses the full source, so changing screens can keep the decoder.
            val startMs = pendingResumePositionMs ?: if (exoPlayer.currentMediaItem?.mediaId == targetMediaId)
                exoPlayer.currentPosition.coerceAtLeast(0L) else {
                val cw = withContext(Dispatchers.IO) { viewModel.getContinueWatching(movie.id) }
                    ?.takeIf { it.title.equals(movie.title, true) &&
                        it.toMovie().catalogMediaKind() == movie.catalogMediaKind() &&
                        (!movie.isSeriesContent() || (it.season == currentSeason && it.episode == currentEpisode)) }
                if (isTrailerPlayback) 0L else cw?.playbackPositionMs?.coerceAtLeast(0L) ?: 0L
            }
            ensureActive()
            if (!viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
            exoPlayer.volume = 1f
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, if (isTrailerPlayback) 1080 else userSubscription.maxVideoHeight.coerceAtLeast(480))
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMaxVideoFrameRate(Int.MAX_VALUE)
                .setForceLowestBitrate(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguages("en", "eng")
                .build()
            updateExoPlayerTrackSelection(exoPlayer, selectedSubLang)
            if (warm) {
                if (activeStream == null && !isTrailerPlayback)
                    activeStream = viewModel.getCachedStream(movie, currentSeason, currentEpisode)
                if (kotlin.math.abs(exoPlayer.currentPosition - startMs) > 250L) {
                    hasVideoFrame = false
                    exoPlayer.seekTo(startMs)
                }
                pendingResumePositionMs = null
                isLoading = false
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
                if (!isTrailerPlayback) viewModel.startStreamHeartbeat(movie.title, movie)
                exoPlayer.play()
                isPlaying = true
                return@LaunchedEffect
            }
            if (!warm) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            isLoading = true
            hasVideoFrame = false
            playbackMarkers = PlaybackMarkers()
            val resolutionStartedAt = com.example.ui.util.RuntimeTiming.start()
            val stream = try {
                withContext(Dispatchers.IO) {
                    if (isTrailerPlayback) kotlinx.coroutines.withTimeoutOrNull(55_000L) {
                        val trailer = viewModel.resolveTrailerStream(movie, allowExternalFallback = true)
                        if (trailer?.type == "youtube") {
                            withContext(Dispatchers.Main) {
                                try {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(trailer.url)))
                                    playbackError = "Trailer opened in YouTube. Press Back to return."
                                } catch (_: Exception) {
                                    playbackError = "Install or enable YouTube to open this official trailer."
                                }
                            }
                            null
                        } else trailer?.toNetMirrorStream(movie.title, "trailer_${movie.id}")
                    } else viewModel.resolveStream(movie, currentSeason, currentEpisode)
                }
            } finally {
                // Include failures/cancellation; readiness alone is not a video frame.
                com.example.ui.util.RuntimeTiming.elapsed("player_resolution_finished", resolutionStartedAt)
            }
            com.example.ui.util.RuntimeTiming.elapsed(
                if (stream == null) "player_resolve_failed" else "player_resolved", resolutionStartedAt
            )
            ensureActive()
            if (!viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
            if (stream == null) {
                if (playbackError == null) playbackError = if (isTrailerPlayback) "The trailer could not be played." else "This title could not be played."
                return@LaunchedEffect
            }
            activeStream = stream
            if (!isTrailerPlayback) viewModel.startStreamHeartbeat(movie.title, movie)
            exoPlayer.setMediaSource(playbackMediaSource(context, stream, targetMediaId, selectedSubLang), startMs)
            pendingResumePositionMs = null
            exoPlayer.prepare()
            exoPlayer.play()
            isPlaying = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limited: com.example.data.PlaybackRateLimitedException) {
            playbackError = "Playback is temporarily rate limited.\nPlease wait and press Retry."
        } catch (e: Exception) {
            android.util.Log.e("PlayerScreen", "Stream load exception", e)
            playbackError = viewModel.playbackAccessError.value ?: "This title could not be played."
        } finally {
            if (resolutionJobHolder.job === thisAttempt) resolutionJobHolder.job = null
            if (isActive && viewModel.ownsSharedPlayback(playbackOwner)) isLoading = false
        }
    }

    LaunchedEffect(initialTargetMediaId, loadAttempt, playbackActive) {
        if (!playbackActive) return@LaunchedEffect
        delay(60_000L)
        if (!hasVideoFrame && playbackError == null && viewModel.ownsSharedPlayback(playbackOwner)) {
            resolutionJobHolder.job?.cancel()
            fallbackJobHolder.job?.cancel()
            if (exoPlayer.currentMediaItem?.mediaId == initialTargetMediaId)
                pendingResumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            isLoading = false
            isBuffering = false
            playbackError = "Playback took too long. Please press Retry."
        }
    }

    val isTvShow = remember(movie, currentSeason, currentEpisode, currentEpisodeName, isTrailerPlayback) {
        !isTrailerPlayback && (movie.isSeriesContent() ||
        currentEpisode > 1 ||
        currentSeason > 1 ||
        currentEpisodeName.isNotBlank())
    }

    LaunchedEffect(activeStream, isTvShow, isLoading, playbackActive) {
        if (!playbackActive || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        if (activeStream != null && !isLoading) {
            val captionUrl = activeStream?.captions?.find {
                it.language.contains("English", ignoreCase = true) && it.type != "thumbnails"
            }?.url ?: activeStream?.captions?.firstOrNull { it.type != "thumbnails" }?.url

            if (captionUrl.isNullOrBlank()) {
                playbackMarkers = PlaybackMarkers()
                return@LaunchedEffect
            }

            val durationMs = try {
                val d = exoPlayer.duration
                if (d > 0 && d != C.TIME_UNSET) d else 0L
            } catch (_: IllegalStateException) {
                0L
            } catch (_: Exception) {
                0L
            }
            val markers = withContext(Dispatchers.IO) {
                try {
                    SubtitleMarkerDetector.detectMarkers(captionUrl, durationMs, isTvShow)
                } catch (e: Exception) {
                    android.util.Log.e("PlayerScreen", "Marker detection failed", e)
                    PlaybackMarkers()
                }
            }
            playbackMarkers = markers
        }
    }

    LaunchedEffect(movie.id, currentSeason, currentEpisode, activeStream, isLoading, isTvShow, playbackActive) {
        if (!playbackActive || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        if (!isTrailerPlayback && isTvShow && activeStream != null && !isLoading) {
            delay(8000L)
            val nextEpNum = currentEpisode + 1
            val tvId = movie.id.toLongOrNull() ?: return@LaunchedEffect
            val episodes = withContext(Dispatchers.IO) { viewModel.getEpisodes(tvId, currentSeason) }
            ensureActive()
            if (viewModel.ownsSharedPlayback(playbackOwner) && episodes.any { it.episodeNumber == nextEpNum })
                viewModel.preloadNextEpisodeStream(movie, currentSeason, nextEpNum)
        }
    }

    LaunchedEffect(activeStream, isLoading, playbackActive) {
        if (!playbackActive || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        if (activeStream != null && !isLoading) {
            val thumbTrack = activeStream?.captions?.find { it.type == "thumbnails" }
            if (thumbTrack != null && thumbTrack.url.isNotBlank()) {
                val cues = withContext(Dispatchers.IO) {
                    try {
                        ThumbnailParser.parseVtt(thumbTrack.url)
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerScreen", "Thumbnail parse failed", e)
                        emptyList<ThumbnailCue>()
                    }
                }
                thumbnailCues = cues
            }
        }
    }

    LaunchedEffect(selectedSubLang, activeStream, isLoading, playbackActive) {
        if (!playbackActive || !viewModel.ownsSharedPlayback(playbackOwner)) return@LaunchedEffect
        if (isLoading) {
            currentSubtitleCues = emptyList()
            exoPlayerSubtitleText = ""
            return@LaunchedEffect
        }
        updateExoPlayerTrackSelection(exoPlayer, selectedSubLang)
        val stream = activeStream
        if (selectedSubLang.equals("Off", ignoreCase = true) || stream == null) {
            currentSubtitleCues = emptyList()
            exoPlayerSubtitleText = ""
        } else {
            val matchingCaptions = stream.captions.filter {
                it.type != "thumbnails" && captionMatchesLanguage(it, selectedSubLang)
            }

            val candidates = if (matchingCaptions.isEmpty()) {
                val freshCaptions = withContext(Dispatchers.IO) {
                    viewModel.fetchSubtitlesForStream(stream, movie, currentSeason, currentEpisode)
                }
                freshCaptions.filter {
                    it.type != "thumbnails" && captionMatchesLanguage(it, selectedSubLang)
                }
            } else {
                matchingCaptions
            }

            if (candidates.isNotEmpty()) {
                var loaded = false
                for (caption in candidates) {
                    val body = SubtitleCueParser.fetchSubtitleText(caption.url, stream.headers)
                    if (!body.isNullOrBlank()) {
                        val cues = SubtitleCueParser.parseCuesFromTextOrM3u8(body, caption.url)
                        if (cues.isNotEmpty()) {
                            currentSubtitleCues = cues
                            loaded = true
                            break
                        }
                    }
                }
                if (!loaded) {
                    currentSubtitleCues = emptyList()
                }
            } else {
                currentSubtitleCues = emptyList()
            }
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (viewModel.ownsSharedPlayback(playbackOwner)) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isExitProgressSaved) saveLatestProgress(true)
                viewModel.releaseSharedPlayback(playbackOwner)
            }
        }
    }

    DisposableEffect(lifecycleOwner, playbackOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                playbackActive = true
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                playbackActive = false
                if (viewModel.ownsSharedPlayback(playbackOwner)) {
                    saveLatestProgress(false)
                    exoPlayer.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var lastFocusedControlRequester by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(lastActivityTime, showControls, showSubtitleModal) {
        if (showControls && !showSubtitleModal) {
            delay(4000L)
            showControls = false
        }
    }
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(80L)
            val toFocus = lastFocusedControlRequester ?: playButtonRequester
            try { toFocus.requestFocus() } catch (_: Exception) {}
        } else {
            try { mainPlayerRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(showSubtitleModal) {
        if (!showSubtitleModal && showControls) {
            delay(60L)
            val toFocus = lastFocusedControlRequester ?: playButtonRequester
            try { toFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isPlaying, playbackActive) {
        if (playbackActive && viewModel.ownsSharedPlayback(playbackOwner)) exoPlayer.playWhenReady = isPlaying
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(15_000L)
            if (exoPlayer.isPlaying) saveLatestProgress(false)
        }
    }

    LaunchedEffect(Unit) {
        try {
            androidx.compose.runtime.withFrameNanos { }
            if (showControls) {
                playButtonRequester.requestFocus()
            } else {
                mainPlayerRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    BackHandler {
        if (showSubtitleModal) {
            showSubtitleModal = false
        } else if (showControls) {
            showControls = false
        } else {
            safeSaveAndExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .focusRequester(mainPlayerRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode

                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (showSubtitleModal) {
                            showSubtitleModal = false
                            return@onPreviewKeyEvent true
                        } else if (showControls) {
                            showControls = false
                            try { mainPlayerRequester.requestFocus() } catch (_: Exception) {}
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }

                    // Android marks a held D-pad seek key as a long press. Do not
                    // turn repeated LEFT/RIGHT (or confirm on the scrubber) into
                    // the subtitle shortcut before the scrubber receives it.
                    val subtitleShortcutLongPress = keyEvent.nativeKeyEvent.isLongPress &&
                        lastFocusedControlRequester != progressRequester &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    if (keyCode == KeyEvent.KEYCODE_MENU || subtitleShortcutLongPress) {
                        showSubtitleModal = true
                        userActivity()
                        return@onPreviewKeyEvent true
                    }

                    if (!showControls) {
                        showControls = true
                        lastActivityTime = System.currentTimeMillis()
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                                isPlaying = !isPlaying
                                val toFocus = lastFocusedControlRequester ?: playButtonRequester
                                try { toFocus.requestFocus() } catch (_: Exception) {}
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                isPlaying = false
                                lastFocusedControlRequester = progressRequester
                                val safePos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { return@onPreviewKeyEvent true } catch (_: Exception) { return@onPreviewKeyEvent true }
                                val newPos = (safePos - 10000).coerceAtLeast(0)
                                val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                if (canSeek) {
                                    try { exoPlayer.seekTo(newPos) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                }
                                try { progressRequester.requestFocus() } catch (_: Exception) {}
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                isPlaying = false
                                lastFocusedControlRequester = progressRequester
                                val safePair = try {
                                    exoPlayer.currentPosition to exoPlayer.duration
                                } catch (_: IllegalStateException) {
                                    return@onPreviewKeyEvent true
                                } catch (_: Exception) {
                                    return@onPreviewKeyEvent true
                                }
                                val maxDur = if (safePair.second > 0L && safePair.second != C.TIME_UNSET) safePair.second else Long.MAX_VALUE
                                val newPos = (safePair.first + 10000).coerceAtMost(maxDur)
                                val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                if (canSeek) {
                                    try { exoPlayer.seekTo(newPos) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                }
                                try { progressRequester.requestFocus() } catch (_: Exception) {}
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val toFocus = lastFocusedControlRequester ?: playButtonRequester
                                try { toFocus.requestFocus() } catch (_: Exception) {}
                            }
                            else -> {
                                val toFocus = lastFocusedControlRequester ?: playButtonRequester
                                try { toFocus.requestFocus() } catch (_: Exception) {}
                            }
                        }
                        return@onPreviewKeyEvent true
                    } else {
                        userActivity()
                    }

                    val isMediaKey = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

                    if (isMediaKey) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> isPlaying = !isPlaying
                            KeyEvent.KEYCODE_MEDIA_PLAY -> isPlaying = true
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> isPlaying = false
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val safePair = try {
                                    exoPlayer.currentPosition to exoPlayer.duration
                                } catch (_: IllegalStateException) {
                                    return@onPreviewKeyEvent true
                                } catch (_: Exception) {
                                    return@onPreviewKeyEvent true
                                }
                                val maxDur = if (safePair.second > 0L && safePair.second != C.TIME_UNSET) safePair.second else Long.MAX_VALUE
                                val newPos = (safePair.first + 10000).coerceAtMost(maxDur)
                                val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                if (canSeek) {
                                    try { exoPlayer.seekTo(newPos) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                }
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                val safePos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { return@onPreviewKeyEvent true } catch (_: Exception) { return@onPreviewKeyEvent true }
                                val newPos = (safePos - 10000).coerceAtLeast(0)
                                val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                if (canSeek) {
                                    try { exoPlayer.seekTo(newPos) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                }
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> playNextEpisode()
                        }
                        return@onPreviewKeyEvent true
                    }
                    false
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        if (!showControls) {
                            showControls = true
                            try { playButtonRequester.requestFocus() } catch (_: Exception) {}
                        }
                        userActivity()
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.example.R.layout.media_player_view, null) as PlayerView
                view.apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    subtitleView?.visibility = android.view.View.GONE
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
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

        val backdropUrl = remember(movie) { movie.backdropUrl.ifBlank { movie.posterUrl } }
        if (showLoadingPoster && !backdropUrl.isNullOrBlank()) {
            val backdropCtx = LocalContext.current
            val density = LocalDensity.current
            val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
            val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
            val backdropRequest = remember(backdropUrl, screenW, screenH) {
                buildPlayerImageRequest(
                    context = backdropCtx,
                    url = backdropUrl,
                    widthPx = screenW,
                    heightPx = screenH,
                    memoryCacheKey = "player_backdrop_$backdropUrl"
                )
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = loadingPosterAlpha.value },
                contentScale = ContentScale.Crop
            )
        }


        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.90f)
                            )
                        )
                    )
            )
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                movie = movie,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                currentEpisodeName = currentEpisodeName,
                logoUrl = logoUrl,
                isLoggedIn = isLoggedIn,
                isTvShow = isTvShow && !isTrailerPlayback,
                isTrailerPlayback = isTrailerPlayback,
                onBack = { safeSaveAndExit() },
                onReplay10 = onReplay10@{
                    userActivity()
                    val safePos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { return@onReplay10 } catch (_: Exception) { return@onReplay10 }
                    val newPos = (safePos - 10000).coerceAtLeast(0)
                    val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                    if (canSeek) {
                        try { exoPlayer.seekTo(newPos) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    }
                },
                onNextEpisode = {
                    userActivity()
                    playNextEpisode()
                }
            )
        }

        AnimatedVisibility(
            visible = showControls && !isPlaying,
            enter = slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 56.dp, top = 20.dp)
        ) {
            PlayerPauseInfoCard(
                movie = movie,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                currentEpisodeName = currentEpisodeName,
                logoUrl = logoUrl,
                onUserActivity = { userActivity() }
            )
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerBottomControls(
                exoPlayer = exoPlayer,
                isPlaying = isPlaying,
                activeStream = activeStream,
                thumbnailCues = thumbnailCues,
                movie = movie,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                isTvShow = isTvShow,
                selectedSubLang = selectedSubLang,
                playButtonRequester = playButtonRequester,
                progressRequester = progressRequester,
                onTogglePlay = {
                    userActivity()
                    isPlaying = !isPlaying
                },
                onUserActivity = { userActivity() },
                onTrackFocus = { requester -> lastFocusedControlRequester = requester },
                onSelectSubtitle = { lang ->
                    userActivity()
                    viewModel.setSelectedSubtitleLanguage(lang)
                },
                onOpenSubtitleSettings = {
                    userActivity()
                    showSubtitleModal = true
                }
            )
        }

        val introStart = playbackMarkers.introStart
        val introEnd = playbackMarkers.introEnd
        if (introStart != null && introEnd != null) {
            val skipBottomPadding by animateDpAsState(
                targetValue = if (showControls) 175.dp else 48.dp,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "skipIntroPadding"
            )
            SkipIntroOverlay(
                exoPlayer = exoPlayer,
                introStart = introStart,
                introEnd = introEnd,
                onUserActivity = { userActivity() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = skipBottomPadding)
            )
        }

        val outroStart = playbackMarkers.outroStart
        if (outroStart != null && isTvShow) {
            val nextBottomPadding by animateDpAsState(
                targetValue = if (showControls) 185.dp else 48.dp,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "nextEpisodePadding"
            )
            NextEpisodeCountdownOverlay(
                exoPlayer = exoPlayer,
                outroStart = outroStart,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                movie = movie,
                onTriggerNextEpisode = { playNextEpisode() },
                onUserActivity = { userActivity() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = nextBottomPadding)
            )
        }

        if (showSubtitleModal) {
            AudioSubtitlesModal(
                exoPlayer = exoPlayer,
                playerTracks = playerTracks,
                activeStream = activeStream,
                selectedSubLang = selectedSubLang,
                onSelectSubtitle = { lang ->
                    userActivity()
                    viewModel.setSelectedSubtitleLanguage(lang)
                },
                onClose = { showSubtitleModal = false },
                onUserActivity = { userActivity() }
            )
        }

        if (!selectedSubLang.equals("Off", ignoreCase = true)) {
            SubtitleOverlay(
                exoPlayer = exoPlayer,
                cues = currentSubtitleCues,
                exoText = exoPlayerSubtitleText,
                showControls = showControls,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
        val isTargetMediaReady = currentMediaId == initialTargetMediaId && !isLoading && hasVideoFrame
        val loadingPercentage = com.example.ui.components.rememberPlayerLoadingPercentage(exoPlayer,
            resolving = isLoading || currentMediaId != initialTargetMediaId, buffering = isBuffering,
            startupBufferMs = 900, rebufferMs = 1_500)
        val showLoadingOverlay = playbackError == null && (isLoading || isBuffering || !isTargetMediaReady)
        var slowLookup by remember(initialTargetMediaId, loadAttempt) { mutableStateOf(false) }
        LaunchedEffect(initialTargetMediaId, loadAttempt, isLoading) {
            slowLookup = false
            if (isLoading) {
                delay(15_000L)
                slowLookup = true
            }
        }

        AnimatedVisibility(
            visible = showLoadingOverlay,
            enter = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing))
        ) {
            Box(Modifier.fillMaxSize()) {
                PlayerLoadingOverlay(
                    isLoading = isLoading,
                    isTargetMediaReady = isTargetMediaReady,
                    percentage = loadingPercentage
                )
                if (slowLookup && isLoading) {
                    androidx.tv.material3.Text("Still finding your video…", color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
                }
            }
        }
        if (playbackError != null) {
            PlayerErrorOverlay(
                message = playbackError ?: "Unable to play this title",
                onRetry = {
                    if (!viewModel.ownsSharedPlayback(playbackOwner)) return@PlayerErrorOverlay
                    sessionRecoveryAttempts = 0
                    if (!isTrailerPlayback && viewModel.playbackAccessError.value != null) {
                        // Recheck the lease without discarding a valid provider URL.
                        viewModel.startStreamHeartbeat(movie.title, movie)
                        restartPlaybackResolution()
                        return@PlayerErrorOverlay
                    }
                    // Resolver verification handles revoked cookies. A missing
                    // title or CDN failure alone must not force a fresh handshake.
                    if (!isTrailerPlayback && playbackError?.contains("rate limited", ignoreCase = true) == true) {
                        viewModel.evictCachedStream(movie, currentSeason, currentEpisode)
                    } else if (!isTrailerPlayback) {
                        viewModel.invalidateStream(movie, currentSeason, currentEpisode)
                    }
                    restartPlaybackResolution()
                }
            )
        }
    }
}
