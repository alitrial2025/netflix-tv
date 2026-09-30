@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.example.R
import com.example.model.Movie
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ambient Wallpaper / Screensaver for Netflix Pro.
 * Features:
 * - OLED-friendly power-saving dark ambient mode
 * - Active Window & PowerManager Wake Lock so TV OS does not switch to system screensaver
 * - Smooth 4K cinematic backdrop crossfade (Ken Burns slow drift)
 * - Burn-in safe dynamic floating clock & weather/date indicator
 * - Dismisses instantly on any user interaction or D-pad key press
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AmbientWallpaperScreen(
    movies: List<Movie>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 1. Manage WakeLock & KeepScreenOn while wallpaper is active
    DisposableEffect(Unit) {
        // Keep screen alive on TV window level
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Acquire CPU partial wake lock
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NetflixPro:AmbientWallpaperWakeLock"
        )
        try {
            wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours safety max
        } catch (_: Exception) {}

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    // perf: Back press should dismiss the ambient screen so the user can return
    // to whatever was on screen before the screensaver kicked in.
    BackHandler(enabled = true) { onDismiss() }

    // perf: Pause infinite animations + timer loops when the screen is in the
    // background. Without this, the Ken Burns + clock loops keep the CPU/GPU
    // active even after the user has left the app, draining battery.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // perf: log ambient screen entry so we can correlate battery drain with
    // how long the screensaver actually ran.
    LaunchedEffect(Unit) {
        android.util.Log.i("Ambient", "wallpaper shown")
    }

    // perf: cap decoded backdrop to the actual screen size so we never blow 16MB
    // of RAM decoding a 4K image for a 1080p TV. We take the *minimum* of the
    // measured screen size and a 1920x1080 ceiling — the screensaver never
    // benefits from decoding at 4K, and most TVs are 1080p.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val measuredWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val measuredHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val maxDecodeWidth = 1920
    val maxDecodeHeight = 1080
    val screenWidthPx = if (measuredWidthPx > 0) measuredWidthPx.coerceAtMost(maxDecodeWidth) else maxDecodeWidth
    val screenHeightPx = if (measuredHeightPx > 0) measuredHeightPx.coerceAtMost(maxDecodeHeight) else maxDecodeHeight

    // Wallpaper items source
    val wallpaperMovies = remember(movies) {
        movies.filter { it.backdropUrl.isNotBlank() }.ifEmpty { movies }.shuffled()
    }

    val tmdbRepo = remember { com.example.data.TmdbRepository() }

    var currentIndex by remember { mutableIntStateOf(0) }
    val currentMovie = wallpaperMovies.getOrNull(if (wallpaperMovies.isNotEmpty()) currentIndex % wallpaperMovies.size else 0)
    if (currentMovie == null) {
        Box(modifier = Modifier.fillMaxSize().background(NetflixBlack))
        return
    }

    // Dynamic TMDB Logo state
    var currentLogoUrl by remember(currentMovie.id) {
        mutableStateOf(currentMovie.logoUrl ?: tmdbRepo.getCachedLogo(currentMovie.id))
    }

    LaunchedEffect(currentMovie.id, isResumed) {
        if (!isResumed) return@LaunchedEffect
        if (currentLogoUrl.isNullOrBlank()) {
            val isTv = currentMovie.type.equals("Series", ignoreCase = true)
            val fetched = tmdbRepo.fetchLogoUrl(currentMovie.id.toLongOrNull() ?: 0L, isTv)
            if (!fetched.isNullOrBlank()) {
                currentLogoUrl = fetched
            }
        }
        // Prefetch next movie's logo for instant display
        val nextMovie = wallpaperMovies.getOrNull((currentIndex + 1) % wallpaperMovies.size)
        if (nextMovie != null && tmdbRepo.getCachedLogo(nextMovie.id).isNullOrBlank()) {
            val isTvNext = nextMovie.type.equals("Series", ignoreCase = true)
            tmdbRepo.fetchLogoUrl(nextMovie.id.toLongOrNull() ?: 0L, isTvNext)
        }
    }

    // Staggered sequence animation triggers on every movie change
    var isAnimationActive by remember(currentIndex) { mutableStateOf(false) }
    LaunchedEffect(currentIndex) {
        isAnimationActive = false
        delay(50L)
        isAnimationActive = true
    }

    // Stagger 1: Logo
    val logoAlpha by animateFloatAsState(
        targetValue = if (isAnimationActive) 1f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "logoAlpha"
    )
    val logoOffsetY by animateFloatAsState(
        targetValue = if (isAnimationActive) 0f else 28f,
        animationSpec = tween(durationMillis = 650, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "logoOffsetY"
    )

    // Stagger 2: Second row (Metadata / Badges)
    val secondRowAlpha by animateFloatAsState(
        targetValue = if (isAnimationActive) 1f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = 350, easing = FastOutSlowInEasing),
        label = "secondRowAlpha"
    )
    val secondRowOffsetY by animateFloatAsState(
        targetValue = if (isAnimationActive) 0f else 28f,
        animationSpec = tween(durationMillis = 650, delayMillis = 350, easing = FastOutSlowInEasing),
        label = "secondRowOffsetY"
    )

    // Stagger 3: Description
    val descAlpha by animateFloatAsState(
        targetValue = if (isAnimationActive) 1f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = 600, easing = FastOutSlowInEasing),
        label = "descAlpha"
    )
    val descOffsetY by animateFloatAsState(
        targetValue = if (isAnimationActive) 0f else 28f,
        animationSpec = tween(durationMillis = 650, delayMillis = 600, easing = FastOutSlowInEasing),
        label = "descOffsetY"
    )

    // Slow ambient rotation every 14 seconds — gated by lifecycle so we don't
    // wake the CPU when the screensaver is hidden behind another activity.
    LaunchedEffect(wallpaperMovies, isResumed) {
        if (!isResumed) return@LaunchedEffect
        while (isActive && isResumed) {
            delay(14000L)
            if (!isResumed) break
            currentIndex++
        }
    }

    // Dynamic Time & Date for ambient clock — same lifecycle gating as above.
    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        while (isActive && isResumed) {
            val now = Date()
            currentTimeString = timeFormat.format(now).uppercase()
            currentDateString = dateFormat.format(now)
            delay(10000L)
        }
    }

    // Subtle Ken Burns slow zoom + OLED anti-burn-in drift. We drive these
    // manually so they can be paused when the screen is hidden — using
    // rememberInfiniteTransition would keep ticking even when the screensaver
    // is in the background, which is a real battery cost.
    var kenBurnsPhase by remember { mutableFloatStateOf(0f) }
    var driftPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        val startNanos = androidx.compose.runtime.withFrameNanos { it }
        // 28s round-trip Ken Burns zoom (14s up + 14s down).
        // 60s round-trip drift for anti-burn-in.
        while (isActive && isResumed) {
            val nowNanos = androidx.compose.runtime.withFrameNanos { it }
            val totalSeconds = ((nowNanos - startNanos) / 1_000_000_000.0)
            // Map 0..28s -> 0..PI (sin gives smooth 0..1..0 reverse).
            kenBurnsPhase = (1.0 + 0.06 * kotlin.math.sin(
                (totalSeconds / 28.0) * kotlin.math.PI
            )).toFloat()
            driftPhase = (8.0 * kotlin.math.sin(
                (totalSeconds / 60.0) * kotlin.math.PI
            )).toFloat()
        }
    }
    val scaleAnim = kenBurnsPhase
    val driftAnim = driftPhase

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else false
            }
            .zIndex(9999f)
    ) {
        // 1. High Resolution Backdrop with smooth crossfade.
        // Use AnimatedContent so the old wallpaper stays alive until the new one
        // is fully faded in (no pop or black flash), and cap Coil's decode to
        // the actual screen size so we never blow 16MB of RAM on a 4K image.
        AnimatedContent(
            targetState = currentMovie.id,
            transitionSpec = {
                fadeIn(animationSpec = tween(1000, easing = LinearEasing))
                    .togetherWith(fadeOut(animationSpec = tween(1000, easing = LinearEasing)))
            },
            label = "backdropCrossfade"
        ) { movieId ->
            val backdropUrl = currentMovie.backdropUrl.ifBlank { currentMovie.posterUrl }
            val backdropRequest = remember(movieId, backdropUrl) {
                ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .size(Size(screenWidthPx, screenHeightPx))
                    .crossfade(1000)
                    .memoryCacheKey("ambient_backdrop_$movieId")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim
                        scaleY = scaleAnim
                        translationX = driftAnim
                        translationY = driftAnim * 0.5f
                        alpha = 1f
                    }
            )
        }

        // 3. Top Header: Ambient Netflix Pro Logo & Status
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 48.dp, top = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_netflix_n),
                contentDescription = "Netflix",
                modifier = Modifier
                    .height(36.dp)
                    .width(20.dp)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(NetflixRed, Color(0xFF900C13))
                        )
                    )
                    .border(1.dp, Color(0xFFFF4D5A).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "PRO AMBIENT",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp
                )
            }
        }

        // 4. Top Right: Dynamic Clock & Date
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 48.dp, top = 36.dp)
                .graphicsLayer {
                    translationX = -driftAnim * 0.5f
                    translationY = driftAnim * 0.3f
                },
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = currentTimeString.ifBlank { "12:00 PM" },
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 38.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp
            )
            Text(
                text = currentDateString.ifBlank { "Monday, August 17" },
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }

        // 5. Bottom Section: Movie Title & "Press any key to resume" with Staggered Entrance Animations
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 44.dp)
                .fillMaxWidth(0.7f)
                .graphicsLayer {
                    translationX = driftAnim * 0.5f
                }
        ) {
            // 1st: TMDB Logo (or Title) - Animates in First
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = logoAlpha
                    translationY = logoOffsetY
                }
            ) {
                if (!currentLogoUrl.isNullOrBlank()) {
                    val logoRequest = remember(currentLogoUrl) {
                        ImageRequest.Builder(context)
                            .data(currentLogoUrl)
                            .crossfade(400)
                            .memoryCacheKey("ambient_logo_${currentMovie.id}")
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                    }
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = currentMovie.title,
                        modifier = Modifier
                            .height(64.dp)
                            .widthIn(max = 300.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else {
                    Text(
                        text = currentMovie.title,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2nd: Second Row (Metadata & Badges) - Animates in Second
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.graphicsLayer {
                    alpha = secondRowAlpha
                    translationY = secondRowOffsetY
                }
            ) {
                Text(
                    text = currentMovie.type.ifBlank { "Series" },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                Text(
                    text = currentMovie.year.ifBlank { "2025" },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = currentMovie.rating.ifBlank { "16" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                Text(
                    text = "Ultra HD 4K",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3rd: Description - Animates in Last
            Text(
                text = currentMovie.description,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.5.sp,
                maxLines = 2,
                lineHeight = 19.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = descAlpha
                    translationY = descOffsetY
                }
            )
        }
    }
}
