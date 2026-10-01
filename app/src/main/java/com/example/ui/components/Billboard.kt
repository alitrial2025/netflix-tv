package com.example.ui.components


import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import coil.compose.AsyncImage
import com.example.model.Movie
import com.example.model.isSeriesContent
import com.example.ui.NetflixViewModel
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.HomeStartupGate
import com.example.ui.util.TvMotion



import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt



@Composable
fun BillboardSection(
    movie: Movie,
    featuredMovies: List<Movie> = emptyList(),
    height: Dp,
    alpha: () -> Float = { 1f },
    ambientColor: Color = Color(0xFF101216),
    playFocusRequester: FocusRequester? = null,
    moreInfoFocusRequester: FocusRequester? = null,
    isBillboardFocused: Boolean = true,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit = {},
    onInfoClick: () -> Unit = {},
    onFocused: () -> Unit = {},
    onMovieSelected: ((Movie) -> Unit)? = null,
    viewModel: NetflixViewModel? = null,
    onArtworkReady: () -> Unit = {},
    waitForHomeReadyBeforeLogo: Boolean = false,
    ambientColorProvider: (() -> Color)? = null
) {
    // The selected movie is owned by the parent. A second carousel index used
    // to show a different hero for one frame after catalogue refreshes.
    val currentMovie = movie
    val internalIndex = remember(movie.id, featuredMovies) {
        featuredMovies.indexOfFirst { it.id == movie.id }.coerceAtLeast(0)
    }

    val backdropUrl = currentMovie.backdropUrl.ifBlank { currentMovie.posterUrl }
    val currentOnArtworkReady by rememberUpdatedState(onArtworkReady)
    LaunchedEffect(currentMovie.id, backdropUrl) {
        if (backdropUrl.isBlank()) currentOnArtworkReady()
    }
    val moreInfoRequester = moreInfoFocusRequester ?: remember { FocusRequester() }

    var isPlayButtonFocused by remember { mutableStateOf(false) }
    var isInfoButtonFocused by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val isLowMemoryDevice = remember(ctx) { TvImagePolicy.isLowMemoryDevice(ctx) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val (backdropWidthPx, backdropHeightPx) = remember(configuration.screenWidthDp, density.density, height, isLowMemoryDevice) {
        with(density) {
            val requested = TvImagePolicy.backdropSize(
                (configuration.screenWidthDp.dp - 32.dp).roundToPx(),
                (height - 10.dp).roundToPx(),
                isLowMemoryDevice
            )
            // TMDB backdrops top out at 1280px. Decoding a larger bitmap only
            // spends heap and upload time; give small-memory TVs a lower ceiling.
            val widthLimit = if (isLowMemoryDevice) 960 else 1280
            if (requested.first <= widthLimit) requested else {
                widthLimit to (requested.second * widthLimit.toFloat() / requested.first)
                    .roundToInt().coerceAtLeast(1)
            }
        }
    }
    val logoWidthPx = with(density) { (configuration.screenWidthDp.dp * 0.23f).roundToPx() }.coerceAtMost(420)
    val logoHeightPx = with(density) { 102.dp.roundToPx() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dynamic TMDB Logo for billboard movie title
    var billboardLogoUrl by remember(currentMovie.id) {
        mutableStateOf(
            currentMovie.logoUrl
                ?: viewModel?.getCachedLogo(currentMovie.id)
                ?: com.example.data.TmdbRepository.getCachedLogo(currentMovie.id)
        )
    }

    var billboardMeta by remember(currentMovie.id) {
        mutableStateOf(
            viewModel?.getCachedBillboardMeta(currentMovie.id)
                ?: com.example.data.TmdbRepository.getCachedBillboardMeta(currentMovie.id)
        )
    }

    LaunchedEffect(currentMovie.id, waitForHomeReadyBeforeLogo) {
        val cachedLogo = viewModel?.getCachedLogo(currentMovie.id)
            ?: com.example.data.TmdbRepository.getCachedLogo(currentMovie.id)
        if (!cachedLogo.isNullOrBlank() && billboardLogoUrl.isNullOrBlank()) {
            billboardLogoUrl = cachedLogo
        }
        val cachedMeta = viewModel?.getCachedBillboardMeta(currentMovie.id)
            ?: com.example.data.TmdbRepository.getCachedBillboardMeta(currentMovie.id)
        if (cachedMeta != null) {
            billboardMeta = cachedMeta
        }

        val needsLogoFetch = billboardLogoUrl.isNullOrBlank()
        val needsMetaFetch = billboardMeta?.isEnriched != true && (currentMovie.id.toLongOrNull() ?: 0L) > 0L
        if (!needsLogoFetch && !needsMetaFetch) return@LaunchedEffect

        // The cached logo/meta path stays immediate. Network enrichment waits
        // until Home's first artwork and rows are ready and input is quiet.
        if (waitForHomeReadyBeforeLogo) HomeStartupGate.awaitIdle()
        else HomeStartupGate.awaitBrowsingIdle()
        val isTv = currentMovie.isSeriesContent()
        val numericId = currentMovie.id.toLongOrNull() ?: 0L
        withContext(Dispatchers.IO) {
            if (needsLogoFetch) {
                val fetched = viewModel?.fetchLogoUrl(currentMovie.id, isTv)
                    ?: com.example.data.TmdbRepository().fetchLogoUrl(numericId, isTv)
                if (!fetched.isNullOrBlank()) {
                    com.example.data.TmdbRepository.putCachedLogo(currentMovie.id, fetched)
                    withContext(Dispatchers.Main) {
                        billboardLogoUrl = fetched
                    }
                }
            }
            if (needsMetaFetch) {
                val enriched = viewModel?.fetchBillboardMeta(currentMovie)
                    ?: com.example.data.TmdbRepository().fetchBillboardMeta(numericId, isTv, currentMovie.title)
                if (enriched != null) {
                    withContext(Dispatchers.Main) {
                        billboardMeta = enriched
                    }
                }
            }
        }
    }

    val displayMovie = remember(currentMovie, billboardMeta) {
        BillboardCalloutGenerator.enrichMovieWithMeta(currentMovie, billboardMeta)
    }
    val calloutBadges = remember(currentMovie, billboardMeta, internalIndex) {
        BillboardCalloutGenerator.resolveBadges(
            movie = currentMovie,
            meta = billboardMeta,
            favoriteGenres = viewModel?.selectedProfile?.value?.favoriteGenres.orEmpty(),
            likedMovies = viewModel?.getLikedMoviesSnapshot().orEmpty(),
            watchedMovies = viewModel?.watchHistoryMovies?.value.orEmpty(),
            inMyList = viewModel?.myListMovieIds?.value?.contains(currentMovie.id) == true,
            rotationSlot = internalIndex
        )
    }

    val previewOwner = remember { "billboard:" + java.util.UUID.randomUUID().toString() }
    val preview by rememberHomePreview(
        owner = previewOwner, movie = currentMovie,
        focused = (isPlayButtonFocused || isInfoButtonFocused) && isBillboardFocused,
        viewModel = viewModel, audible = true
    )
    val isPreviewPlaying by rememberUpdatedState(preview.player != null)
    val isDpadOnBillboard = isBillboardFocused && (isPlayButtonFocused || isInfoButtonFocused)
    val titleAndMetaVisible = !preview.firstFrameReady
    val storyVisible = isDpadOnBillboard && !preview.firstFrameReady
    val buttonReveal = animateFloatAsState(
        targetValue = if (isDpadOnBillboard) 1f else 0f,
        animationSpec = tween(TvMotion.duration(260), easing = LinearOutSlowInEasing),
        label = "billboardButtonReveal"
    )
    val scrimAlpha = animateFloatAsState(
        if (preview.firstFrameReady) 0.35f else 1f,
        tween(TvMotion.duration(340), easing = LinearOutSlowInEasing), label = "heroScrim"
    )

    // Ambient auto-rotation across curated billboard candidates: waits 60 seconds (1 minute) per item
    // and never collides with or interrupts active video preview playback
    val currentOnMovieSelected by rememberUpdatedState(onMovieSelected)
    LaunchedEffect(featuredMovies, currentMovie.id, isBillboardFocused, isResumed) {
        if (featuredMovies.size > 1 && isBillboardFocused && isResumed) {
            while (true) {
                delay(60000L) // Full 1 minute candidate display cycle

                // If a preview is actively playing, wait until the 1-minute preview finishes
                while (isPreviewPlaying) {
                    delay(1000L)
                }

                // If user is actively focused on Billboard primary actions, hold rotation
                if (isBillboardFocused && (isPlayButtonFocused || isInfoButtonFocused)) {
                    delay(5000L)
                    continue
                }

                val nextIdx = (internalIndex + 1) % featuredMovies.size
                val nextMovie = featuredMovies[nextIdx]
                currentOnMovieSelected?.invoke(nextMovie)
            }
        }
    }

    // Warm compressed artwork only after entry has settled. execute() is cancelled
    // with this effect when the user leaves the hero; no full-size bitmap is retained.
    LaunchedEffect(internalIndex, featuredMovies, isBillboardFocused, isResumed, isLowMemoryDevice) {
        if (featuredMovies.size > 1 && isBillboardFocused && isResumed && !isLowMemoryDevice) {
            // Rotation is a minute away. Do not download the next hero during landing.
            delay(45_000L)
            HomeStartupGate.awaitBrowsingIdle()
            val nextMovie = featuredMovies.getOrNull((internalIndex + 1) % featuredMovies.size)
            if (nextMovie != null) {
                val nextBackdrop = nextMovie.backdropUrl.ifBlank { nextMovie.posterUrl }
                if (nextBackdrop.isNotBlank()) {
                    val prefetchReq = coil.request.ImageRequest.Builder(ctx)
                        .data(TvImagePolicy.artworkUrl(
                            nextBackdrop,
                            backdropWidthPx,
                            if (nextMovie.backdropUrl.isNotBlank()) TvArtworkKind.BACKDROP else TvArtworkKind.POSTER
                        ))
                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                        .allowRgb565(true)
                        .size(1, 1)
                        .precision(coil.size.Precision.EXACT)
                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                    coil.Coil.imageLoader(ctx).execute(prefetchReq)
                }
                viewModel?.fetchLogoForMovie(nextMovie)
            }
        }
    }

    val artworkKind = if (currentMovie.backdropUrl.isNotBlank()) TvArtworkKind.BACKDROP else TvArtworkKind.POSTER
    val heroRequest = remember(ctx, backdropUrl, artworkKind, backdropWidthPx, backdropHeightPx) {
        coil.request.ImageRequest.Builder(ctx)
            .data(TvImagePolicy.artworkUrl(backdropUrl, backdropWidthPx, artworkKind))
            .size(backdropWidthPx, backdropHeightPx)
            .precision(coil.size.Precision.EXACT)
            .scale(coil.size.Scale.FILL)
            .crossfade(false)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }

    val cardShape = remember { RoundedCornerShape(14.dp) }
    val contentWidth = (configuration.screenWidthDp.dp * 0.38f).coerceIn(260.dp, 430.dp)

    Box(
        modifier = modifier.fillMaxWidth().height(height)
            .graphicsLayer { this.alpha = alpha() }
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(cardShape)
                .drawBehind {
                    drawRect(lerp(ambientColorProvider?.invoke() ?: ambientColor, Color.Black, 0.22f))
                }
        ) {
            if (backdropUrl.isNotBlank()) {
                ReadyArtwork(
                    request = heroRequest,
                    contentDescription = currentMovie.title,
                    modifier = Modifier.fillMaxSize(),
                    animateReplacement = !isLowMemoryDevice,
                    onReady = { currentOnArtworkReady() }
                )
            }

            if (viewModel != null && preview.player != null) {
                HomePreviewSurface(
                    viewModel.homePreviewController, preview,
                    modifier = Modifier.fillMaxSize(),
                    placeholderRequest = heroRequest,
                    captionAlignment = Alignment.BottomEnd,
                    captionBottomInset = 20.dp,
                    captionHorizontalPadding = 24.dp
                )
            }

            // Natural scrims that darken with the ambient mood color rather than turning black,
            // blending the billboard seamlessly with the background and internal artwork colors.
            Box(
                Modifier.fillMaxSize().graphicsLayer { this.alpha = scrimAlpha.value }.drawWithCache {
                    var cachedMood = Color.Unspecified
                    var horizontal: Brush? = null
                    var vertical: Brush? = null
                    onDrawBehind {
                        // Ambient ticks redraw this scrim without recomposing Home
                        // or measuring its text. Reuse shaders while the tint is still.
                        val mood = lerp(ambientColorProvider?.invoke() ?: ambientColor, Color.Black, 0.36f)
                        if (mood != cachedMood) {
                            cachedMood = mood
                            horizontal = Brush.horizontalGradient(
                                0f to mood.copy(alpha = 0.68f),
                                0.30f to mood.copy(alpha = 0.38f),
                                0.54f to mood.copy(alpha = 0.12f),
                                0.72f to Color.Transparent,
                                endX = size.width
                            )
                            vertical = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.48f to Color.Transparent,
                                0.82f to mood.copy(alpha = 0.45f),
                                1f to mood.copy(alpha = 0.78f),
                                endY = size.height
                            )
                        }
                        horizontal?.let { drawRect(it) }
                        vertical?.let { drawRect(it) }
                    }
                }
            )

            Column(
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 18.dp, bottom = 20.dp).width(contentWidth)
            ) {
                // Title & metadata (e.g., Series • Kids • 2026) remain visible when D-pad is not on the billboard
                AnimatedVisibility(
                    visible = titleAndMetaVisible,
                    enter = fadeIn(tween(TvMotion.duration(260))),
                    exit = fadeOut(tween(TvMotion.duration(220)))
                ) {
                    Column {
                        BillboardTitleArtwork(currentMovie.title, billboardLogoUrl, logoWidthPx, logoHeightPx)
                        Spacer(Modifier.height(8.dp))
                        TitleMetadata(displayMovie)
                    }
                }

                // Story description only expands when D-pad is actively focused on the billboard
                AnimatedVisibility(
                    visible = storyVisible,
                    // A size animation remeasured the hero on every frame of the vertical glide.
                    enter = fadeIn(tween(TvMotion.duration(300))),
                    exit = fadeOut(tween(TvMotion.duration(220)))
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = currentMovie.description, color = Color.White,
                            fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }

                // Action buttons stay attached for deterministic D-pad focus, but only reveal when D-pad is on the billboard
                Row(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val revealFraction = buttonReveal.value
                            val topSpacerPx = (14.dp * revealFraction).roundToPx()
                            val placeable = measurable.measure(constraints)
                            val revealedHeight = ((placeable.height + topSpacerPx) * revealFraction).roundToInt()
                            layout(placeable.width, revealedHeight) {
                                placeable.placeRelativeWithLayer(0, topSpacerPx) {
                                    this.alpha = revealFraction
                                }
                            }
                        }
                        .then(if (isDpadOnBillboard) Modifier else Modifier.clearAndSetSemantics { }),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    val isLoggedIn = viewModel?.isUserLoggedIn() ?: true
                    val isLocked = viewModel?.isMovieLocked(currentMovie) ?: false
                    BillboardButton(
                        text = when { !isLoggedIn -> "Play Trailer"; isLocked -> "Unlock"; else -> "Play" },
                        icon = if (isLocked && isLoggedIn) Icons.Default.Lock else Icons.Default.PlayArrow,
                        isPrimary = true, focusRequester = playFocusRequester,
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel; right = moreInfoRequester },
                        onClick = { if (isLocked && isLoggedIn) onInfoClick() else onPlayClick() },
                        onFocusChange = { isPlayButtonFocused = it }, onFocused = onFocused
                    )
                    BillboardButton(
                        text = "More Info", icon = Icons.Default.Info,
                        isPrimary = false, focusRequester = moreInfoRequester,
                        modifier = Modifier.focusProperties {
                            left = playFocusRequester ?: FocusRequester.Default
                            right = FocusRequester.Cancel
                        },
                        onClick = onInfoClick,
                        onFocusChange = { isInfoButtonFocused = it }, onFocused = onFocused
                    )
                }
            }

            // Dynamic TMDB Callout Badges on Bottom Right before preview playback starts
            AnimatedVisibility(
                visible = titleAndMetaVisible && calloutBadges.isNotEmpty(),
                enter = fadeIn(tween(TvMotion.duration(260))),
                exit = fadeOut(tween(TvMotion.duration(220))),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp)
            ) {
                BillboardCalloutBadgesRow(
                    badges = calloutBadges,
                    darkenedMoodColor = lerp(ambientColorProvider?.invoke() ?: ambientColor, Color.Black, 0.36f)
                )
            }
        }
    }
}

@Composable
private fun BillboardTitleArtwork(title: String, logoUrl: String?, widthPx: Int, heightPx: Int) {
    val context = LocalContext.current
    var logoReady by remember(title, logoUrl) { mutableStateOf(false) }
    var logoAspectRatio by remember(title, logoUrl) { mutableStateOf<Float?>(null) }
    val request = remember(context, logoUrl, widthPx, heightPx) {
        logoUrl?.takeIf { it.isNotBlank() }?.let {
            coil.request.ImageRequest.Builder(context)
                .data(TvImagePolicy.artworkUrl(it, widthPx, TvArtworkKind.LOGO))
                .size(widthPx, heightPx)
                .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                .allowRgb565(false).crossfade(false).build()
        }
    }
    val density = LocalDensity.current
    val artworkWidth = with(density) { widthPx.toDp() }.coerceAtMost(340.dp)
    val maxArtworkHeight = with(density) { heightPx.toDp() }
    // Fit the actual logo ratio; short logos and text never reserve an empty 102dp panel.
    val fittedHeight = logoAspectRatio?.let { (artworkWidth / it).coerceAtMost(maxArtworkHeight) }
    Box(
        Modifier.width(artworkWidth).then(
            if (logoReady && fittedHeight != null) Modifier.height(fittedHeight) else Modifier.wrapContentHeight()),
        contentAlignment = Alignment.BottomStart
    ) {
        if (!logoReady) {
            Text(title, color = Color.White, fontSize = 34.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
        if (request != null) {
            AsyncImage(
                model = request, contentDescription = title, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    logoAspectRatio = drawable.intrinsicWidth.coerceAtLeast(1).toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
                    logoReady = true
                },
                onError = { logoReady = false; logoAspectRatio = null }
            )
        }
    }
}

@Composable
fun BillboardButton(
    text: String,
    icon: ImageVector,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = tween(TvMotion.duration(140), easing = LinearOutSlowInEasing),
        label = "billboardActionScale"
    )
    val containerColor = if (isPrimary) {
        Color(0xFFFFFFFF)
    } else {
        if (isFocused) Color(0xFFFFFFFF) else Color(0x6654565C)
    }
    val contentColor = if (isPrimary) {
        Color(0xFF000000)
    } else {
        if (isFocused) Color(0xFF000000) else Color(0xFFFFFFFF)
    }

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .border(2.dp, if (isFocused) Color.White else Color.Transparent, CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .height(44.dp)
                .width(if (isPrimary && text == "Play Trailer") 154.dp else if (isPrimary) 108.dp else 128.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusChange?.invoke(it.isFocused)
                    if (it.isFocused) onFocused()
                },
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = containerColor,
                focusedContainerColor = Color(0xFFFFFFFF)
            ),
            border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                if (isPrimary) Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = text,
                    color = contentColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}
