package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object SpriteBitmapCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4096)

    val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

@Composable
fun SpriteThumbnail(
    cue: ThumbnailCue?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val cacheKey = remember(cue?.imageUrl, headers) { "${cue?.imageUrl}:${headers.hashCode()}" }
    var imageBitmap by remember(cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(cacheKey) { mutableStateOf(false) }

    LaunchedEffect(cacheKey) {
        val url = cue?.imageUrl
        if (!url.isNullOrBlank()) {
            val cached = SpriteBitmapCache.cache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                imageBitmap = cached.asImageBitmap()
                isLoading = false
            } else {
                isLoading = true
                withContext(Dispatchers.IO) {
                    try {
                        val imageLoader = Coil.imageLoader(context)
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            // VTT coordinates refer to the original sheet, not a downsampled image.
                            .size(Size.ORIGINAL)
                            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                            .allowHardware(false) // Required for canvas drawing compatibility
                            .build()
                        val result = imageLoader.execute(request)
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) {
                            SpriteBitmapCache.cache.put(cacheKey, drawable.bitmap)
                            withContext(Dispatchers.Main) {
                                imageBitmap = drawable.bitmap.asImageBitmap()
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }
        } else {
            imageBitmap = null
            isLoading = false
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = imageBitmap
        if (bitmap != null && cue != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val crop = spritePreviewCrop(cue, bitmap.width, bitmap.height, size.width, size.height)
                if (crop != null) {
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset(crop.x, crop.y),
                        srcSize = IntSize(crop.width, crop.height),
                        dstOffset = IntOffset(0, 0),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }
        } else {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.Red,
                    strokeWidth = 2.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray.copy(alpha = 0.5f))
                )
            }
        }
    }
}

