package com.example.ui.components

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import coil.compose.AsyncImage
import com.example.R
import com.example.model.Movie
import com.example.model.Profile
import com.example.ui.NetflixViewModel
import com.example.ui.screens.*
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import com.example.ui.theme.KidsAccent
import com.example.ui.util.TvMotion
import com.example.ui.util.TvKeyPacer
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private fun FocusRequester.safeRequestFocus(): Boolean {
    return try {
        this.requestFocus()
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun NavigationFocusPill(
    horizontalOffset: State<Float>,
    width: State<Float>,
    alpha: State<Float>
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(horizontalOffset.value.roundToInt(), 0) }
            .layout { measurable, constraints ->
                // Animate the footprint during measurement, without recomposing
                // the profile menu and every navigation tab on each frame.
                val widthPx = width.value.roundToInt()
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
                val placeable = measurable.measure(
                    constraints.copy(minWidth = widthPx, maxWidth = widthPx)
                )
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }
            }
            .height(32.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = Color.LightGray,
                spotColor = Color.White
            )
            .background(Color.White, CircleShape)
    )
}

/**
 * perf: extracted the search-icon Surface so its local focus state
 * (`isSearchFocused`) does not invalidate the whole tabs Row.
 */
@androidx.compose.runtime.Composable
private fun SearchIcon(
    isSearchSelected: Boolean,
    isDropdownExpanded: Boolean,
    focusPillCoverage: State<Boolean>,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit,
    onDpadUp: () -> Boolean,
    onDpadDown: () -> Boolean
) {
    var isSearchFocused by remember { mutableStateOf(false) }
    var pressArmed by remember { mutableStateOf(false) }
    val onClickState = rememberUpdatedState(onClick)
    val onFocusedState = rememberUpdatedState(onFocused)
    val onUnfocusedState = rememberUpdatedState(onUnfocused)
    val onDpadUpState = rememberUpdatedState(onDpadUp)
    val onDpadDownState = rememberUpdatedState(onDpadDown)
    val onPlacedState = rememberUpdatedState(onPlaced)
    val leftRequesterState = rememberUpdatedState(leftFocusRequester)
    val rightRequesterState = rememberUpdatedState(rightFocusRequester)

    Surface(
        onClick = { onClickState.value() },
        modifier = Modifier
            .height(32.dp)
            .onPlaced { coords -> onPlacedState.value(coords.boundsInParent()) }
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = !isDropdownExpanded
                left = if (isDropdownExpanded) FocusRequester.Cancel else leftRequesterState.value
                right = rightRequesterState.value
            }
            .onFocusChanged {
                isSearchFocused = it.isFocused
                if (!it.isFocused) pressArmed = false
                if (it.isFocused) {
                    if (!isDropdownExpanded) {
                        onFocusedState.value()
                    }
                } else {
                    onUnfocusedState.value()
                }
            }
            .onPreviewKeyEvent { event ->
                if (isDropdownExpanded) return@onPreviewKeyEvent true
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) pressArmed = true
                        if (event.type == KeyEventType.KeyUp) {
                            if (pressArmed) onClickState.value()
                            pressArmed = false
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (event.type == KeyEventType.KeyDown) onDpadDownState.value() else true
                    KeyEvent.KEYCODE_DPAD_UP -> if (event.type == KeyEventType.KeyDown) onDpadUpState.value() else true
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.type == KeyEventType.KeyDown && leftRequesterState.value != FocusRequester.Cancel) {
                            leftRequesterState.value.safeRequestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.type == KeyEventType.KeyDown && rightRequesterState.value != FocusRequester.Cancel) {
                            rightRequesterState.value.safeRequestFocus()
                        }
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isSearchFocused && focusPillCoverage.value) Color.Black else Color.White,
                    modifier = Modifier.size(15.dp)
                )
                if (isSearchSelected && !isSearchFocused) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(14.dp)
                            .height(2.5.dp)
                            .shadow(elevation = 3.dp, shape = RoundedCornerShape(1.5.dp), spotColor = NetflixRed, ambientColor = NetflixRed)
                            .background(NetflixRed, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}

/**
 * perf: extracted tab Surface into its own composable. Per-tab focus state
 * (isTabFocused) is now scoped to the tab, so when the user D-pads between tabs
 * only the newly-focused and previously-focused tabs recompose — not the whole Row.
 */
@androidx.compose.runtime.Composable
private fun NavTab(
    tabName: String,
    isSelected: Boolean,
    isDropdownExpanded: Boolean,
    focusPillCoverage: State<Boolean>,
    tabFocusRequester: FocusRequester,
    leftRequester: FocusRequester,
    rightRequester: FocusRequester,
    onClick: () -> Unit,
    onTabFocused: () -> Unit,
    onTabUnfocused: () -> Unit,
    onDpadUp: () -> Boolean,
    onDpadDown: () -> Boolean,
    onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit
) {
    var isTabFocused by remember { mutableStateOf(false) }
    var pressArmed by remember { mutableStateOf(false) }
    // perf: keep latest lambdas in rememberUpdatedState so the key event handler
    // below doesn't need to be re-allocated on every parent recomposition.
    val leftRequesterState = rememberUpdatedState(leftRequester)
    val rightRequesterState = rememberUpdatedState(rightRequester)
    val onClickState = rememberUpdatedState(onClick)
    val onDpadUpState = rememberUpdatedState(onDpadUp)
    val onDpadDownState = rememberUpdatedState(onDpadDown)
    val onPlacedState = rememberUpdatedState(onPlaced)

    Surface(
        onClick = { onClickState.value() },
        modifier = Modifier
            .height(32.dp)
            .onPlaced { coords -> onPlacedState.value(coords.boundsInParent()) }
            .focusRequester(tabFocusRequester)
            .focusProperties {
                canFocus = !isDropdownExpanded
                left = if (isDropdownExpanded) FocusRequester.Cancel else leftRequesterState.value
                right = rightRequesterState.value
            }
            .onFocusChanged {
                isTabFocused = it.isFocused
                if (!it.isFocused) pressArmed = false
                if (it.isFocused) {
                    if (!isDropdownExpanded) {
                        onTabFocused()
                    }
                } else {
                    onTabUnfocused()
                }
            }
            .onPreviewKeyEvent { event ->
                if (isDropdownExpanded) return@onPreviewKeyEvent true
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) pressArmed = true
                        if (event.type == KeyEventType.KeyUp) {
                            if (pressArmed) onClickState.value()
                            pressArmed = false
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (event.type == KeyEventType.KeyDown) onDpadDownState.value() else true
                    KeyEvent.KEYCODE_DPAD_UP -> if (event.type == KeyEventType.KeyDown) onDpadUpState.value() else true
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.type == KeyEventType.KeyDown && leftRequesterState.value != FocusRequester.Cancel) {
                            leftRequesterState.value.safeRequestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.type == KeyEventType.KeyDown && rightRequesterState.value != FocusRequester.Cancel) {
                            rightRequesterState.value.safeRequestFocus()
                        }
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tabName,
                    color = when {
                        isTabFocused && focusPillCoverage.value -> Color.Black
                        isTabFocused -> Color.White
                        isSelected -> Color.White
                        else -> Color(0xFFB3B3B3)
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp
                )

                if (isSelected && !isTabFocused) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(18.dp)
                            .height(2.5.dp)
                            .shadow(elevation = 3.dp, shape = RoundedCornerShape(1.5.dp), spotColor = NetflixRed, ambientColor = NetflixRed)
                            .background(NetflixRed, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileAvatar(
    profile: Profile,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val fallbackUrl = if (profile.isKid) {
        ProfileIcons.KIDS_ANIMATION.firstOrNull() ?: ProfileIcons.CLASSICS.getOrNull(2)
    } else {
        ProfileIcons.CLASSICS.firstOrNull()
    }
    val targetUrl = profile.avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(profile.avatarColor),
        contentAlignment = Alignment.Center
    ) {
        if (!targetUrl.isNullOrBlank()) {
            val context = LocalContext.current
            val request = remember(context, targetUrl) { profileAvatarRequest(context, targetUrl) }
            AsyncImage(
                model = request,
                contentDescription = "${profile.name} Avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = profile.name.take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private class ProfileMenuPositionProvider(
    private val horizontalInsetPx: Int,
    private val verticalInsetPx: Int,
    private val edgePx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val left = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left - horizontalInsetPx
        } else {
            anchorBounds.right - popupContentSize.width + horizontalInsetPx
        }
        // Clamp the whole panel, rather than letting a parent crop its lower rows.
        val maxX = (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(0)
        return IntOffset(
            left.coerceIn(edgePx.coerceAtMost(maxX), maxX),
            (anchorBounds.top - verticalInsetPx).coerceIn(edgePx.coerceAtMost(maxY), maxY)
        )
    }
}

@Composable
private fun HomeProfileMenu(
    currentProfile: Profile,
    alternateProfile: Profile?,
    onSwitchProfiles: () -> Unit,
    onAlternateProfile: () -> Unit,
    onHelp: () -> Unit,
    onExit: () -> Unit,
    onReturnToTabs: () -> Unit
) {
    // The current profile is always first; other account profiles belong in the picker.
    val itemIds = remember { listOf("current", "alternate", "help", "exit") }
    val requesters = remember { List(itemIds.size) { FocusRequester() } }
    val keyPacer = remember { TvKeyPacer() }
    val fallbackKids = remember {
        Profile(id = "menu-kids-artwork", name = "Kids", isKid = true, avatarColor = KidsAccent)
    }
    LaunchedEffect(currentProfile.id) {
        repeat(2) {
            withFrameNanos { }
            if (requesters.first().safeRequestFocus()) return@LaunchedEffect
        }
    }
    Column(
        Modifier.width(216.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .background(Color(0xFF17212B), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemIds.forEachIndexed { index, id ->
            key(id) {
                val profile = when (id) {
                    "current" -> currentProfile
                    "alternate" -> alternateProfile ?: fallbackKids
                    else -> null
                }
                val click = when (id) {
                    "current" -> onSwitchProfiles
                    "alternate" -> onAlternateProfile
                    "help" -> onHelp
                    else -> onExit
                }
                val clickState = rememberUpdatedState(click)
                var focused by remember { mutableStateOf(false) }
                var pressArmed by remember { mutableStateOf(false) }
                Surface(
                    onClick = click,
                    modifier = Modifier.fillMaxWidth().height(when (id) {
                        "current" -> 32.dp
                        "alternate" -> 40.dp
                        else -> 36.dp
                    })
                        .focusRequester(requesters[index])
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = requesters[(index - 1).coerceAtLeast(0)]
                            down = requesters[(index + 1).coerceAtMost(itemIds.lastIndex)]
                        }
                        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) pressArmed = false }
                        .onPreviewKeyEvent { event ->
                            val code = event.nativeKeyEvent.keyCode
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) pressArmed = true
                                    if (event.type == KeyEventType.KeyUp) {
                                        if (pressArmed) clickState.value()
                                        pressArmed = false
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    val direction = if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                    if (event.type == KeyEventType.KeyDown && keyPacer.accept(direction, repeatCount = event.nativeKeyEvent.repeatCount)) {
                                        requesters[(index + direction).coerceIn(itemIds.indices)].safeRequestFocus()
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (event.type == KeyEventType.KeyDown) onReturnToTabs()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> true
                                else -> false
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    val ink = if (focused) Color(0xFF11151B) else Color(0xFFCAD5DE)
                    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (profile != null) ProfileAvatar(profile, size = 28.dp)
                        else Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            Icon(if (id == "help") Icons.Default.HelpOutline else Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            val label = when (id) {
                                "current" -> currentProfile.name.ifBlank { if (currentProfile.isKid) "Kids" else "Profile" }
                                "alternate" -> if (currentProfile.isKid && alternateProfile != null) alternateProfile.name.ifBlank { "Home" } else "Kids"
                                "help" -> "Get Help"
                                else -> "Exit Netflix"
                            }
                            Text(label, color = ink, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (id == "current") Text("Switch Profiles", color = ink.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopNavBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onTabActivated: (String) -> Unit = onTabSelected,
    profileFocusRequester: FocusRequester,
    searchIconFocusRequester: FocusRequester,
    homeTabFocusRequester: FocusRequester,
    seriesTabFocusRequester: FocusRequester,
    filmsTabFocusRequester: FocusRequester,
    myNetflixTabFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    selectedProfile: Profile? = null,
    profiles: List<Profile> = emptyList(),
    onProfileSelected: (Profile) -> Unit = {},
    onManageProfiles: () -> Unit = {},
    compactProfile: Boolean = false,
    onDpadUp: () -> Boolean = { false },
    onDpadDown: () -> Boolean = { false }
) {
    val context = LocalContext.current
    val onTabActivatedState = rememberUpdatedState(onTabActivated)
    val onTabSelectedState = rememberUpdatedState(onTabSelected)
    val onFocusedState = rememberUpdatedState(onFocused)
    val navTabsList = remember { listOf("Home", "Series", "Films", "My Netflix") }
    var showHelpDialog by remember { mutableStateOf(false) }
    val guestProfile = remember { Profile(id = "guest", name = "Guest", avatarColor = NetflixRed) }
    val currentProfile = selectedProfile ?: profiles.firstOrNull() ?: guestProfile
    var expanded by remember(currentProfile.id) { mutableStateOf(false) }
    var lastAdultId by remember { mutableStateOf(selectedProfile?.takeUnless { it.isKid }?.id) }
    LaunchedEffect(currentProfile.id, currentProfile.isKid) {
        if (!currentProfile.isKid) lastAdultId = currentProfile.id
    }
    val alternateProfile = if (currentProfile.isKid) {
        profiles.firstOrNull { it.id == lastAdultId && !it.isKid }
            ?: profiles.firstOrNull { !it.isKid }
    } else profiles.firstOrNull { it.isKid }
    var menuReturnTarget by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(currentProfile.id) { menuReturnTarget = null }
    val horizontalKeyPacer = remember { TvKeyPacer() }

    fun dismissMenu(returnTarget: FocusRequester? = profileFocusRequester) {
        menuReturnTarget = returnTarget
        expanded = false
    }
    fun activeTabRequester(): FocusRequester = when (selectedTab) {
        "Search" -> searchIconFocusRequester
        "Series" -> seriesTabFocusRequester
        "Films" -> filmsTabFocusRequester
        "My Netflix" -> myNetflixTabFocusRequester
        else -> homeTabFocusRequester
    }
    LaunchedEffect(expanded, menuReturnTarget, showHelpDialog, currentProfile.id) {
        val target = menuReturnTarget ?: return@LaunchedEffect
        if (expanded || showHelpDialog) return@LaunchedEffect
        repeat(2) {
            withFrameNanos { }
            // A newer remote focus event owns the cursor; a close must not steal it.
            if (expanded || showHelpDialog || menuReturnTarget !== target) return@LaunchedEffect
            if (target.safeRequestFocus()) {
                menuReturnTarget = null
                return@LaunchedEffect
            }
        }
        menuReturnTarget = null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 24.dp, vertical = 5.dp)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                event.type == KeyEventType.KeyDown && horizontal &&
                    !horizontalKeyPacer.accept(code, repeatCount = event.nativeKeyEvent.repeatCount)
            }
    ) {
        Box(Modifier.align(Alignment.CenterStart)) {
            var pressArmed by remember { mutableStateOf(false) }
            val openMenu = {
                menuReturnTarget = null
                onFocusedState.value()
                expanded = true
            }
            Surface(
                onClick = openMenu,
                modifier = Modifier.height(32.dp)
                    .focusRequester(profileFocusRequester)
                    .focusProperties { right = searchIconFocusRequester }
                    .onFocusChanged {
                        if (!it.isFocused) pressArmed = false
                        if (it.isFocused && !expanded) {
                            menuReturnTarget = null
                            onFocusedState.value()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) pressArmed = true
                                if (event.type == KeyEventType.KeyUp) {
                                    if (pressArmed) openMenu()
                                    pressArmed = false
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) openMenu()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> true
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (event.type == KeyEventType.KeyDown) searchIconFocusRequester.safeRequestFocus()
                                true
                            }
                            else -> false
                        }
                    },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                    focusedContainerColor = Color(0xFF333333)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfileAvatar(currentProfile, size = 24.dp)
                    if (!compactProfile) Text(currentProfile.name, color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select profile",
                        tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }
            if (expanded) {
                val density = LocalDensity.current
                val positionProvider = remember(density) {
                    with(density) { ProfileMenuPositionProvider(8.dp.roundToPx(), 8.dp.roundToPx(), 0) }
                }
                // The popup starts at the header avatar and draws over the billboard.
                // It has its own window, so content slides and row clipping cannot crop it.
                Popup(
                    popupPositionProvider = positionProvider,
                    onDismissRequest = { dismissMenu() },
                    properties = PopupProperties(focusable = true, dismissOnBackPress = true,
                        dismissOnClickOutside = true, clippingEnabled = false)
                ) {
                    HomeProfileMenu(
                        currentProfile = currentProfile,
                        alternateProfile = alternateProfile,
                        onSwitchProfiles = { dismissMenu(null); onManageProfiles() },
                        onAlternateProfile = {
                            dismissMenu(null)
                            // Missing Kids routes to the picker; artwork never creates a profile.
                            if (alternateProfile != null) onProfileSelected(alternateProfile) else onManageProfiles()
                        },
                        onHelp = { dismissMenu(null); showHelpDialog = true },
                        onExit = { dismissMenu(null); (context as? Activity)?.finishAffinity() },
                        onReturnToTabs = { dismissMenu(activeTabRequester()) }
                    )
                }
            }
        }

        // Center: High-performance TV Navigation Tabs with instant zero-latency focus
        val tabBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
        var currentlyFocusedTab by remember { mutableStateOf<String?>(null) }

        // Publish focus in the key event itself. A quick Down must commit the
        // tab already under the cursor, without a stale deferred callback.
        val onNavigationFocused: (String) -> Unit = remember {
            { name ->
                menuReturnTarget = null
                currentlyFocusedTab = name
                onTabSelectedState.value(name)
                onFocusedState.value()
            }
        }

        // perf: stable derivedStateOf so the rest of the tab bar doesn't recompose
        // every time `currentlyFocusedTab` changes before the bounds map is updated.
        val selectedTabState = rememberUpdatedState(selectedTab)
        val targetBounds by remember {
            derivedStateOf { tabBounds[currentlyFocusedTab ?: selectedTabState.value] }
        }

        val animatedX = animateFloatAsState(
            targetValue = targetBounds?.left ?: 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(650f), visibilityThreshold = 0.5f),
            label = "tabX"
        )
        val animatedWidth = animateFloatAsState(
            targetValue = targetBounds?.width ?: 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TvMotion.stiffness(650f), visibilityThreshold = 0.5f),
            label = "tabWidth"
        )
        val animatedAlpha = animateFloatAsState(
            targetValue = if (currentlyFocusedTab != null && targetBounds != null) 1f else 0f,
            animationSpec = tween(durationMillis = TvMotion.duration(200), easing = FastOutSlowInEasing),
            label = "tabAlpha"
        )

        val density = LocalDensity.current
        val focusPillCoverage = remember(density, animatedX, animatedWidth, animatedAlpha, tabBounds) {
            (listOf("Search") + navTabsList).associateWith { name ->
                derivedStateOf {
                    val bounds = tabBounds[name]
                    val inset = with(density) { (if (name == "Search") 6.dp else 10.dp).toPx() }
                    bounds != null && animatedAlpha.value >= 0.6f && animatedX.value <= bounds.left + inset &&
                        animatedX.value + animatedWidth.value >= bounds.right - inset
                }
            }
        }

        // perf: removed dead `isTrackFocused` write in onFocusChanged.
        Box(
            modifier = Modifier.align(Alignment.Center)
                .onFocusChanged { if (it.hasFocus && !expanded) onFocusedState.value() }
        ) {
            NavigationFocusPill(
                horizontalOffset = animatedX,
                width = animatedWidth,
                alpha = animatedAlpha
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            // Search Icon (Index 0)
            // perf: extracted to its own composable so its focus-state change
            // (isSearchFocused) doesn't recompose the whole tab Row.
            val onSearchFocused = remember { { onNavigationFocused("Search") } }
            val onSearchUnfocused = remember { { if (currentlyFocusedTab == "Search") currentlyFocusedTab = null } }
            val onSearchClick = remember { { onTabActivatedState.value("Search") } }
            val onSearchPlaced = remember { { rect: androidx.compose.ui.geometry.Rect -> tabBounds["Search"] = rect } }
            SearchIcon(
                isSearchSelected = selectedTab == "Search",
                isDropdownExpanded = expanded,
                focusPillCoverage = focusPillCoverage.getValue("Search"),
                focusRequester = searchIconFocusRequester,
                leftFocusRequester = profileFocusRequester,
                rightFocusRequester = homeTabFocusRequester,
                onClick = onSearchClick,
                onFocused = onSearchFocused,
                onUnfocused = onSearchUnfocused,
                onPlaced = onSearchPlaced,
                onDpadUp = onDpadUp,
                onDpadDown = onDpadDown
            )

            // Tabs: Home, Series, Films, My Netflix
            // perf: extract each tab to a child composable so per-tab focus state
            // changes (e.g. D-pad) only recompose that tab, not the whole Row.
            // perf: when() lookups moved out of the lambda body so they're hoisted.
            val onTabFocused: (String) -> Unit = remember(navTabsList) {
                { name -> onNavigationFocused(name) }
            }
            val onTabUnfocused: (String) -> Unit = remember(navTabsList) {
                { name -> if (currentlyFocusedTab == name) currentlyFocusedTab = null }
            }
            val onTabPlaced: (String, androidx.compose.ui.geometry.Rect) -> Unit = remember(navTabsList) {
                { name, rect -> tabBounds[name] = rect }
            }
            navTabsList.forEach { tabName ->
                key(tabName) {
                    val (tabFocusRequester, leftRequester, rightRequester) = when (tabName) {
                        "Home" -> Triple(homeTabFocusRequester, searchIconFocusRequester, seriesTabFocusRequester)
                        "Series" -> Triple(seriesTabFocusRequester, homeTabFocusRequester, filmsTabFocusRequester)
                        "Films" -> Triple(filmsTabFocusRequester, seriesTabFocusRequester, myNetflixTabFocusRequester)
                        "My Netflix" -> Triple(myNetflixTabFocusRequester, filmsTabFocusRequester, FocusRequester.Cancel)
                        else -> Triple(homeTabFocusRequester, FocusRequester.Default, FocusRequester.Default)
                    }
                    NavTab(
                        tabName = tabName,
                        isSelected = selectedTab == tabName,
                        isDropdownExpanded = expanded,
                        focusPillCoverage = focusPillCoverage.getValue(tabName),
                        tabFocusRequester = tabFocusRequester,
                        leftRequester = leftRequester,
                        rightRequester = rightRequester,
                        // perf: re-use the same lambda reference across recompositions
                        // by capturing the tabName once.
                        onClick = remember(tabName) { { onTabActivatedState.value(tabName) } },
                        onTabFocused = remember(tabName) { { onTabFocused(tabName) } },
                        onTabUnfocused = remember(tabName) { { onTabUnfocused(tabName) } },
                        onDpadUp = onDpadUp,
                        onDpadDown = onDpadDown,
                        onPlaced = remember(tabName) { { rect: androidx.compose.ui.geometry.Rect -> onTabPlaced(tabName, rect) } }
                    )
                }
            }
        }
        }

        // Right: Red Netflix 'N' Logo or Kids Pill Badge
        if (currentProfile.isKid) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "kids",
                    color = NetflixRed,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_netflix_n),
                contentDescription = "Netflix Logo",
                modifier = Modifier.align(Alignment.CenterEnd).height(29.dp).width(17.dp)
            )
        }
    }

    if (showHelpDialog) {
        val closeButtonFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            closeButtonFocusRequester.safeRequestFocus()
        }

        Dialog(
            onDismissRequest = {
                showHelpDialog = false
                menuReturnTarget = profileFocusRequester
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141414))
                    .clickable {
                        showHelpDialog = false
                        menuReturnTarget = profileFocusRequester
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = { /* Keep inside dialog */ },
                    modifier = Modifier
                        .width(460.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1E24))
                        .border(1.dp, Color(0xFF2B2B2B), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1E1E24),
                        focusedContainerColor = Color(0xFF1E1E24)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            tint = NetflixRed,
                            modifier = Modifier.size(44.dp)
                        )

                        Text(
                            text = "Netflix Help Center",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF141416), RoundedCornerShape(10.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "• App Version: 2.4.0 (Android TV Edition)",
                                color = Color(0xFFE6E6E6),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "• Quick Controls: Use D-pad to navigate, Select/OK to play.",
                                color = Color(0xFFE6E6E6),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "• Back button returns to previous screen or exits dialogs.",
                                color = Color(0xFFE6E6E6),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "• Manage profiles or adjust playback in the Profiles screen.",
                                color = Color(0xFFE6E6E6),
                                fontSize = 13.5.sp
                            )
                        }

                        var isCloseFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                showHelpDialog = false
                                menuReturnTarget = profileFocusRequester
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .focusRequester(closeButtonFocusRequester)
                                .onFocusChanged { isCloseFocused = it.isFocused }
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        val code = keyEvent.nativeKeyEvent.keyCode
                                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                                            showHelpDialog = false
                                            menuReturnTarget = profileFocusRequester
                                            true
                                        } else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                                            showHelpDialog = false
                                            menuReturnTarget = profileFocusRequester
                                            true
                                        } else false
                                    } else false
                                },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isCloseFocused) Color.White else NetflixRed,
                                focusedContainerColor = Color.White
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Close",
                                    color = if (isCloseFocused) Color.Black else Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
