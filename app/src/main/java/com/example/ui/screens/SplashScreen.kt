package com.example.ui.screens

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.NetflixBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SplashScreen(
    isWarmupFinished: Boolean = false,
    onSplashComplete: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val currentIsWarmupFinished by rememberUpdatedState(isWarmupFinished)
    var logoSize by remember { mutableStateOf(IntSize.Zero) }

    // Intercept back button during splash
    BackHandler(enabled = true) { /* no-op: splash in progress */ }

    // Pre-load tudum audio player off main thread
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(Unit) {
        val player = withContext(Dispatchers.IO) {
            try {
                MediaPlayer.create(context, R.raw.netflix_tudum)
            } catch (_: Exception) {
                null
            }
        }
        mediaPlayer = player
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    // Mathematically calculated proportions of "N" within the NETFLIX logo
    val nLeftPercent = 0.043f
    val nWidthPercent = 0.0945f
    val nRightPercent = nLeftPercent + nWidthPercent // 0.1375f
    val nCenterPercent = nLeftPercent + nWidthPercent / 2f // 0.09025f

    // Animation states running strictly on GPU compositor
    val wipeProgress = remember { Animatable(1f) }
    val slideOffset = remember { Animatable(0f) }
    val crossfadeAlpha = remember { Animatable(0f) }
    val spinnerAlpha = remember { Animatable(0f) }
    val zoomAlpha = remember { Animatable(1f) }
    val tadumScale = remember { Animatable(1f) }

    // Single pre-allocated path to eliminate GC pressure and prevent frame drops
    val reusableWipePath = remember { Path() }

    val hasFinished = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 1. Pause briefly on the full logo
        delay(800)

        // 2. Wipe ETFLIX from right to left, leaving ONLY the N intact at the end
        wipeProgress.animateTo(
            targetValue = nRightPercent,
            animationSpec = tween(durationMillis = 1100, easing = LinearEasing)
        )

        // 3. Slide N to the exact center smoothly while crossfading/morphing to the standalone N logo
        val targetShift = if (logoSize.width > 0) (logoSize.width * (0.50f - nCenterPercent)) else 300f
        launch {
            crossfadeAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
            )
        }
        slideOffset.animateTo(
            targetValue = targetShift,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )

        // Fade in the spinner below
        launch {
            spinnerAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300)
            )
        }

        // 4. Stand under standalone N logo for a smooth visual beat (600ms)
        delay(600)

        // 5. Play the dramatic Netflix "Tadum" sound & zoom effect!
        try {
            mediaPlayer?.start()
        } catch (_: Exception) {}

        launch {
            spinnerAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200)
            )
        }

        // Rapid zoom in (scale up to 12x) to mimic zooming into the red ribbon portal
        launch {
            tadumScale.animateTo(
                targetValue = 12f,
                animationSpec = tween(durationMillis = 750, easing = CubicBezierEasing(0.5f, 0f, 0.1f, 1f))
            )
        }

        // Start fading out shortly after zoom starts
        delay(100)
        zoomAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
        )

        if (!hasFinished.value) {
            hasFinished.value = true
            onSplashComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = zoomAlpha.value
            }
            .background(NetflixBlack),
        contentAlignment = Alignment.Center
    ) {
        // Container for GPU-accelerated sliding & scaling
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = slideOffset.value
                    scaleX = tadumScale.value
                    scaleY = tadumScale.value
                    transformOrigin = TransformOrigin(nCenterPercent, 0.5f)
                },
            contentAlignment = Alignment.Center
        ) {
            // Original NETFLIX logo with right-to-left Zigzag / Scissors wipe (Zero-allocation GPU path)
            Image(
                painter = painterResource(id = R.drawable.ic_netflix_logo),
                contentDescription = "Netflix Logo",
                modifier = Modifier
                    .width(280.dp)
                    .height(76.dp)
                    .onSizeChanged { logoSize = it }
                    .graphicsLayer {
                        alpha = (1f - crossfadeAlpha.value).coerceIn(0f, 1f)
                    }
                    .drawWithContent {
                        drawContent()
                        if (wipeProgress.value < 1f) {
                            val wipeX = size.width * wipeProgress.value

                            // Smoothly reduce zigzag tooth amplitude near the end for a sharp N boundary
                            val currentToothWidth = if (wipeProgress.value > 0.22f) {
                                32f
                            } else {
                                val ratio = (wipeProgress.value - nRightPercent) / (0.22f - nRightPercent)
                                32f * ratio.coerceIn(0f, 1f)
                            }

                            reusableWipePath.rewind()
                            val steps = 12
                            val stepHeight = size.height / steps
                            reusableWipePath.moveTo(size.width + 4f, -4f)
                            for (i in 0..steps) {
                                val y = i * stepHeight
                                val x = if (i % 2 == 0) wipeX + currentToothWidth else wipeX - currentToothWidth
                                reusableWipePath.lineTo(x, y)
                            }
                            reusableWipePath.lineTo(size.width + 4f, size.height + 4f)
                            reusableWipePath.close()

                            // Draw black cover polygon over wiped letters - 100% hardware accelerated
                            drawPath(reusableWipePath, NetflixBlack)
                        }
                    }
            )

            // Standalone N logo (GPU-composed alpha without relayout)
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .height(76.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_netflix_n),
                    contentDescription = "Netflix N",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = 12.dp)
                        .width(42.dp)
                        .height(76.dp)
                        .graphicsLayer {
                            alpha = crossfadeAlpha.value
                        },
                    contentScale = ContentScale.FillBounds
                )
            }
        }

        // The Spinner below the logo
        com.example.ui.components.NetflixSpinner(
            modifier = Modifier
                .offset(y = 100.dp)
                .graphicsLayer {
                    alpha = spinnerAlpha.value
                },
            size = 50.dp
        )
    }
}
