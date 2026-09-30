@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset

@Composable
fun TestColumnOffset() {
    Column(modifier = Modifier.offset { IntOffset(0, 0) }) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.onPlaced { it.positionInParent().y })
    }
}
