@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.screens

import com.example.ui.components.NetflixProLogoGeometry

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.ui.components.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.example.model.Movie
import com.example.ui.NetflixViewModel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MovieExtraInfo(
    val studioTag: String,
    val genre: String,
    val ratingBadge: String,
    val triviaText: String,
    val reviewText: String,
    val reviewAuthor: String,
    val cast: String,
    val director: String,
    val writers: String,
    val moods: String
)

fun getMovieExtraInfo(movie: Movie): MovieExtraInfo {
    return when (movie.id) {
        "despicable_me_4" -> MovieExtraInfo(
            studioTag = "Illumination",
            genre = "Kids & Family • Animated Comedy • Adventure",
            ratingBadge = "PG",
            triviaText = "Gru's Mega Minions are inspired by classic comic book superheroes with wild superpowers!",
            reviewText = "Hilarious slapstick humor and delightful Minion chaos that kids will adore.",
            reviewAuthor = "Common Sense Media",
            cast = "Steve Carell, Kristen Wiig, Will Ferrell, Sofia Vergara",
            director = "Chris Renaud",
            writers = "Mike White, Ken Daurio",
            moods = "Hilarious, Cheerful, Action-Packed"
        )
        "inside_out_2" -> MovieExtraInfo(
            studioTag = "Disney • Pixar",
            genre = "Kids & Family • Animated Fantasy • Comedy",
            ratingBadge = "PG",
            triviaText = "Anxiety's wild orange hair was specially designed by Pixar artists to convey her lightning-fast energy.",
            reviewText = "A heartfelt, emotional triumph that connects brilliantly with kids and parents.",
            reviewAuthor = "Kids First!",
            cast = "Amy Poehler, Maya Hawke, Phyllis Smith, Lewis Black",
            director = "Kelsey Mann",
            writers = "Meg LeFauve, Dave Holstein",
            moods = "Heartwarming, Feel-Good, Imaginative"
        )
        "pokemon_horizons" -> MovieExtraInfo(
            studioTag = "The Pokémon Company",
            genre = "Kids TV • Animated Series • Adventure",
            ratingBadge = "TV-Y7",
            triviaText = "Liko's mysterious pendant and Roy's ancient Poké Ball hold ancient secrets of the Legendary Pokémon!",
            reviewText = "An exciting fresh chapter in the Pokémon world with charming heroes and amazing battles.",
            reviewAuthor = "Anime News Network",
            cast = "Minori Suzuki, Yuka Terasaki, Taku Yashiro, Ikue Otani",
            director = "Saori Den",
            writers = "Dai Sato",
            moods = "Exciting, Magical, Adventure"
        )
        "one_piece_kids" -> MovieExtraInfo(
            studioTag = "Toei Animation",
            genre = "Kids TV • Animated Series • Pirate Adventure",
            ratingBadge = "12",
            triviaText = "Luffy's dream is to find the legendary One Piece treasure and become King of the Pirates!",
            reviewText = "An unforgettable journey of friendship, courage, and boundless adventure across the seas.",
            reviewAuthor = "IGN Kids",
            cast = "Mayumi Tanaka, Akemi Okamura, Kazuya Nakai, Kappei Yamaguchi",
            director = "Konosuke Uda",
            writers = "Eiichiro Oda",
            moods = "Epic, Heroic, Fun"
        )
        "jujutsu_kaisen" -> MovieExtraInfo(
            studioTag = "Mappa Studios",
            genre = "Anime • Dark Fantasy",
            ratingBadge = "18",
            triviaText = "Want to know the secrets of the upcoming Culling Game and cursed spirits?",
            reviewText = "Breathtaking animation and intense supernatural battles!",
            reviewAuthor = "Anime News Network",
            cast = "Junya Enoki, Yuma Uchida, Asami Seto, Yuichi Nakamura",
            director = "Sunghoo Park",
            writers = "Gege Akutami",
            moods = "Action-Packed, Dark, Supernatural"
        )
        "101" -> MovieExtraInfo(
            studioTag = "Netflix Original",
            genre = "Sci-Fi • Horror • Drama",
            ratingBadge = "16",
            triviaText = "Curious about how they designed the horrifying Demogorgon from the Upside Down?",
            reviewText = "A loving, nostalgic tribute to Spielberg and 80s genre films.",
            reviewAuthor = "IGN",
            cast = "Winona Ryder, David Harbour, Millie Bobby Brown, Finn Wolfhard",
            director = "The Duffer Brothers",
            writers = "Matt Duffer, Ross Duffer",
            moods = "Suspenseful, Nostalgic, Sci-Fi"
        )
        "102" -> MovieExtraInfo(
            studioTag = "Legendary Pictures",
            genre = "Sci-Fi • Adventure • Epic",
            ratingBadge = "16",
            triviaText = "Curious about the constructed language of the Fremen and sandworm visual effects?",
            reviewText = "An absolute masterpiece of modern science fiction cinema.",
            reviewAuthor = "Variety",
            cast = "Timothée Chalamet, Zendaya, Rebecca Ferguson, Josh Brolin",
            director = "Denis Villeneuve",
            writers = "Denis Villeneuve, Jon Spaihts",
            moods = "Epic, Immersive, Mind-Bending"
        )
        "103" -> MovieExtraInfo(
            studioTag = "Riot Games",
            genre = "Action • Sci-Fi • Animation",
            ratingBadge = "18",
            triviaText = "Discover the hidden League of Legends lore and Easter eggs inside Piltover & Zaun.",
            reviewText = "Visually spectacular and emotionally devastating storytelling.",
            reviewAuthor = "Collider",
            cast = "Hailee Steinfeld, Ella Purnell, Kevin Alejandro, Reed Shannon",
            director = "Pascal Charrue, Arnaud Delord",
            writers = "Christian Linke, Alex Yee",
            moods = "Emotional, Steampunk, Thrilling"
        )
        "104" -> MovieExtraInfo(
            studioTag = "Universal Pictures",
            genre = "Biography • Drama • History",
            ratingBadge = "18",
            triviaText = "Did you know Nolan recreated the Trinity nuclear test entirely without CGI effects?",
            reviewText = "Cillian Murphy delivers the towering performance of a lifetime.",
            reviewAuthor = "The Guardian",
            cast = "Cillian Murphy, Emily Blunt, Matt Damon, Robert Downey Jr.",
            director = "Christopher Nolan",
            writers = "Christopher Nolan",
            moods = "Intense, Historical, Intellectual"
        )
        else -> {
            val isKid = com.example.model.isKidSafeMovie(movie)
            val isSeries = movie.type.equals("Series", ignoreCase = true) || movie.duration.contains("Season", ignoreCase = true)

            if (isKid) {
                if (isSeries) {
                    MovieExtraInfo(
                        studioTag = "Netflix Kids",
                        genre = "Kids TV • Animated Series • Adventure",
                        ratingBadge = movie.rating.ifBlank { "TV-Y7" },
                        triviaText = "Discover fun facts and secret adventures from the world of ${movie.title}!",
                        reviewText = "A colorful, exciting series packed with laughs and teamwork for kids!",
                        reviewAuthor = "Kids First!",
                        cast = "Voice Cast, Animated Characters",
                        director = "Animation Director",
                        writers = "Series Writers",
                        moods = "Cheerful, Exciting, Fun"
                    )
                } else {
                    MovieExtraInfo(
                        studioTag = if (movie.type == "Animation") "Animation Studio" else "Family Cinema",
                        genre = "Kids & Family • Animated Film • Adventure",
                        ratingBadge = movie.rating.ifBlank { "PG" },
                        triviaText = "Want to discover behind-the-scenes secrets of how ${movie.title} was animated?",
                        reviewText = "A heartwarming, beautifully animated film that the whole family will love.",
                        reviewAuthor = "Common Sense Media",
                        cast = "Voice Cast, Lead Characters",
                        director = "Feature Director",
                        writers = "Screenplay Writers",
                        moods = "Heartwarming, Magical, Fun"
                    )
                }
            } else if (movie.title.contains("Spider", ignoreCase = true) || movie.title.contains("Home", ignoreCase = true)) {
                MovieExtraInfo(
                    studioTag = "Marvel Studios",
                    genre = "Action • Adventure • Fantasy",
                    ratingBadge = "PG-13",
                    triviaText = "Want to know what happens after \"Avengers: Infinity War\"?",
                    reviewText = "Light, bright and wildly entertaining.",
                    reviewAuthor = "Chicago Sun-Times",
                    cast = "Tom Holland, Samuel L. Jackson, Zendaya, Cobie Smulders, Jon Favreau",
                    director = "Jon Watts",
                    writers = "Chris McKenna, Erik Sommers",
                    moods = "Exciting, Mind-Bending, Action-Packed"
                )
            } else {
                val fallbackGenres = movie.type.ifBlank { "Drama" }
                MovieExtraInfo(
                    studioTag = "Netflix Original",
                    genre = (if (isSeries) "TV Series" else "Feature Film") + " • " + fallbackGenres,
                    ratingBadge = movie.rating.ifBlank { "16" },
                    triviaText = "Want to explore exclusive behind-the-scenes diaries and cast interviews?",
                    reviewText = "A beautiful, gripping story that keeps you hooked from start to finish.",
                    reviewAuthor = "Rotten Tomatoes",
                    cast = "Lead Actor, Supporting Actor, Ensemble Cast",
                    director = "Featured Director",
                    writers = "Original Writers",
                    moods = "Captivating, Entertaining, Polished"
                )
            }
        }
    }
}

/** Compatibility entry point; all details previews use the same owner and startup policy. */
@Composable
fun DetailsScreen(
    movie: Movie,
    onBack: () -> Unit,
    onPlayMovie: (Movie, Int, Int, String) -> Unit,
    onNavigateToDetails: (Movie) -> Unit,
    viewModel: NetflixViewModel
) = com.example.ui.screens.details.DetailsScreen(
    movie = movie,
    onBack = onBack,
    onPlayMovie = onPlayMovie,
    onPlayTrailer = onPlayMovie,
    onNavigateToDetails = onNavigateToDetails,
    viewModel = viewModel
)

@Composable
fun DetailsHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_netflix_n),
            contentDescription = "NetflixPro logo",
            // bugfix: the source PNG is not 24x40. Without an explicit contentScale
            // the painter is cropped/distorted unpredictably across devices.
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(40.dp).width(40.dp * NetflixProLogoGeometry.MarkAspectRatio)
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
    continueWatchingData: com.example.data.ContinueWatchingEntity?,
    continueWatchingList: List<com.example.data.ContinueWatchingEntity>,
    currentSeason: Int,
    currentEpisode: Int,
    isMyListAdded: Boolean,
    isLiked: Boolean,
    detailsLogoAlpha: Float,
    detailsLogoOffsetY: Float,
    detailsSecondRowAlpha: Float,
    detailsSecondRowOffsetY: Float,
    detailsDescAlpha: Float,
    detailsDescOffsetY: Float,
    detailsActionsAlpha: Float,
    detailsActionsOffsetY: Float,
    playButtonRequester: FocusRequester,
    myListButtonRequester: FocusRequester,
    likeButtonRequester: FocusRequester,
    removeButtonRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    exoPlayer: ExoPlayer,
    viewModel: NetflixViewModel,
    onPlayClick: (Int, Int, String) -> Unit,
    onClearProgress: () -> Unit,
    onShowUpgradeModal: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = detailsLogoAlpha
                translationY = detailsLogoOffsetY
            }
        ) {
            if (extraInfo.studioTag.equals("Netflix Original", ignoreCase = true)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_netflix_n),
                        contentDescription = "Npro logo",
                        // bugfix: same as the header logo — explicit contentScale avoids
                        // unexpected stretching of the small N glyph.
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(17.dp)
                            .width(17.dp * NetflixProLogoGeometry.MarkAspectRatio)
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
                        fontSize = 14.sp, // a11y: bump to 14sp TV floor
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
                        fontSize = 10.sp, // a11y: bumped 8.5→10sp; still a badge but legible at couch distance
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!currentDetailsLogoUrl.isNullOrBlank()) {
                // perf: remember the ImageRequest + URL so Coil doesn't rebuild it on recomposition.
                // perf: pre-size the request to logo bounding box so Coil downsamples at decode.
                val logoCtx = LocalContext.current
                val logoDensity = LocalDensity.current
                val logoRequest = remember(logoCtx, currentDetailsLogoUrl, logoDensity) {
                    val widthPx = with(logoDensity) { 320.dp.toPx().toInt() }
                    val heightPx = with(logoDensity) { 60.dp.toPx().toInt() }
                    ImageRequest.Builder(logoCtx)
                        .data(currentDetailsLogoUrl)
                        .size(widthPx, heightPx)
                        .crossfade(true)
                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
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
                alpha = detailsSecondRowAlpha
                translationY = detailsSecondRowOffsetY
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
                fontSize = 14.sp, // a11y: bump to 14sp TV floor
                fontWeight = FontWeight.Bold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            Text(
                text = extraInfo.genre,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp, // a11y: bump to 14sp TV floor
                fontWeight = FontWeight.Medium
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            Text(
                text = movie.year.ifBlank { "2025" },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp, // a11y: bump to 14sp TV floor
                fontWeight = FontWeight.SemiBold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            val durationBadgeText = when {
                isTvSeries -> movie.duration.ifBlank { "1 Season" }
                else -> movie.duration.ifBlank { "1h 42m" }
            }
            Text(
                text = durationBadgeText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp, // a11y: bump to 14sp TV floor
                fontWeight = FontWeight.SemiBold
            )

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            val ratingBgColor = if (isKidContent) Color(0xFF22C55E) else Color(0xFFEAB308)
            Box(
                modifier = Modifier
                    .background(ratingBgColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = extraInfo.ratingBadge,
                    color = if (isKidContent) Color.White else Color.Black,
                    fontSize = 11.sp, // a11y: bumped 10→11sp; rating badge needs to be readable
                    fontWeight = FontWeight.Bold
                )
            }

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            Box(
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "AD)))",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp, // a11y: bumped 9→10sp; AD icon badge
                    fontWeight = FontWeight.Bold
                )
            }

            Text(text = "•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) // a11y: bump dot size

            Box(
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "CC",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp, // a11y: bumped 9→10sp; CC icon badge
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val savedProgress = continueWatchingData?.takeIf {
            it.playbackPositionMs > 0L && it.durationMs > 0L
        }
        // bugfix: keep this derived flag for the play-button label that runs later
        // in this composable. Avoids recomputing the `?.` chain a second time.
        val hasSavedProgress = savedProgress != null

        Column(
            modifier = Modifier.graphicsLayer {
                alpha = detailsDescAlpha
                translationY = detailsDescOffsetY
            }
        ) {
            if (movie.description.isNotBlank()) {
                Text(
                    text = movie.description,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 14.sp, // a11y: bump to 14sp TV floor
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
                // bugfix: previously used `continueWatchingData!!` which relied on
                // a smart-cast through a complex boolean. Snapshot to a local non-null
                // value so we never NPE if `continueWatchingData` flips to null
                // between the guard and the read (e.g. when onClearProgress() fires).
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
                            fontSize = 14.sp, // a11y: bump to 14sp TV floor
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${remainingMin}m remaining",
                            color = Color.LightGray,
                            fontSize = 13.sp, // a11y: bumped 10.5→13sp; secondary but must be readable
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
                    alpha = detailsActionsAlpha
                    translationY = detailsActionsOffsetY
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
            // bugfix: smart-cast won't work on a `var` mutable property read through `?.`.
            // Capture into a local non-null so we never NPE between the check and the read.
            val cw = continueWatchingData
            val playEpisode = if (isTvSeries && cw != null && cw.season == currentSeason) {
                cw.episode
            } else {
                currentEpisode
            }
            val playEpName = if (cw != null && cw.season == currentSeason) cw.episodeName else ""
            val isLoggedIn = viewModel.isUserLoggedIn()
            val isMovieLocked = viewModel.isMovieLocked(movie)
            val lockReason = viewModel.getLockReason(movie)
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
                    border = Border(BorderStroke(if (isPlayFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
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
                            modifier = Modifier.size(20.dp) // a11y: bumped 16→20dp icon
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_play),
                            contentDescription = playButtonText,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp) // a11y: bumped 16→20dp icon
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
                    onClick = { onPlayClick(playSeason, playEpisode, playEpName) },
                    modifier = Modifier.onFocusChanged { isTrailerFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF333333),
                        focusedContainerColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isTrailerFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
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
                            modifier = Modifier.size(18.dp) // a11y: bumped 14→18dp icon for visibility
                        )
                        Text(
                            text = "Play Trailer",
                            color = Color.White,
                            fontSize = 14.sp, // a11y: bump to 14sp TV floor
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            var isMyListFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { viewModel.toggleMyList(movie.id) },
                modifier = Modifier
                    .size(48.dp) // a11y: bump to 48dp TV focus target floor
                    .focusRequester(myListButtonRequester)
                    .onFocusChanged { isMyListFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isMyListAdded) NetflixRed.copy(alpha = 0.8f) else Color.Transparent,
                    focusedContainerColor = if (isMyListAdded) NetflixRed else Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isMyListFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // anim: my-list icon snapped white->black on focus. Animated
                    // so the icon transitions in time with the surface background.
                    val myListTint by animateColorAsState(
                        targetValue = if (isMyListFocused && !isMyListAdded) Color.Black else Color.White,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "myListIconTint"
                    )
                    Icon(
                        painter = painterResource(id = if (isMyListAdded) R.drawable.fa_check else R.drawable.fa_plus),
                        contentDescription = "My List",
                        tint = myListTint,
                        modifier = Modifier.size(20.dp) // a11y: bumped icon size for 48dp button
                    )
                }
            }

            var isLikeFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { viewModel.toggleLike(movie.id) },
                modifier = Modifier
                    .size(48.dp) // a11y: bump to 48dp TV focus target floor
                    .focusRequester(likeButtonRequester)
                    .onFocusChanged { isLikeFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isLiked) NetflixRed.copy(alpha = 0.3f) else Color.Transparent,
                    focusedContainerColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(if (isLikeFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // anim: like icon snapped between three colors (black/NetflixRed/white)
                    // on focus. Animated to match the focus-state cross-fade.
                    val likeTint by animateColorAsState(
                        targetValue = if (isLikeFocused) Color.Black else (if (isLiked) NetflixRed else Color.White),
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "likeIconTint"
                    )
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Like",
                        tint = likeTint,
                        modifier = Modifier.size(20.dp) // a11y: bumped icon size for 48dp button
                    )
                }
            }

            // perf: derivedStateOf only recomputes when its inputs change, not on
            // every recomposition that doesn't affect membership.
            val isCurrentlyInContinueWatching by remember(continueWatchingData, continueWatchingList, movie.id) {
                derivedStateOf { continueWatchingData != null || continueWatchingList.any { it.movieId == movie.id } }
            }
            if (isCurrentlyInContinueWatching) {
                var isRemoveFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        viewModel.deletePlaybackProgress(movie.id)
                        onClearProgress()
                        // bugfix: don't silently swallow focus failures
                        try { playButtonRequester.requestFocus() } catch (e: Exception) { android.util.Log.w("DetailsScreen", "playButtonRequester.requestFocus failed", e) }
                    },
                    modifier = Modifier
                        .size(48.dp) // a11y: bump to 48dp TV focus target floor
                        .focusRequester(removeButtonRequester)
                        .onFocusChanged { isRemoveFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NetflixRed
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isRemoveFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.fa_xmark),
                            contentDescription = "Remove from Continue Watching",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp) // a11y: bumped icon size for 48dp button
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
    calloutBadges: List<BillboardCalloutBadge> = emptyList()
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End
    ) {
        if (calloutBadges.isNotEmpty()) {
            BillboardCalloutBadgesRow(
                badges = calloutBadges,
                darkenedMoodColor = Color(0xFF10141E)
            )
        }
    }
}

@Composable
fun DetailsBottomTabsRow(
    isTvSeries: Boolean,
    detailsTabsAlpha: Float,
    detailsTabsOffsetX: Float,
    tabRowRequester: FocusRequester,
    playButtonRequester: FocusRequester,
    onTabClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .graphicsLayer {
                alpha = detailsTabsAlpha
                translationX = detailsTabsOffsetX
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
                    border = Border(BorderStroke(if (isTabFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))), // a11y: always-on focus state
                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    // anim: bottom-tabs text + chevron color snapped on focus.
                    // animateColorAsState makes the inversion match the surface
                    // color cross-fade so the label doesn't look out of sync.
                    val tabLabelColor by animateColorAsState(
                        targetValue = if (isTabFocused) Color.Black else Color.White.copy(alpha = 0.9f),
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
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

@Composable
fun DetailsModalOverlay(
    movie: Movie,
    isTvSeries: Boolean,
    activeTabName: String,
    onActiveTabChange: (String) -> Unit,
    onCloseModal: () -> Unit,
    episodesList: List<com.example.model.Episode>,
    currentSeason: Int,
    currentEpisode: Int,
    availableSeasons: List<Int>,
    continueWatchingData: com.example.data.ContinueWatchingEntity?,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (com.example.model.Episode) -> Unit,
    extraInfo: MovieExtraInfo,
    recommendations: List<Movie>,
    onNavigateToDetails: (Movie) -> Unit,
    exoPlayer: ExoPlayer,
    streamCaptions: List<com.example.data.Caption>,
    selectedSubLang: String,
    onSetSelectedSubLang: (String) -> Unit,
    onPlayMovie: (Movie, Int, Int, String) -> Unit,
    modalUpRequester: FocusRequester
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        val modalCtx = LocalContext.current
        val modalBackdropUrl = remember(movie.backdropUrl, movie.posterUrl) {
            movie.backdropUrl.ifBlank { movie.posterUrl }
        }
        val modalBackdropRequest = remember(modalCtx, modalBackdropUrl) {
            ImageRequest.Builder(modalCtx)
                .data(modalBackdropUrl)
                .crossfade(true)
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
                                androidx.compose.material3.CircularProgressIndicator(color = NetflixRed)
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
                                        .data(movie.logoUrl)
                                        .crossfade(true)
                                        .size(widthPx, heightPx)
                                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
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
                                            val isoCode = com.example.ui.screens.getIsoLanguageCode(opt)
                                            val iso3 = com.example.ui.screens.getIso3LanguageCode(opt)
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
                                            val isSelected = selectedSubLang.equals(opt, ignoreCase = true) ||
                                                    (opt != "Off" && selectedSubLang.contains(opt, ignoreCase = true)) ||
                                                    (selectedSubLang.equals("Off", ignoreCase = true) && opt == "Off")
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

                            val extras = remember {
                                listOf(
                                    "Official Trailer 1" to "2m 15s",
                                    "Behind the Scenes Featurette" to "4m 30s",
                                    "Teaser Trailer" to "1m 05s"
                                )
                            }

                            extras.forEach { (title, dur) ->
                                Surface(
                                    onClick = {
                                        exoPlayer.pause()
                                        onPlayMovie(movie, 1, 1, title)
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

@Composable
fun EpisodesRowSection(
    episodes: List<com.example.model.Episode>,
    currentSeason: Int,
    currentEpisodeNumber: Int,
    availableSeasons: List<Int> = listOf(1, 2, 3, 4),
    continueWatchingData: com.example.data.ContinueWatchingEntity?,
    onSeasonSelected: (Int) -> Unit = {},
    onEpisodeFocused: (com.example.model.Episode) -> Unit = {},
    onEpisodeClick: (com.example.model.Episode) -> Unit = {},
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false },
    episodesFocusRequester: FocusRequester? = null
) {
    com.example.ui.screens.details.EpisodesRowSection(
        episodes = episodes,
        currentSeason = currentSeason,
        currentEpisodeNumber = currentEpisodeNumber,
        availableSeasons = availableSeasons,
        continueWatchingData = continueWatchingData,
        onSeasonSelected = onSeasonSelected,
        onEpisodeFocused = onEpisodeFocused,
        onEpisodeClick = onEpisodeClick,
        onDpadUp = onDpadUp,
        onDpadDown = onDpadDown,
        episodesFocusRequester = episodesFocusRequester
    )
}

@Composable
fun EpisodeCardItem(
    episode: com.example.model.Episode,
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
        )

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

@Composable
fun UpgradePlanModal(
    currentPlanName: String,
    lockReason: String,
    onDismiss: () -> Unit,
    onUpgradeConfirm: (planId: String, planName: String) -> Unit,
    onWatchTrailer: () -> Unit
) = com.example.ui.screens.details.UpgradePlanModal(
    currentPlanName, lockReason, onDismiss, onUpgradeConfirm, onWatchTrailer
)
