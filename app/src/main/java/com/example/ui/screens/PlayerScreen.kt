package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Movie
import com.example.ui.NetflixViewModel
import com.example.ui.components.NetflixSpinner
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// perf: shared, stable image-request builder for AsyncImage sites in this screen.
// Builds an ImageRequest with RGB_565 (halves bitmap RAM), crossfade(false) to match
// the global Coil config in MainActivity, an explicit memory cache key, and the
// black placeholder/error/fallback drawable. widthPx/heightPx are required so Coil
// can downsample to the exact drawable size — never decode full-res into RAM.
private fun buildPlayerImageRequest(
    context: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    memoryCacheKey: String
): ImageRequest {
    val placeholderDrawable = ColorDrawable(android.graphics.Color.BLACK)
    return ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .crossfade(false)
        .memoryCacheKey(memoryCacheKey)
        .placeholder(placeholderDrawable)
        .error(placeholderDrawable)
        .fallback(placeholderDrawable)
        .build()
}
private fun streamMimeTypeForUrl(url: String): String? {
    val lower = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        lower.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    movie: Movie,
    season: Int = 1,
    episode: Int = 1,
    episodeName: String = "",
    onBack: () -> Unit,
    viewModel: NetflixViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // perf: skip the redundant LaunchedEffect that just re-assigned state that was
    // already keyed by remember(movie.id, ...). The remember keys above already
    // re-initialize when navigation props change.
    var currentSeason by remember(movie.id, season) { mutableIntStateOf(season) }
    var currentEpisode by remember(movie.id, season, episode) { mutableIntStateOf(episode) }
    var currentEpisodeName by remember(movie.id, season, episode, episodeName) { mutableStateOf(episodeName) }

    var playbackMarkers by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(PlaybackMarkers()) }
    var thumbnailCues by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf<List<ThumbnailCue>>(emptyList()) }

    var isPlaying by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(true) }
    var showControls by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(true) }
    var lastActivityTime by remember(movie.id, currentSeason, currentEpisode) { mutableLongStateOf(System.currentTimeMillis()) }

    val exoPlayer = viewModel.sharedExoPlayer
    val initialTargetMediaId = "${movie.id}_${currentSeason}_${currentEpisode}"
    val isAlreadyWarm = remember(movie.id, currentSeason, currentEpisode) {
        try {
            val currId = exoPlayer.currentMediaItem?.mediaId
            val state = exoPlayer.playbackState
            (currId == initialTargetMediaId || currId == "${movie.id}_preview") &&
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

    var showSubtitleModal by remember { mutableStateOf(false) }
    val selectedSubLang by viewModel.selectedSubtitleLanguage.collectAsStateWithLifecycle()
    var activeStream by remember(movie.id, currentSeason, currentEpisode) {
        mutableStateOf(viewModel.getCachedStream(movie, currentSeason, currentEpisode))
    }
    var logoUrl by remember(movie) { mutableStateOf(movie.logoUrl) }
    val isLoggedIn = viewModel.isUserLoggedIn()

    var currentSubtitleCues by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var exoPlayerSubtitleText by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf("") }
    var playerTracks by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }

    val mainPlayerRequester = remember { FocusRequester() }
    val playButtonRequester = remember { FocusRequester() }
    val progressRequester = remember { FocusRequester() }

    var isExitProgressSaved by remember { mutableStateOf(false) }
    fun safeSaveAndExit() {
        if (!isExitProgressSaved) {
            isExitProgressSaved = true
            val (safePos, safeDur) = try {
                exoPlayer.currentPosition to exoPlayer.duration
            } catch (_: Exception) {
                0L to 0L
            }
            if (safeDur > 0L) {
                viewModel.savePlaybackProgress(
                    movie = movie,
                    positionMs = safePos,
                    durationMs = safeDur,
                    season = currentSeason,
                    episode = currentEpisode,
                    episodeName = currentEpisodeName,
                    forceFirestoreSync = true
                )
            }
            try { exoPlayer.pause() } catch (_: Exception) {}
        }
        onBack()
    }

    val nextEpisodeJobHolder = remember(movie.id) { object { var job: kotlinx.coroutines.Job? = null } }
    fun playNextEpisode() {
        if (isLoading || isBuffering) return
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
            val nextEpNum = currentEpisode + 1
            if (movieIdLong > 0L) {
                // bugfix: getEpisodes may hit the network — keep it off the main thread
                val episodesList = withContext(Dispatchers.IO) { viewModel.getEpisodes(movieIdLong, currentSeason) }
                val nextEp = episodesList.find { it.episodeNumber == nextEpNum }
                if (nextEp != null) {
                    currentEpisode = nextEpNum
                    currentEpisodeName = nextEp.title
                } else {
                    val nextSeasonNum = currentSeason + 1
                    val nextSeasonEps = withContext(Dispatchers.IO) { viewModel.getEpisodes(movieIdLong, nextSeasonNum) }
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
                // bugfix: Support next episode for catalog shows with string-based IDs
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
            val isTv = movie.type == "Series"
            // bugfix: logo fetch is a network call — must run on IO, not the main thread
            val fetchedLogo = withContext(Dispatchers.IO) { viewModel.fetchLogoUrl(movie.id, isTv) }
            if (!fetchedLogo.isNullOrBlank()) {
                logoUrl = fetchedLogo
            }
        }
    }

    // Attach listeners to shared player
    // bugfix: do NOT pause player in onDispose when switching episodes — pause only on full screen exit
    DisposableEffect(exoPlayer, movie.id, currentSeason, currentEpisode, selectedSubLang) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    isLoading = false
                    val durationMs = try { exoPlayer.duration } catch (_: Exception) { 0L }
                    // Rate Limit Video is ~9 minutes (approx 540 seconds). If we detect it, rotate session.
                    if (durationMs in 535000L..545000L) {
                        activeStream?.headers?.get("Cookie")?.let { cookie ->
                            viewModel.reportBadSession(cookie)
                        }
                        viewModel.invalidateStream(movie, currentSeason, currentEpisode)
                        playbackError = "Rate limit detected. Session rotated.\nPlease press Retry."
                        try { exoPlayer.stop() } catch (_: Exception) {}
                        try { exoPlayer.clearMediaItems() } catch (_: Exception) {}
                    }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
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
                playerTracks = tracks
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isLoading = false
                isBuffering = false
                playbackError = "This title could not be played."
                android.util.Log.e("PlayerScreen", "Playback failed: ${error.errorCodeName}", error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Load Stream Media
    LaunchedEffect(movie.id, currentSeason, currentEpisode, loadAttempt) {
        val targetMediaId = "${movie.id}_${currentSeason}_${currentEpisode}"
        val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
        val currentState = try { exoPlayer.playbackState } catch (_: IllegalStateException) { Player.STATE_IDLE } catch (_: Exception) { Player.STATE_IDLE }

        // DetailsScreen preloads this exact media item on the shared player. Preserve
        // that warm source when opening the full player instead of resolving it again.
        if ((currentMediaId == targetMediaId || currentMediaId == "${movie.id}_preview") && currentState != Player.STATE_IDLE) {
            if (activeStream == null) {
                activeStream = viewModel.getCachedStream(movie, currentSeason, currentEpisode)
            }
            isBuffering = currentState == Player.STATE_BUFFERING
            isLoading = false
            playbackError = null
            try { exoPlayer.volume = 1.0f } catch (_: Exception) {}
            try { exoPlayer.playWhenReady = true } catch (_: IllegalStateException) {} catch (_: Exception) {}
            try { exoPlayer.play() } catch (_: IllegalStateException) {} catch (_: Exception) {}
            isPlaying = true
            return@LaunchedEffect
        }

        isLoading = true
        playbackError = null
        playbackMarkers = PlaybackMarkers()
        try {
            // bugfix: guard the volume setter against a released player.
            try { exoPlayer.volume = 1.0f } catch (_: IllegalStateException) {} catch (_: Exception) {}

            fun needsNewMediaSource(mediaId: String?): Boolean {
                val playbackState = try { exoPlayer.playbackState } catch (_: Exception) { Player.STATE_IDLE }
                val matches = mediaId == targetMediaId || mediaId == "${movie.id}_preview"
                return !matches || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED
            }
            // bugfix: do NOT stop/clear here. If the new stream fails to resolve, the
            // player would be left idle even when a previous media item was still playable.
            // Each successful branch below sets the new source and the player will
            // auto-release the old source via setMediaSource().

            // Stream resolution and its trailer fallback share one visible loading budget.
            val resolveStartedAtMs = android.os.SystemClock.elapsedRealtime()
            // bugfix: Stream resolution hits the network/CDN — always attempt full title resolution via DirectCDN first.
            val stream = withContext(Dispatchers.IO) { viewModel.resolveStream(movie, currentSeason, currentEpisode) }
            activeStream = stream
            if (stream != null) {
                // bugfix: guard the player read in case it was released while
                // the network call was in flight.
                val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
                if (needsNewMediaSource(currentMediaId)) {
                    val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                        setDefaultRequestProperties(stream.headers)
                    }
                    val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)

                    val subtitleConfigs = stream.captions
                        .filter { it.type != "thumbnails" && (it.url.startsWith("http://") || it.url.startsWith("https://")) }
                        .map { caption ->
                            val isDefault = captionMatchesLanguage(caption, selectedSubLang)

                            val mimeType = when {
                                caption.url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                                caption.url.contains(".ttml", ignoreCase = true) || caption.url.contains(".xml", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
                                caption.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                                else -> MimeTypes.TEXT_VTT
                            }

                            val cleanSubUrl = caption.url.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
                            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(cleanSubUrl))
                                .setMimeType(mimeType)
                                .setLanguage(getIso2LanguageCode(caption.language))
                                .setLabel(caption.language)
                                .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                                .build()
                        }

                    val mediaItem = MediaItem.Builder()
                        .setUri(stream.url)
                        .setMediaId(targetMediaId)
                        .setMimeType(streamMimeTypeForUrl(stream.url))
                        .setSubtitleConfigurations(subtitleConfigs)
                        .build()

                    val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
                    try {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setPreferredAudioLanguages("en", "eng")
                            .build()
                    } catch (_: Exception) {}
                    try { exoPlayer.setMediaSource(mediaSource) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    try { exoPlayer.prepare() } catch (_: IllegalStateException) {} catch (_: Exception) {}

                    // bugfix: continue-watching lookup is a DB read — off the main thread
                    val cw = withContext(Dispatchers.IO) { viewModel.getContinueWatching(movie.id) }
                    val savedProgress = cw?.playbackPositionMs ?: 0L
                    if (savedProgress > 0) {
                        if (movie.type != "Series" || (cw != null && cw.season == currentSeason && cw.episode == currentEpisode)) {
                            try { exoPlayer.seekTo(savedProgress) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                        }
                    }
                    try { exoPlayer.playWhenReady = true } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    try { exoPlayer.play() } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    isPlaying = true
                } else {
                    try { exoPlayer.volume = 1.0f } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    try { exoPlayer.playWhenReady = true } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    try { exoPlayer.play() } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    isPlaying = true
                }
                isLoading = false
            } else {
                // If main stream resolution fails, attempt trailer stream resolution as fallback
                val trailerTimeLeftMs = (60_000L -
                    (android.os.SystemClock.elapsedRealtime() - resolveStartedAtMs)).coerceAtLeast(0L)
                val trailerStream = if (trailerTimeLeftMs > 0L) {
                    kotlinx.coroutines.withTimeoutOrNull(trailerTimeLeftMs) {
                        withContext(Dispatchers.IO) { viewModel.resolveTrailerStream(movie) }
                    }
                } else null
                if (trailerStream != null) {
                    val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
                    if (needsNewMediaSource(currentMediaId)) {
                        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                            setDefaultRequestProperties(trailerStream.headers)
                        }
                        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
                        val streamMimeType = when {
                            trailerStream.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                            trailerStream.url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                            trailerStream.url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                            else -> null
                        }
                        val mediaItemBuilder = MediaItem.Builder()
                            .setUri(trailerStream.url)
                            .setMediaId(targetMediaId)
                        if (streamMimeType != null) {
                            mediaItemBuilder.setMimeType(streamMimeType)
                        }
                        val mediaItem = mediaItemBuilder.build()
                        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
                        try { exoPlayer.setMediaSource(mediaSource) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                        try { exoPlayer.prepare() } catch (_: IllegalStateException) {} catch (_: Exception) {}
                        try { exoPlayer.playWhenReady = true } catch (_: IllegalStateException) {} catch (_: Exception) {}
                        try { exoPlayer.play() } catch (_: IllegalStateException) {} catch (_: Exception) {}
                        isPlaying = true
                    }
                } else {
                    playbackError = "This title could not be played."
                }
                isLoading = false
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerScreen", "Stream load exception", e)
            playbackError = "This title could not be played."
        } finally {
            isLoading = false
        }
    }

    val isTvShow = remember(movie, currentSeason, currentEpisode, currentEpisodeName) {
        movie.type.equals("Series", ignoreCase = true) ||
        movie.type.equals("TV", ignoreCase = true) ||
        movie.duration.contains("Season", ignoreCase = true) ||
        currentEpisode > 1 ||
        currentSeason > 1 ||
        currentEpisodeName.isNotBlank()
    }

    // perf: marker detection hits the network and parses a (potentially large) SRT
    // file. Move the work to Dispatchers.IO so the main thread isn't blocked.
    LaunchedEffect(activeStream, isTvShow, isLoading) {
        if (activeStream != null && !isLoading) {
            val captionUrl = activeStream?.captions?.find {
                it.language.contains("English", ignoreCase = true) && it.type != "thumbnails"
            }?.url ?: activeStream?.captions?.firstOrNull { it.type != "thumbnails" }?.url

            // bugfix: skip the network fetch entirely when there are no captions
            // (saves a no-op HTTP round trip and prevents the detector from choking
            // on a null URL).
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

    LaunchedEffect(activeStream, isLoading) {
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

    // perf: collapse the two previously separate selectedSubLang effects into one
    // coroutine. The track-selection switch on the player is a fast, synchronous
    // call so it doesn't need its own effect, and merging removes one Compose
    // effect slot + one cancellation/restart per language change.
    LaunchedEffect(selectedSubLang, activeStream, isLoading) {
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
            val candidateCaptions = if (matchingCaptions.isNotEmpty()) {
                matchingCaptions
            } else {
                stream.captions.filter {
                    it.type != "thumbnails" && (
                        it.language.contains(selectedSubLang, ignoreCase = true) ||
                        selectedSubLang.contains(it.language, ignoreCase = true)
                    )
                }
            }

            if (candidateCaptions.isNotEmpty()) {
                var loaded = false
                for (caption in candidateCaptions) {
                    val body = SubtitleCueParser.fetchSubtitleText(caption.url)
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

    // Keep screen on & save on dispose
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isExitProgressSaved) {
                isExitProgressSaved = true
                val (safePos, safeDur) = try {
                    exoPlayer.currentPosition to exoPlayer.duration
                } catch (_: IllegalStateException) {
                    0L to 0L
                } catch (_: Exception) {
                    0L to 0L
                }
                if (safeDur > 0L) {
                    viewModel.savePlaybackProgress(
                        movie = movie,
                        positionMs = safePos,
                        durationMs = safeDur,
                        season = currentSeason,
                        episode = currentEpisode,
                        episodeName = currentEpisodeName,
                        forceFirestoreSync = true
                    )
                }
                try { exoPlayer.pause() } catch (_: IllegalStateException) {} catch (_: Exception) {}
            }
        }
    }

    // Auto-hide controls after 4s inactivity (per spec, disabled during modal)
    var lastFocusedControlRequester by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(lastActivityTime, showControls, showSubtitleModal) {
        if (showControls && !showSubtitleModal) {
            delay(4000L)
            showControls = false
        }
    }
    LaunchedEffect(showControls) {
        if (showControls) {
            // Re-focus the last interactive control so D-pad navigation continues from
            // the same logical button the user was last on.
            delay(80L) // allow AnimatedVisibility to place children
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

    // Playback state update
    LaunchedEffect(isPlaying) {
        // bugfix: if the player has been released (e.g. process teardown) setting
        // playWhenReady throws IllegalStateException. Guard.
        try { exoPlayer.playWhenReady = isPlaying } catch (_: IllegalStateException) {} catch (_: Exception) {}
    }

    // Periodic progress save (every 15 seconds)
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(15_000L)
            // bugfix: guard the player reads — a released player throws.
            val safeState = try {
                Triple(exoPlayer.duration > 0, exoPlayer.isPlaying, exoPlayer.currentPosition to exoPlayer.duration)
            } catch (_: IllegalStateException) {
                null
            } catch (_: Exception) {
                null
            }
            if (safeState != null) {
                val (hasDuration, playing, posDur) = safeState
                if (hasDuration && playing) {
                    viewModel.savePlaybackProgress(
                        movie = movie,
                        positionMs = posDur.first,
                        durationMs = posDur.second,
                        season = currentSeason,
                        episode = currentEpisode,
                        episodeName = currentEpisodeName,
                        forceFirestoreSync = false
                    )
                }
            }
        }
    }

    // focus: settle focus AFTER the first committed frame instead of a fixed 300ms delay.
    LaunchedEffect(Unit) {
        try {
            androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
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

                    // a11y: long-press (or menu key) opens the audio/subtitle settings.
                    if (keyCode == KeyEvent.KEYCODE_MENU ||
                        keyEvent.nativeKeyEvent.isLongPress) {
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
                        // When controls are already visible, extend visibility timer on any remote interaction
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
        // 1a. Backdrop placeholder: ensures the first frame is the movie's backdrop
        // (not a black flash) while ExoPlayer is still resolving/buffering the stream.
        // transition: first-frame polish — covers the 200–500ms gap before first video frame.
        val backdropUrl = remember(movie) { movie.backdropUrl.ifBlank { movie.posterUrl } }
        if (!backdropUrl.isNullOrBlank()) {
            val backdropCtx = LocalContext.current
            val density = LocalDensity.current
            // Fullscreen — sample at the screen's effective size so we never decode 4K.
            val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
            val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
            // perf: stable ImageRequest, RGB_565, screen-sized sample, memoryCacheKey, placeholder.
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // 1b. Hardware Video Player Surface
        AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.example.R.layout.media_player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    useController = false
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
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            // bugfix: detach the PlayerView from the player on dispose to release the
            // ExoPlayer reference held by the view, preventing leaks on recomposition.
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Cinematic Gradient overlay for UI visibility
        // anim: symmetric 200ms fade in/out (per spec).
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

        // 3. TOP BAR
        // anim: symmetric 200ms enter/exit slide+fade for premium feel.
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
                isTvShow = isTvShow,
                onBack = {
                    // bugfix: same IllegalStateException guard as the BackHandler.
                    val (safePos, safeDur) = try {
                        exoPlayer.currentPosition to exoPlayer.duration
                    } catch (_: IllegalStateException) {
                        0L to 0L
                    } catch (_: Exception) {
                        0L to 0L
                    }
                    viewModel.savePlaybackProgress(
                        movie = movie,
                        positionMs = safePos,
                        durationMs = safeDur,
                        season = currentSeason,
                        episode = currentEpisode,
                        episodeName = currentEpisodeName,
                        forceFirestoreSync = true
                    )
                    try { exoPlayer.pause() } catch (_: IllegalStateException) {} catch (_: Exception) {}
                    onBack()
                },
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

        // 4. CENTER LEFT AREA: Shows ONLY when video is ON PAUSE
        // anim: symmetric 200ms enter/exit slide+fade.
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

        // 5. BOTTOM CONTROLS (Isolated so it doesn't cause full screen recompositions)
        // anim: symmetric 200ms enter/exit slide+fade.
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
                // bugfix: feed focus events from interactive controls back to the parent
                // so the next control-show can restore focus to the same button.
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

        // 6. SKIP INTRO OVERLAY
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

        // 7. NEXT EPISODE COUNTDOWN OVERLAY
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

        // 8. FULL AUDIO & SUBTITLES MODAL OVERLAY
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

        // 9. SUBTITLE TEXT OVERLAY (Custom isolated overlay)
        if (!selectedSubLang.equals("Off", ignoreCase = true)) {
            SubtitleOverlay(
                exoPlayer = exoPlayer,
                cues = currentSubtitleCues,
                exoText = exoPlayerSubtitleText,
                showControls = showControls,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 10. LOADING / BUFFERING OVERLAY
        // bugfix: wrap in AnimatedVisibility so the spinner fades out when the player
        // actually starts playing — previously it would just pop out, and the boolean
        // expression kept it visible during any temporary buffer event after playback.
        // bugfix: guard the player read for a released-player IllegalStateException.
        val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
        val isTargetMediaReady = ((currentMediaId == "${movie.id}_${currentSeason}_${currentEpisode}") || (currentMediaId == "${movie.id}_preview")) && !isLoading
        val loadingPercentage = com.example.ui.components.rememberPlayerLoadingPercentage(exoPlayer,
            resolving = isLoading || !isTargetMediaReady, buffering = isBuffering,
            startupBufferMs = 900, rebufferMs = 1_500)
        val showLoadingOverlay = playbackError == null && (isLoading || isBuffering || !isTargetMediaReady)
        AnimatedVisibility(
            visible = showLoadingOverlay,
            enter = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing))
        ) {
            PlayerLoadingOverlay(
                isLoading = isLoading,
                isTargetMediaReady = isTargetMediaReady,
                percentage = loadingPercentage
            )
        }
        if (playbackError != null) {
            PlayerErrorOverlay(
                onRetry = {
                    viewModel.invalidateStream(movie, currentSeason, currentEpisode)
                    playbackError = null
                    loadAttempt += 1
                }
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    currentEpisodeName: String,
    logoUrl: String?,
    isLoggedIn: Boolean,
    isTvShow: Boolean,
    onBack: () -> Unit,
    onReplay10: () -> Unit,
    onNextEpisode: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Arrow Button
                var isBackFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onBack,
                    modifier = Modifier.onFocusChanged { isBackFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
                ) {
                    Box(modifier = Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isBackFocused) Color.Black else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Replay 10s Button
                var isReplayFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onReplay10,
                    modifier = Modifier.onFocusChanged { isReplayFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
                ) {
                    Box(modifier = Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Replay 10s",
                            tint = if (isReplayFocused) Color.Black else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Next Episode Button (for TV Shows)
                if (isTvShow) {
                    var isNextEpFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onNextEpisode,
                        modifier = Modifier.onFocusChanged { isNextEpFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
                    ) {
                        Box(modifier = Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next Episode",
                                tint = if (isNextEpFocused) Color.Black else Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "OPTIONS",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        // Top Right Title Header
        Column(horizontalAlignment = Alignment.End) {
            if (!logoUrl.isNullOrBlank()) {
                val ctx = LocalContext.current
                val density = LocalDensity.current
                val logoW = with(density) { 180.dp.roundToPx() }
                val logoH = with(density) { 38.dp.roundToPx() }
                // perf: stable ImageRequest with RGB_565, explicit size hint, memoryCacheKey,
                // and placeholder/error/fallback. LocalContext is read INSIDE the remember block.
                val imageRequest = remember(logoUrl, logoW, logoH) {
                    buildPlayerImageRequest(
                        context = ctx,
                        url = logoUrl,
                        widthPx = logoW,
                        heightPx = logoH,
                        memoryCacheKey = "player_topbar_logo_$logoUrl"
                    )
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = movie.title,
                    modifier = Modifier
                        .heightIn(max = 38.dp)
                        .widthIn(max = 180.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd
                )
            } else {
                Text(
                    text = movie.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (!isLoggedIn) "Official Trailer" else if (movie.type == "Series") "S$currentSeason: E$currentEpisode" + (if (currentEpisodeName.isNotBlank()) " \"$currentEpisodeName\"" else "") else movie.year,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PlayerPauseInfoCard(
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    currentEpisodeName: String,
    logoUrl: String?,
    onUserActivity: () -> Unit
) {
    var rotateIndex by remember { mutableIntStateOf(0) }
    var selectedRating by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            rotateIndex = (rotateIndex + 1) % 2
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.widthIn(max = 520.dp)
    ) {
        if (!logoUrl.isNullOrBlank()) {
            val ctx = LocalContext.current
            val density = LocalDensity.current
            val logoW = with(density) { 340.dp.roundToPx() }
            val logoH = with(density) { 95.dp.roundToPx() }
            // perf: stable ImageRequest for the pause-info card logo, same recipe as top-bar.
            val imageRequest = remember(logoUrl, logoW, logoH) {
                buildPlayerImageRequest(
                    context = ctx,
                    url = logoUrl,
                    widthPx = logoW,
                    heightPx = logoH,
                    memoryCacheKey = "player_pause_logo_$logoUrl"
                )
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = movie.title,
                modifier = Modifier
                    .heightIn(max = 95.dp)
                    .widthIn(max = 340.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = movie.title.uppercase(),
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }

        AnimatedContent(
            targetState = rotateIndex,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()) togetherWith
                        (slideOutVertically { height -> -height } + fadeOut())
            },
            label = "InfoRotation"
        ) { page ->
            if (page == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val ratings = listOf(
                            Triple(0, "Not for me", Icons.Default.ThumbDown),
                            Triple(1, "I like this", Icons.Default.ThumbUp),
                            Triple(2, "Love this!", Icons.Default.Favorite)
                        )

                        ratings.forEach { (id, label, icon) ->
                            val isSelected = selectedRating == id
                            var isRatingFocused by remember { mutableStateOf(false) }

                            Surface(
                                onClick = {
                                    onUserActivity()
                                    selectedRating = if (isSelected) -1 else id
                                },
                                modifier = Modifier.onFocusChanged { isRatingFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.3f))),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.18f),
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.10f)
                            ) {
                                val itemColor = if (isRatingFocused || isSelected) Color.Black else Color.White
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = itemColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = label,
                                        color = itemColor,
                                        // a11y: 14sp min on 4K for readability at couch distance.
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Enjoying this? Rating helps us know if we should recommend more like this.",
                        color = Color.White.copy(alpha = 0.8f),
                        // a11y: 14sp min on 4K for readability at couch distance.
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 18.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (movie.type == "Series") "Season $currentSeason : Episode $currentEpisode" else movie.year,
                            color = Color(0xFF46D369),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "98% Match",
                            color = Color(0xFF46D369),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "HD",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "5.1",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (currentEpisodeName.isNotBlank()) {
                        Text(
                            text = "\"$currentEpisodeName\"",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = movie.description.take(130) + if (movie.description.length > 130) "..." else "",
                        color = Color.White.copy(alpha = 0.85f),
                        // a11y: 14sp min on 4K for readability at couch distance.
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerBottomControls(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    activeStream: com.example.data.NetMirrorStream?,
    thumbnailCues: List<ThumbnailCue>,
    // bugfix: report focus changes so PlayerScreen can restore the same button
    // on control re-entry instead of resetting to the play button every time.
    onTrackFocus: (FocusRequester) -> Unit = {},
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    isTvShow: Boolean,
    selectedSubLang: String,
    playButtonRequester: FocusRequester,
    progressRequester: FocusRequester,
    onTogglePlay: () -> Unit,
    onUserActivity: () -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onOpenSubtitleSettings: () -> Unit
) {
    var isProgressFocused by remember { mutableStateOf(false) }
    var isScrubbing by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(false) }
    var scrubProgress by remember(movie.id, currentSeason, currentEpisode) { mutableFloatStateOf(0f) }

    var currentProgress by remember(movie.id, currentSeason, currentEpisode) { mutableFloatStateOf(0f) }
    var totalSeconds by remember(movie.id, currentSeason, currentEpisode) { mutableIntStateOf(0) }
    var elapsedSeconds by remember(movie.id, currentSeason, currentEpisode) { mutableIntStateOf(0) }

    // anim: smooth progress-bar movement between 500ms ticks (LinearEasing, 50ms).
    val animatedProgress by animateFloatAsState(
        targetValue = if (isScrubbing) scrubProgress else currentProgress,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "progressBar"
    )
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500L)
            // bugfix: guard against a released player. Without this the loop
            // throws IllegalStateException at teardown and the coroutine scope
            // shows up as "still running" in the leak trace.
            val safeSnapshot: Pair<Long, Long>? = try {
                val dur = exoPlayer.duration
                if (dur <= 0) null
                else dur to exoPlayer.currentPosition
            } catch (_: IllegalStateException) { null } catch (_: Exception) { null }
            if (safeSnapshot != null) {
                val (dur, pos) = safeSnapshot
                totalSeconds = (dur / 1000).toInt()
                elapsedSeconds = (pos / 1000).toInt()
                if (!isScrubbing) {
                    currentProgress = pos.toFloat() / dur.toFloat()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Thumbnail Seeking Band
        AnimatedVisibility(
            visible = isProgressFocused,
            enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) + slideInVertically(
                initialOffsetY = { -20 },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) + slideOutVertically(
                targetOffsetY = { -20 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            val activeScrubTimeMs = ((if (isScrubbing) scrubProgress else currentProgress) * totalSeconds * 1000L).toLong()

            ThumbnailSeekingBand(
                activeScrubTimeMs = activeScrubTimeMs,
                totalSeconds = totalSeconds,
                thumbnailCues = thumbnailCues,
                movie = movie,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                isTvShow = isTvShow
            )
        }

        // 2. Play Button + Interactive Scrubber + Timestamps
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                onClick = onTogglePlay,
                modifier = Modifier
                    // a11y: ensure the play/pause hit-target is at least 48dp (12dp pad + 28dp icon = 52dp).
                    .focusRequester(playButtonRequester)
                    .onFocusChanged { state -> if (state.isFocused) onTrackFocus(playButtonRequester) },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.5f))),
                    focusedBorder = Border(BorderStroke(3.dp, NetflixRed))
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
            ) {
                Box(
                    modifier = Modifier.padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // anim: crossfade the play/pause icon in 100ms for premium feel.
                    Crossfade(
                        targetState = isPlaying,
                        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                        label = "playPauseIcon"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Text(
                text = formatTime(if (isScrubbing) (scrubProgress * totalSeconds).toInt() else elapsedSeconds),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            // Scrubber Bar Line
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = {
                        onUserActivity()
                        if (isScrubbing) {
                            // bugfix: guard seekTo before prepare completes (and a
                            // released player).
                            val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                            if (canSeek) {
                                try { exoPlayer.seekTo((scrubProgress * totalSeconds * 1000L).toLong()) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                            }
                            isScrubbing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isProgressFocused) 10.dp else 4.dp)
                        .focusRequester(progressRequester)
                        .onFocusChanged {
                            isProgressFocused = it.isFocused
                            if (it.isFocused) {
                                scrubProgress = currentProgress
                                onTrackFocus(progressRequester)
                            } else {
                                isScrubbing = false
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (isScrubbing && scrubProgress > 0f) {
                                            onUserActivity()
                                            val step = 10f / totalSeconds.coerceAtLeast(1)
                                            scrubProgress = (scrubProgress - step).coerceIn(0f, 1f)
                                            true
                                        } else {
                                            // Allow focus to navigate left to the Play/Pause button
                                            false
                                        }
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        onUserActivity()
                                        if (!isScrubbing) {
                                            isScrubbing = true
                                            scrubProgress = currentProgress
                                        }
                                        val step = 10f / totalSeconds.coerceAtLeast(1)
                                        scrubProgress = (scrubProgress + step).coerceIn(0f, 1f)
                                        true
                                    }
                                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                                        onUserActivity()
                                        if (isScrubbing) {
                                            // bugfix: guard seekTo before prepare completes (and a released player).
                                            val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                            if (canSeek) {
                                                try { exoPlayer.seekTo((scrubProgress * totalSeconds * 1000L).toLong()) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                            }
                                            isScrubbing = false
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .testTag("interactive_progress_bar"),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.35f),
                        focusedContainerColor = Color.White.copy(alpha = 0.35f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // anim: use animatedProgress for smooth tweening between ticks.
                        val activeProgress = if (isScrubbing) scrubProgress else animatedProgress
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(activeProgress.coerceIn(0f, 1f))
                                .background(if (isProgressFocused) NetflixRed else Color.White)
                        )
                    }
                }
            }

            Text(
                text = formatTime(totalSeconds),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 3. Audio & Subtitles Quick Pills + Settings Gear
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val subtitlePills = remember(activeStream) {
                val caps = activeStream?.captions?.filter { it.type != "thumbnails" }?.map { it.language }?.distinct() ?: emptyList()
                val list = (listOf("Off") + caps).distinct()
                if (list.size <= 1) {
                    listOf("Off", "English", "Spanish", "French", "German", "Italian", "Portuguese")
                } else {
                    list
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ClosedCaption,
                    contentDescription = "Subtitles",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Subtitles:",
                    color = Color.White.copy(alpha = 0.8f),
                    // a11y: 14sp min on 4K for readability at couch distance.
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // perf: LazyRow prefetch + contentType to keep subtitle-pill rail smooth.
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(end = 8.dp)
            ) {
                items(
                    items = subtitlePills,
                    key = { it },
                    contentType = { "subtitle_pill" }
                ) { title ->
                    val isSelected = if (title.equals("Off", ignoreCase = true)) {
                        selectedSubLang.equals("Off", ignoreCase = true) || selectedSubLang.isBlank()
                    } else {
                        selectedSubLang.equals(title, ignoreCase = true) ||
                        (!selectedSubLang.equals("Off", ignoreCase = true) && (
                            selectedSubLang.contains(title, ignoreCase = true) ||
                            title.contains(selectedSubLang, ignoreCase = true) ||
                            getIso2LanguageCode(selectedSubLang) == getIso2LanguageCode(title)
                        ))
                    }
                    var isPillFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSelectSubtitle(title) },
                        modifier = Modifier.onFocusChanged { isPillFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, if (isSelected) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
                    ) {
                        val pillColor = if (isPillFocused) Color.Black else Color.White
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = pillColor,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = title,
                                color = pillColor,
                                // a11y: 14sp min on 4K for readability at couch distance.
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            var isSettingsFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onOpenSubtitleSettings,
                modifier = Modifier.onFocusChanged { isSettingsFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))),
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
            ) {
                // a11y: 15dp pad + 22dp icon = 52dp hit target, exceeds 48dp min.
                Box(
                    modifier = Modifier
                        .size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Audio & Subtitles Settings",
                        tint = if (isSettingsFocused) Color.Black else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailSeekingBand(
    activeScrubTimeMs: Long,
    totalSeconds: Int,
    thumbnailCues: List<ThumbnailCue>,
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    isTvShow: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (thumbnailCues.isNotEmpty()) {
            val currentCue = remember(activeScrubTimeMs, thumbnailCues) {
                findCue(thumbnailCues, activeScrubTimeMs)
            }
            val leftCue = remember(activeScrubTimeMs, thumbnailCues) {
                findCue(thumbnailCues, (activeScrubTimeMs - 15000L).coerceAtLeast(0))
            }
            val rightCue = remember(activeScrubTimeMs, thumbnailCues, totalSeconds) {
                findCue(thumbnailCues, (activeScrubTimeMs + 15000L).coerceAtMost(totalSeconds * 1000L))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 80.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                ) {
                    SpriteThumbnail(
                        cue = leftCue,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                }

                // Middle Focused Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 200.dp, height = 112.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(3.dp, Color.White, RoundedCornerShape(8.dp))
                ) {
                    SpriteThumbnail(
                        cue = currentCue,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Right Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 80.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                ) {
                    SpriteThumbnail(
                        cue = rightCue,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(width = 200.dp, height = 112.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.5.dp, Color.White, RoundedCornerShape(8.dp))
            ) {
                val previewImage = movie.backdropUrl.ifBlank { movie.posterUrl }
                if (previewImage.isNotBlank()) {
                    val ctx = LocalContext.current
                    val density = LocalDensity.current
                    val w = with(density) { 200.dp.roundToPx() }
                    val h = with(density) { 112.dp.roundToPx() }
                    // perf: scrubber preview is 200x112 px; size hint + RGB_565 + cache key.
                    val imageRequest = remember(previewImage, w, h) {
                        buildPlayerImageRequest(
                            context = ctx,
                            url = previewImage,
                            widthPx = w,
                            heightPx = h,
                            memoryCacheKey = "scrubber_preview_$previewImage"
                        )
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                )
                if (isTvShow) {
                    Text(
                        text = "S${currentSeason}:E${currentEpisode}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = formatTime((activeScrubTimeMs / 1000L).toInt()),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun findCue(cues: List<ThumbnailCue>, timeMs: Long): ThumbnailCue? {
    if (cues.isEmpty()) return null
    var low = 0
    var high = cues.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (timeMs < cue.startMs) {
            high = mid - 1
        } else if (timeMs > cue.endMs) {
            low = mid + 1
        } else {
            return cue
        }
    }
    return cues.getOrNull(low.coerceIn(0, cues.size - 1))
}

// Find active subtitle cue at timeMs, scanning sorted cues and supporting slight overlaps.
private fun findActiveCueAt(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
    if (cues.isEmpty()) return null
    for (i in cues.indices) {
        val cue = cues[i]
        if (timeMs in cue.startMs..cue.endMs) {
            return cue
        }
        if (cue.startMs > timeMs + 10000L) {
            break
        }
    }
    return null
}

@Composable
private fun SkipIntroOverlay(
    exoPlayer: ExoPlayer,
    introStart: Long,
    introEnd: Long,
    onUserActivity: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isInsideIntro by remember { mutableStateOf(false) }

    LaunchedEffect(exoPlayer, introStart, introEnd) {
        while (true) {
            delay(500L)
            // bugfix: guard against a released player on disposal / process teardown.
            val pos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { break } catch (_: Exception) { break }
            isInsideIntro = pos in introStart..introEnd
        }
    }

    AnimatedVisibility(
        visible = isInsideIntro,
        // anim: symmetric 200ms enter/exit slide+fade.
        enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) + slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)) + slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ),
        modifier = modifier
    ) {
        val skipBtnRequester = remember { FocusRequester() }

        LaunchedEffect(isInsideIntro) {
            if (isInsideIntro) {
                try { skipBtnRequester.requestFocus() } catch (_: Exception) {}
            }
        }

        var isSkipIntroFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = {
                onUserActivity()
                // bugfix: guard seekTo before prepare completes (and a released player).
                val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                if (canSeek) {
                    try { exoPlayer.seekTo(introEnd + 500L) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                }
            },
            modifier = Modifier
                .focusRequester(skipBtnRequester)
                .testTag("skip_intro_button")
                .onFocusChanged { isSkipIntroFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(32.dp)),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f))),
                focusedBorder = Border(BorderStroke(3.5.dp, Color.White))
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF141414).copy(alpha = 0.92f),
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
        ) {
            val skipContentColor = if (isSkipIntroFocused) Color.Black else Color.White
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Skip Intro",
                    tint = skipContentColor,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "SKIP INTRO",
                    color = skipContentColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

@Composable
private fun NextEpisodeCountdownOverlay(
    exoPlayer: ExoPlayer,
    outroStart: Long,
    currentSeason: Int,
    currentEpisode: Int,
    movie: Movie,
    onTriggerNextEpisode: () -> Unit,
    onUserActivity: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isCreditsDismissed by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(false) }
    var isAtOutro by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(false) }

    val countdownTotalSeconds = 30
    val fillAnimatable = remember(movie.id, currentSeason, currentEpisode) { Animatable(0f) }
    var isNextTriggered by remember(movie.id, currentSeason, currentEpisode) { mutableStateOf(false) }

    LaunchedEffect(exoPlayer, outroStart) {
        while (true) {
            delay(500L)
            // bugfix: guard against a released player.
            val pos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { break } catch (_: Exception) { break }
            isAtOutro = pos >= outroStart && !isCreditsDismissed
        }
    }

    LaunchedEffect(isAtOutro) {
        if (isAtOutro) {
            fillAnimatable.snapTo(0f)
            fillAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = countdownTotalSeconds * 1000, easing = LinearEasing)
            )
            if (!isNextTriggered) {
                isNextTriggered = true
                onTriggerNextEpisode()
            }
        } else {
            isNextTriggered = false
            fillAnimatable.snapTo(0f)
        }
    }

    AnimatedVisibility(
        visible = isAtOutro,
        enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) + slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)) + slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ),
        modifier = modifier
    ) {
        val nextBtnRequester = remember { FocusRequester() }

        LaunchedEffect(isAtOutro) {
            if (isAtOutro) {
                delay(150L)
                try { nextBtnRequester.requestFocus() } catch (_: Exception) {}
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. WATCH CREDITS PILL
            var isWatchCreditsFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {
                    onUserActivity()
                    isCreditsDismissed = true
                },
                modifier = Modifier
                    .testTag("watch_credits_button")
                    .onFocusChanged { isWatchCreditsFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(32.dp)),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.85f))),
                    focusedBorder = Border(BorderStroke(3.5.dp, Color.White))
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
            ) {
                val creditsContentColor = if (isWatchCreditsFocused) Color.Black else Color.White
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "WATCH CREDITS",
                        color = creditsContentColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            // 2. NEXT EPISODE PILL (Translucent pill with 30s white progressive fill)
            var isNextEpFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {
                    onUserActivity()
                    if (!isNextTriggered) {
                        isNextTriggered = true
                        onTriggerNextEpisode()
                    }
                },
                modifier = Modifier
                    .focusRequester(nextBtnRequester)
                    .testTag("play_next_episode_button")
                    .onFocusChanged { isNextEpFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(32.dp)),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.85f))),
                    focusedBorder = Border(BorderStroke(3.5.dp, Color.White))
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    focusedContentColor = Color.White
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
            ) {
                val currentFill = fillAnimatable.value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    // Base unfilled layer (translucent dark with crisp white icon & text)
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Next Episode",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "NEXT EPISODE",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                    }

                    // Progressive White Fill Layer (clipped from left to right as white sweeps across)
                    if (currentFill > 0f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    clip = true
                                    shape = object : Shape {
                                        override fun createOutline(
                                            size: Size,
                                            layoutDirection: LayoutDirection,
                                            density: Density
                                        ): Outline {
                                            return Outline.Rectangle(
                                                Rect(0f, 0f, size.width * currentFill, size.height)
                                            )
                                        }
                                    }
                                }
                                .background(Color.White)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "NEXT EPISODE",
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSubtitlesModal(
    exoPlayer: ExoPlayer,
    playerTracks: androidx.media3.common.Tracks,
    activeStream: com.example.data.NetMirrorStream?,
    selectedSubLang: String,
    onSelectSubtitle: (String) -> Unit,
    onClose: () -> Unit,
    onUserActivity: () -> Unit
) {
    val allSubtitleOptions = remember(activeStream) {
        val caps = activeStream?.captions?.filter { it.type != "thumbnails" }?.map { it.language }?.distinct() ?: emptyList()
        val list = (listOf("Off") + caps).distinct()
        if (list.size <= 1) {
            listOf("Off", "English", "Spanish", "French", "German", "Italian", "Portuguese")
        } else {
            list
        }
    }
    data class PlayerAudioTrackItem(
        val group: androidx.media3.common.TrackGroup,
        val trackIndex: Int,
        val label: String,
        val language: String,
        val isSelected: Boolean
    )

    val currentAudioTracks = remember(playerTracks) {
        val list = mutableListOf<PlayerAudioTrackItem>()
        for (group in playerTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language ?: "und"
                        val rawLabel = format.label?.ifBlank { null }
                        val displayLabel = rawLabel ?: getLanguageDisplayName(lang)
                        list.add(
                            PlayerAudioTrackItem(
                                group = group.mediaTrackGroup,
                                trackIndex = i,
                                label = displayLabel,
                                language = lang,
                                isSelected = group.isTrackSelected(i)
                            )
                        )
                    }
                }
            }
        list
    }

    val closeButtonRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
            closeButtonRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .padding(horizontal = 60.dp, vertical = 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = NetflixRed,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Audio & Subtitles",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                var isCloseFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onClose,
                    modifier = Modifier
                        .focusRequester(closeButtonRequester)
                        .onFocusChanged { isCloseFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
                ) {
                    // a11y: 48dp min hit target.
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = if (isCloseFocused) Color.Black else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // Audio Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Audio (${currentAudioTracks.size})",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = currentAudioTracks,
                            key = { i, track -> "${track.language}_${track.trackIndex}_$i" },
                            contentType = { _, _ -> "audio_option" }
                        ) { _, track ->
                            val isSelected = track.isSelected
                            var isAudioFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    onUserActivity()
                                    try {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
                                            .setPreferredAudioLanguages(track.language, getIso2LanguageCode(track.language), getIso3LanguageCode(track.language))
                                            .build()
                                    } catch (_: Exception) {}
                                    onClose()
                                },
                                modifier = Modifier.onFocusChanged { isAudioFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (isSelected) Color.White.copy(alpha = 0.4f) else Color.Transparent)),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                            ) {
                                val audioTextColor = if (isAudioFocused) Color.Black else Color.White
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = track.label,
                                        color = audioTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isAudioFocused) Color.Black else NetflixRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Subtitles Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Subtitles (${allSubtitleOptions.size})",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = allSubtitleOptions,
                            key = { it },
                            contentType = { "subtitle_option" }
                        ) { opt ->
                            val isSelected = if (opt.equals("Off", ignoreCase = true)) {
                                selectedSubLang.equals("Off", ignoreCase = true) || selectedSubLang.isBlank()
                            } else {
                                selectedSubLang.equals(opt, ignoreCase = true) ||
                                (!selectedSubLang.equals("Off", ignoreCase = true) && (
                                    selectedSubLang.contains(opt, ignoreCase = true) ||
                                    opt.contains(selectedSubLang, ignoreCase = true) ||
                                    getIso2LanguageCode(selectedSubLang) == getIso2LanguageCode(opt)
                                ))
                            }
                            var isSubFocused by remember { mutableStateOf(false) }

                            Surface(
                                onClick = {
                                    onSelectSubtitle(opt)
                                    onClose()
                                },
                                modifier = Modifier.onFocusChanged { isSubFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (isSelected) Color.White.copy(alpha = 0.4f) else Color.Transparent)),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                            ) {
                                val subTextColor = if (isSubFocused) Color.Black else Color.White
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = opt,
                                        color = subTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isSubFocused) Color.Black else NetflixRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    exoPlayer: ExoPlayer,
    cues: List<SubtitleCue>,
    exoText: String = "",
    showControls: Boolean,
    modifier: Modifier = Modifier
) {
    var currentText by remember { mutableStateOf("") }

    LaunchedEffect(exoPlayer, cues) {
        while (cues.isNotEmpty()) {
            val pos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { break } catch (_: Exception) { break }
            currentText = findActiveCueAt(cues, pos)?.text ?: ""
            delay(100L)
        }
        currentText = ""
    }

    val displayText = if (currentText.isNotBlank()) currentText else exoText

    // anim: 100ms crossfade between cue changes per spec.
    Crossfade(
        targetState = displayText,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "subtitleCrossfade",
        modifier = modifier
    ) { text ->
        if (text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .padding(bottom = if (showControls) 115.dp else 40.dp)
                    .padding(horizontal = 48.dp)
                    .testTag("subtitle_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.9f),
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun PlayerLoadingOverlay(
    isLoading: Boolean,
    isTargetMediaReady: Boolean,
    percentage: Int?
) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (!isTargetMediaReady || isLoading) 1f else 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        NetflixSpinner(
            size = 90.dp,
            percentage = percentage
        )
    }
}

@Composable
private fun PlayerErrorOverlay(onRetry: () -> Unit) {
    val retryRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { retryRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Unable to play this title",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                onClick = onRetry,
                modifier = Modifier.focusRequester(retryRequester),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    focusedContainerColor = NetflixRed,
                    focusedContentColor = Color.White
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Retry", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// Subtitle and Audio Language Matching Helpers
fun getIso2LanguageCode(language: String): String {
    val lower = language.lowercase().trim()
    return when {
        lower.contains("eng") || lower == "en" -> "en"
        lower.contains("spa") || lower.contains("esp") || lower == "es" -> "es"
        lower.contains("fre") || lower.contains("fra") || lower == "fr" -> "fr"
        lower.contains("ger") || lower.contains("deu") || lower == "de" -> "de"
        lower.contains("ita") || lower == "it" -> "it"
        lower.contains("por") || lower == "pt" -> "pt"
        lower.contains("jap") || lower.contains("jpn") || lower == "ja" -> "ja"
        lower.contains("chi") || lower.contains("zho") || lower == "zh" -> "zh"
        lower.contains("ara") || lower == "ar" -> "ar"
        lower.contains("rus") || lower == "ru" -> "ru"
        lower.contains("kor") || lower == "ko" -> "ko"
        lower.contains("hin") || lower == "hi" -> "hi"
        lower.contains("swe") || lower == "sv" -> "sv"
        lower.contains("dut") || lower.contains("nld") || lower == "nl" -> "nl"
        lower.contains("tur") || lower == "tr" -> "tr"
        lower.contains("pol") || lower == "pl" -> "pl"
        lower.contains("vie") || lower == "vi" -> "vi"
        lower.contains("tha") || lower == "th" -> "th"
        lower.contains("ind") || lower == "id" -> "id"
        lower.contains("gre") || lower.contains("ell") || lower == "el" -> "el"
        lower.contains("heb") || lower == "he" -> "he"
        lower.contains("cze") || lower.contains("ces") || lower == "cs" -> "cs"
        lower.contains("rum") || lower.contains("ron") || lower == "ro" -> "ro"
        lower.length == 2 -> lower
        lower.length == 3 -> lower.take(2)
        else -> "en"
    }
}

fun getIso3LanguageCode(language: String): String {
    val lower = language.lowercase().trim()
    return when {
        lower.contains("eng") || lower == "en" -> "eng"
        lower.contains("spa") || lower.contains("esp") || lower == "es" -> "spa"
        lower.contains("fre") || lower.contains("fra") || lower == "fr" -> "fre"
        lower.contains("ger") || lower.contains("deu") || lower == "de" -> "ger"
        lower.contains("ita") || lower == "it" -> "ita"
        lower.contains("por") || lower == "pt" -> "por"
        lower.contains("jap") || lower.contains("jpn") || lower == "ja" -> "jpn"
        lower.contains("chi") || lower.contains("zho") || lower == "zh" -> "chi"
        lower.contains("ara") || lower == "ar" -> "ara"
        lower.contains("rus") || lower == "ru" -> "rus"
        lower.contains("kor") || lower == "ko" -> "kor"
        lower.contains("hin") || lower == "hi" -> "hin"
        lower.contains("swe") || lower == "sv" -> "swe"
        lower.contains("dut") || lower.contains("nld") || lower == "nl" -> "dut"
        lower.contains("tur") || lower == "tr" -> "tur"
        lower.contains("pol") || lower == "pl" -> "pol"
        lower.contains("vie") || lower == "vi" -> "vie"
        lower.contains("tha") || lower == "th" -> "tha"
        lower.contains("ind") || lower == "id" -> "ind"
        lower.contains("gre") || lower.contains("ell") || lower == "el" -> "ell"
        lower.contains("heb") || lower == "he" -> "heb"
        lower.contains("cze") || lower.contains("ces") || lower == "cs" -> "ces"
        lower.contains("rum") || lower.contains("ron") || lower == "ro" -> "ron"
        lower.length == 3 -> lower
        else -> "eng"
    }
}

fun getLanguageDisplayName(code: String): String {
    val lower = code.lowercase().trim()
    return when {
        lower == "en" || lower == "eng" || lower.startsWith("en-") -> "English"
        lower == "es" || lower == "spa" || lower.startsWith("es-") -> "Spanish"
        lower == "fr" || lower == "fre" || lower == "fra" || lower.startsWith("fr-") -> "French"
        lower == "de" || lower == "ger" || lower == "deu" || lower.startsWith("de-") -> "German"
        lower == "it" || lower == "ita" || lower.startsWith("it-") -> "Italian"
        lower == "pt" || lower == "por" || lower.startsWith("pt-") -> "Portuguese"
        lower == "ja" || lower == "jpn" -> "Japanese"
        lower == "zh" || lower == "zho" || lower == "chi" -> "Chinese"
        lower == "ru" || lower == "rus" -> "Russian"
        lower == "ar" || lower == "ara" -> "Arabic"
        lower == "hi" || lower == "hin" -> "Hindi"
        lower == "ko" || lower == "kor" -> "Korean"
        lower == "tr" || lower == "tur" -> "Turkish"
        lower == "nl" || lower == "dut" || lower == "nld" -> "Dutch"
        lower == "pl" || lower == "pol" -> "Polish"
        lower == "id" || lower == "ind" -> "Indonesian"
        lower == "th" || lower == "tha" -> "Thai"
        lower == "vi" || lower == "vie" -> "Vietnamese"
        lower == "sv" || lower == "swe" -> "Swedish"
        lower == "el" || lower == "ell" || lower == "gre" -> "Greek"
        lower == "he" || lower == "heb" -> "Hebrew"
        lower == "cs" || lower == "ces" || lower == "cze" -> "Czech"
        lower == "ro" || lower == "ron" || lower == "rum" -> "Romanian"
        else -> code.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

fun trackMatchesLanguage(format: androidx.media3.common.Format, selectedSubLang: String): Boolean {
    if (selectedSubLang.isBlank() || selectedSubLang.equals("Off", ignoreCase = true)) return false
    val label = (format.label ?: "").trim().lowercase()
    val lang = (format.language ?: "").trim().lowercase()
    val target = selectedSubLang.trim().lowercase()
    val targetIso2 = getIso2LanguageCode(target)
    val targetIso3 = getIso3LanguageCode(target)

    if (label.isNotEmpty()) {
        if (label == target || label.contains(target) || (target.length > 2 && target.contains(label))) return true
        if (label.contains(targetIso2) || label.contains(targetIso3)) return true
    }

    if (lang.isNotEmpty()) {
        if (lang == targetIso2 || lang == targetIso3 || lang.startsWith(targetIso2)) return true
        if (lang == target || lang.contains(target) || (target.length > 2 && target.contains(lang))) return true
    }

    if (targetIso2 == "en" && (label.contains("english") || label.contains("[cc]") || label.contains("en") || lang.contains("en"))) return true
    if (targetIso2 == "es" && (label.contains("spanish") || label.contains("español") || label.contains("es") || lang.contains("es") || lang.contains("spa"))) return true
    if (targetIso2 == "fr" && (label.contains("french") || label.contains("français") || label.contains("fr") || lang.contains("fr") || lang.contains("fre") || lang.contains("fra"))) return true
    if (targetIso2 == "de" && (label.contains("german") || label.contains("deutsch") || label.contains("de") || lang.contains("de") || lang.contains("ger") || lang.contains("deu"))) return true
    if (targetIso2 == "it" && (label.contains("italian") || label.contains("italiano") || label.contains("it") || lang.contains("it") || lang.contains("ita"))) return true
    if (targetIso2 == "pt" && (label.contains("portuguese") || label.contains("português") || label.contains("pt") || lang.contains("pt") || lang.contains("por"))) return true
    if (targetIso2 == "ja" && (label.contains("japanese") || label.contains("ja") || lang.contains("ja") || lang.contains("jpn"))) return true
    if (targetIso2 == "zh" && (label.contains("chinese") || label.contains("zh") || lang.contains("zh") || lang.contains("zho") || lang.contains("chi"))) return true
    if (targetIso2 == "ru" && (label.contains("russian") || label.contains("ru") || lang.contains("ru") || lang.contains("rus"))) return true
    if (targetIso2 == "ar" && (label.contains("arabic") || label.contains("ar") || lang.contains("ar") || lang.contains("ara"))) return true
    if (targetIso2 == "hi" && (label.contains("hindi") || label.contains("hi") || lang.contains("hi") || lang.contains("hin"))) return true
    if (targetIso2 == "ko" && (label.contains("korean") || label.contains("ko") || lang.contains("ko") || lang.contains("kor"))) return true

    return false
}

fun captionMatchesLanguage(caption: com.example.data.Caption, selectedSubLang: String): Boolean {
    if (selectedSubLang.isBlank() || selectedSubLang.equals("Off", ignoreCase = true)) return false
    val label = caption.language.trim().lowercase()
    val code = caption.languageCode.trim().lowercase()
    val target = selectedSubLang.trim().lowercase()
    val targetIso2 = getIso2LanguageCode(target)
    val targetIso3 = getIso3LanguageCode(target)

    if (label == target || label == targetIso2 || label == targetIso3) return true
    if (code == target || code == targetIso2 || code == targetIso3) return true
    if (label.contains(target) || (target.length > 2 && target.contains(label))) return true

    val labelIso2 = getIso2LanguageCode(label)
    val codeIso2 = getIso2LanguageCode(code)
    if (targetIso2.isNotBlank() && (labelIso2 == targetIso2 || codeIso2 == targetIso2)) return true

    val labelIso3 = getIso3LanguageCode(label)
    val codeIso3 = getIso3LanguageCode(code)
    if (targetIso3.isNotBlank() && (labelIso3 == targetIso3 || codeIso3 == targetIso3)) return true

    return false
}

private fun updateExoPlayerTrackSelection(exoPlayer: ExoPlayer, selectedSubLang: String) {
    if (selectedSubLang.equals("Off", ignoreCase = true) || selectedSubLang.isBlank()) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        return
    }

    val iso2 = getIso2LanguageCode(selectedSubLang)
    val iso3 = getIso3LanguageCode(selectedSubLang)
    val builder = exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setPreferredTextLanguages(iso2, iso3, selectedSubLang.lowercase())
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)

    val currentTracks = try { exoPlayer.currentTracks } catch (_: Exception) { null }
    if (currentTracks != null) {
        var overrideAdded = false
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (trackMatchesLanguage(format, selectedSubLang)) {
                        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                        overrideAdded = true
                        break
                    }
                }
            }
            if (overrideAdded) break
        }
    }
    exoPlayer.trackSelectionParameters = builder.build()
}

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleCueParser {
    val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val textCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun fetchSubtitleText(rawUrl: String): String? = withContext(Dispatchers.IO) {
        if (rawUrl.isBlank()) return@withContext null
        val url = rawUrl.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
        textCache[url]?.let { return@withContext it }

        // Attempt 1: Direct clean GET
        try {
            val req1 = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .build()
            client.newCall(req1).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        textCache[url] = body
                        return@withContext body
                    }
                } else if (res.code == 404) {
                    return@withContext null
                }
            }
        } catch (_: Exception) {}

        // Attempt 2: NetMirror / net52 spoofed request
        try {
            val req2 = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://net52.cc/")
                .header("Origin", "https://net52.cc")
                .header("X-Requested-With", "app.netmirror.netmirrornew")
                .build()
            client.newCall(req2).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        textCache[url] = body
                        return@withContext body
                    }
                }
            }
        } catch (_: Exception) {}

        null
    }

    suspend fun parseCuesFromTextOrM3u8(body: String, parentUrl: String): List<SubtitleCue> {
        if (body.isBlank()) return emptyList()

        if (body.contains("-->") || body.contains("WEBVTT") || body.startsWith("1\n") || body.startsWith("1\r\n")) {
            val cues = parseCues(body)
            if (cues.isNotEmpty()) return cues
        }

        if (body.contains("#EXTM3U") && (body.contains(".vtt") || body.contains(".webvtt") || body.contains("EXTINF"))) {
            val baseUrl = if (parentUrl.contains("/")) parentUrl.substringBeforeLast("/") + "/" else ""
            val lines = body.split("\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            val allCues = mutableListOf<SubtitleCue>()
            for (segLine in lines.take(20)) {
                val segUrl = if (segLine.startsWith("http://") || segLine.startsWith("https://")) segLine else "$baseUrl$segLine"
                val segText = fetchSubtitleText(segUrl)
                if (!segText.isNullOrBlank()) {
                    val segCues = parseCues(segText)
                    allCues.addAll(segCues)
                }
            }
            if (allCues.isNotEmpty()) {
                return allCues.sortedBy { it.startMs }
            }
        }
        return emptyList()
    }

    fun parseCues(srtOrVttText: String): List<SubtitleCue> {
        val normalized = srtOrVttText.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        val cues = mutableListOf<SubtitleCue>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val times = line.split("-->").map { it.trim() }
                if (times.size == 2) {
                    val startMs = parseTimestampToMs(times[0])
                    val endMs = parseTimestampToMs(times[1])
                    if (startMs >= 0 && endMs > startMs) {
                        val textLines = mutableListOf<String>()
                        var j = i + 1
                        while (j < lines.size) {
                            val nextLine = lines[j].trim()
                            if (nextLine.isEmpty() || nextLine.contains("-->")) {
                                break
                            }
                            if (nextLine.all { it.isDigit() } && j + 1 < lines.size && lines[j + 1].contains("-->")) {
                                break
                            }
                            textLines.add(nextLine)
                            j++
                        }
                        val rawText = textLines.joinToString("\n")
                        val cleanText = cleanSubtitleText(rawText)
                        if (cleanText.isNotBlank()) {
                            cues.add(SubtitleCue(startMs, endMs, cleanText))
                        }
                        i = j - 1
                    }
                }
            }
            i++
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestampToMs(timeStr: String): Long {
        try {
            val cleanStr = timeStr.split(Regex("\\s+"))[0].replace(',', '.').trim()
            val parts = cleanStr.split(":")
            return when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secondsAndMs = parts[2].toDouble()
                    ((hours * 3600 + minutes * 60 + secondsAndMs) * 1000).toLong()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secondsAndMs = parts[1].toDouble()
                    ((minutes * 60 + secondsAndMs) * 1000).toLong()
                }
                1 -> {
                    val secondsAndMs = parts[0].toDouble()
                    (secondsAndMs * 1000).toLong()
                }
                else -> -1L
            }
        } catch (e: Exception) {
            return -1L
        }
    }

    private fun cleanSubtitleText(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .trim()
    }
}

fun getIsoLanguageCode(language: String): String {
    return getIso2LanguageCode(language)
}

fun formatTime(sec: Int): String {
    val safeSec = sec.coerceAtLeast(0)
    val hrs = safeSec / 3600
    val mins = (safeSec % 3600) / 60
    val secs = safeSec % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
