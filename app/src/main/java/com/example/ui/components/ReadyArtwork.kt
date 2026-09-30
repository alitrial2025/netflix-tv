package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.ui.util.TvMotion

/** Keep the last decoded frame opaque until its replacement can actually be drawn. */
@Composable
internal fun ReadyArtwork(
    request: ImageRequest,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    animateReplacement: Boolean = true,
    onReady: () -> Unit = {}
) {
    // A new request gets a new painter, so Success from the previous URL cannot
    // be mistaken for completion of the next request.
    val requestedPainter = key(request) {
        rememberAsyncImagePainter(model = request, contentScale = ContentScale.Crop)
    }
    val state = requestedPainter.state
    var displayed by remember { mutableStateOf<Painter?>(null) }
    var outgoing by remember { mutableStateOf<Painter?>(null) }
    val reveal = remember { Animatable(1f) }
    val currentOnReady by rememberUpdatedState(onReady)

    LaunchedEffect(request, state) {
        when (state) {
            is AsyncImagePainter.State.Success -> {
                if (displayed !== state.painter) {
                    outgoing = if (animateReplacement) displayed else null
                    // Low-memory TVs show the decoded replacement directly, avoiding
                    // two full-screen textures during a billboard rotation.
                    reveal.snapTo(if (outgoing == null) 1f else 0f)
                    displayed = state.painter
                    currentOnReady()
                    if (outgoing != null) reveal.animateTo(1f, tween(TvMotion.duration(240), easing = LinearOutSlowInEasing))
                    outgoing = null
                }
            }
            is AsyncImagePainter.State.Error -> {
                outgoing = null
                reveal.snapTo(1f)
                currentOnReady()
            }
            else -> {
                // Rapid browsing retains just the most recent image, not a stack
                // of interrupted full-screen transitions.
                outgoing = null
                reveal.snapTo(1f)
            }
        }
    }

    Box(modifier) {
        outgoing?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        displayed?.let {
            Image(
                painter = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
                contentScale = ContentScale.Crop
            )
        }
    }
}
