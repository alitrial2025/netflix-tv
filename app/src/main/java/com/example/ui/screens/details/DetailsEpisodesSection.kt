package com.example.ui.screens.details

import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.ui.util.TvKeyPacer
import com.example.ui.components.movieRowWindow
import com.example.data.ContinueWatchingEntity
import com.example.model.Episode
import com.example.ui.screens.handleTvDpadNavigation
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion

@Composable
fun EpisodesRowSection(
    episodes: List<Episode>,
    currentSeason: Int,
    currentEpisodeNumber: Int,
    availableSeasons: List<Int> = listOf(1, 2, 3, 4),
    continueWatchingData: ContinueWatchingEntity?,
    onSeasonSelected: (Int) -> Unit = {},
    onEpisodeFocused: (Episode) -> Unit = {},
    onEpisodeClick: (Episode) -> Unit = {},
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false },
    episodesFocusRequester: FocusRequester? = null
) {
    if (episodes.isEmpty()) return

    val initialIndex = remember(episodes, currentEpisodeNumber) {
        val idx = episodes.indexOfFirst { it.episodeNumber == currentEpisodeNumber }
        if (idx >= 0) idx else 0
    }
    var focusedIndex by remember(episodes, initialIndex) { mutableIntStateOf(initialIndex) }

    LaunchedEffect(episodes, currentEpisodeNumber) {
        val idx = episodes.indexOfFirst { it.episodeNumber == currentEpisodeNumber }
        if (idx >= 0 && idx != focusedIndex) {
            focusedIndex = idx
        }
    }

    val keyPacer = remember(currentSeason) { TvKeyPacer() }
    // animateFloatAsState retargets the running spring with its current velocity.
    // A new season starts on its own selection, rather than gliding through the old list.
    val animIndex = key(currentSeason) {
        animateFloatAsState(
            targetValue = focusedIndex.toFloat(),
            animationSpec = TvMotion.carouselSpring(0.001f),
            label = "episodesPosition"
        )
    }

    val baseWidth = 230.dp
    val expandedWidth = 290.dp
    val cardHeight = 150.dp
    val gap = 16.dp
    val itemSpacing = baseWidth + gap
    val ringStart = 0.dp
    val ringEnd = ringStart + expandedWidth

    val density = LocalDensity.current
    val itemSpacingPx = remember(density, itemSpacing) { with(density) { itemSpacing.toPx() } }
    val ringStartPx = remember(density, ringStart) { with(density) { ringStart.toPx() } }
    val ringEndPx = remember(density, ringEnd) { with(density) { ringEnd.toPx() } }
    val gapPx = remember(density, gap) { with(density) { gap.toPx() } }
    val heroSlideDistancePx = remember(density, expandedWidth) { with(density) { (expandedWidth * 0.35f).toPx() } }

    var isRowFocused by remember { mutableStateOf(false) }
    val actualFocusRequester = episodesFocusRequester ?: remember { FocusRequester() }
    val activeSeasonRequester = remember { FocusRequester() }

    val ringAlphaState = animateFloatAsState(
        targetValue = if (isRowFocused) 1f else 0f,
        animationSpec = tween(durationMillis = TvMotion.duration(180), easing = FastOutSlowInEasing),
        label = "episodesRingAlpha"
    )
    val heroIndexState = remember(animIndex, episodes.size) {
        derivedStateOf {
            kotlin.math.floor(animIndex.value).toInt().coerceIn(0, episodes.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableSeasons.forEach { seasonNum ->
                val isSeasonActive = currentSeason == seasonNum
                var isSeasonFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onSeasonSelected(seasonNum) },
                    modifier = Modifier
                        .then(if (isSeasonActive) Modifier.focusRequester(activeSeasonRequester) else Modifier)
                        .onFocusChanged { isSeasonFocused = it.isFocused }
                        .handleTvDpadNavigation(
                            onDpadUp = { onDpadUp() },
                            onDpadDown = {
                                try { actualFocusRequester.requestFocus(); true } catch (_: Exception) { false }
                            }
                        ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSeasonActive) Color.White else Color.White.copy(alpha = 0.1f),
                        focusedContainerColor = if (isSeasonActive) Color.White else Color.White.copy(alpha = 0.25f)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isSeasonFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
                ) {
                    Text(
                        text = "Season $seasonNum",
                        color = if (isSeasonActive) Color.Black else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (isSeasonActive) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight + 10.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (isRowFocused && keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (episodes.isEmpty()) return@onPreviewKeyEvent true
                                if (!keyPacer.accept(1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)) return@onPreviewKeyEvent true
                                if (focusedIndex < episodes.size - 1) {
                                    focusedIndex++
                                    val ep = episodes[focusedIndex]
                                    onEpisodeFocused(ep)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (episodes.isEmpty()) return@onPreviewKeyEvent true
                                if (!keyPacer.accept(-1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)) return@onPreviewKeyEvent true
                                if (focusedIndex > 0) {
                                    focusedIndex--
                                    val ep = episodes[focusedIndex]
                                    onEpisodeFocused(ep)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (keyEvent.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                                val ep = episodes.getOrNull(focusedIndex)
                                if (ep != null) {
                                    onEpisodeClick(ep)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDown()
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                try {
                                    activeSeasonRequester.requestFocus()
                                    true
                                } catch (_: Exception) {
                                    onDpadUp()
                                }
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
                .focusRequester(actualFocusRequester)
                .focusable()
                .clipToBounds()
        ) {
            // Mount the rendered viewport, not the latest requested episode.
            // Fast taps must not remove the posters the spring is still crossing.
            val posterSlots = kotlin.math.ceil((maxWidth / itemSpacing).toDouble()).toInt() + 1
            val posterWindow = movieRowWindow(heroIndexState.value, posterSlots, episodes.size, isInfinite = false)

            // ── LAYER 1: Sliding base episode card strip ──
            for (i in posterWindow) {
                key("ep_card_$i") {
                    val ep = episodes.getOrNull(i)
                    if (ep != null) {
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    val animVal = animIndex.value
                                    val f = kotlin.math.floor(animVal).toInt()
                                    val frac = animVal - f
                                    val offsetPx = when {
                                        i < f -> ringStartPx - itemSpacingPx * (f - i) - itemSpacingPx * frac
                                        i == f -> ringStartPx - itemSpacingPx * frac
                                        i == f + 1 -> ringEndPx + gapPx - itemSpacingPx * frac
                                        else -> ringEndPx + gapPx + itemSpacingPx * (i - f - 1) - itemSpacingPx * frac
                                    }
                                    translationX = offsetPx
                                }
                                .zIndex(1f)
                        ) {
                            EpisodeCardItem(
                                episode = ep,
                                currentSeason = currentSeason,
                                cardWidth = baseWidth,
                                height = cardHeight,
                                isExpanded = false,
                                isSelectedEpisode = (continueWatchingData?.season == currentSeason && continueWatchingData?.episode == ep.episodeNumber),
                                onClick = {
                                    focusedIndex = i
                                    onEpisodeFocused(ep)
                                    onEpisodeClick(ep)
                                }
                            )
                        }
                    }
                }
            }

            // ── LAYER 2: Expanded episode card inside ring viewport ──
            Box(
                modifier = Modifier
                    .offset(x = ringStart)
                    .width(expandedWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .clipToBounds()
                    .zIndex(5f)
            ) {
                // Only replace hero content when the episode changes. Motion stays
                // in the layers, avoiding image/text recomposition on every frame.
                val animFloor = heroIndexState.value

                // Outgoing expanded episode card
                val outgoingEp = episodes.getOrNull(animFloor)
                if (outgoingEp != null) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val currentFrac = (animIndex.value - animFloor).coerceIn(0f, 1f)
                                translationX = -heroSlideDistancePx * currentFrac
                                this.alpha = (1f - currentFrac).coerceIn(0f, 1f)
                            }
                    ) {
                        EpisodeCardItem(
                            episode = outgoingEp,
                            currentSeason = currentSeason,
                            cardWidth = expandedWidth,
                            height = cardHeight,
                            isExpanded = true,
                            isSelectedEpisode = (continueWatchingData?.season == currentSeason && continueWatchingData?.episode == outgoingEp.episodeNumber),
                            onClick = { onEpisodeClick(outgoingEp) }
                        )
                    }
                }

                // Incoming expanded episode card
                val incomingEp = episodes.getOrNull(animFloor + 1)
                if (incomingEp != null) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val currentFrac = (animIndex.value - animFloor).coerceIn(0f, 1f)
                                translationX = heroSlideDistancePx * (1f - currentFrac)
                                this.alpha = currentFrac.coerceIn(0f, 1f)
                            }
                    ) {
                        EpisodeCardItem(
                            episode = incomingEp,
                            currentSeason = currentSeason,
                            cardWidth = expandedWidth,
                            height = cardHeight,
                            isExpanded = true,
                            isSelectedEpisode = (continueWatchingData?.season == currentSeason && continueWatchingData?.episode == incomingEp.episodeNumber),
                            onClick = { onEpisodeClick(incomingEp) }
                        )
                    }
                }
            }

            // ── LAYER 3: Fixed white selection ring on top ──
            Box(
                modifier = Modifier
                    .offset(x = ringStart)
                    .width(expandedWidth)
                    .height(cardHeight)
                    .graphicsLayer { alpha = ringAlphaState.value }
                    .border(3.5.dp, Color.White, RoundedCornerShape(12.dp))
                    .zIndex(10f)
            )
        }
    }
}

@Composable
fun EpisodeCardItem(
    episode: Episode,
    currentSeason: Int,
    cardWidth: Dp,
    height: Dp,
    isExpanded: Boolean,
    isSelectedEpisode: Boolean = false,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val imgUrl = remember(episode.stillUrl) { episode.stillUrl }
    val imgReq = remember(ctx, imgUrl, cardWidth, height, density) {
        val widthPx = with(density) { cardWidth.toPx().toInt() }
        val heightPx = with(density) { height.toPx().toInt() }
        ImageRequest.Builder(ctx)
            .data(imgUrl)
            .crossfade(true)
            .size(widthPx, heightPx)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF222222))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = imgReq,
            contentDescription = episode.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = if (isExpanded) 0.85f else 0.7f)
                        ),
                        startY = 50f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "EP ${episode.episodeNumber}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isSelectedEpisode) {
                        Box(
                            modifier = Modifier
                                .background(NetflixRed, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PLAYING",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column {
                    val titleSp = if (isExpanded) 16.sp else 14.sp
                    Text(
                        text = episode.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleSp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
