@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser

// The rounded head leads clockwise; the 240-degree tapered body trails behind it.
const val NETFLIX_SPINNER_PATH_DATA = "M 100.00 21.00 L 96.56 21.18 L 93.13 21.51 L 89.73 21.98 L 86.35 22.61 L 83.01 23.37 L 79.71 24.29 L 76.46 25.34 L 73.26 26.54 L 70.12 27.87 L 67.05 29.33 L 64.04 30.93 L 61.11 32.65 L 58.27 34.49 L 55.51 36.46 L 52.84 38.54 L 50.27 40.74 L 47.80 43.04 L 45.44 45.44 L 43.18 47.94 L 41.04 50.53 L 39.02 53.21 L 37.12 55.97 L 35.35 58.81 L 33.70 61.72 L 32.18 64.70 L 30.80 67.73 L 29.55 70.82 L 28.43 73.95 L 27.46 77.13 L 26.63 80.34 L 25.94 83.58 L 25.39 86.84 L 24.98 90.12 L 24.72 93.41 L 24.61 96.71 L 24.63 100.00 L 24.80 103.28 L 25.12 106.55 L 25.57 109.80 L 26.16 113.02 L 26.90 116.21 L 27.77 119.35 L 28.77 122.46 L 29.91 125.51 L 31.18 128.51 L 32.58 131.44 L 34.10 134.31 L 35.74 137.10 L 37.50 139.82 L 39.38 142.45 L 41.36 144.99 L 43.45 147.45 L 45.65 149.80 L 47.94 152.06 L 50.32 154.21 L 52.80 156.25 L 55.35 158.19 L 57.99 160.00 L 60.69 161.70 L 63.47 163.27 L 66.31 164.72 L 69.20 166.05 L 72.15 167.24 L 75.14 168.30 L 78.17 169.24 L 81.23 170.03 L 84.33 170.70 L 87.44 171.22 L 90.57 171.61 L 93.71 171.86 L 96.86 171.98 L 100.00 171.96 L 103.13 171.80 L 106.26 171.51 L 109.36 171.08 L 112.43 170.51 L 115.48 169.82 L 118.49 168.99 L 121.45 168.04 L 124.37 166.96 L 127.23 165.75 L 130.04 164.42 L 132.78 162.98 L 135.46 161.41 L 138.06 159.74 L 140.58 157.95 L 143.02 156.06 L 145.37 154.07 L 147.63 151.98 L 149.80 149.80 L 151.87 147.53 L 153.83 145.17 L 155.69 142.73 L 157.44 140.22 L 159.09 137.64 L 160.62 135.00 L 160.62 135.00 L 158.99 137.58 L 157.24 140.08 L 155.38 142.49 L 153.41 144.82 L 151.35 147.06 L 149.20 149.20 L 146.95 151.24 L 144.62 153.17 L 142.21 155.01 L 139.72 156.73 L 137.16 158.34 L 134.54 159.83 L 131.86 161.21 L 129.13 162.46 L 126.34 163.59 L 123.51 164.60 L 120.65 165.48 L 117.75 166.24 L 114.82 166.86 L 111.88 167.36 L 108.92 167.72 L 105.95 167.96 L 102.97 168.07 L 100.00 168.04 L 97.04 167.89 L 94.09 167.60 L 91.15 167.19 L 88.25 166.65 L 85.37 165.99 L 82.53 165.20 L 79.73 164.28 L 76.98 163.25 L 74.28 162.10 L 71.63 160.84 L 69.05 159.46 L 66.53 157.97 L 64.08 156.38 L 61.71 154.68 L 59.42 152.88 L 57.21 150.99 L 55.09 149.01 L 53.07 146.93 L 51.13 144.78 L 49.30 142.54 L 47.57 140.23 L 45.94 137.85 L 44.42 135.41 L 43.01 132.90 L 41.72 130.34 L 40.54 127.73 L 39.47 125.07 L 38.53 122.37 L 37.71 119.64 L 37.00 116.88 L 36.42 114.10 L 35.96 111.29 L 35.63 108.47 L 35.42 105.65 L 35.33 102.82 L 35.37 100.00 L 35.53 97.19 L 35.81 94.38 L 36.21 91.60 L 36.74 88.85 L 37.38 86.12 L 38.14 83.43 L 39.02 80.77 L 40.01 78.17 L 41.11 75.61 L 42.32 73.10 L 43.64 70.66 L 45.06 68.28 L 46.58 65.97 L 48.20 63.73 L 49.91 61.56 L 51.71 59.48 L 53.60 57.48 L 55.57 55.57 L 57.62 53.75 L 59.74 52.02 L 61.93 50.39 L 64.19 48.86 L 66.51 47.43 L 68.89 46.11 L 71.31 44.89 L 73.79 43.79 L 76.30 42.79 L 78.86 41.91 L 81.44 41.14 L 84.05 40.48 L 86.69 39.94 L 89.34 39.52 L 92.00 39.22 L 94.67 39.03 L 97.33 38.96 L 100.00 39.00 A 9 9 0 0 0 100 21 Z"

@Composable
fun NetflixSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    color: Color = Color(0xFFE50914),
    percentage: Int? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "netflixSpinner")
    val spinnerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pathSpinner = remember {
        PathParser.createPathFromPathData(NETFLIX_SPINNER_PATH_DATA).asComposePath()
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = spinnerRotation }
        ) {
            val scaleX = this.size.width / 200f
            val scaleY = this.size.height / 200f
            withTransform({
                scale(scaleX, scaleY, pivot = Offset.Zero)
            }) {
                drawPath(pathSpinner, color)
            }
        }

        if (percentage != null) {
            val clamped = percentage.coerceIn(0, 100)
            val fontSize = when {
                size >= 100.dp -> 18.sp
                size >= 80.dp -> 14.sp
                size >= 50.dp -> 12.sp
                else -> 10.sp
            }
            Text(
                text = "$clamped%",
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
