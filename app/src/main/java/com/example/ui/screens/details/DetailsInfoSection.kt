package com.example.ui.screens.details

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.R
import com.example.data.ContinueWatchingEntity
import com.example.model.Movie
import com.example.ui.NetflixViewModel
import com.example.ui.screens.handleTvDpadNavigation
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion

@Composable
fun DetailsHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_netflix_n),
            contentDescription = "Netflix Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(40.dp).width(24.dp)
        )
    }
}

@Composable
fun DetailsLeftInfoColumn(
    modifier: Modifier = Modifier,
    movie: Movie,
    extraInfo: MovieExtraInfo,
    currentDetailsLogoUrl: String?,
    isKidContent: Boolean,
    isTvSeries: Boolean,
    continueWatchingData: ContinueWatchingEntity?,
    continueWatchingList: List<ContinueWatchingEntity>,
    currentSeason: Int,
    currentEpisode: Int,
    isMyListAdded: Boolean,
    isLiked: Boolean,
    entranceProgressProvider: () -> Float,
    playButtonRequester: FocusRequester,
    myListButtonRequester: FocusRequester,
    likeButtonRequester: FocusRequester,
    removeButtonRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    exoPlayer: ExoPlayer,
    viewModel: NetflixViewModel,
    onPlayClick: (Int, Int, String) -> Unit,
    onTrailerClick: (Int, Int, String) -> Unit,
    onClearProgress: () -> Unit,
    onShowUpgradeModal: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                val reveal = (entranceProgressProvider() / 0.6f).coerceIn(0f, 1f)
                alpha = reveal
                translationY = (1f - reveal) * 24f
            }
        ) {
            if (extraInfo.studioTag.equals("Netflix Original", ignoreCase = true)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_netflix_n),
                        contentDescription = "Netflix N Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(17.dp)
                            .width(9.5.dp)
                    )
                    val label = when {
                        isKidContent && isTvSeries -> "KIDS SERIES"
                        isKidContent && !isTvSeries -> "KIDS FILM"
                        isTvSeries -> "SERIES"
                        else -> "FILM"
                    }
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = extraInfo.studioTag.uppercase(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!currentDetailsLogoUrl.isNullOrBlank()) {
                val logoCtx = LocalContext.current
                val logoDensity = LocalDensity.current
                val logoRequest = remember(logoCtx, currentDetailsLogoUrl, logoDensity) {
                    val widthPx = with(logoDensity) { 320.dp.toPx().toInt() }
                    val heightPx = with(logoDensity) { 60.dp.toPx().toInt() }
                    ImageRequest.Builder(logoCtx)
                        .data(com.example.ui.util.TvImagePolicy.artworkUrl(currentDetailsLogoUrl, widthPx, com.example.ui.util.TvArtworkKind.LOGO))
                        .size(widthPx, heightPx)
                        .crossfade(true)
                        .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
                AsyncImage(
                    model = logoRequest,
                    contentDescription = movie.title,
                    modifier = Modifier
                        .height(60.dp)
                        .widthIn(max = 320.dp)
                        .padding(bottom = 4.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                Text(
                    text = movie.title,
                    fontSize = 32.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.95f),
                            blurRadius = 12f
                        )
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.graphicsLayer {
                val reveal = ((entranceProgressProvider() - 0.21f) / 0.36f).coerceIn(0f, 1f)
                alpha = reveal
                translationY = (1f - reveal) * 24f
            }
        ) {
            val typeBadgeText = when {
                isKidContent && isTvSeries -> "Kids Series"
                isKidContent && movie.type.equals("Animation", ignoreCase = true) -> "Animated Film"
                isKidContent -> "Kids Film"
                isTvSeries -> "Series"
                movie.type.equals("Animation", ignoreCase = true) -> "Animated Film"
                else -> "Film"
            }
            Text(
                text = typeBadgeText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            Text(
                text = extraInfo.genre,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            Text(
                text = movie.year.ifBlank { "2025" },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            val durationBadgeText = when {
                isTvSeries -> movie.duration.ifBlank { "1 Season" }
                else -> movie.duration.ifBlank { "1h 42m" }
            }
            Text(
                text = durationBadgeText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            val ratingBgColor = if (isKidContent) Color(0xFF22C55E) else Color(0xFFEAB308)
            Box(
                modifier = Modifier
                    .background(ratingBgColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = extraInfo.ratingBadge,
                    color = if (isKidContent) Color.White else Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            Box(
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "AD)))",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)

            Box(
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "CC",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val savedProgress = continueWatchingData?.takeIf {
            it.playbackPositionMs > 0L && it.durationMs > 0L
        }
        val hasSavedProgress = savedProgress != null

        Column(
            modifier = Modifier.graphicsLayer {
                val reveal = ((entranceProgressProvider() - 0.36f) / 0.36f).coerceIn(0f, 1f)
                alpha = reveal
                translationY = (1f - reveal) * 24f
            }
        ) {
            if (movie.description.isNotBlank()) {
                Text(
                    text = movie.description,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            blurRadius = 8f
                        )
                    ),
                    modifier = Modifier.widthIn(max = 500.dp)
                )
            }

            savedProgress?.let { cw ->
                val pos = cw.playbackPositionMs
                val dur = cw.durationMs
                val progressFraction = (pos.toFloat() / dur.toFloat()).coerceIn(0.02f, 1f)
                val remainingMin = maxOf(1, ((dur - pos) / (60 * 1000)).toInt())

                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isTvSeries) "Resume S${cw.season}:E${cw.episode}" else "Resume Watch",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${remainingMin}m remaining",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(1.5.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = progressFraction)
                                .background(NetflixRed, RoundedCornerShape(1.5.dp))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .graphicsLayer {
                    val reveal = ((entranceProgressProvider() - 0.48f) / 0.36f).coerceIn(0f, 1f)
                    alpha = reveal
                    translationY = (1f - reveal) * 24f
                }
                .handleTvDpadNavigation(
                    onDpadDown = {
                        try { tabRowRequester.requestFocus() } catch (e: Exception) { android.util.Log.w("DetailsScreen", "tabRowRequester.requestFocus failed", e) }
                        true
                    }
                )
        ) {
            var isPlayFocused by remember { mutableStateOf(false) }
            val playSeason = currentSeason
            val cw = continueWatchingData
            val playEpisode = if (isTvSeries && cw != null && cw.season == currentSeason) {
                cw.episode
            } else {
                currentEpisode
            }
            val playEpName = if (cw != null && cw.season == currentSeason) cw.episodeName else ""
            val isLoggedIn = viewModel.isUserLoggedIn()
            val isMovieLocked = viewModel.isMovieLocked(movie)
            val playButtonText = when {
                !isLoggedIn -> "Play Trailer"
                isMovieLocked -> "Upgrade Plan to Unlock"
                isTvSeries && hasSavedProgress -> "Resume S${playSeason}:E${playEpisode}"
                hasSavedProgress -> "Resume"
                else -> "Play"
            }
            Surface(
                onClick = {
                    if (isMovieLocked && isLoggedIn) {
                        onShowUpgradeModal()
                    } else if (!isLoggedIn) {
                        onTrailerClick(playSeason, playEpisode, playEpName)
                    } else {
                        onPlayClick(playSeason, playEpisode, playEpName)
                    }
                },
                modifier = Modifier
                    .focusRequester(playButtonRequester)
                    .onFocusChanged { isPlayFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isMovieLocked && isLoggedIn) Color(0xFFFFC107) else Color.White,
                    focusedContainerColor = if (isMovieLocked && isLoggedIn) Color(0xFFFFD54F) else Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isPlayFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (isMovieLocked && isLoggedIn) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_play),
                            contentDescription = playButtonText,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = playButtonText,
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isMovieLocked && isLoggedIn) {
                var isTrailerFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onTrailerClick(playSeason, playEpisode, playEpName) },
                    modifier = Modifier.onFocusChanged { isTrailerFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF333333),
                        focusedContainerColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isTrailerFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_play),
                            contentDescription = "Play Trailer",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Play Trailer",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            var isMyListFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { viewModel.toggleMyList(movie.id) },
                modifier = Modifier
                    .size(48.dp)
                    .focusRequester(myListButtonRequester)
                    .onFocusChanged { isMyListFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isMyListAdded) NetflixRed.copy(alpha = 0.8f) else Color.Transparent,
                    focusedContainerColor = if (isMyListAdded) NetflixRed else Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isMyListFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val myListTint by animateColorAsState(
                        targetValue = if (isMyListFocused && !isMyListAdded) Color.Black else Color.White,
                        animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                        label = "myListIconTint"
                    )
                    Icon(
                        painter = painterResource(id = if (isMyListAdded) R.drawable.fa_check else R.drawable.fa_plus),
                        contentDescription = "My List",
                        tint = myListTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            var isLikeFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { viewModel.toggleLike(movie.id) },
                modifier = Modifier
                    .size(48.dp)
                    .focusRequester(likeButtonRequester)
                    .onFocusChanged { isLikeFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isLiked) NetflixRed.copy(alpha = 0.3f) else Color.Transparent,
                    focusedContainerColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isLikeFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val likeTint by animateColorAsState(
                        targetValue = if (isLikeFocused) Color.Black else (if (isLiked) NetflixRed else Color.White),
                        animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                        label = "likeIconTint"
                    )
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Like",
                        tint = likeTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            val isCurrentlyInContinueWatching by remember(continueWatchingData, continueWatchingList, movie.id) {
                derivedStateOf { continueWatchingData != null || continueWatchingList.any { it.movieId == movie.id } }
            }
            if (isCurrentlyInContinueWatching) {
                var isRemoveFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        viewModel.deletePlaybackProgress(movie.id)
                        onClearProgress()
                        try { playButtonRequester.requestFocus() } catch (e: Exception) { android.util.Log.w("DetailsScreen", "playButtonRequester.requestFocus failed", e) }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .focusRequester(removeButtonRequester)
                        .onFocusChanged { isRemoveFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NetflixRed
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isRemoveFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_xmark),
                            contentDescription = "Remove from Continue Watching",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DetailsRightTriviaColumn(
    modifier: Modifier = Modifier,
    extraInfo: MovieExtraInfo,
    calloutBadges: List<com.example.ui.components.BillboardCalloutBadge> = emptyList(),
    entranceProgressProvider: () -> Float = { 1f },
    darkenedMoodColor: Color = Color(0xFF10141E)
) {
    Column(
        modifier = modifier.graphicsLayer {
            val reveal = ((entranceProgressProvider() - 0.36f) / 0.38f).coerceIn(0f, 1f)
            alpha = reveal
            translationY = (1f - reveal) * 20f
        },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End
    ) {
        if (calloutBadges.isNotEmpty()) {
            com.example.ui.components.BillboardCalloutBadgesRow(
                badges = calloutBadges,
                darkenedMoodColor = darkenedMoodColor
            )
        }
    }
}

@Composable
fun DetailsBottomTabsRow(
    isTvSeries: Boolean,
    entranceProgressProvider: () -> Float,
    tabRowRequester: FocusRequester,
    playButtonRequester: FocusRequester,
    onTabClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .graphicsLayer {
                val reveal = ((entranceProgressProvider() - 0.57f) / 0.39f).coerceIn(0f, 1f)
                alpha = reveal
                translationX = (1f - reveal) * -60f
            }
            .handleTvDpadNavigation(
                onDpadUp = {
                    try { playButtonRequester.requestFocus() } catch (e: Exception) { android.util.Log.w("DetailsScreen", "playButtonRequester.requestFocus failed", e) }
                    true
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = remember(isTvSeries) {
            if (isTvSeries) {
                listOf("Episodes", "Details", "More like this", "Audio & Subtitles")
            } else {
                listOf("Details", "More like this", "Audio & Subtitles")
            }
        }
        tabs.forEachIndexed { index, tabName ->
            var isTabFocused by remember { mutableStateOf(false) }

            Surface(
                onClick = { onTabClick(tabName) },
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(tabRowRequester) else Modifier)
                    .onFocusChanged { isTabFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isTabFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    val tabLabelColor by animateColorAsState(
                        targetValue = if (isTabFocused) Color.Black else Color.White.copy(alpha = 0.9f),
                        animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                        label = "tabLabelColor"
                    )
                    if (index == 0) {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_chevron_down),
                            contentDescription = null,
                            tint = tabLabelColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = tabName,
                        color = tabLabelColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
