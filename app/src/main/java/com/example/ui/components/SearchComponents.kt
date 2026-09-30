@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.components


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.example.model.Profile
import com.example.ui.screens.handleTvDpadNavigation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.model.Movie
import com.example.ui.NetflixViewModel
import com.example.ui.components.NetflixMovieRow
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import com.example.ui.util.TvMotion
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.TvKeyPacer

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.R

import androidx.compose.ui.input.key.*
import android.view.KeyEvent
import com.example.ui.screens.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchSection(
    viewModel: NetflixViewModel,
    allMovies: List<Movie>,
    onPlayMovie: (Movie) -> Unit,
    onMovieClick: (Movie) -> Unit,
    firstFocusRequester: FocusRequester,
    navBarFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val apiSearchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val selectedGenreId by viewModel.selectedGenreId.collectAsStateWithLifecycle()
    
    val focusedRowIndexState = remember { mutableIntStateOf(0) }
    var focusedRowIndex by focusedRowIndexState
    var focusedCardIndex by remember { mutableIntStateOf(-1) }
    var isResultsFocused by remember { mutableStateOf(false) }
    val resultKeyPacer = remember { TvKeyPacer() }
    var artworkAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The keyboard and its focus target get the first frames before posters
        // begin decoding. The sidebar stays interactive throughout the reveal.
        withFrameNanos { }
        withFrameNanos { }
        kotlinx.coroutines.delay(180)
        artworkAllowed = true
    }

    val searchGenres = remember(genres) {
        genres.ifEmpty { 
            listOf(
                com.example.api.TmdbGenreDto(28, "Action"),
                com.example.api.TmdbGenreDto(12, "Adventure"),
                com.example.api.TmdbGenreDto(16, "Animation"),
                com.example.api.TmdbGenreDto(35, "Comedy"),
                com.example.api.TmdbGenreDto(99, "Documentary"),
                com.example.api.TmdbGenreDto(18, "Drama"),
                com.example.api.TmdbGenreDto(10751, "Family"),
                com.example.api.TmdbGenreDto(14, "Fantasy"),
                com.example.api.TmdbGenreDto(27, "Horror"),
                com.example.api.TmdbGenreDto(10749, "Romance"),
                com.example.api.TmdbGenreDto(878, "Science Fiction"),
                com.example.api.TmdbGenreDto(53, "Thriller")
            )
        }
    }

    val keyboardKeys = remember {
        listOf(
            "a", "b", "c", "d", "e", "f",
            "g", "h", "i", "j", "k", "l",
            "m", "n", "o", "p", "q", "r",
            "s", "t", "u", "v", "w", "x",
            "y", "z", "1", "2", "3", "4",
            "5", "6", "7", "8", "9", "0"
        )
    }

    // Memoize the shuffled fallback list independently so it does not
    // re-shuffle on every recomposition (e.g. Flow re-emit on profile switch
    // or continued-watching updates). The order is keyed only on allMovies.
    val shuffledFallback = remember(allMovies) { allMovies.take(16).shuffled() }

    // Use TMDB API results if we are actively searching or have a genre selected.
    // If both are empty/null, fall back to the memoized locally-mixed popular movies.
    val searchResults = remember(searchQuery, selectedGenreId, apiSearchResults, shuffledFallback) {
        if (searchQuery.isNotBlank() || selectedGenreId != null) {
            apiSearchResults
        } else {
            shuffledFallback
        }
    }

    val searchRows = remember(searchResults) { searchResults.chunked(4) }

    // Only reset focus if the previously focused card is no longer in bounds
    // (e.g. results went from 16 -> 0). Avoids teleporting focus back to row 0
    // on every keystroke that changes the result list.
    LaunchedEffect(searchResults) {
        if (focusedCardIndex >= searchResults.size) {
            focusedCardIndex = -1
            focusedRowIndex = 0
        }
    }

    val headerTitle = when {
        searchQuery.isNotBlank() -> "Search results for \"$searchQuery\""
        selectedGenreId != null -> searchGenres.find { it.id == selectedGenreId }?.name ?: "Genre results"
        else -> "Your search recommendations"
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Left Sidebar: Search Input + Keyboard + Genres
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Search Query Display Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222222), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = if (searchQuery.isNotEmpty() || selectedGenreId != null) Color.White else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) searchQuery else if (selectedGenreId != null) (searchGenres.find { it.id == selectedGenreId }?.name ?: "Genre") else "Search movies, TV shows...",
                        color = if (searchQuery.isNotEmpty() || selectedGenreId != null) Color.White else Color.Gray,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty() || selectedGenreId != null) {
                        Surface(
                            onClick = {
                                viewModel.onSearchQueryChanged("")
                            },
                            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Color.White.copy(alpha = 0.3f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(16.dp)
                            )
                        }
                    }
                }
            }

            // Keyboard
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Space & Backspace Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .handleTvDpadNavigation(
                            onDpadUp = {
                                try { navBarFocusRequester.requestFocus() } catch (e: Exception) {}
                                true
                            }
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Space Key
                    KeyboardKeyButton(
                        text = "",
                        customContent = {
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(5.dp)
                                    .background(Color.White.copy(alpha = 0.7f), CircleShape)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { left = FocusRequester.Cancel },
                        focusRequester = firstFocusRequester,
                        onClick = {
                            viewModel.onSearchQueryChanged(searchQuery + " ")
                        }
                    )

                    // Backspace Key
                    KeyboardKeyButton(
                        text = "",
                        icon = Icons.AutoMirrored.Default.Backspace,
                        modifier = Modifier.width(56.dp),
                        onClick = {
                            if (searchQuery.isNotEmpty()) {
                                viewModel.onSearchQueryChanged(searchQuery.dropLast(1))
                            }
                        }
                    )
                }

                // 6-column Grid of Keys
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(198.dp)
                ) {
                    itemsIndexed(keyboardKeys, key = { _, keyChar -> "key_$keyChar" }) { index, keyChar ->
                        KeyboardKeyButton(
                            text = keyChar,
                            modifier = if (index % 6 == 0) Modifier.focusProperties { left = FocusRequester.Cancel } else Modifier,
                            onClick = {
                                viewModel.onSearchQueryChanged(searchQuery + keyChar)
                            }
                        )
                    }
                }
            }

            // Genres Header
            Text(
                text = "GENRES & CATEGORIES",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Genres List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(searchGenres, key = { it.id }) { genre ->
                    val isGenreSelected = selectedGenreId == genre.id
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = {
                            viewModel.selectGenre(genre.id)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { left = FocusRequester.Cancel }
                            .onFocusChanged { isFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isGenreSelected) Color.White else Color.Transparent,
                            focusedContainerColor = Color.White
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = genre.name,
                                color = if (isFocused || isGenreSelected) Color.Black else Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                fontWeight = if (isFocused || isGenreSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Right Column: Search Results Grid
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = headerTitle,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isSearching) {
                    NetflixSpinner(
                        size = 28.dp
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onFocusChanged { isResultsFocused = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else {
                            val code = event.nativeKeyEvent.keyCode
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> !resultKeyPacer.accept(code)
                                else -> false
                            }
                        }
                    }
            ) {
                val totalWidth = maxWidth
                val gap = 8.dp
                val cardWidth = (totalWidth - (gap * 3)) / 4
                val cardHeight = cardWidth * 1.5f
                val rowSpacing = 8.dp
                val rowStep = cardHeight + rowSpacing
                val density = LocalDensity.current
                val rowStepPx = with(density) { rowStep.toPx() }
                val viewportHeightPx = with(density) { maxHeight.toPx() }

                val activeColIndex = if (focusedCardIndex >= 0) focusedCardIndex % 4 else 0

                val targetRingX = (activeColIndex * (cardWidth + gap).value).dp

                val animatedRingX = animateDpAsState(
                    targetValue = targetRingX,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(600f)),
                    label = "searchRingX"
                )

                val targetScrollY = (focusedRowIndex * rowStep.value).dp
                val animatedScrollY = animateDpAsState(
                    targetValue = targetScrollY,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(430f)),
                    label = "searchGridScroll"
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.Top, unbounded = true)
                            .graphicsLayer { translationY = -animatedScrollY.value.toPx() },
                        verticalArrangement = Arrangement.spacedBy(rowSpacing)
                    ) {
                        searchRows.forEachIndexed { rowIndex, rowMovies ->
                            key(rowIndex) {
                                SearchResultRow(
                                    rowIndex = rowIndex,
                                    movies = rowMovies,
                                    cardWidth = cardWidth,
                                    cardHeight = cardHeight,
                                    gap = gap,
                                    rowStepPx = rowStepPx,
                                    viewportHeightPx = viewportHeightPx,
                                    scrollY = animatedScrollY,
                                    focusedRow = focusedRowIndexState,
                                    artworkAllowed = artworkAllowed,
                                    sidebarFocusRequester = firstFocusRequester,
                                    onMovieClick = onMovieClick,
                                    onFocused = { globalIndex ->
                                        focusedCardIndex = globalIndex
                                        focusedRowIndex = rowIndex
                                    }
                                )
                            }
                        }
                    }

                    // Smooth Sliding White Selection Ring — fits exactly over card without expanding
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    animatedRingX.value.roundToPx(),
                                    0
                                )
                            }
                            .width(cardWidth)
                            .height(cardHeight)
                            .graphicsLayer {
                                alpha = if (isResultsFocused && focusedCardIndex >= 0) 1f else 0f
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(12.dp),
                                spotColor = Color.White.copy(alpha = 0.5f),
                                ambientColor = Color.White.copy(alpha = 0.25f)
                            )
                            .border(
                                BorderStroke(3.dp, Color.White),
                                RoundedCornerShape(12.dp)
                            )
                    )
                }
            }
        }
    }
}

/** Keep only visible rows and the next remote targets alive, at their fixed size. */
@Composable
private fun SearchResultRow(
    rowIndex: Int,
    movies: List<Movie>,
    cardWidth: Dp,
    cardHeight: Dp,
    gap: Dp,
    rowStepPx: Float,
    viewportHeightPx: Float,
    scrollY: State<Dp>,
    focusedRow: State<Int>,
    artworkAllowed: Boolean,
    sidebarFocusRequester: FocusRequester,
    onMovieClick: (Movie) -> Unit,
    onFocused: (Int) -> Unit
) {
    val density = LocalDensity.current
    val visible by remember(rowIndex, rowStepPx, viewportHeightPx, scrollY, focusedRow, density) {
        derivedStateOf {
            val top = rowIndex * rowStepPx - with(density) { scrollY.value.toPx() }
            rowIndex in (focusedRow.value - 1)..(focusedRow.value + 1) ||
                (top < viewportHeightPx && top + rowStepPx > 0f)
        }
    }
    if (!visible) {
        Spacer(Modifier.fillMaxWidth().height(cardHeight))
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        movies.forEachIndexed { column, movie ->
            key(column) {
                SearchResultMovieCard(
                    movie = movie,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    artworkAllowed = artworkAllowed,
                    onClick = { onMovieClick(movie) },
                    onFocused = { onFocused(rowIndex * 4 + column) },
                    modifier = when (column) {
                        0 -> Modifier.focusProperties { left = sidebarFocusRequester }
                        movies.lastIndex -> Modifier.focusProperties { right = FocusRequester.Cancel }
                        else -> Modifier
                    }
                )
            }
        }
        repeat(4 - movies.size) { Spacer(Modifier.width(cardWidth)) }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeyboardKeyButton(
    text: String,
    icon: ImageVector? = null,
    customContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(28.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2A2A2A),
            focusedContainerColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (customContent != null) {
                customContent()
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused) Color.Black else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = text,
                    color = if (isFocused) Color.Black else Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchResultMovieCard(
    movie: Movie,
    cardWidth: Dp,
    cardHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    artworkAllowed: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }

    // perf: hoist context + density reads once per card. The card is recomposed
    // on every focus change; reading these at the top keeps the rebuild cheap.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1E1E1E),
            focusedContainerColor = Color(0xFF1E1E1E)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // perf: search grid cards are 4-up in a box whose width is (totalWidth - 3*gap)/4.
            // Bound the decode to that exact size (we know cardWidth / cardHeight at
            // composition time). Also wrap in remember to avoid per-recomposition
            // request rebuilds on every focus or genre change.
            val imageUrl = movie.posterUrl.ifEmpty { movie.backdropUrl }
            // perf: convert dp to px once for the size constraint; Coil needs raw px.
            val widthPx = with(density) { cardWidth.roundToPx().coerceIn(1, 320) }
            val heightPx = with(density) { cardHeight.roundToPx().coerceIn(1, 480) }
            val cardImageReq = remember(imageUrl, widthPx, heightPx) {
                coil.request.ImageRequest.Builder(ctx)
                    .data(TvImagePolicy.artworkUrl(imageUrl, widthPx, if (movie.posterUrl.isNotBlank()) TvArtworkKind.POSTER else TvArtworkKind.BACKDROP))
                    .crossfade(false)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .allowRgb565(true)
                    .size(widthPx, heightPx)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = if (artworkAllowed) cardImageReq else null,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Netflix 'N' Badge
            Image(
                painter = painterResource(id = R.drawable.ic_netflix_n),
                contentDescription = "Netflix N Logo",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .height(18.dp)
                    .width(10.dp)
            )

            // Bottom Gradient Overlay when focused — smooth fade in without expanding bounds
            val titleAlpha = animateFloatAsState(
                targetValue = if (isFocused) 1f else 0f,
                animationSpec = tween(durationMillis = TvMotion.duration(180)),
                label = "searchTitleAlpha"
            )
            val showTitle by remember(titleAlpha) {
                derivedStateOf { titleAlpha.value > 0.01f }
            }
            if (showTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = titleAlpha.value }
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
