package com.example.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wrap whole metadata items instead of clipping the tail of a single horizontal row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsMetadataLine(
    type: String,
    genre: String,
    year: String,
    duration: String,
    rating: String,
    isKidContent: Boolean,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(type, genre, year, duration).filter(String::isNotBlank).forEachIndexed { index, value ->
            MetadataItem(separator = index != 0) {
                Text(value, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium)
            }
        }
        MetadataItem {
            Box(Modifier.background(
                if (isKidContent) Color(0xFF22C55E) else Color(0xFFEAB308), RoundedCornerShape(3.dp)
            ).padding(horizontal = 5.dp, vertical = 1.5.dp)) {
                Text(rating, color = if (isKidContent) Color.White else Color.Black,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        listOf("AD)))", "CC").forEach { label ->
            MetadataItem {
                Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MetadataItem(separator: Boolean = true, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (separator) Text("•", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
        content()
    }
}
