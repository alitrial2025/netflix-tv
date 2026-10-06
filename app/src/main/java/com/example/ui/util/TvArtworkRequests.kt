package com.example.ui.util

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

/** Startup warmup and the visible billboard must use the same cache entry and decode size. */
internal object TvArtworkRequests {
    fun billboard(context: Context, url: String, kind: TvArtworkKind, size: Pair<Int, Int>): ImageRequest =
        ImageRequest.Builder(context)
            .data(TvImagePolicy.artworkUrl(url, size.first, kind))
            .size(size.first, size.second)
            .precision(Precision.EXACT)
            .scale(Scale.FILL)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
}
