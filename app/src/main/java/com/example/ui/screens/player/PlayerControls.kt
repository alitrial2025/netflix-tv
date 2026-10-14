package com.example.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.example.data.NetMirrorStream
import com.example.data.SeekFrameExtractor
import com.example.model.Movie
import com.example.ui.screens.SpriteThumbnail
import com.example.ui.screens.ThumbnailCue
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun PlayerTopBar(
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    currentEpisodeName: String,
    logoUrl: String?,
    isLoggedIn: Boolean,
    isTvShow: Boolean,
    isTrailerPlayback: Boolean = false,
    onBack: () -> Unit,
    onReplay10: () -> Unit,
    onNextEpisode: () -> Unit,
    hasNextEpisode: Boolean = isTvShow
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
                if (hasNextEpisode) {
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
                val imageRequest = remember(logoUrl, logoW, logoH) {
                    buildPlayerImageRequest(
                        context = ctx,
                        url = logoUrl,
                        widthPx = logoW,
                        heightPx = logoH,
                        memoryCacheKey = "player_topbar_logo_$logoUrl",
                        artworkKind = com.example.ui.util.TvArtworkKind.LOGO
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
                text = if (isTrailerPlayback) "Official Trailer" else if (isTvShow) "S$currentSeason: E$currentEpisode" + (if (currentEpisodeName.isNotBlank()) " \"$currentEpisodeName\"" else "") else movie.year,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun PlayerPauseInfoCard(
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
            val imageRequest = remember(logoUrl, logoW, logoH) {
                buildPlayerImageRequest(
                    context = ctx,
                    url = logoUrl,
                    widthPx = logoW,
                    heightPx = logoH,
                    memoryCacheKey = "player_pause_logo_$logoUrl",
                    artworkKind = com.example.ui.util.TvArtworkKind.LOGO
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
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerBottomControls(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    activeStream: NetMirrorStream?,
    thumbnailCues: List<ThumbnailCue>,
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

    val animatedProgress by animateFloatAsState(
        targetValue = if (isScrubbing) scrubProgress else currentProgress,
        animationSpec = tween(durationMillis = if (isScrubbing) 120 else 80, easing = LinearEasing),
        label = "progressBar"
    )
    LaunchedEffect(exoPlayer) {
        while (true) {
            withFrameNanos { }
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
            enter = fadeIn(animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)) + slideInVertically(
                initialOffsetY = { -20 },
                animationSpec = tween(TvMotion.duration(240), easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing)) + slideOutVertically(
                targetOffsetY = { -20 },
                animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            val activeScrubTimeMs = ((if (isScrubbing) scrubProgress else currentProgress) * totalSeconds * 1000L).toLong()

            ThumbnailSeekingBand(
                activeScrubTimeMs = activeScrubTimeMs,
                totalSeconds = totalSeconds,
                thumbnailCues = thumbnailCues,
                activeStream = activeStream
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
                    Crossfade(
                        targetState = isPlaying,
                        animationSpec = tween(durationMillis = TvMotion.duration(100), easing = LinearEasing),
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
                            val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                            if (canSeek) {
                                try { exoPlayer.seekTo((scrubProgress * totalSeconds * 1000L).toLong()) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                            }
                            currentProgress = scrubProgress
                            isScrubbing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isProgressFocused) 6.dp else 4.dp)
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
                                        onUserActivity()
                                        if (!isScrubbing) {
                                            isScrubbing = true
                                            scrubProgress = currentProgress
                                        }
                                        val step = 10f / totalSeconds.coerceAtLeast(1)
                                        scrubProgress = (scrubProgress - step).coerceIn(0f, 1f)
                                        true
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
                                            val canSeek = try { exoPlayer.playbackState != Player.STATE_IDLE } catch (_: IllegalStateException) { false } catch (_: Exception) { false }
                                            if (canSeek) {
                                                try { exoPlayer.seekTo((scrubProgress * totalSeconds * 1000L).toLong()) } catch (_: IllegalStateException) {} catch (_: Exception) {}
                                            }
                                            currentProgress = scrubProgress
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
                        val activeProgress = animatedProgress
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
                text = formatTime((totalSeconds - if (isScrubbing) (scrubProgress * totalSeconds).toInt() else elapsedSeconds).coerceAtLeast(0)),
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
                availableSubtitleOptions(activeStream?.captions)
            }
            val selectedSubtitlePill = remember(subtitlePills, selectedSubLang) {
                resolveSelectedSubtitleOption(subtitlePills, selectedSubLang)
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

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
                    val isSelected = title == selectedSubtitlePill
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
                Box(
                    modifier = Modifier.size(48.dp),
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
fun ThumbnailSeekingBand(
    activeScrubTimeMs: Long,
    totalSeconds: Int,
    thumbnailCues: List<ThumbnailCue>,
    activeStream: NetMirrorStream? = null
) {
    val context = LocalContext.current
    val frameExtractor = remember(activeStream?.url, activeStream?.headers) { SeekFrameExtractor(context) }
    val extractedFrames = remember(activeStream?.url, activeStream?.headers) { mutableStateMapOf<Int, ThumbnailCue>() }
    val frameCount = ((totalSeconds.toLong() + 9) / 10).coerceIn(1, 8640).toInt()
    val selectedFrame = (activeScrubTimeMs / 10_000L).toInt().coerceIn(0, frameCount - 1)
    LaunchedEffect(activeStream, selectedFrame, frameCount, thumbnailCues) {
        val stream = activeStream ?: return@LaunchedEffect
        if (thumbnailCues.isNotEmpty()) return@LaunchedEffect
        // A held remote button changes targets quickly. Fetch after it settles,
        // and prioritize the selected scene before its neighboring frames.
        delay(250L)
        val first = (selectedFrame - 1).coerceIn(0, (frameCount - 3).coerceAtLeast(0))
        val indices = (listOf(selectedFrame) + (first..(first + 3).coerceAtMost(frameCount - 1))).distinct()
        for (index in indices) {
            if (index !in extractedFrames) {
                frameExtractor.frame(stream, index * 10_000L)?.let { cue ->
                    extractedFrames[index] = cue.copy(startMs = index * 10_000L, endMs = (index + 1) * 10_000L)
                }
            }
        }
        // Keep the UI's frame references bounded while the extractor caches on disk.
        extractedFrames.keys.filter { kotlin.math.abs(it - selectedFrame) > 12 }.forEach(extractedFrames::remove)
    }
    val previewCues = if (thumbnailCues.isNotEmpty()) thumbnailCues else if (activeStream != null) {
        List(frameCount) { index -> extractedFrames[index]
            ?: ThumbnailCue(index * 10_000L, (index + 1) * 10_000L, "", 0, 0, 1, 1) }
    } else emptyList()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (previewCues.isNotEmpty()) {
            val selectedIndex = seekPreviewIndex(previewCues, activeScrubTimeMs)
            val animatedIndex by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = tween(160, easing = FastOutSlowInEasing),
                label = "seekFilmstrip"
            )
            val windowStart = seekPreviewWindowStart(animatedIndex, previewCues.size)
            BoxWithConstraints(
                modifier = Modifier.widthIn(max = 616.dp).fillMaxWidth()
                    .testTag("seek_thumbnail_filmstrip")
            ) {
                val gap = 8.dp
                val cardWidth = (maxWidth - gap * 2) / 3
                val cardHeight = cardWidth * 9 / 16
                val density = LocalDensity.current
                val stridePx = with(density) { (cardWidth + gap).toPx() }
                Box(Modifier.fillMaxWidth().height(cardHeight + 8.dp).clip(RoundedCornerShape(8.dp))) {
                    // Render neighboring cues while the strip glides beneath the outline.
                    val first = floor(windowStart).toInt().coerceAtLeast(0)
                    for (index in first..(first + 3).coerceAtMost(previewCues.lastIndex)) {
                        Box(
                            Modifier.offset { IntOffset(((index - windowStart) * stridePx).roundToInt(), 0) }
                                .padding(vertical = 4.dp)
                                .size(cardWidth, cardHeight)
                                .clip(RoundedCornerShape(6.dp))
                        ) {
                            SpriteThumbnail(previewCues[index], Modifier.fillMaxSize(), activeStream?.headers.orEmpty())
                        }
                    }
                    Box(
                        Modifier.offset { IntOffset(((animatedIndex - windowStart) * stridePx).roundToInt(), 0) }
                            .size(cardWidth, cardHeight + 8.dp)
                            .border(3.dp, Color.White, RoundedCornerShape(8.dp))
                            .testTag("seek_thumbnail_ring")
                    )
                }
            }
        }
    }
}
