package com.example.ui.screens.details

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
import com.example.data.Caption
import com.example.data.ContinueWatchingEntity
import com.example.model.Episode
import com.example.model.Movie
import com.example.ui.screens.player.getIso3LanguageCode
import com.example.ui.screens.player.getIsoLanguageCode
import com.example.ui.screens.player.resolveSelectedSubtitleOption
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed

@Composable
fun DetailsModalOverlay(
    movie: Movie,
    isTvSeries: Boolean,
    activeTabName: String,
    onActiveTabChange: (String) -> Unit,
    onCloseModal: () -> Unit,
    episodesList: List<Episode>,
    episodesLoading: Boolean,
    onRetryEpisodes: () -> Unit,
    currentSeason: Int,
    currentEpisode: Int,
    availableSeasons: List<Int>,
    continueWatchingData: ContinueWatchingEntity?,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    extraInfo: MovieExtraInfo,
    recommendations: List<Movie>,
    onNavigateToDetails: (Movie) -> Unit,
    exoPlayer: ExoPlayer,
    streamCaptions: List<Caption>,
    selectedSubLang: String,
    onSetSelectedSubLang: (String) -> Unit,
    onPlayTrailer: (Movie, Int, Int, String) -> Unit,
    modalUpRequester: FocusRequester
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        val modalCtx = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val backdropSize = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
            TvImagePolicy.backdropSize(with(density) { configuration.screenWidthDp.dp.roundToPx() },
                with(density) { configuration.screenHeightDp.dp.roundToPx() }, TvImagePolicy.isLowMemoryDevice(modalCtx))
        }
        val modalBackdropUrl = remember(movie.backdropUrl, movie.posterUrl) {
            movie.backdropUrl.ifBlank { movie.posterUrl }
        }
        val modalBackdropRequest = remember(modalCtx, modalBackdropUrl, backdropSize) {
            ImageRequest.Builder(modalCtx)
                .data(TvImagePolicy.artworkUrl(modalBackdropUrl, backdropSize.first, TvArtworkKind.BACKDROP))
                .size(backdropSize.first, backdropSize.second)
                .crossfade(false)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
        AsyncImage(
            model = modalBackdropRequest,
            contentDescription = movie.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.45f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF060608).copy(alpha = 0.96f),
                            0.20f to Color(0xFF08080C).copy(alpha = 0.88f),
                            0.65f to Color(0xFF08080C).copy(alpha = 0.88f),
                            1.00f to Color(0xFF050507).copy(alpha = 0.98f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFE50914).copy(alpha = 0.07f),
                            0.45f to Color.Transparent
                        ),
                        center = Offset(180f, 120f),
                        radius = 800f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                var isUpFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onCloseModal,
                    modifier = Modifier
                        .size(48.dp)
                        .focusRequester(modalUpRequester)
                        .onFocusChanged { isUpFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isUpFocused) Color.White else Color.White.copy(alpha = 0.15f),
                        focusedContainerColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isUpFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val upIconTint by animateColorAsState(
                            targetValue = if (isUpFocused) Color.Black else Color.White,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "upIconTint"
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.fa_chevron_up),
                            contentDescription = "Up / Back",
                            tint = upIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                val topTabs = remember(isTvSeries) {
                    if (isTvSeries) {
                        listOf("Episodes", "Details", "More like this", "Audio & Subtitles", "Previews & Extras")
                    } else {
                        listOf("Details", "More like this", "Audio & Subtitles", "Previews & Extras")
                    }
                }
                topTabs.forEach { tabName ->
                    var isTabFocused by remember { mutableStateOf(false) }
                    val isSelected = activeTabName == tabName

                    Surface(
                        onClick = { onActiveTabChange(tabName) },
                        modifier = Modifier.onFocusChanged { isTabFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Color.White else Color.Transparent,
                            focusedContainerColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(if (isTabFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                    ) {
                        val modalTabColor by animateColorAsState(
                            targetValue = if (isSelected) Color.Black else Color.White.copy(alpha = 0.8f),
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "modalTabColor"
                        )
                        Text(
                            text = tabName,
                            color = modalTabColor,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                key(activeTabName) {
                    when (activeTabName) {
                        "Episodes" -> {
                            if (episodesList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (episodesLoading) CircularProgressIndicator(color = NetflixRed)
                                    else Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("Episodes could not be loaded.", color = Color.White)
                                        Surface(onClick = onRetryEpisodes,
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = Color(0xFF333333), focusedContainerColor = Color.White)) {
                                            Text("Retry", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                                        }
                                    }
                                }
                            } else {
                                EpisodesRowSection(
                                    episodes = episodesList,
                                    currentSeason = currentSeason,
                                    currentEpisodeNumber = currentEpisode,
                                    availableSeasons = availableSeasons,
                                    continueWatchingData = continueWatchingData,
                                    onSeasonSelected = onSeasonSelected,
                                    onEpisodeClick = onEpisodeClick,
                                    onDpadUp = {
                                        try { modalUpRequester.requestFocus() } catch (e: Exception) { android.util.Log.w("DetailsScreen", "modalUpRequester.requestFocus failed", e) }
                                        true
                                    }
                                )
                            }
                        }

                        "Details" -> {
                            var subTabDetailsIndex by remember { mutableIntStateOf(0) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 750.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (!movie.logoUrl.isNullOrBlank()) {
                                    val modalLogoCtx = LocalContext.current
                                    val modalLogoDensity = LocalDensity.current
                                    val modalLogoRequest = remember(modalLogoCtx, movie.logoUrl, modalLogoDensity) {
                                        val widthPx = with(modalLogoDensity) { 250.dp.toPx().toInt() }
                                        val heightPx = with(modalLogoDensity) { 55.dp.toPx().toInt() }
                                        ImageRequest.Builder(modalLogoCtx)
                                            .data(TvImagePolicy.artworkUrl(movie.logoUrl, widthPx, TvArtworkKind.LOGO))
                                            .crossfade(true)
                                            .size(widthPx, heightPx)
                                            .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = modalLogoRequest,
                                        contentDescription = movie.title,
                                        modifier = Modifier
                                            .height(55.dp)
                                            .widthIn(max = 250.dp)
                                            .padding(bottom = 6.dp),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterStart
                                    )
                                } else {
                                    Text(
                                        text = movie.title.uppercase(),
                                        color = Color.White,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 2.sp
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val subTabs = remember { listOf("More Info", "Cast & Credits") }
                                    subTabs.forEachIndexed { subIdx, subName ->
                                        val isSubSelected = subTabDetailsIndex == subIdx
                                        var isSubTabFocused by remember { mutableStateOf(false) }
                                        Surface(
                                            onClick = { subTabDetailsIndex = subIdx },
                                            modifier = Modifier.onFocusChanged { isSubTabFocused = it.isFocused },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = if (isSubSelected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                                focusedContainerColor = Color.White.copy(alpha = 0.35f)
                                            ),
                                            border = ClickableSurfaceDefaults.border(
                                                border = Border(BorderStroke(if (isSubTabFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                                                focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                            )
                                        ) {
                                            Text(
                                                text = subName,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = if (isSubSelected) FontWeight.Bold else FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }

                                if (subTabDetailsIndex == 0) {
                                    Text(
                                        text = "${movie.type} • ${extraInfo.genre} • ${movie.year} • ${movie.duration} • AD))) CC",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        text = movie.description,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "“${extraInfo.reviewText}” — ${extraInfo.reviewAuthor}",
                                            color = Color.LightGray,
                                            fontSize = 13.sp,
                                            fontStyle = FontStyle.Italic
                                        )
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row {
                                            Text("Cast: ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(extraInfo.cast, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                        }
                                        Row {
                                            Text("Director: ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(extraInfo.director, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                        }
                                        Row {
                                            Text("Writers: ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(extraInfo.writers, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                        }
                                        Row {
                                            Text("Genres: ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(extraInfo.genre, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                        }
                                        Row {
                                            Text("Moods: ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(extraInfo.moods, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }

                        "More like this" -> {
                            var focusedRecItem by remember { mutableStateOf<Movie?>(null) }
                            val activeRec = focusedRecItem ?: recommendations.firstOrNull()

                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (activeRec != null) {
                                    val recExtraInfo = remember(activeRec.id) { getMovieExtraInfo(activeRec) }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = activeRec.title,
                                            color = Color.White,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${activeRec.type} • ${recExtraInfo.genre} • ${activeRec.year} • ${activeRec.duration} • ${activeRec.rating}",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = activeRec.description,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("You might also like", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                                    val lazyCtx = LocalContext.current
                                    val lazyDensity = LocalDensity.current
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(
                                            items = recommendations,
                                            key = { rec -> rec.id },
                                            contentType = { "RecCard" }
                                        ) { rec ->
                                            Surface(
                                                onClick = {
                                                    exoPlayer.pause()
                                                    onNavigateToDetails(rec)
                                                },
                                                modifier = Modifier
                                                    .width(130.dp)
                                                    .height(80.dp)
                                                    .onFocusChanged { if (it.isFocused) focusedRecItem = rec },
                                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                                                border = ClickableSurfaceDefaults.border(
                                                    focusedBorder = Border(BorderStroke(2.dp, Color.White))
                                                ),
                                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
                                            ) {
                                                val rowImgReq = remember(lazyCtx, rec.backdropUrl, rec.posterUrl, lazyDensity) {
                                                    val widthPx = with(lazyDensity) { 130.dp.toPx().toInt() }
                                                    val heightPx = with(lazyDensity) { 80.dp.toPx().toInt() }
                                                    ImageRequest.Builder(lazyCtx)
                                                        .data(rec.backdropUrl.ifBlank { rec.posterUrl })
                                                        .crossfade(true)
                                                        .size(widthPx, heightPx)
                                                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                                        .diskCachePolicy(CachePolicy.ENABLED)
                                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                                        .build()
                                                }
                                                AsyncImage(
                                                    model = rowImgReq,
                                                    contentDescription = rec.title,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "Audio & Subtitles" -> {
                            val audioOptions = remember {
                                listOf("English [Original]", "Spanish (España)", "French", "Japanese", "German")
                            }
                            var selAudio by remember { mutableIntStateOf(0) }

                            val subtitleOptions = remember(streamCaptions) {
                                listOf("Off") + streamCaptions.map { it.language }.distinct()
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(64.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Audio Languages", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    audioOptions.forEachIndexed { i, opt ->
                                        val isSelected = selAudio == i
                                        var isAudioFocused by remember { mutableStateOf(false) }
                                        Surface(
                                            onClick = {
                                                selAudio = i
                                                val isoCode = getIsoLanguageCode(opt)
                                                val iso3 = getIso3LanguageCode(opt)
                                                try {
                                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                        .buildUpon()
                                                        .setPreferredAudioLanguages(isoCode, iso3)
                                                        .build()
                                                } catch (_: Exception) {}
                                            },
                                            modifier = Modifier.onFocusChanged { isAudioFocused = it.isFocused },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                                focusedContainerColor = Color.White.copy(alpha = 0.35f)
                                            ),
                                            border = ClickableSurfaceDefaults.border(
                                                border = Border(BorderStroke(if (isAudioFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                                                focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(opt, color = Color.White, fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                if (isSelected) {
                                                    Icon(painter = painterResource(id = R.drawable.fa_check), contentDescription = null, tint = NetflixRed, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Subtitles (${subtitleOptions.size})", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    if (streamCaptions.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            Text("Captions load when streaming", color = Color.Gray.copy(alpha = 0.6f), fontSize = 14.sp)
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 280.dp)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            subtitleOptions.forEach { opt ->
                                                val isSelected = resolveSelectedSubtitleOption(subtitleOptions, selectedSubLang) == opt
                                                var isSubFocused by remember { mutableStateOf(false) }

                                                Surface(
                                                    onClick = { onSetSelectedSubLang(opt) },
                                                    modifier = Modifier.onFocusChanged { isSubFocused = it.isFocused },
                                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                                    colors = ClickableSurfaceDefaults.colors(
                                                        containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                                        focusedContainerColor = Color.White.copy(alpha = 0.35f)
                                                    ),
                                                    border = ClickableSurfaceDefaults.border(
                                                        border = Border(BorderStroke(if (isSubFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                                                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                                    )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(14.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(opt, color = Color.White, fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                        if (isSelected) {
                                                            Icon(painter = painterResource(id = R.drawable.fa_check), contentDescription = null, tint = NetflixRed, modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "Previews & Extras" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Trailers & Clips", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

                                val extras = remember { listOf("Official Trailer" to "Preview") }

                                extras.forEach { (title, dur) ->
                                    Surface(
                                        onClick = {
                                            onPlayTrailer(movie, 1, 1, title)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth(0.6f)
                                            .height(70.dp),
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color.White.copy(alpha = 0.1f),
                                            focusedContainerColor = Color.White.copy(alpha = 0.25f)
                                        ),
                                        border = ClickableSurfaceDefaults.border(
                                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                                        ),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(painter = painterResource(id = R.drawable.fa_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
    }
}
