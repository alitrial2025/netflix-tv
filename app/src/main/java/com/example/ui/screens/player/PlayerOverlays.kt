package com.example.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.model.Movie
import com.example.ui.components.NetflixSpinner
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion
import kotlinx.coroutines.delay

@Composable
fun SkipIntroOverlay(
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
            val pos = try { exoPlayer.currentPosition } catch (_: IllegalStateException) { break } catch (_: Exception) { break }
            isInsideIntro = pos in introStart..introEnd
        }
    }

    AnimatedVisibility(
        visible = isInsideIntro,
        enter = fadeIn(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)) + slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)) + slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)
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
fun NextEpisodeCountdownOverlay(
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
            val pos = try { exoPlayer.currentPosition } catch (_: Exception) { continue }
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
        enter = fadeIn(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)) + slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)) + slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)
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
                    // Base unfilled layer
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

                    // Progressive White Fill Layer
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
fun SubtitleOverlay(
    exoPlayer: ExoPlayer,
    cues: List<SubtitleCue>,
    exoText: String = "",
    showControls: Boolean,
    modifier: Modifier = Modifier
) {
    var currentText by remember { mutableStateOf("") }

    LaunchedEffect(exoPlayer, cues) {
        while (cues.isNotEmpty()) {
            val pos = try {
                exoPlayer.currentPosition
            } catch (_: Exception) {
                delay(100L)
                continue
            }
            currentText = findActiveCueAt(cues, pos)?.text ?: ""
            delay(100L)
        }
        currentText = ""
    }

    val displayText = if (currentText.isNotBlank()) currentText else exoText

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
fun PlayerLoadingOverlay(
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
fun PlayerErrorOverlay(
    onRetry: () -> Unit
) {
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
