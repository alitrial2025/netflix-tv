package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.util.TvMotion
import com.example.ui.util.TvKeyPacer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

fun getCategoryThemeColor(category: String): Color {
    val clean = category.trim().lowercase()
    return when {
        clean.contains("drama") -> Color(0xFFCC8CAA)
        clean.contains("comed") -> Color(0xFFE1B576)
        clean.contains("action") -> Color(0xFFE78C77)
        clean.contains("romance") -> Color(0xFFE89BB8)
        clean.contains("anime") -> Color(0xFFADA5EA)
        clean.contains("kid") || clean.contains("family") -> Color(0xFF88C9C2)
        clean.contains("sci-fi") || clean.contains("scifi") || clean.contains("fiction") -> Color(0xFF8CC6E5)
        clean.contains("thrill") || clean.contains("crime") -> Color(0xFFB1C59D)
        clean.contains("docu") -> Color(0xFFD2BD94)
        clean.contains("horror") -> Color(0xFFC493AC)
        clean.contains("fantasy") -> Color(0xFFC1A6DF)
        else -> Color(0xFF475569)
    }
}

@Suppress("UNUSED_PARAMETER")
fun getCategoryCardColor(category: String, isFocused: Boolean): Color =
    if (isFocused) Color(0xFF35414E) else Color(0xFF2B3540)

@Suppress("UNUSED_PARAMETER")
@Composable
fun CategoriesBarSection(
    categoriesFocusRequester: FocusRequester? = null,
    height: Dp = 116.dp,
    alpha: () -> Float = { 1f },
    modifier: Modifier = Modifier,
    moodColor: Color = Color.Transparent,
    onCategorySelected: (String) -> Unit = {},
    onCategoryClick: (String) -> Unit = {},
    onFocused: () -> Unit = {},
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false },
    verticalRingOffsetProvider: () -> Float = { 0f },
    moodColorProvider: (() -> Color)? = null
) {
    val categories = remember {
        listOf(
            "Action", "Dramas", "Comedies", "Romance", "Sci-Fi",
            "Animation", "Kids & Family", "Thrillers", "Documentaries",
            "Horror", "Fantasy"
        )
    }
    val initialIndex = remember { 1000 * categories.size }
    var focusedIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
    val keyPacer = remember { TvKeyPacer() }
    var isCategoriesFocused by remember { mutableStateOf(false) }
    // Category identity uses the saved loop counter; fractional motion starts
    // near zero so the spring keeps subpixel precision at high refresh rates.
    val animationOrigin = remember { focusedIndex }
    // Retarget from the current position and velocity, even while a key is held.
    val animIndex = animateFloatAsState(
        targetValue = (focusedIndex - animationOrigin).toFloat(),
        animationSpec = TvMotion.carouselSpring(0.005f),
        label = "categoriesPosition"
    )
    val animatedFloor by remember { derivedStateOf { animationOrigin + floor(animIndex.value).toInt() } }
    val localFocusRequester = remember { FocusRequester() }
    val actualFocusRequester = categoriesFocusRequester ?: localFocusRequester

    val onFocusedState = rememberUpdatedState(onFocused)
    val onCategoryClickState = rememberUpdatedState(onCategoryClick)
    val onCategorySelectedState = rememberUpdatedState(onCategorySelected)
    val onDpadUpState = rememberUpdatedState(onDpadUp)
    val onDpadDownState = rememberUpdatedState(onDpadDown)
    LaunchedEffect(isCategoriesFocused, focusedIndex) {
        if (isCategoriesFocused) {
            // Navigation responds immediately; a held key does not restart the
            // whole screen's colour blend for every title that passes through.
            delay(140)
            onCategorySelectedState.value(categories[Math.floorMod(focusedIndex, categories.size)])
        }
    }

    val homeLayout = rememberHomeLayoutMetrics()
    val pillWidth = homeLayout.categoryWidth
    val pillHeight = height - 8.dp
    val gap = 12.dp
    val leftInset = 16.dp
    val pillsTop = 0.dp
    val density = LocalDensity.current
    val spacingPx = with(density) { (pillWidth + gap).toPx() }
    val leftInsetPx = with(density) { leftInset.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha() }
            .onPreviewKeyEvent { event ->
                if (!isCategoriesFocused || event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (keyPacer.accept(1, repeatCount = event.nativeKeyEvent.repeatCount)) focusedIndex++
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (keyPacer.accept(-1, repeatCount = event.nativeKeyEvent.repeatCount)) focusedIndex--
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                onCategoryClickState.value(categories[Math.floorMod(focusedIndex, categories.size)])
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> onDpadUpState.value()
                        KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDownState.value()
                        else -> false
                    }
                }
            }
            .onFocusChanged { state ->
                val gainedFocus = state.hasFocus && !isCategoriesFocused
                isCategoriesFocused = state.hasFocus
                if (gainedFocus) onFocusedState.value()
            }
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .focusRequester(actualFocusRequester)
            .focusable()
            .semantics {
                contentDescription = "Explore categories"
                stateDescription = categories[Math.floorMod(focusedIndex, categories.size)]
            }
    ) {

        BoxWithConstraints(
            modifier = Modifier
                .offset(y = pillsTop)
                .fillMaxWidth()
                .height(pillHeight)
                .clipToBounds()
        ) {
            // Follow the animated viewport rather than the requested destination so
            // holding a D-pad key never removes pills that are still crossing the screen.
            val startIndex = animatedFloor - 1
            val visibleCount = ceil(maxWidth.value / (pillWidth + gap).value).toInt() + 2
            Row(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer {
                        translationX = leftInsetPx + ((startIndex - animationOrigin) - animIndex.value) * spacingPx
                    },
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                for (index in startIndex until startIndex + visibleCount) {
                    key(index) {
                        val category = categories[Math.floorMod(index, categories.size)]
                        CategoryChipItem(
                            title = category,
                            cardWidth = pillWidth,
                            height = pillHeight,
                            isExpanded = false,
                            isFocused = isCategoriesFocused && index == focusedIndex,
                            moodColor = moodColor,
                            onClick = {
                                focusedIndex = index
                                actualFocusRequester.requestFocus()
                                onCategoryClickState.value(category)
                            }
                        )
                    }
                }
            }
        }

        // Home counter-translates this border while its content glides vertically.
        // Keep it outside the horizontal clipping viewport so it can stay on screen.
        Box(
            modifier = Modifier
                .offset(x = leftInset, y = pillsTop)
                .width(pillWidth)
                .height(pillHeight)
                .graphicsLayer {
                    this.alpha = if (isCategoriesFocused) 1f else 0f
                    translationY = verticalRingOffsetProvider()
                }
                .border(2.dp, Color.White, CategoryChipShape)
                .zIndex(2f)
        )
    }
}

private val CategoryChipShape = RoundedCornerShape(16.dp)
@Suppress("UNUSED_PARAMETER")
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun CategoryChipItem(
    title: String,
    cardWidth: Dp,
    height: Dp,
    isExpanded: Boolean,
    isFocused: Boolean = false,
    moodColor: Color = Color.Transparent,
    moodColorProvider: (() -> Color)? = null,
    onClick: () -> Unit
) {
    val onClickState = rememberUpdatedState(onClick)
    val focusAmount = animateFloatAsState(if (isFocused) 1f else 0f,
        tween(TvMotion.duration(180), easing = FastOutSlowInEasing), label = "categoryFocus")
    val textMeasurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val titleStyle = remember(typography) {
        typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
    Box(
        Modifier.width(cardWidth).height(height).clip(CategoryChipShape)
            .drawWithCache {
                val spotlight = Brush.radialGradient(
                    0f to Color(0xFFBDCEDF).copy(alpha = 0.34f),
                    0.45f to Color(0xFF91A6BC).copy(alpha = 0.15f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.5f, -size.height * 0.12f),
                    radius = size.width * 0.86f
                )
                val focusedLight = Brush.radialGradient(
                    colors = listOf(Color(0xFFDCE8F3).copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.5f, 0f), radius = size.width * 0.9f
                )
                val shade = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f)), endY = size.height
                )
                val layout = textMeasurer.measure(AnnotatedString(title), style = titleStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = (size.width - 16.dp.toPx()).toInt().coerceAtLeast(1)))
                val origin = Offset((size.width - layout.size.width) * 0.5f, (size.height - layout.size.height) * 0.5f)
                val radius = CornerRadius(16.dp.toPx())
                val stroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    drawRoundRect(Color(0xFF2B3540), cornerRadius = radius)
                    drawRoundRect(spotlight, cornerRadius = radius)
                    drawRoundRect(shade, cornerRadius = radius)
                    drawRoundRect(focusedLight, cornerRadius = radius, alpha = focusAmount.value)
                    drawRoundRect(Color.White.copy(alpha = 0.24f), cornerRadius = radius, style = stroke)
                    drawText(layout, color = Color.White, topLeft = origin)
                }
            }
            .focusProperties { canFocus = false }
            .clickable(role = Role.Button, onClick = { onClickState.value() })
            .semantics(mergeDescendants = true) { contentDescription = title; selected = isFocused }
    )
}
