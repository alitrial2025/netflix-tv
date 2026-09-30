package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import coil.request.ImageRequest

/** One small decoded avatar is shared by the picker, navbar and dropdown. */
internal fun profileAvatarRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(128, 128)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .crossfade(false)
        .build()
