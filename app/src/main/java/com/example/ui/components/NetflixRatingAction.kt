@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.R
import com.example.ui.theme.NetflixRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TvRatingOption(
    val key: String,
    val label: String,
    val iconRes: Int
) {
    DISLIKE("DISLIKE", "Not for me", R.drawable.ic_netflix_thumbs_down),
    LIKE("LIKE", "I like this", R.drawable.ic_netflix_thumbs_up),
    DOUBLE_LIKE("DOUBLE_LIKE", "Love this!", R.drawable.ic_netflix_love_this)
}

@Composable
fun TvNetflixRatingAction(
    currentRating: String? = null,
    onRatingSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isButtonFocused by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val activeIconRes = remember(currentRating) {
        when (currentRating) {
            "DOUBLE_LIKE" -> R.drawable.ic_netflix_love_this
            "DISLIKE" -> R.drawable.ic_netflix_thumbs_down
            else -> R.drawable.ic_netflix_thumbs_up
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Floating Rating Capsule Popup
        if (isExpanded) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(x = 0, y = -140),
                onDismissRequest = { isExpanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                TvRatingCapsuleContent(
                    onOptionClick = { option ->
                        coroutineScope.launch {
                            delay(180)
                            onRatingSelect(option.key)
                            isExpanded = false
                        }
                    }
                )
            }
        }

        // Anchor Button
        Surface(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .size(48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { isButtonFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = when {
                    isExpanded -> Color(0xFF2B2B2B)
                    currentRating != null -> Color(0xFF4D1414)
                    else -> Color.Transparent
                },
                focusedContainerColor = if (isExpanded) NetflixRed else Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                border = if (isExpanded) Border(BorderStroke(1.dp, Color(0xFF666666))) else Border.None,
                focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isExpanded) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Rating",
                        tint = if (isButtonFocused) Color.White else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = activeIconRes),
                        contentDescription = "Rate",
                        tint = if (isButtonFocused) Color.Black else (if (currentRating != null) NetflixRed else Color.White),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TvRatingCapsuleContent(
    onOptionClick: (TvRatingOption) -> Unit
) {
    val options = listOf(
        TvRatingOption.DISLIKE,
        TvRatingOption.LIKE,
        TvRatingOption.DOUBLE_LIKE
    )

    val infiniteTransition = rememberInfiniteTransition(label = "SparklePulse")
    val sparkleScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SparkleScale"
    )

    val shinyGlassBorder = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF595959),
            Color(0xFF1A1A1A)
        )
    )

    Box(
        modifier = Modifier
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(32.dp),
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, shinyGlassBorder, RoundedCornerShape(32.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                TvRatingItemView(
                    option = option,
                    index = index,
                    sparkleScale = if (option == TvRatingOption.DOUBLE_LIKE) sparkleScale else 1f,
                    onClick = { onOptionClick(option) }
                )
            }
        }
    }
}

@Composable
private fun TvRatingItemView(
    option: TvRatingOption,
    index: Int,
    sparkleScale: Float,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var isTapped by remember { mutableStateOf(false) }

    val entryScale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        delay(index * 40L)
        entryScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    val tapScale by animateFloatAsState(
        targetValue = if (isTapped) 1.35f else if (isFocused) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TvTapBounce"
    )

    Surface(
        onClick = {
            isTapped = true
            onClick()
        },
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isFocused) Color(0xFF333333) else Color.Transparent,
            focusedContainerColor = Color(0xFF404040)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = entryScale.value * tapScale * sparkleScale
                        scaleY = entryScale.value * tapScale * sparkleScale
                    }
            ) {
                Icon(
                    painter = painterResource(id = option.iconRes),
                    contentDescription = option.label,
                    tint = if (isTapped) NetflixRed else Color.White,
                    modifier = Modifier.size(if (option == TvRatingOption.DOUBLE_LIKE) 30.dp else 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = option.label,
                color = if (isFocused) Color.White else Color(0xFFD9D9D9),
                fontSize = 11.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
