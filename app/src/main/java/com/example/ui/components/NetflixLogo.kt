package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Official Netflix Wordmark Vector Logo for Android TV
 */
@Composable
fun NetflixWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 28.dp
) {
    Image(
        painter = painterResource(id = R.drawable.ic_netflix_logo),
        contentDescription = "Netflix",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height)
    )
}

/**
 * Official Netflix 3D Ribbon 'N' Vector Logo for Android TV
 */
@Composable
fun NetflixNLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    val width = size * 0.60f
    Image(
        painter = painterResource(id = R.drawable.ic_netflix_n),
        contentDescription = "Netflix N Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width = width, height = size)
    )
}

@Composable
fun NSeriesBadge(
    modifier: Modifier = Modifier,
    nSize: Dp = 16.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetflixNLogo(size = nSize, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = "SERIES",
            color = Color(0xFFE6E6E6),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun NFilmBadge(
    modifier: Modifier = Modifier,
    nSize: Dp = 16.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetflixNLogo(size = nSize, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = "FILM",
            color = Color(0xFFE6E6E6),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
