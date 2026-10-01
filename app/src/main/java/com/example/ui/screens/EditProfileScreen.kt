@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import com.example.ui.components.ProfilePinEntryScreen
import com.example.ui.components.ProfilePinVerificationScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.*
import android.view.KeyEvent
import androidx.tv.material3.*
import coil.compose.AsyncImage
import android.graphics.Bitmap
import com.example.model.Profile
import com.example.ui.NetflixViewModel
import com.example.ui.components.ProfileIcons
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixRed
import com.example.ui.theme.NetflixWhite
import com.example.ui.util.TvKeyPacer
import com.example.ui.util.TvMotion

enum class EditProfileSubScreen {
    MAIN,
    ICON_PICKER,
    LANGUAGE_PICKER,
    NAME_EDITOR,
    GAME_HANDLE_EDITOR,
    DELETE_CONFIRM,
    PIN_EDITOR
}

// 32 Languages arranged into 4 columns matching Image 3
private val LANGUAGE_COLUMNS = listOf(
    listOf("Dansk", "Deutsch", "English", "Español", "Filipino", "Français", "Hrvatski"),
    listOf("Indonesia", "Italiano", "Magyar", "Melayu", "Nederlands", "Norsk bokmål", "Polski", "Português"),
    listOf("Română", "Suomi", "Svenska", "Tiếng Việt", "Türkçe", "Čeština", "Ελληνικά", "Русский"),
    listOf("Українська", "עברית", "العربية", "हिन्दी", "ไทย", "中文", "日本語", "한국어")
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditProfileScreen(
    profileId: String,
    viewModel: NetflixViewModel,
    onBack: () -> Unit
) {
    val isNewProfile = profileId == "new"
    val profiles by viewModel.profiles.collectAsState()
    val initialProfile = remember(profiles, profileId) {
        if (isNewProfile) {
            Profile(
                id = "new",
                name = "Home",
                avatarColor = Color(0xFFE50914),
                isKid = false,
                avatarUrl = ProfileIcons.ICONS.firstOrNull(),
                pin = null,
                language = "English",
                autoplayNext = true,
                autoplayPreviews = true,
                maturityRating = "18+",
                gameHandle = null
            )
        } else {
            profiles.find { it.id == profileId }
        }
    }

    if (initialProfile == null) {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize().background(NetflixBlack), contentAlignment = Alignment.Center) {
            Button(onClick = onBack) {
                Text(if (profiles.isEmpty()) "Loading profile…" else "Profile unavailable. Go back")
            }
        }
        return
    }

    var currentSubScreen by remember(profileId) { mutableStateOf(EditProfileSubScreen.MAIN) }

    var name by remember(profileId) { mutableStateOf(initialProfile.name) }
    var language by remember(profileId) { mutableStateOf(initialProfile.language) }
    var gameHandle by remember(profileId) { mutableStateOf(initialProfile.gameHandle ?: "") }
    var avatarUrl by remember(profileId) { mutableStateOf(initialProfile.avatarUrl ?: ProfileIcons.ICONS.firstOrNull()) }
    var avatarColor by remember(profileId) { mutableStateOf(initialProfile.avatarColor) }
    var isKid by remember(profileId) { mutableStateOf(initialProfile.isKid) }
    var maturityRating by remember(profileId) { mutableStateOf(initialProfile.maturityRating) }
    var autoplayNext by remember(profileId) { mutableStateOf(initialProfile.autoplayNext) }
    var autoplayPreviews by remember(profileId) { mutableStateOf(initialProfile.autoplayPreviews) }
    var newPin by remember(profileId) { mutableStateOf<String?>(null) }
    var pinRemoved by remember(profileId) { mutableStateOf(false) }
    val pin = if (pinRemoved) "" else newPin ?: initialProfile.pin.orEmpty()
    var menuReadyAt by remember(profileId) { mutableLongStateOf(0L) }
    var isSaving by remember(profileId) { mutableStateOf(false) }
    var saveError by remember(profileId) { mutableStateOf<String?>(null) }

    fun openSubScreen(screen: EditProfileSubScreen) {
        if (currentSubScreen == EditProfileSubScreen.MAIN &&
            android.os.SystemClock.uptimeMillis() >= menuReadyAt) {
            currentSubScreen = screen
        }
    }

    fun closeSubScreen() {
        currentSubScreen = EditProfileSubScreen.MAIN
        // The outgoing editor is still composed during its exit animation.
        // Ignore a held OK key until that transition finishes.
        menuReadyAt = android.os.SystemClock.uptimeMillis() + 350L
    }

    // Back handling
    BackHandler {
        if (currentSubScreen != EditProfileSubScreen.MAIN) {
            closeSubScreen()
        } else {
            onBack()
        }
    }

    fun saveProfileAndExit() {
        if (isSaving) return
        isSaving = true
        saveError = null
        // Validate the name: require 1..25 chars, fall back to "Profile" if blank,
        // and reject pure-whitespace or control-character names.
        val rawName = name.trim()
        val sanitizedName = if (rawName.isEmpty()) "Profile"
            else rawName.filterNot { it.isISOControl() }.take(25).ifBlank { "Profile" }
        // Validate the optional PIN: must be exactly 4 digits or empty (to clear)
        val pinToStore = if (pinRemoved) null else newPin ?: initialProfile.pin
        val finalGameHandle = if (gameHandle.isBlank()) null else gameHandle.trim().take(20)
        try {
            if (isNewProfile) {
                viewModel.addProfile(
                    name = sanitizedName,
                    avatarUrl = avatarUrl,
                    avatarColor = avatarColor,
                    isKid = isKid,
                    pin = pinToStore,
                    language = language,
                    maturityRating = maturityRating,
                    gameHandle = finalGameHandle
                )
            } else {
                val updated = viewModel.updateProfile(
                    initialProfile.copy(
                        name = sanitizedName,
                        language = language,
                        isKid = isKid,
                        maturityRating = maturityRating,
                        autoplayNext = autoplayNext,
                        autoplayPreviews = autoplayPreviews,
                        pin = pinToStore,
                        avatarUrl = avatarUrl,
                        avatarColor = avatarColor,
                        gameHandle = finalGameHandle
                    )
                )
                if (!updated) error("This profile is no longer available.")
            }
            onBack()
        } catch (e: Exception) {
            saveError = e.message ?: "Could not save the profile. Please try again."
            isSaving = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        AnimatedContent(
            targetState = currentSubScreen,
            transitionSpec = {
                if (targetState != EditProfileSubScreen.MAIN) {
                    (slideInHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() }
                    ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> (-fullWidth * 0.08f).toInt() }
                        ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                    )
                } else {
                    (slideInHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.12f).toInt() }
                    ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> (fullWidth * 0.08f).toInt() }
                        ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                    )
                }
            },
            label = "EditProfileNavigation"
        ) { subScreen ->
            when (subScreen) {
                EditProfileSubScreen.MAIN -> {
                    EditProfileMainView(
                        name = name,
                        language = language,
                        gameHandle = gameHandle,
                        avatarUrl = avatarUrl,
                        avatarColor = avatarColor,
                        isKid = isKid,
                        pin = pin,
                        errorMessage = saveError,
                        canDelete = !isNewProfile && profiles.size > 1,
                        onNameClick = { openSubScreen(EditProfileSubScreen.NAME_EDITOR) },
                        onIconClick = { openSubScreen(EditProfileSubScreen.ICON_PICKER) },
                        onKidsToggle = {
                            isKid = !isKid
                            if (isKid) {
                                maturityRating = "7+"
                                if (avatarColor == Color(0xFFE50914)) {
                                    avatarColor = Color(0xFFFF9D2B)
                                }
                            } else {
                                maturityRating = "18+"
                            }
                        },
                        onGameHandleClick = { openSubScreen(EditProfileSubScreen.GAME_HANDLE_EDITOR) },
                        onLanguageClick = { openSubScreen(EditProfileSubScreen.LANGUAGE_PICKER) },
                        onPinClick = { openSubScreen(EditProfileSubScreen.PIN_EDITOR) },
                        onDoneClick = { saveProfileAndExit() },
                        onDeleteClick = { openSubScreen(EditProfileSubScreen.DELETE_CONFIRM) }
                    )
                }

                EditProfileSubScreen.PIN_EDITOR -> {
                    ProfilePinEditorScreen(
                        profileName = name,
                        currentPin = pin,
                        onDismiss = { closeSubScreen() },
                        onSavePin = { updatedPin ->
                            if (updatedPin == null) {
                                pinRemoved = true
                                newPin = null
                            } else {
                                pinRemoved = false
                                newPin = updatedPin
                            }
                            closeSubScreen()
                        }
                    )
                }

                EditProfileSubScreen.ICON_PICKER -> {
                    UpdateProfileIconView(
                        currentProfileName = name,
                        currentAvatarUrl = avatarUrl,
                        currentAvatarColor = avatarColor,
                        onSelectIcon = { selectedUrl ->
                            avatarUrl = selectedUrl
                        },
                        onDone = { closeSubScreen() }
                    )
                }

                EditProfileSubScreen.LANGUAGE_PICKER -> {
                    UpdateProfileLanguageView(
                        selectedLanguage = language,
                        onSelectLanguage = { selected ->
                            language = selected
                        },
                        onDone = { closeSubScreen() }
                    )
                }

                EditProfileSubScreen.NAME_EDITOR -> {
                    ProfileTextEditorScreen(
                        title = "Edit Profile Name",
                        currentText = name,
                        label = "Profile Name",
                        quickSuggestions = listOf("Home", "Derrick", "Karan", "Mom", "Kids", "Family", "Guest"),
                        onDismiss = { closeSubScreen() },
                        onSave = { newName ->
                            name = newName
                            closeSubScreen()
                        }
                    )
                }

                EditProfileSubScreen.GAME_HANDLE_EDITOR -> {
                    ProfileTextEditorScreen(
                        title = "Edit Game Handle",
                        currentText = gameHandle,
                        label = "Game Handle",
                        allowEmpty = true,
                        maxLength = 20,
                        quickSuggestions = listOf("GamerTag", "ProPlayer", "Shadow", "Speedy", "Legend", "Pixel"),
                        onDismiss = { closeSubScreen() },
                        onSave = { newHandle ->
                            gameHandle = newHandle
                            closeSubScreen()
                        }
                    )
                }

                EditProfileSubScreen.DELETE_CONFIRM -> {
                    DeleteProfileConfirmScreen(
                        profileName = name,
                        onDismiss = { closeSubScreen() },
                        onConfirmDelete = {
                            val deleted = runCatching { viewModel.deleteProfile(profileId) }.getOrDefault(false)
                            if (deleted) onBack()
                            deleted
                        }
                    )
                }
            }
        }
    }
}

// =============================================================================
// 1. MAIN SCREEN VIEW: Exactly matching Image 1
// =============================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditProfileMainView(
    name: String,
    language: String,
    gameHandle: String,
    avatarUrl: String?,
    avatarColor: Color,
    isKid: Boolean,
    pin: String,
    errorMessage: String?,
    canDelete: Boolean,
    onNameClick: () -> Unit,
    onIconClick: () -> Unit,
    onKidsToggle: () -> Unit,
    onGameHandleClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPinClick: () -> Unit,
    onDoneClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Title, Subtitle, Menu Buttons, Age Rating, Done & Delete
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Edit profile",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Select what you want to change.",
                    fontSize = 15.sp,
                    color = Color(0xFFAAAAAA)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Menu items
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. Name Option
                    EditProfileMenuButton(
                        icon = Icons.Default.Person,
                        label = "Name",
                        modifier = Modifier.focusRequester(initialFocusRequester),
                        onClick = onNameClick
                    )

                    // 2. Icon Option
                    EditProfileMenuButton(
                        icon = Icons.Default.Mood,
                        label = "Icon",
                        onClick = onIconClick
                    )

                    // 3. Kids Experience Toggle Option
                    EditProfileMenuButton(
                        icon = Icons.Default.ChildCare,
                        label = if (isKid) "Kids experience (ON)" else "Kids experience (OFF)",
                        onClick = onKidsToggle
                    )

                    // 4. Game handle Option
                    EditProfileMenuButton(
                        icon = Icons.Default.SportsEsports,
                        label = if (gameHandle.isNotBlank()) "Game handle ($gameHandle)" else "Game handle",
                        onClick = onGameHandleClick
                    )

                    // 5. Language Option
                    EditProfileMenuButton(
                        icon = Icons.Default.Translate,
                        label = "Language",
                        onClick = onLanguageClick
                    )

                    // 6. Profile Lock PIN Option
                    EditProfileMenuButton(
                        icon = Icons.Default.Lock,
                        label = if (pin.isNotBlank()) "Profile lock (PIN On)" else "Profile lock (PIN Off)",
                        onClick = onPinClick
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Age Rating Tag
                Box(
                    modifier = Modifier
                        .background(
                            if (isKid) Color(0xFFFF9D2B) else Color(0xFF333333),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isKid) "12 and under (TV-Y, TV-G, PG, 12)" else "All Age Ratings (18+)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isKid) Color.Black else Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isKid) "Only shows titles rated 12 and under for kids and family. Adult and mature content is restricted."
                           else "Showing titles of all age ratings for this profile.",
                    fontSize = 13.sp,
                    color = Color(0xFF999999),
                    lineHeight = 18.sp
                )
                Text(
                    text = "Use the profile settings on your phone to change viewing restrictions.",
                    fontSize = 13.sp,
                    color = Color(0xFF999999),
                    lineHeight = 18.sp
                )
            }

            // Bottom Buttons: Done & Delete Profile
            if (errorMessage != null) {
                Text(errorMessage, color = NetflixRed, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Done Button (Pill shaped)
                var isDoneFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onDoneClick,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.White
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp) // a11y minimum tap target
                        .semantics {
                            contentDescription = "Done"
                            role = Role.Button
                        }
                        .onFocusChanged { isDoneFocused = it.isFocused }
                ) {
                    Box(Modifier.height(48.dp).widthIn(min = 128.dp).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                        Text("Done", color = if (isDoneFocused) Color.Black else Color.White,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }

                // Delete Profile Button
                if (canDelete) {
                    var isDeleteFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onDeleteClick,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp) // a11y minimum tap target
                            .semantics {
                                contentDescription = "Delete Profile"
                                role = Role.Button
                            }
                            .onFocusChanged { isDeleteFocused = it.isFocused }
                    ) {
                        Box(Modifier.height(48.dp).widthIn(min = 128.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text("Delete Profile", color = if (isDeleteFocused) NetflixRed else Color(0xFFCCCCCC),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Right Column: Profile Avatar Preview with Radial Ambient Glow
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            val glowColor = if (isKid) Color(0xFFFF9D2B) else Color(0xFFE50914)
            val glowBrush = remember(isKid) {
                Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.50f),
                        glowColor.copy(alpha = 0.20f),
                        Color.Transparent
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(360.dp)
                    .drawBehind {
                        drawCircle(
                            brush = glowBrush,
                            radius = size.minDimension / 1.5f
                        )
                    }
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Large Avatar Card
                Box(
                    modifier = Modifier
                        .size(175.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(avatarColor)
                        .shadow(16.dp, RoundedCornerShape(12.dp))
                ) {
                    if (!avatarUrl.isNullOrBlank()) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val avSizePx = remember(density) { with(density) { 175.dp.toPx().toInt().coerceAtLeast(1) } }
                        val avatarRequest = remember(avatarUrl, context, avSizePx) {
                            coil.request.ImageRequest.Builder(context)
                                .data(avatarUrl)
                                .size(avSizePx, avSizePx)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .memoryCacheKey("edit_main_avatar_${avatarUrl.hashCode()}")
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .allowHardware(false)
                                .build()
                        }
                        AsyncImage(
                            model = avatarRequest,
                            contentDescription = "Profile Avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = name.take(1).uppercase(),
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (isKid) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0xFFFF9D2B).copy(alpha = 0.92f))
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "KIDS",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Profile Name (e.g., "Home")
                Text(
                    text = name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Profile Language (e.g., "English")
                Text(
                    text = language,
                    fontSize = 14.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Menu pill button component
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditProfileMenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1E1E1E),
            focusedContainerColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = modifier
            .fillMaxWidth(0.72f)
            .heightIn(min = 48.dp) // a11y minimum tap target
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = label,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// 2. ICON PICKER VIEW: Exactly matching Image 2 with Grouped Categories & Smooth D-Pad Sliding
// =============================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateProfileIconView(
    currentProfileName: String,
    currentAvatarUrl: String?,
    currentAvatarColor: Color,
    onSelectIcon: (String) -> Unit,
    onDone: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusedCategoryIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    // Only move the vertical list when the focused row would be clipped.
    LaunchedEffect(focusedCategoryIndex) {
        if (focusedCategoryIndex == 0 && listState.firstVisibleItemIndex == 0) {
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == focusedCategoryIndex }
        if (row == null) {
            listState.animateScrollToItem(focusedCategoryIndex)
        } else {
            val top = layout.viewportStartOffset + 8
            val bottom = layout.viewportEndOffset - 8
            when {
                row.offset < top -> listState.animateScrollBy((row.offset - top).toFloat())
                row.offset + row.size > bottom ->
                    listState.animateScrollBy((row.offset + row.size - bottom).toFloat())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 36.dp, bottom = 16.dp, start = 56.dp, end = 56.dp)
    ) {
        // Header Row: Title & Subtitle on left, Profile mini preview on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Edit profile",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Update your profile icon.",
                    fontSize = 15.sp,
                    color = Color(0xFFAAAAAA)
                )
            }

            // Top Right Mini Profile Indicator (Matching Image 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = currentProfileName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentAvatarColor)
                ) {
                    if (!currentAvatarUrl.isNullOrBlank()) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val miniSize = remember(density) { with(density) { 48.dp.toPx().toInt().coerceAtLeast(1) } }
                        val miniRequest = remember(currentAvatarUrl, ctx, miniSize) {
                            coil.request.ImageRequest.Builder(ctx)
                                .data(currentAvatarUrl)
                                .size(miniSize, miniSize)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .memoryCacheKey("edit_mini_avatar_${currentAvatarUrl.hashCode()}")
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .allowHardware(false)
                                .build()
                        }
                        AsyncImage(
                            model = miniRequest,
                            contentDescription = "Current Avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = currentProfileName.take(1).uppercase(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Categories with Smooth Sliding Horizontal Rows of Icons
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            items(
                count = ProfileIcons.CATEGORIES.size,
                key = { ProfileIcons.CATEGORIES[it].id },
                contentType = { "icon_category_row" }
            ) { catIdx ->
                val category = ProfileIcons.CATEGORIES[catIdx]
                IconCategoryRow(
                    category = category,
                    selectedAvatarUrl = currentAvatarUrl,
                    isFirstCategory = catIdx == 0,
                    initialFocusRequester = initialFocusRequester,
                    onRowFocused = {
                        focusedCategoryIndex = catIdx
                    },
                    onSelectIcon = onSelectIcon
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDone) {
                Text("Done", modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconCategoryRow(
    category: com.example.ui.components.ProfileIconCategory,
    selectedAvatarUrl: String?,
    isFirstCategory: Boolean,
    initialFocusRequester: FocusRequester,
    onRowFocused: () -> Unit,
    onSelectIcon: (String) -> Unit
) {
    val cardSize = 92.dp
    val gap = 14.dp
    val initialIndex = remember(category.id, selectedAvatarUrl) {
        category.icons.indexOfFirst { it == selectedAvatarUrl }.coerceAtLeast(0)
    }
    var focusedIconIndex by rememberSaveable(category.id) { mutableIntStateOf(initialIndex) }
    var isRowFocused by remember { mutableStateOf(false) }
    val keyPacer = remember(category.id) { TvKeyPacer() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spacingPx = remember(density) { with(density) { (cardSize + gap).toPx() } }
    val iconSizePx = remember(density) { with(density) { cardSize.roundToPx().coerceAtLeast(1) } }
    val animatedIndex by animateFloatAsState(
        targetValue = focusedIconIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = TvMotion.stiffness(500f),
            visibilityThreshold = 0.005f
        ),
        label = "iconRowSlide_${category.id}"
    )
    val context = androidx.compose.ui.platform.LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Category Name on Left
        Box(
            modifier = Modifier
                .width(150.dp)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = category.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRowFocused) Color.White else Color(0xFFBBBBBB),
                lineHeight = 20.sp
            )
        }

        // Each row has one stationary focus target. The artwork moves beneath
        // it, so fast D-pad repeats cannot lose the ring or hit an absent card.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(cardSize + 28.dp)
                .clipToBounds()
        ) {
            val visibleCount = (maxWidth / (cardSize + gap)).toInt() + 2
            Row(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -animatedIndex * spacingPx }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                category.icons.forEachIndexed { index, iconUrl ->
                    val isSelected = iconUrl == selectedAvatarUrl
                    val showArtwork = index in (focusedIconIndex - 4)..(focusedIconIndex + visibleCount)
                    val iconRequest = remember(iconUrl, showArtwork, context, iconSizePx) {
                        if (showArtwork) {
                            coil.request.ImageRequest.Builder(context)
                                .data(iconUrl)
                                .size(iconSizePx, iconSizePx)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                // Avatar icons are shared across many profile flows; using the URL
                                // as the cache key lets the same icon in different surfaces share
                                // a single bitmap.
                                .memoryCacheKey("profile_icon_${iconUrl.hashCode()}")
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .allowHardware(false)
                                .build()
                        } else null
                    }

                    Box(
                        modifier = Modifier
                            .size(cardSize)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF222222))
                    ) {
                        if (iconRequest != null) {
                            AsyncImage(
                                model = iconRequest,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (isSelected) {
                            Box(
                                modifier = Modifier.matchParentSize()
                                    .border(3.dp, NetflixRed, RoundedCornerShape(10.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(18.dp)
                                    .background(NetflixRed, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 4.dp, y = 10.dp)
                    .size(cardSize)
                    .then(if (isFirstCategory) Modifier.focusRequester(initialFocusRequester) else Modifier)
                    .onFocusChanged {
                        isRowFocused = it.isFocused
                        if (it.isFocused) onRowFocused()
                    }
                    .onPreviewKeyEvent { event ->
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (event.type == KeyEventType.KeyDown &&
                                    keyPacer.accept(event.nativeKeyEvent.keyCode)) {
                                    val step = if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                    focusedIconIndex = (focusedIconIndex + step).coerceIn(0, category.icons.lastIndex)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    category.icons.getOrNull(focusedIconIndex)?.let(onSelectIcon)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics {
                        contentDescription = "${category.title}, avatar ${focusedIconIndex + 1} of ${category.icons.size}"
                        role = Role.Button
                    }
                    .focusable()
                    .then(
                        if (isRowFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp))
                        else Modifier
                    )
            )
        }
    }
}

// =============================================================================
// 3. LANGUAGE PICKER VIEW: Exactly matching Image 3 (4 Columns of Languages)
// =============================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateProfileLanguageView(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    onDone: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    val focusLanguage = remember(selectedLanguage) {
        LANGUAGE_COLUMNS.flatten().firstOrNull { it.equals(selectedLanguage, ignoreCase = true) }
            ?: LANGUAGE_COLUMNS.first().first()
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { initialFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Edit profile",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Update your language.",
                fontSize = 15.sp,
                color = Color(0xFFAAAAAA)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // 4 Clean Columns of Languages (Matching Image 3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                LANGUAGE_COLUMNS.forEachIndexed { colIndex, columnLanguages ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        columnLanguages.forEach { lang ->
                            val isSelected = lang.equals(selectedLanguage, ignoreCase = true)
                            var isFocused by remember { mutableStateOf(false) }

                            Surface(
                                onClick = { onSelectLanguage(lang) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White else Color.Transparent,
                                    focusedContainerColor = Color.White
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .then(
                                        if (lang == focusLanguage) Modifier.focusRequester(initialFocusRequester)
                                        else Modifier
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = lang,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected || isFocused) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Done Button (Pill shape on bottom left)
        var isDoneFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = onDone,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF2A2A2A),
                focusedContainerColor = Color.White
            ),
            modifier = Modifier.onFocusChanged { isDoneFocused = it.isFocused }
        ) {
            Box(Modifier.height(48.dp).widthIn(min = 128.dp).padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                Text("Done", color = if (isDoneFocused) Color.Black else Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

// =============================================================================
// 4. TEXT EDITOR FULL SCREEN (For Name & Game Handle with Side-by-Side TV Keyboard)
// =============================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfileTextEditorScreen(
    title: String,
    currentText: String,
    label: String,
    quickSuggestions: List<String>,
    allowEmpty: Boolean = false,
    maxLength: Int = 25,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var editedText by remember(title, currentText) { mutableStateOf(currentText) }
    
    val inputFocusRequester = remember { FocusRequester() }
    val spaceKeyRequester = remember { FocusRequester() }
    val backspaceRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val firstKeyRequester = remember { FocusRequester() }
    val saveButtonRequester = remember { FocusRequester() }
    val cancelButtonRequester = remember { FocusRequester() }

    val keyboardKeys = remember {
        listOf(
            "a", "b", "c", "d", "e", "f",
            "g", "h", "i", "j", "k", "l",
            "m", "n", "o", "p", "q", "r",
            "s", "t", "u", "v", "w", "x",
            "y", "z", "1", "2", "3", "4",
            "5", "6", "7", "8", "9", "0",
            "-", "_", ".", "'", "&", "!"
        )
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.Top
        ) {
            // ==========================================
            // LEFT PANE: Header, Input Field & Action Buttons
            // ==========================================
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "PROFILE SETTINGS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = NetflixRed,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter a name for this profile or select a suggestion.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }

                // Interactive TV Text Input Field
                var isInputFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { /* Keep focused */ },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isInputFocused) Color(0xFF1E1E24) else Color(0xFF16161A),
                        focusedContainerColor = Color(0xFF22222A)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.5.dp, Color(0xFF2A2A30))),
                        focusedBorder = Border(BorderStroke(2.dp, Color.White))
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .focusRequester(inputFocusRequester)
                        .focusProperties {
                            right = spaceKeyRequester
                            down = saveButtonRequester
                        }
                        .onFocusChanged { isInputFocused = it.isFocused }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        try { spaceKeyRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        try { saveButtonRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DEL -> {
                                        if (editedText.isNotEmpty()) editedText = editedText.dropLast(1)
                                        true
                                    }
                                    else -> {
                                        val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar
                                        if (unicodeChar > 31 && unicodeChar != 127 && editedText.length < maxLength) {
                                            editedText += unicodeChar.toChar().toString()
                                            true
                                        } else false
                                    }
                                }
                            } else false
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isInputFocused) Color.White else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )

                        Text(
                            text = if (editedText.isEmpty()) "Enter profile name" else editedText,
                            color = if (editedText.isEmpty()) Color.Gray else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        // Cursor
                        if (isInputFocused) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(20.dp)
                                    .background(Color.White)
                            )
                        }

                        Text(
                            text = "${editedText.length}/$maxLength",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                // Quick Suggestions Chips
                if (quickSuggestions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Suggestions",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickSuggestions.forEach { suggestion ->
                                var isSuggestionFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { editedText = suggestion },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        focusedContainerColor = Color.White
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                    modifier = Modifier
                                        .onFocusChanged { isSuggestionFocused = it.isFocused }
                                        .focusProperties {
                                            right = spaceKeyRequester
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = suggestion,
                                            color = if (isSuggestionFocused) Color.Black else Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Action Buttons Row (Save & Cancel)
                val canSave = allowEmpty || editedText.trim().isNotEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Save Button
                    var isSaveFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            if (canSave) onSave(editedText.trim())
                        },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (canSave) NetflixRed else Color(0xFF252528),
                            focusedContainerColor = Color.White
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, if (canSave) NetflixRed else Color(0xFF3A3A40))),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        modifier = Modifier
                            .height(48.dp)
                            .focusRequester(saveButtonRequester)
                            .focusProperties {
                                up = inputFocusRequester
                                right = cancelButtonRequester
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Save Changes"
                                role = Role.Button
                            }
                            .onFocusChanged { isSaveFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (isSaveFocused) Color.Black else if (canSave) Color.White else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Save Changes",
                                color = if (isSaveFocused) Color.Black else if (canSave) Color.White else Color.Gray,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Cancel Button
                    var isCancelFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onDismiss,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF202024),
                            focusedContainerColor = Color.White
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, Color(0xFF383840))),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        modifier = Modifier
                            .height(48.dp)
                            .focusRequester(cancelButtonRequester)
                            .focusProperties {
                                left = saveButtonRequester
                                up = inputFocusRequester
                                right = spaceKeyRequester
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Cancel"
                                role = Role.Button
                            }
                            .onFocusChanged { isCancelFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isCancelFocused) Color.Black else Color(0xFFCCCCCC),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Cancel",
                                color = if (isCancelFocused) Color.Black else Color(0xFFE0E0E0),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ==========================================
            // RIGHT PANE: On-Screen TV Keyboard
            // ==========================================
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ON-SCREEN KEYBOARD",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                // Top Action Row: Space, Backspace & Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Space Key
                    com.example.ui.components.KeyboardKeyButton(
                        text = "",
                        customContent = {
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(5.dp)
                                    .background(Color.White.copy(alpha = 0.7f), CircleShape)
                            )
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .focusRequester(spaceKeyRequester)
                            .focusProperties {
                                left = inputFocusRequester
                                right = backspaceRequester
                                down = firstKeyRequester
                            },
                        onClick = {
                            if (editedText.isNotEmpty() && !editedText.endsWith(" ") && editedText.length < maxLength) {
                                editedText += " "
                            }
                        }
                    )

                    // Backspace Key
                    com.example.ui.components.KeyboardKeyButton(
                        text = "",
                        icon = Icons.AutoMirrored.Default.Backspace,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(backspaceRequester)
                            .focusProperties {
                                left = spaceKeyRequester
                                right = clearRequester
                            },
                        onClick = {
                            if (editedText.isNotEmpty()) editedText = editedText.dropLast(1)
                        }
                    )

                    // Clear Key
                    com.example.ui.components.KeyboardKeyButton(
                        text = "",
                        icon = Icons.Default.Clear,
                        modifier = Modifier
                            .weight(0.8f)
                            .focusRequester(clearRequester)
                            .focusProperties {
                                left = backspaceRequester
                            },
                        onClick = { editedText = "" }
                    )
                }

                // 6-Column Grid of Keys
                LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(keyboardKeys, key = { _, k -> "edit_key_$k" }) { index, keyChar ->
                        val isLeftEdge = index % 6 == 0
                        val isTopRow = index < 6

                        val keyModifier = when {
                            index == 0 -> Modifier.focusRequester(firstKeyRequester).focusProperties {
                                up = spaceKeyRequester
                                left = inputFocusRequester
                            }
                            isLeftEdge -> Modifier.focusProperties {
                                left = inputFocusRequester
                            }
                            isTopRow -> Modifier.focusProperties {
                                up = spaceKeyRequester
                            }
                            else -> Modifier
                        }

                        com.example.ui.components.KeyboardKeyButton(
                            text = keyChar,
                            modifier = keyModifier,
                            onClick = {
                                if (editedText.length < maxLength) {
                                    editedText += keyChar
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// 5. DELETE PROFILE CONFIRMATION FULL SCREEN
// =============================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DeleteProfileConfirmScreen(
    profileName: String,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var deleteError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(horizontal = 64.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(Color(0xFF1B1B1B), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(NetflixRed.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(36.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Delete Profile?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Are you sure you want to delete '$profileName'? This profile's viewing history and ratings will be removed. This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (deleteError) {
                Text("Could not delete this profile. Please try again.", color = NetflixRed, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(6.dp))
                ) {
                    Text("Cancel", modifier = Modifier.padding(vertical = 10.dp), fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { deleteError = !onConfirmDelete() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.colors(
                        containerColor = NetflixRed,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFB81D24),
                        focusedContentColor = Color.White
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(6.dp))
                ) {
                    Text("Delete", modifier = Modifier.padding(vertical = 10.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =============================================================================
// 6. PROFILE PIN EDITOR SCREEN
// =============================================================================
@Composable
fun ProfilePinEditorScreen(
    profileName: String,
    currentPin: String,
    onDismiss: () -> Unit,
    onSavePin: (String?) -> Unit
) {
    var existingPinVerified by remember(profileName) { mutableStateOf(currentPin.isBlank()) }
    var candidatePin by remember(profileName) { mutableStateOf<String?>(null) }
    var confirming by remember(profileName) { mutableStateOf(false) }
    var error by remember(profileName) { mutableStateOf<String?>(null) }
    var clearToken by remember(profileName) { mutableIntStateOf(0) }

    if (!existingPinVerified) {
        ProfilePinVerificationScreen(
            profileKey = "edit_$profileName",
            profileName = profileName,
            subtitle = "Enter your current PIN to change or turn off profile lock.",
            confirmLabel = "Continue",
            verifyPin = { NetflixViewModel.matchesProfilePin(currentPin, it) },
            onVerified = { existingPinVerified = true },
            onBack = onDismiss
        )
        return
    }

    ProfilePinEntryScreen(
        profileName = profileName,
        title = if (confirming) "Confirm your PIN" else if (currentPin.isBlank()) "Create a PIN" else "Choose a new PIN",
        subtitle = if (confirming) "Re-enter the 4 digits. Choose Done on the profile screen to save."
            else "Choose 4 digits to protect this profile.",
        confirmLabel = if (confirming) "Confirm PIN" else "Continue",
        entryKey = if (confirming) "confirm_$profileName" else "new_$profileName",
        clearEntryToken = clearToken,
        errorMessage = error,
        onInputChanged = { error = null },
        onBack = {
            if (confirming) { confirming = false; candidatePin = null; error = null }
            else onDismiss()
        },
        onRemovePin = if (currentPin.isNotBlank() && !confirming) ({ onSavePin(null) }) else null,
        onSubmit = { entered ->
            if (!confirming) {
                candidatePin = entered
                error = null
                confirming = true
            } else if (entered == candidatePin) {
                onSavePin(entered)
            } else {
                error = "The PINs do not match. Try again."
                clearToken++
            }
        }
    )
}
