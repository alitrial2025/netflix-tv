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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.example.model.Movie
import com.example.ui.screens.SpriteThumbnail
import com.example.ui.screens.ThumbnailCue
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion
import kotlinx.coroutines.delay

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
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "progressBar"
    )
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500L)
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
                                        // The first LEFT press can still move focus to Play.
                                        // A held seek arriving after the player opened the
                                        // controls starts scrubbing back on repeat events.
                                        if (!isScrubbing && keyEvent.nativeKeyEvent.repeatCount > 0) {
                                            isScrubbing = true
                                            scrubProgress = currentProgress
                                        }
                                        if (isScrubbing) {
                                            onUserActivity()
                                            val step = 10f / totalSeconds.coerceAtLeast(1)
                                            scrubProgress = (scrubProgress - step).coerceIn(0f, 1f)
                                            true
                                        } else {
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
