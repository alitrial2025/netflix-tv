@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import android.graphics.Bitmap
import com.example.model.Profile
import com.example.ui.NetflixViewModel
import com.example.ui.components.ProfileIcons
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import com.example.ui.theme.NetflixWhite
import kotlinx.coroutines.delay

data class GenreItem(
    val id: String,
    val name: String,
    val icon: String,
    val color: Color,
    val subtitle: String
)

private val WALKTHROUGH_GENRES = listOf(
    GenreItem("Action", "Action & Adventure", "💥", Color(0xFFE50914), "High-octane blockbusters"),
    GenreItem("Sci-Fi", "Sci-Fi & Cyberpunk", "🚀", Color(0xFF2B59FF), "Mind-bending futures"),
    GenreItem("Crime", "Crime & Mystery", "🔍", Color(0xFF8B5CF6), "Gritty detectives"),
    GenreItem("Anime", "Anime & Japanese", "⚔️", Color(0xFFF59E0B), "Shonen hits"),
    GenreItem("Comedy", "Comedy & Sitcoms", "😂", Color(0xFF10B981), "Laugh-out-loud favorites"),
    GenreItem("Documentary", "Documentaries", "📜", Color(0xFF3B82F6), "Real stories"),
    GenreItem("Horror", "Horror & Thrillers", "👻", Color(0xFF991B1B), "Spine-chilling scares"),
    GenreItem("Romance", "Romance & Drama", "❤️", Color(0xFFEC4899), "Heartfelt emotions")
)

enum class WalkthroughSubScreen {
    MAIN,
    ICON_PICKER,
    LANGUAGE_PICKER,
    NAME_EDITOR,
    PIN_EDITOR
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfileSetupWalkthroughScreen(
    viewModel: NetflixViewModel,
    onComplete: (Profile) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    var step by remember { mutableIntStateOf(1) }
    var currentSubScreen by remember { mutableStateOf(WalkthroughSubScreen.MAIN) }

    var name by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf<String?>(ProfileIcons.ICONS.firstOrNull()) }
    var avatarColor by remember { mutableStateOf(Color(0xFFE50914)) }
    var language by remember { mutableStateOf("English") }
    var isKid by remember { mutableStateOf(false) }
    var maturityRating by remember { mutableStateOf("18+") }
    var pin by remember { mutableStateOf("") }
    var favoriteGenres by remember { mutableStateOf(setOf<String>()) }
    var setupError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = currentSubScreen != WalkthroughSubScreen.MAIN || step > 1 || onCancel != null) {
        if (currentSubScreen != WalkthroughSubScreen.MAIN) {
            currentSubScreen = WalkthroughSubScreen.MAIN
        } else if (step > 1) {
            step--
        } else {
            onCancel?.invoke()
        }
    }

    // Step 4 auto-completion
    LaunchedEffect(step) {
        if (step == 4) {
            delay(1800)
            val finalPin = pin.takeIf { it.length == 4 && it.all(Char::isDigit) }
            val newProfile = try {
                viewModel.addProfile(
                    name = name.ifBlank { if (isKid) "Kids" else "User" },
                    avatarUrl = avatarUrl,
                    avatarColor = if (isKid) Color(0xFFFF9D2B) else Color(0xFFE50914),
                    isKid = isKid,
                    pin = finalPin,
                    language = language,
                    maturityRating = if (isKid) "7+" else maturityRating,
                    favoriteGenres = favoriteGenres.toList()
                )
            } catch (e: Exception) {
                android.util.Log.e("Walkthrough", "Failed to add profile", e)
                setupError = e.message ?: "Profile could not be created. Please try again."
                step = 3
                return@LaunchedEffect
            }
            try {
                viewModel.selectProfile(newProfile)
            } catch (e: Exception) {
                android.util.Log.e("Walkthrough", "Failed to select profile", e)
            }
            onComplete(newProfile)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        AnimatedContent(
            targetState = currentSubScreen,
            transitionSpec = {
                (slideInHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> if (targetState != WalkthroughSubScreen.MAIN) (fullWidth * 0.12f).toInt() else (-fullWidth * 0.12f).toInt() }
                ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)))
                .togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> if (targetState != WalkthroughSubScreen.MAIN) (-fullWidth * 0.08f).toInt() else (fullWidth * 0.08f).toInt() }
                    ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                )
            },
            label = "WalkthroughNavigation"
        ) { subScreen ->
            when (subScreen) {
                WalkthroughSubScreen.MAIN -> {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            val isForward = targetState > initialState
                            (slideInHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> if (isForward) (fullWidth * 0.12f).toInt() else (-fullWidth * 0.12f).toInt() }
                            ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> if (isForward) (-fullWidth * 0.08f).toInt() else (fullWidth * 0.08f).toInt() }
                                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                            )
                        },
                        label = "WalkthroughStepTransition"
                    ) { currentStep ->
                        when (currentStep) {
                            1 -> WalkthroughStep1(
                                name = name,
                                avatarUrl = avatarUrl,
                                avatarColor = avatarColor,
                                onNameClick = { currentSubScreen = WalkthroughSubScreen.NAME_EDITOR },
                                onIconClick = { currentSubScreen = WalkthroughSubScreen.ICON_PICKER },
                                onNext = { step = 2 }
                            )
                            2 -> WalkthroughStep2(
                                isKid = isKid,
                                language = language,
                                maturityRating = maturityRating,
                                pin = pin,
                                avatarUrl = avatarUrl,
                                avatarColor = avatarColor,
                                onKidToggle = { 
                                    isKid = !isKid
                                    maturityRating = if (isKid) "7+" else "18+"
                                    avatarColor = if (isKid) Color(0xFFFF9D2B) else Color(0xFFE50914)
                                },
                                onLanguageClick = { currentSubScreen = WalkthroughSubScreen.LANGUAGE_PICKER },
                                onMaturityClick = { 
                                    if (!isKid) {
                                        val levels = listOf("All Ages", "7+", "13+", "16+", "18+")
                                        val idx = levels.indexOf(maturityRating)
                                        maturityRating = levels[(idx + 1) % levels.size]
                                    }
                                },
                                onPinClick = { currentSubScreen = WalkthroughSubScreen.PIN_EDITOR },
                                onNext = { step = 3 }
                            )
                            3 -> WalkthroughStep3(
                                favoriteGenres = favoriteGenres,
                                errorMessage = setupError,
                                avatarUrl = avatarUrl,
                                avatarColor = avatarColor,
                                onToggleGenre = { g -> 
                                    if (favoriteGenres.contains(g)) favoriteGenres = favoriteGenres - g
                                    else favoriteGenres = favoriteGenres + g
                                },
                                onNext = { setupError = null; step = 4 }
                            )
                            4 -> WalkthroughStep4(
                                name = name,
                                avatarUrl = avatarUrl,
                                isKid = isKid
                            )
                        }
                    }
                }
                WalkthroughSubScreen.ICON_PICKER -> {
                    UpdateProfileIconView(
                        currentProfileName = name.ifBlank { "New Profile" },
                        currentAvatarUrl = avatarUrl,
                        currentAvatarColor = avatarColor,
                        onSelectIcon = { avatarUrl = it; currentSubScreen = WalkthroughSubScreen.MAIN },
                        onDone = { currentSubScreen = WalkthroughSubScreen.MAIN }
                    )
                }
                WalkthroughSubScreen.LANGUAGE_PICKER -> {
                    UpdateProfileLanguageView(
                        selectedLanguage = language,
                        onSelectLanguage = { language = it },
                        onDone = { currentSubScreen = WalkthroughSubScreen.MAIN }
                    )
                }
                WalkthroughSubScreen.NAME_EDITOR -> {
                    ProfileTextEditorScreen(
                        title = "Profile Name",
                        currentText = name,
                        label = "Name",
                        quickSuggestions = listOf("Alex", "Home", "Cinema Fan", "Family"),
                        onDismiss = { currentSubScreen = WalkthroughSubScreen.MAIN },
                        onSave = { 
                            name = it
                            currentSubScreen = WalkthroughSubScreen.MAIN
                        }
                    )
                }
                WalkthroughSubScreen.PIN_EDITOR -> {
                    ProfilePinEditorScreen(
                        profileName = name.ifBlank { "this" },
                        currentPin = pin,
                        onDismiss = { currentSubScreen = WalkthroughSubScreen.MAIN },
                        onSavePin = { updatedPin ->
                            pin = updatedPin.orEmpty()
                            currentSubScreen = WalkthroughSubScreen.MAIN
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WalkthroughRightAvatarColumn(avatarUrl: String?, avatarColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE50914).copy(alpha = 0.50f),
                                Color(0xFFE50914).copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            radius = size.minDimension / 1.5f
                        )
                    )
                }
        )
        Box(
            modifier = Modifier
                .size(175.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(avatarColor)
        ) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val sizePx = remember(density) { with(density) { 175.dp.toPx().toInt().coerceAtLeast(1) } }
            val req = remember(avatarUrl, ctx, sizePx) {
                coil.request.ImageRequest.Builder(ctx)
                    .data(avatarUrl)
                    .size(sizePx, sizePx)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .memoryCacheKey("walkthrough_avatar_${avatarUrl.hashCode()}")
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build()
            }
            AsyncImage(
                model = req,
                contentDescription = "Profile Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WalkthroughStep1(
    name: String,
    avatarUrl: String?,
    avatarColor: Color,
    onNameClick: () -> Unit,
    onIconClick: () -> Unit,
    onNext: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Who will be watching?",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Step 1: Setup Profile Identity",
                    fontSize = 15.sp,
                    color = Color(0xFFAAAAAA)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EditProfileMenuButton(
                        icon = Icons.Default.Person,
                        label = name.ifBlank { "Profile Name" },
                        modifier = Modifier.focusRequester(initialFocusRequester),
                        onClick = onNameClick
                    )
                    EditProfileMenuButton(
                        icon = Icons.Default.Mood,
                        label = "Choose Icon",
                        onClick = onIconClick
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                var isNextFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onNext,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    modifier = Modifier
                        .height(48.dp)
                        .onFocusChanged { isNextFocused = it.isFocused }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Next: Experience",
                            color = if (isNextFocused) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            WalkthroughRightAvatarColumn(avatarUrl, avatarColor)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WalkthroughStep2(
    isKid: Boolean,
    language: String,
    maturityRating: String,
    pin: String,
    avatarUrl: String?,
    avatarColor: Color,
    onKidToggle: () -> Unit,
    onLanguageClick: () -> Unit,
    onMaturityClick: () -> Unit,
    onPinClick: () -> Unit,
    onNext: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Viewing Experience",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Step 2: Parental Controls & Language",
                    fontSize = 15.sp,
                    color = Color(0xFFAAAAAA)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EditProfileMenuButton(
                        icon = Icons.Default.ChildCare,
                        label = if (isKid) "Kids Experience (12 & under): ON" else "Kids Experience (12 & under): OFF",
                        modifier = Modifier.focusRequester(initialFocusRequester),
                        onClick = onKidToggle
                    )
                    EditProfileMenuButton(
                        icon = Icons.Default.Language,
                        label = "Language: $language",
                        onClick = onLanguageClick
                    )
                    EditProfileMenuButton(
                        icon = Icons.Default.Warning,
                        label = if (isKid) "Maturity Rating: 7+ (Kids)" else "Maturity Rating: $maturityRating",
                        onClick = onMaturityClick
                    )
                    EditProfileMenuButton(
                        icon = Icons.Default.Lock,
                        label = if (pin.isNotEmpty()) "PIN Lock: ON" else "PIN Lock: OFF",
                        onClick = onPinClick
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                var isNextFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onNext,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    modifier = Modifier
                        .height(48.dp)
                        .onFocusChanged { isNextFocused = it.isFocused }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Next: Tastes",
                            color = if (isNextFocused) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            WalkthroughRightAvatarColumn(avatarUrl, avatarColor)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WalkthroughStep3(
    favoriteGenres: Set<String>,
    errorMessage: String?,
    avatarUrl: String?,
    avatarColor: Color,
    onToggleGenre: (String) -> Unit,
    onNext: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Your Tastes",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Step 3: Select favorite genres",
                    fontSize = 15.sp,
                    color = Color(0xFFAAAAAA)
                )
                Spacer(modifier = Modifier.height(32.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Stable key + contentType so the grid can recycle items instead of
                    // re-composing on every recomposition / scroll.
                    items(WALKTHROUGH_GENRES, key = { it.id }, contentType = { "genre_card" }) { genre ->
                        val isSelected = favoriteGenres.contains(genre.id)
                        var isFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onToggleGenre(genre.id) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) NetflixRed.copy(alpha = 0.2f) else Color(0xFF1E1E1E),
                                focusedContainerColor = Color.White
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(1.dp, if (isSelected) NetflixRed else Color.Transparent)),
                                focusedBorder = Border(BorderStroke(2.dp, Color.White))
                            ),
                            modifier = Modifier
                                .height(56.dp)
                                .onFocusChanged { isFocused = it.isFocused }
                                .then(if (genre == WALKTHROUGH_GENRES.first()) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${genre.icon} ${genre.name}",
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                if (errorMessage != null) {
                    Text(errorMessage, color = NetflixRed, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                var isNextFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onNext,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NetflixRed,
                        focusedContainerColor = Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    modifier = Modifier
                        .height(48.dp)
                        .onFocusChanged { isNextFocused = it.isFocused }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Complete Setup",
                            color = if (isNextFocused) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            WalkthroughRightAvatarColumn(avatarUrl, avatarColor)
        }
    }
}

@Composable
fun WalkthroughStep4(name: String, avatarUrl: String?, isKid: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "launch_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_pulse"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .scale(scale)
                .size(136.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(4.dp, if (isKid) Color(0xFFFF9D2B) else NetflixRed, RoundedCornerShape(20.dp))
        ) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val sizePx = remember(density) { with(density) { 136.dp.toPx().toInt().coerceAtLeast(1) } }
            val req = remember(avatarUrl, ctx, sizePx) {
                coil.request.ImageRequest.Builder(ctx)
                    .data(avatarUrl)
                    .size(sizePx, sizePx)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .memoryCacheKey("walkthrough_launch_${avatarUrl.hashCode()}")
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build()
            }
            AsyncImage(
                model = req,
                contentDescription = "Profile Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Launching ${name.ifBlank { if (isKid) "Kids" else "User" }}...",
            color = NetflixWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
