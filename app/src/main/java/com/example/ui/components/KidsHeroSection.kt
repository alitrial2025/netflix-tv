package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.KidsCharacterArtwork
import com.example.data.KidsCharacterCatalog
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isSeriesContent
import com.example.ui.NetflixViewModel
import com.example.ui.util.HomeStartupGate
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvKeyPacer
import com.example.ui.util.TvMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun KidsHeroSection(
    featuredMovies: List<Movie>,
    activeMovie: Movie,
    onMovieSelected: (Movie) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onMovieClick: (Movie) -> Unit,
    focusRequester: FocusRequester,
    onDpadUp: () -> Boolean,
    onDpadDown: () -> Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 340.dp,
    viewModel: NetflixViewModel? = null,
    onFocused: () -> Unit = {}
) {
    val effectiveMovies = remember(featuredMovies) {
        featuredMovies.distinctBy { "${it.catalogMediaKind()}:${it.id}" }.take(5)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val cardWidth = (configuration.screenWidthDp.dp * 0.12f).coerceIn(96.dp, 150.dp)
    val cardHeight = cardWidth * (9f / 16f)
    val characterHeight = cardHeight * 1.65f
    val logoSize = with(density) { 230.dp.roundToPx() to 96.dp.roundToPx() }
    val cardSize = with(density) { cardWidth.roundToPx() to cardHeight.roundToPx() }
    val characterSize = with(density) { cardWidth.roundToPx() to characterHeight.roundToPx() }
    val resolvedLogos = remember(effectiveMovies) { mutableStateMapOf<String, String>() }
    val resolvedCharacters = remember(effectiveMovies) { mutableStateMapOf<String, String>() }
    var characterCatalog by remember { mutableStateOf(KidsCharacterCatalog.EMPTY) }
    var stageFocused by remember { mutableStateOf(false) }
    val featuredIdentities = effectiveMovies.map { "${it.catalogMediaKind()}:${it.id}" }
    var focusedIndex by remember(featuredIdentities) {
        mutableIntStateOf(effectiveMovies.indexOfFirst {
            it.id == activeMovie.id && it.catalogMediaKind() == activeMovie.catalogMediaKind()
        }.coerceAtLeast(0))
    }
    val itemFocusRequesters = remember(effectiveMovies.size, focusRequester) {
        List(effectiveMovies.size) { index -> if (index == 0) focusRequester else FocusRequester() }
    }
    val keyPacer = remember { TvKeyPacer() }

    LaunchedEffect(context) { characterCatalog = KidsCharacterArtwork.load(context) }
    LaunchedEffect(characterCatalog, effectiveMovies, viewModel) {
        if (viewModel != null) for (movie in effectiveMovies) {
            if (!characterCatalog.needsImdbLookup(movie)) continue
            HomeStartupGate.awaitIdle()
            val imdbId = viewModel.fetchImdbId(movie) ?: continue
            characterCatalog.urlFor(movie, imdbId)?.let {
                resolvedCharacters["${movie.catalogMediaKind()}:${movie.id}"] = it
            }
        }
    }
    // Resolve the active title first; enrich the other four only while browsing is idle.
    LaunchedEffect(activeMovie, effectiveMovies, viewModel) {
        for (movie in (listOf(activeMovie) + effectiveMovies).distinctBy { "${it.catalogMediaKind()}:${it.id}" }) {
            val key = "${movie.catalogMediaKind()}:${movie.id}"
            if (resolvedLogos[key] != null) continue
            val cached = movie.logoUrl?.takeIf { it.isNotBlank() } ?: viewModel?.getCachedLogo(movie.id)
            val logo = cached ?: run {
                HomeStartupGate.awaitBrowsingIdle()
                withContext(Dispatchers.IO) { viewModel?.fetchLogoUrl(movie.id, movie.isSeriesContent()) }
            }
            if (!logo.isNullOrBlank()) resolvedLogos[key] = logo
        }
    }

    val heroLogo = activeMovie.logoUrl?.takeIf { it.isNotBlank() }
        ?: resolvedLogos["${activeMovie.catalogMediaKind()}:${activeMovie.id}"]
        ?: viewModel?.getCachedLogo(activeMovie.id)
    Box(modifier.fillMaxWidth().height(height).onFocusChanged {
        stageFocused = it.hasFocus
        if (it.hasFocus) onFocused()
    }) {
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 22.dp)
                .width((configuration.screenWidthDp.dp * 0.37f).coerceIn(240.dp, 420.dp))
        ) {
            KidsTitleLogo(activeMovie.title, heroLogo, logoSize.first, logoSize.second,
                Modifier.width(230.dp).height(96.dp))
            Spacer(Modifier.height(8.dp))
            TitleMetadata(activeMovie, isKids = true, showKind = false)
            Spacer(Modifier.height(8.dp))
            Text(activeMovie.description, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(58.dp))
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            effectiveMovies.forEachIndexed { index, movie ->
                key(movie.catalogMediaKind(), movie.id) {
                    val isFocused = stageFocused && focusedIndex == index
                    val scale = animateFloatAsState(if (isFocused) 1.035f else 1f,
                        tween(TvMotion.duration(180), easing = FastOutSlowInEasing), label = "kidsTitleFocus")
                    val key = "${movie.catalogMediaKind()}:${movie.id}"
                    val character = characterCatalog.urlFor(movie) ?: resolvedCharacters[key]
                    val logo = movie.logoUrl?.takeIf { it.isNotBlank() } ?: resolvedLogos[key]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(cardWidth).height(cardHeight + characterHeight - 10.dp)
                            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
                            // This slot accepts a genuine transparent cutout. A poster is never
                            // substituted for a character, and alpha is kept in its decode.
                            if (character != null) {
                                val characterRequest = remember(character, characterSize, context) {
                                    ImageRequest.Builder(context).data(character)
                                        .size(characterSize.first, characterSize.second)
                                        .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                                        .allowRgb565(false).crossfade(false).build()
                                }
                                AsyncImage(characterRequest, contentDescription = null,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .offset(y = -(cardHeight - 10.dp)).width(cardWidth)
                                        .height(characterHeight).zIndex(2f), contentScale = ContentScale.Fit,
                                    alignment = Alignment.BottomCenter)
                            }
                            Surface(
                                onClick = { onPlayMovie(movie) },
                                modifier = Modifier.align(Alignment.BottomCenter).width(cardWidth).height(cardHeight)
                                    .focusRequester(itemFocusRequesters[index])
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focusedIndex = index
                                            onMovieSelected(movie)
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) false else when (event.nativeKeyEvent.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_UP -> onDpadUp()
                                            KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDown()
                                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                val direction = if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                                if (keyPacer.accept(direction, repeatCount = event.nativeKeyEvent.repeatCount)) {
                                                    val next = (index + direction).coerceIn(0, effectiveMovies.lastIndex)
                                                    runCatching { itemFocusRequesters[next].requestFocus() }
                                                }
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                                if (event.nativeKeyEvent.repeatCount == 0) onPlayMovie(movie)
                                                true
                                            }
                                            KeyEvent.KEYCODE_MENU -> { onMovieClick(movie); true }
                                            else -> false
                                        }
                                    },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF172232),
                                    focusedContainerColor = Color(0xFF172232)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                val artwork = movie.backdropUrl.ifBlank { movie.posterUrl }
                                val request = remember(artwork, cardSize, context) {
                                    ImageRequest.Builder(context)
                                        .data(TvImagePolicy.artworkUrl(artwork, cardSize.first,
                                            if (movie.backdropUrl.isNotBlank()) TvArtworkKind.BACKDROP else TvArtworkKind.POSTER))
                                        .size(cardSize.first, cardSize.second)
                                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                        .allowRgb565(true).crossfade(false).build()
                                }
                                Box(Modifier.fillMaxSize()) {
                                    AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.35f)))))
                                    KidsTitleLogo(movie.title, logo, cardSize.first, cardSize.second,
                                        Modifier.fillMaxSize().padding(8.dp), compact = true)
                                }
                            }
                        }
                        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                            if (isFocused && movie.releaseDateBadge != null) {
                                Text(movie.releaseDateBadge, color = Color.White, fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KidsTitleLogo(
    title: String, url: String?, widthPx: Int, heightPx: Int,
    modifier: Modifier = Modifier, compact: Boolean = false
) {
    val context = LocalContext.current
    var ready by remember(title, url) { mutableStateOf(false) }
    val request = remember(url, widthPx, heightPx, context) {
        url?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context).data(TvImagePolicy.artworkUrl(it, widthPx, TvArtworkKind.LOGO))
                .size(widthPx, heightPx).bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                .allowRgb565(false).crossfade(false).build()
        }
    }
    Box(modifier, contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart) {
        if (!ready) Text(title, color = Color.White, fontSize = if (compact) 12.sp else 32.sp,
            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start)
        if (request != null) AsyncImage(request, title, Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit, alignment = if (compact) Alignment.Center else Alignment.CenterStart,
            onSuccess = { ready = true }, onError = { ready = false })
    }
}
