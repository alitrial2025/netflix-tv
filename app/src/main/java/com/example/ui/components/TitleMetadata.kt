package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Movie
import com.example.model.isSeriesContent
import com.example.ui.theme.RatingDefaults

@Composable
fun TitleMetadata(movie: Movie, isKids: Boolean = false, showKind: Boolean = true, modifier: Modifier = Modifier) {
    val labels = remember(movie.type, movie.year, movie.duration, isKids, showKind) {
        buildList {
            if (showKind) add(if (movie.isSeriesContent()) "Series" else "Film")
            if (isKids) add("Kids")
            else if (movie.type.equals("Animation", true)) add("Animation")
            if (movie.year.isNotBlank()) add(movie.year)
            val duration = movie.duration.trim()
            if (duration.isNotBlank() && !duration.equals("Series", true) &&
                !duration.equals("Feature", true) && !duration.equals("Movie", true)) add(duration)
        }.joinToString("  •  ")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(labels, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(movie.rating.ifBlank { RatingDefaults.MISSING }, color = if (isKids) Color.Black else Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.background(if (isKids) Color(0xFFF1DC39) else Color(0xFFE50914), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
