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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser

const val NETFLIX_SPINNER_PATH_DATA = "M 100.00 39.00 A 9.00 9.00 0 0 1 100.00 21.00 L 103.66 21.32 L 107.30 21.82 L 110.90 22.47 L 114.45 23.29 L 117.95 24.27 L 121.39 25.40 L 124.77 26.69 L 128.07 28.13 L 131.29 29.71 L 134.43 31.44 L 137.48 33.30 L 140.43 35.30 L 143.28 37.42 L 146.02 39.66 L 148.65 42.03 L 151.15 44.50 L 153.54 47.08 L 155.80 49.76 L 157.93 52.53 L 159.92 55.39 L 161.78 58.33 L 163.49 61.35 L 165.06 64.43 L 166.49 67.57 L 167.76 70.77 L 168.89 74.01 L 169.86 77.30 L 170.68 80.62 L 171.35 83.96 L 171.86 87.33 L 172.22 90.71 L 172.42 94.09 L 172.46 97.47 L 172.36 100.84 L 172.10 104.20 L 171.68 107.53 L 171.12 110.84 L 170.41 114.11 L 169.55 117.34 L 168.55 120.52 L 167.40 123.65 L 166.12 126.71 L 164.70 129.71 L 163.15 132.64 L 161.47 135.49 L 159.67 138.26 L 157.74 140.93 L 155.70 143.52 L 153.55 146.00 L 151.29 148.39 L 148.93 150.66 L 146.47 152.83 L 143.92 154.88 L 141.28 156.81 L 138.56 158.63 L 135.76 160.31 L 132.90 161.87 L 129.97 163.30 L 126.98 164.61 L 123.94 165.78 L 126.97 164.58 L 129.94 163.23 L 132.83 161.74 L 135.64 160.11 L 138.37 158.34 L 141.01 156.45 L 143.55 154.43 L 145.99 152.29 L 148.33 150.04 L 150.54 147.69 L 152.64 145.23 L 154.62 142.67 L 156.47 140.03 L 158.19 137.31 L 159.77 134.51 L 161.22 131.64 L 162.53 128.71 L 163.69 125.73 L 164.70 122.70 L 165.57 119.63 L 166.29 116.53 L 166.86 113.40 L 167.28 110.26 L 167.55 107.10 L 167.67 103.94 L 167.63 100.79 L 167.45 97.64 L 167.12 94.52 L 166.64 91.42 L 166.01 88.36 L 165.24 85.34 L 164.33 82.36 L 163.29 79.44 L 162.10 76.57 L 160.79 73.78 L 159.34 71.06 L 157.78 68.41 L 156.09 65.85 L 154.29 63.38 L 152.38 61.01 L 150.36 58.73 L 148.24 56.56 L 146.03 54.50 L 143.73 52.56 L 141.34 50.73 L 138.88 49.02 L 136.35 47.43 L 133.76 45.98 L 131.10 44.65 L 128.40 43.45 L 125.65 42.39 L 122.86 41.46 L 120.04 40.67 L 117.20 40.02 L 114.34 39.51 L 111.46 39.13 L 108.59 38.89 L 105.71 38.79 L 102.85 38.83 L 100.00 39.00 Z"

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
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
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
            modifier = Modifier.fillMaxSize()
        ) {
            val scaleX = this.size.width / 200f
            val scaleY = this.size.height / 200f
            withTransform({
                rotate(spinnerRotation, pivot = Offset(this.size.width / 2f, this.size.height / 2f))
                scale(scaleX, scaleY, pivot = Offset.Zero)
            }) {
                drawPath(pathSpinner, color)
            }
        }

        if (percentage != null) {
            val clamped = percentage.coerceIn(1, 100)
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
