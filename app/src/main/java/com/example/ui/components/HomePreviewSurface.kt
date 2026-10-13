@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.components

import android.view.LayoutInflater
import android.view.View
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.ui.NetflixViewModel
import com.example.ui.util.HomePreviewController
import com.example.ui.util.HomePreviewState
import com.example.ui.util.TvMotion
import com.example.ui.util.homePreviewKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Focus owns a request, not a player. Unfocused/offscreen rows allocate no video resources. */
@Composable
internal fun rememberHomePreview(
    owner: String,
    movie: Movie,
    focused: Boolean,
    viewModel: NetflixViewModel?,
    audible: Boolean = true,
    waitForHomeReady: Boolean = false
): State<HomePreviewState> {
    // Leaving this composition group disposes the previous owner's effects and stops it.
    // Inactive rows collect no preview/subscription flows and register no lifecycle observer.
    if (!focused || viewModel == null) return rememberUpdatedState(HomePreviewState())
    val controller = viewModel.homePreviewController
    val subscription = viewModel.userSubscription.collectAsStateWithLifecycle().value
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, controller, owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) controller?.stop(owner, releaseImmediately = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller, owner, movie.id, movie.title, movie.catalogMediaKind(), focused, resumed, audible, subscription, waitForHomeReady) {
        if (focused && resumed) controller?.request(owner, movie, audible, waitForHomeReady)
        onDispose { controller?.stop(owner) }
    }
    val ownerState = remember(controller, owner) {
        controller?.state?.map { if (it.owner == owner) it else HomePreviewState() }?.distinctUntilChanged()
    }
    val observed = ownerState?.collectAsStateWithLifecycle(initialValue = HomePreviewState())
        ?: remember { mutableStateOf(HomePreviewState()) }
    // A changed list can reuse the same focused index. Never render the old
    // title's frame during the composition before the focus effect is applied.
    val matching = observed.value.takeIf { focused && resumed && it.contentKey == homePreviewKey(movie) }
    return rememberUpdatedState(matching ?: HomePreviewState())
}

@Composable
internal fun HomePreviewSurface(
    controller: HomePreviewController,
    preview: HomePreviewState,
    modifier: Modifier = Modifier,
    captionAlignment: Alignment = Alignment.BottomEnd,
    captionBottomInset: Dp = 14.dp,
    captionHorizontalPadding: Dp = 14.dp,
    captionMaxWidth: Dp = 420.dp,
    placeholderRequest: ImageRequest? = null
) {
    preview.player ?: return
    val reveal = animateFloatAsState(
        if (preview.firstFrameReady) 1f else 0f,
        tween(TvMotion.duration(280), easing = LinearOutSlowInEasing), label = "previewReveal"
    )
    val textAlign = when (captionAlignment) {
        Alignment.BottomEnd, Alignment.CenterEnd, Alignment.TopEnd -> TextAlign.End
        Alignment.BottomCenter, Alignment.Center, Alignment.TopCenter -> TextAlign.Center
        else -> TextAlign.Start
    }
    val showPlaceholder by remember(reveal) { derivedStateOf { reveal.value < 1f } }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.media_player_view, null) as PlayerView).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    subtitleView?.visibility = View.GONE
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { controller.attachSurface(it) },
            onRelease = { controller.detachSurface(it) },
            modifier = Modifier.fillMaxSize()
        )
        // The video view remains visible while preparing its first frame. Hiding
        // the TextureView with alpha zero can prevent its surface from starting.
        // Cover it with the already-cached artwork, then fade that cover away.
        if (showPlaceholder && placeholderRequest != null) AsyncImage(
            model = placeholderRequest, contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - reveal.value },
            contentScale = ContentScale.Crop
        )
        // Cue changes recompose this small child only, never the carousel or hero copy.
        PreviewCaption(
            controller = controller,
            textAlign = textAlign,
            maxWidth = captionMaxWidth,
            modifier = Modifier
                .graphicsLayer { alpha = reveal.value }
                .align(captionAlignment)
                .padding(start = captionHorizontalPadding, end = captionHorizontalPadding, bottom = captionBottomInset)
        )
    }
}

@Composable
private fun PreviewCaption(
    controller: HomePreviewController,
    textAlign: TextAlign = TextAlign.End,
    maxWidth: Dp = 420.dp,
    modifier: Modifier
) {
    val caption by controller.caption.collectAsStateWithLifecycle()
    if (caption.isBlank()) return
    Text(
        text = caption,
        color = Color.White,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = textAlign,
        maxLines = 3,
        style = TextStyle(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.9f),
                offset = Offset(2f, 2f),
                blurRadius = 4f
            )
        ),
        modifier = modifier.widthIn(max = maxWidth)
    )
}
