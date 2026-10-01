package com.example.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalDensity
import com.example.R
import com.example.model.Movie
import com.example.model.Profile
import com.example.ui.NetflixViewModel
import com.example.ui.components.ProfilePinVerificationScreen
import com.example.ui.components.NetflixSpinner
import com.example.ui.components.ProfileIcons
import com.example.ui.components.ReadyArtwork
import com.example.ui.components.profileAvatarRequest
import com.example.ui.util.HomeStartupGate
import com.example.ui.theme.AnimationSpecs
import com.example.ui.theme.KidsAccent
import com.example.ui.theme.NetflixWhite
import com.example.ui.theme.ProfileAddTileBorderDim
import com.example.ui.theme.ProfileAddTileBright
import com.example.ui.theme.ProfileAddTileDim
import com.example.ui.theme.ProfileNameDim
import com.example.ui.theme.ProfilePinDotEmpty
import com.example.ui.theme.ProfilePinKeyBg
import com.example.ui.theme.ProfilePinPanelBg
import com.example.ui.theme.ProfilePinPanelBorder
import com.example.ui.theme.ProfilePinScrimBottom
import com.example.ui.theme.ProfilePinScrimMid
import com.example.ui.theme.ProfilePinScrimTop
import com.example.ui.theme.ProfileScrim
import com.example.ui.util.TvArtworkKind
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvMotion

fun getMoviesForProfile(profileId: String, categoryRows: List<Pair<String, List<Movie>>>): List<Movie> {
    if (categoryRows.isEmpty()) return emptyList()
    val index = when (profileId) {
        "1" -> 0 // Trending Now
        "2" -> 1 // Popular Movies
        "3" -> 2 // Popular TV Series
        "4" -> 3 // Top Rated Blockbusters
        "5" -> 4 // Top Rated TV Shows
        // For arbitrary profile IDs, hash to a stable index so the same profile
        // always picks the same row across recompositions / app restarts.
        else -> ((profileId.hashCode() and Int.MAX_VALUE) % categoryRows.size)
    }
    val row = categoryRows.getOrNull(index) ?: categoryRows.first()
    return row.second
}

private val AvatarCornerRadius = 12.dp
private val AvatarShape = RoundedCornerShape(AvatarCornerRadius)
private val AvatarSize = 58.dp
private val AvatarFocusedSize = 68.dp

enum class ProfileScreenMode {
    LIST,
    PIN_UNLOCK,
    LOADING
}

@Composable
fun ProfileScreen(
    viewModel: NetflixViewModel,
    onProfileSelected: (Profile) -> Unit,
    onEditProfile: (String) -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val userSubscription by viewModel.userSubscription.collectAsStateWithLifecycle()
    val isProfilesLoading by viewModel.isProfilesLoading.collectAsStateWithLifecycle()
    val profilesLoadError by viewModel.profilesLoadError.collectAsStateWithLifecycle()

    if ((profiles.isEmpty() && isProfilesLoading) || profilesLoadError != null) {
        ProfileSelectionLoadScreen(
            errorMessage = profilesLoadError,
            onRetry = { viewModel.listenToFirestoreProfiles() }
        )
        return
    }

    // Guests keep their explicit local setup flow. Signed-in users remain on
    // the picker, including a confirmed empty account, until they choose Add.
    if (profiles.isEmpty() && !viewModel.isUserLoggedIn()) {
        ProfileSetupWalkthroughScreen(
            viewModel = viewModel,
            onComplete = onProfileSelected,
            onCancel = null
        )
        return
    }

    var screenMode by remember { mutableStateOf(ProfileScreenMode.LIST) }
    var activePinProfile by remember { mutableStateOf<Profile?>(null) }
    var activeLoadingProfile by remember { mutableStateOf<Profile?>(null) }

    androidx.activity.compose.BackHandler {
        when (screenMode) {
            ProfileScreenMode.PIN_UNLOCK -> {
                screenMode = ProfileScreenMode.LIST
                activePinProfile = null
            }
            ProfileScreenMode.LOADING -> {
                // Don't allow back during the loading transition; let it finish.
            }
            ProfileScreenMode.LIST -> {
                // No-op on TV — this is the app root. Avoids accidentally exiting to
                // the launcher when the user presses Back from the profile list.
            }
        }
    }

    var previouslyFocusedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var focusedIndex by remember { mutableIntStateOf(previouslyFocusedIndex ?: 0) }
    var isPencilFocused by remember { mutableStateOf(false) }
    var newlyAddedProfileId by remember { mutableStateOf<String?>(null) }
    var profileNotificationMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // A fresh entry gets its own quiet window, including automatic navigation
        // from Splash. Optional catalog/artwork work waits behind the first input.
        HomeStartupGate.onInteraction()
    }

    val canAddProfile = !isProfilesLoading && profilesLoadError == null &&
        profiles.size < userSubscription.maxProfiles
    val totalItems = profiles.size + (if (canAddProfile) 1 else 0)

    val profileFocusRequesters = remember(totalItems) {
        List(totalItems) { FocusRequester() }
    }
    val pencilFocusRequesters = remember(profiles.size) {
        List(profiles.size) { FocusRequester() }
    }

    fun handleProfileClick(profile: Profile) {
        // Save which profile the user is interacting with so focus can be
        // restored when the PIN/loading flow completes (point #6).
        previouslyFocusedIndex = focusedIndex
        if (!profile.pin.isNullOrBlank()) {
            activePinProfile = profile
            screenMode = ProfileScreenMode.PIN_UNLOCK
        } else {
            activeLoadingProfile = profile
            screenMode = ProfileScreenMode.LOADING
        }
    }

    // A single focus handoff covers first entry, PIN dismissal, and a newly added
    // profile. Waiting for one frame attaches the requesters without a visible timer.
    LaunchedEffect(screenMode, totalItems, newlyAddedProfileId) {
        if (screenMode != ProfileScreenMode.LIST || totalItems == 0) return@LaunchedEffect
        val newIndex = newlyAddedProfileId?.let { id -> profiles.indexOfFirst { it.id == id } } ?: -1
        if (newlyAddedProfileId != null && newIndex < 0) return@LaunchedEffect
        val targetIndex = if (newIndex >= 0) newIndex else
            (previouslyFocusedIndex ?: focusedIndex).coerceIn(0, totalItems - 1)
        repeat(2) {
            withFrameNanos { }
            try {
                profileFocusRequesters[targetIndex].requestFocus()
                focusedIndex = targetIndex
                isPencilFocused = false
                previouslyFocusedIndex = null
                if (newIndex >= 0) newlyAddedProfileId = null
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                // A replacement AnimatedContent child may attach one frame later.
            }
        }
    }

    // Auto-dismiss notification pill
    LaunchedEffect(profileNotificationMessage) {
        if (profileNotificationMessage != null) {
            kotlinx.coroutines.delay(2800)
            profileNotificationMessage = null
        }
    }

    fun handleAddProfile() {
        if (!canAddProfile) {
            profileNotificationMessage = "Profile limit reached (${userSubscription.maxProfiles})"
            return
        }
        try {
            val existingAvatars = profiles.mapNotNull { it.avatarUrl }.toSet()
            val nextAvatar = ProfileIcons.ICONS.firstOrNull { it !in existingAvatars }
                ?: ProfileIcons.ICONS.getOrNull(profiles.size % ProfileIcons.ICONS.size)
            val palette = listOf(
                Color(0xFFE50914), // Netflix Red
                Color(0xFF0071EB), // Royal Blue
                Color(0xFF22B14C), // Emerald Green
                Color(0xFFFF9900), // Amber Orange
                Color(0xFF9B51E0), // Purple
                Color(0xFF00A8E8), // Cyan
                Color(0xFFE91E63)  // Magenta
            )
            val nextColor = palette.getOrElse(profiles.size % palette.size) { Color(0xFFE50914) }
            val nextName = "Profile ${profiles.size + 1}"

            val newProfile = viewModel.addProfile(
                name = nextName,
                avatarUrl = nextAvatar,
                avatarColor = nextColor,
                isKid = false
            )
            newlyAddedProfileId = newProfile.id
            profileNotificationMessage = "${newProfile.name} added"
        } catch (e: Exception) {
            android.util.Log.e("ProfileScreen", "Failed to add profile", e)
            profileNotificationMessage = "Unable to add profile"
        }
    }

    // The preview collects catalog updates in its own composition scope. Focus
    // movement never rebuilds the artwork, logo requests or genre metadata.
    val previewProfile = rememberUpdatedState(profiles.getOrNull(focusedIndex) ?: profiles.firstOrNull())

    AnimatedContent(
        targetState = if (screenMode == ProfileScreenMode.LOADING) ProfileScreenMode.LOADING else ProfileScreenMode.LIST,
        transitionSpec = {
            if (targetState == ProfileScreenMode.LOADING) {
                fadeIn(tween(350, easing = FastOutSlowInEasing)).togetherWith(fadeOut(tween(200)))
            } else {
                fadeIn(tween(250)).togetherWith(fadeOut(tween(200)))
            }
        },
        label = "ProfileScreenModeTransition"
    ) { mode ->
        when (mode) {
            ProfileScreenMode.LOADING -> {
                activeLoadingProfile?.let { loadProf ->
                    ProfileLoadingScreen(
                        profile = loadProf,
                        viewModel = viewModel,
                        onReadyToNavigate = {
                            onProfileSelected(loadProf)
                        }
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // Polish #6: a slightly darker wash behind the profile cards so the column
                    // never competes with a busy backdrop. 40% black scrim — adds weight without
                    // making the row feel like a separate panel.
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(420.dp)
                            .align(Alignment.CenterStart)
                            .background(ProfileScrim)
                    )
                    ProfilePreview(
                        viewModel = viewModel,
                        focusedProfile = previewProfile,
                        screenMode = screenMode
                    )

                    // 2. Left Column: present from the first frame, with a short
                    // layer-only reveal that never shifts focus targets or layout.
                    var screenVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { screenVisible = true }
                    val screenAlphaState = animateFloatAsState(
                        targetValue = if (screenVisible) 1f else 0.88f,
                        animationSpec = AnimationSpecs.ScreenExit,
                        label = "profileScreenAlpha"
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer { alpha = screenAlphaState.value }
                            .padding(start = 52.dp, top = 42.dp, bottom = 36.dp)
                            .align(Alignment.TopStart),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Netflix Red Logo + "Who's watching?" retain their
                        // intro glide without delaying the profile list below.
                        var headerVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { headerVisible = true }
                        val headerAlphaState = animateFloatAsState(
                            targetValue = if (headerVisible) 1f else 0.70f,
                            animationSpec = AnimationSpecs.TitleIntro,
                            label = "profileHeaderAlpha"
                        )
                        val headerOffsetState = animateDpAsState(
                            targetValue = if (headerVisible) 0.dp else 6.dp,
                            animationSpec = tween(TvMotion.duration(400), easing = FastOutSlowInEasing),
                            label = "profileHeaderOffsetY"
                        )
                        Column(
                            modifier = Modifier
                                .padding(bottom = 28.dp)
                                .graphicsLayer {
                                    alpha = headerAlphaState.value
                                    translationY = headerOffsetState.value.toPx()
                                },
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_netflix_logo),
                                    contentDescription = "NetflixPro",
                                    modifier = Modifier
                                        .height(36.dp)
                                        .width(136.dp),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.CenterStart
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Polish #7: 24-32sp, FontWeight.Bold, letterSpacing = (-0.5).sp, Color.White.
                            Text(
                                text = "Who's watching?",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                                color = NetflixWhite
                            )
                        }

                        // Profile Avatars with Smooth Sliding Ring & Left Pencil Indicator
                        val rowStepHeight = 74.dp
                        val targetRingY = (focusedIndex * rowStepHeight.value).dp

                        // Retarget the ring from its current position and velocity.
                        val animatedRingY by animateDpAsState(
                            targetValue = targetRingY,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = TvMotion.stiffness(550f)
                            ),
                            label = "profileSlidingRingY"
                        )

                        // Polish #1: animated focus-ring alpha. Toggles with the same easing as
                        // every other micro-animation in this file. The ring is drawn slightly
                        // *outside* the card so it never overlaps the avatar edge.
                        val focusRingAlpha by animateFloatAsState(
                            targetValue = if (isPencilFocused) 0.45f else 1f,
                            animationSpec = AnimationSpecs.FocusMicro,
                            label = "profileFocusRingAlpha"
                        )

                        Box(
                            modifier = Modifier.wrapContentSize()
                        ) {
                            // Smooth Sliding Focus Border Ring (Outer Ring on Focused Item)
                            // Polish #1: gradient brush (white → semi-transparent white), 2-3dp,
                            // animates its alpha over FocusMicro.
                            Box(
                                modifier = Modifier
                                    .offset(x = 48.dp - 4.dp, y = (rowStepHeight - AvatarFocusedSize) / 2 - 4.dp)
                                    .size(AvatarFocusedSize + 8.dp)
                                    .graphicsLayer {
                                        translationY = animatedRingY.toPx()
                                        alpha = focusRingAlpha
                                    }
                                    .clip(RoundedCornerShape(AvatarCornerRadius + 4.dp))
                                    .border(
                                        width = 2.5.dp,
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color.White,
                                                Color.White.copy(alpha = 0.55f),
                                                Color.White.copy(alpha = 0.85f)
                                            )
                                        ),
                                        shape = RoundedCornerShape(AvatarCornerRadius + 4.dp)
                                    )
                            )

                            // Column of Profiles
                            Column(
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                profiles.forEachIndexed { index, profile ->
                                    // Preserve each row's animation and press state across profile updates.
                                    key(profile.id) {
                                    val isCurrentFocused = focusedIndex == index
                                    val isCurrentPencilFocused = isCurrentFocused && isPencilFocused

                                    ProfileSelectionRow(
                                        profile = profile,
                                        index = index,
                                        isCurrentFocused = isCurrentFocused,
                                        isCurrentPencilFocused = isCurrentPencilFocused,
                                        cardFocusRequester = profileFocusRequesters[index],
                                        pencilFocusRequester = pencilFocusRequesters[index],
                                        previousFocusRequester = profileFocusRequesters.getOrNull((index - 1).coerceAtLeast(0)),
                                        nextFocusRequester = profileFocusRequesters.getOrNull(index + 1),
                                        onAvatarFocused = { focusedIndex = index; isPencilFocused = false },
                                        onPencilFocused = { focusedIndex = index; isPencilFocused = true },
                                        onProfileClick = { handleProfileClick(profile) },
                                        onEditClick = { previouslyFocusedIndex = index; onEditProfile(profile.id) }
                                    )
                                    } // end key(profile.id)
                                }

                                // Add Profile Row Item (if under limit)
                                if (canAddProfile) {
                                    val addIndex = profiles.size
                                    val isAddFocused = focusedIndex == addIndex
                                    val addFocusRequester = profileFocusRequesters.getOrNull(addIndex)
                                    val previousProfileFocuser = profileFocusRequesters.getOrNull(addIndex - 1)

                                    // Polish #3: press-down scale for Add Profile too.
                                    var addPressed by remember { mutableStateOf(false) }
                                    val addPressScale by animateFloatAsState(
                                        targetValue = if (addPressed) 0.97f else 1f,
                                        animationSpec = AnimationSpecs.PressDown,
                                        label = "addProfilePress"
                                    )
                                    val addFocusScale by animateFloatAsState(
                                        targetValue = if (isAddFocused) 1.04f else 1f,
                                        animationSpec = AnimationSpecs.FocusMicro,
                                        label = "addProfileFocusScale"
                                    )
                                    // Keep the small focused pulse, but do not run an
                                    // infinite clock while a profile row has focus.
                                    val addIconScaleState = if (isAddFocused) {
                                        val transition = rememberInfiniteTransition(label = "addProfilePulse")
                                        transition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 1.05f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1500, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "addProfileIconScale"
                                        )
                                    } else null
                                    val addIconColor by animateColorAsState(
                                        targetValue = if (isAddFocused) NetflixWhite else Color.White.copy(alpha = 0.70f),
                                        animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                                        label = "addProfileIconColor"
                                    )

                                    Row(
                                        modifier = Modifier
                                            .height(rowStepHeight)
                                            .wrapContentWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Empty spacer matching left pencil width
                                        Spacer(modifier = Modifier.size(48.dp))

                                        Surface(
                                            onClick = {
                                                handleAddProfile()
                                            },
                                            shape = ClickableSurfaceDefaults.shape(AvatarShape),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = Color.Transparent,
                                                focusedContainerColor = Color.Transparent
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                            modifier = Modifier
                                                .graphicsLayer {
                                                    scaleX = addPressScale * addFocusScale
                                                    scaleY = addPressScale * addFocusScale
                                                }
                                                .semantics(mergeDescendants = true) {
                                                    contentDescription = "Add Profile"
                                                    role = Role.Button
                                                }
                                                .then(if (addFocusRequester != null) Modifier.focusRequester(addFocusRequester) else Modifier)
                                                .onFocusChanged {
                                                    if (it.isFocused) {
                                                        focusedIndex = addIndex
                                                        isPencilFocused = false
                                                    }
                                                }
                                                .focusProperties {
                                                    // Up: last profile in list (TV cycle, no dead-end)
                                                    up = previousProfileFocuser ?: FocusRequester.Default
                                                }
                                                .onPreviewKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                                                addPressed = true
                                                                handleAddProfile()
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    } else false
                                                }
                                        ) {
                                            LaunchedEffect(addPressed) {
                                                if (addPressed) {
                                                    kotlinx.coroutines.delay(TvMotion.duration(100).toLong())
                                                    addPressed = false
                                                }
                                            }

                                            val animatedAddScale by animateFloatAsState(
                                                targetValue = if (isAddFocused) 1f else AvatarSize.value / AvatarFocusedSize.value,
                                                animationSpec = tween(TvMotion.duration(200), easing = LinearOutSlowInEasing),
                                                label = "addProfileSizeAnim"
                                            )

                                            Box(
                                                modifier = Modifier.size(AvatarFocusedSize),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(AvatarFocusedSize)
                                                        .graphicsLayer {
                                                            scaleX = animatedAddScale
                                                            scaleY = animatedAddScale
                                                        }
                                                        .clip(AvatarShape)
                                                        .background(
                                                            if (isAddFocused) ProfileAddTileBright
                                                            else ProfileAddTileDim
                                                        )
                                                        .border(
                                                            // Polish #8: dashed when not focused, solid when focused.
                                                            width = 1.5.dp,
                                                            color = if (isAddFocused) Color.White else ProfileAddTileBorderDim,
                                                            shape = AvatarShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Add,
                                                        contentDescription = null, // Decorative — handled by Surface's semantics
                                                        // Polish #14: 32dp plus icon.
                                                        tint = addIconColor,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .graphicsLayer {
                                                                val scale = addIconScaleState?.value ?: 1f
                                                                scaleX = scale
                                                                scaleY = scale
                                                            }
                                                    )
                                                }
                                            }
                                        }

                                        AnimatedVisibility(
                                            visible = isAddFocused,
                                            enter = fadeIn(tween(TvMotion.duration(200))) + expandHorizontally(tween(TvMotion.duration(200))),
                                            exit = fadeOut(tween(TvMotion.duration(150))) + shrinkHorizontally(tween(TvMotion.duration(150)))
                                        ) {
                                            // Polish #4: animated label color.
                                            val addNameColor by animateColorAsState(
                                                targetValue = NetflixWhite,
                                                animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                                                label = "addProfileNameColor"
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(start = 16.dp)
                                            ) {
                                                Text(
                                                    text = "Add Profile",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = (-0.1).sp,
                                                    color = addNameColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Streamlined Profile Addition Feedback Pill
                    androidx.compose.animation.AnimatedVisibility(
                        visible = profileNotificationMessage != null,
                        enter = fadeIn(tween(TvMotion.duration(220))) + slideInVertically(tween(TvMotion.duration(260))) { -it },
                        exit = fadeOut(tween(TvMotion.duration(200))) + slideOutVertically(tween(TvMotion.duration(200))) { -it },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 28.dp)
                            .zIndex(90f)
                    ) {
                        Row(
                            modifier = Modifier
                                .background(Color(0xF0181818), RoundedCornerShape(24.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = profileNotificationMessage.orEmpty(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 5. PIN Unlock Modal Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = screenMode == ProfileScreenMode.PIN_UNLOCK && activePinProfile != null,
                    enter = fadeIn(tween(TvMotion.duration(280))),
                    exit = fadeOut(tween(TvMotion.duration(220))),
                    modifier = Modifier.fillMaxSize().zIndex(30f)
                ) {
                    activePinProfile?.let { pinProf ->
                        ProfilePinUnlockScreen(
                            profile = pinProf,
                            onBack = {
                                screenMode = ProfileScreenMode.LIST
                                activePinProfile = null
                            },
                            onUnlockSuccess = {
                                activeLoadingProfile = pinProf
                                screenMode = ProfileScreenMode.LOADING
                            },
                            onVerifyPin = { entered -> viewModel.verifyPin(pinProf.id, entered) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectionLoadScreen(errorMessage: String?, onRetry: () -> Unit) {
    val retryRequester = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            withFrameNanos { }
            runCatching { retryRequester.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Who's watching?", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (errorMessage == null) {
                NetflixSpinner(size = 42.dp)
                Text("Loading profiles…", color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp)
            } else {
                Text(errorMessage, color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 540.dp).padding(horizontal = 24.dp))
                Surface(onClick = onRetry,
                    modifier = Modifier.width(180.dp).height(44.dp).focusRequester(retryRequester),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.White, focusedContainerColor = Color.White)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Retry", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfilePinUnlockScreen(
    profile: Profile,
    onBack: () -> Unit,
    onUnlockSuccess: () -> Unit,
    onVerifyPin: (String) -> Boolean
) {
    ProfilePinVerificationScreen(
        profileKey = profile.id,
        profileName = profile.name,
        avatarUrl = profile.avatarUrl,
        verifyPin = onVerifyPin,
        onVerified = onUnlockSuccess,
        onBack = onBack
    )
}

@Composable
fun ProfileLoadingScreen(
    profile: Profile,
    viewModel: NetflixViewModel,
    onReadyToNavigate: () -> Unit
) {
    LaunchedEffect(profile) {
        try {
            viewModel.selectProfile(profile)
        } catch (e: Exception) {
            android.util.Log.e("ProfileLoadingScreen", "selectProfile failed", e)
        }
        kotlinx.coroutines.delay(650)
        onReadyToNavigate()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(profile.avatarColor),
                contentAlignment = Alignment.Center
            ) {
                if (!profile.avatarUrl.isNullOrBlank()) {
                    val context = LocalContext.current
                    val loadingAvatarRequest = remember(profile.avatarUrl, context) {
                        profileAvatarRequest(context, profile.avatarUrl.orEmpty())
                    }
                    AsyncImage(
                        model = loadingAvatarRequest,
                        contentDescription = profile.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                        color = Color.White
                    )
                }
            }

            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = NetflixWhite
            )

            NetflixSpinner(size = 36.dp)
        }
    }
}

/** Decorative work has a separate scope from the interactive profile column. */
@Composable
private fun ProfilePreview(
    viewModel: NetflixViewModel,
    focusedProfile: State<Profile?>,
    screenMode: ProfileScreenMode
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val lowMemory = remember(context) { TvImagePolicy.isLowMemoryDevice(context) }
    var debouncedFocusedProfile by remember { mutableStateOf(focusedProfile.value) }
    var artworkAllowed by remember { mutableStateOf(false) }

    LaunchedEffect(focusedProfile.value, screenMode) {
        if (screenMode != ProfileScreenMode.LIST) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        // Avatars and the focus ring draw first. Rapid input keeps the current
        // backdrop intact instead of launching a new full-screen decode per key.
        HomeStartupGate.awaitBrowsingIdle()
        debouncedFocusedProfile = focusedProfile.value
        artworkAllowed = true
    }

    val categoryRows by viewModel.categoryRows.collectAsStateWithLifecycle()
    val profileMovies = remember(debouncedFocusedProfile?.id, categoryRows) {
        debouncedFocusedProfile?.let { getMoviesForProfile(it.id, categoryRows) } ?: emptyList()
    }
    var currentMovieIndex by remember(debouncedFocusedProfile?.id) { mutableIntStateOf(0) }
    val activeMovie = profileMovies.getOrNull(currentMovieIndex) ?: profileMovies.firstOrNull()
    var activeMovieLogoUrl by remember(activeMovie?.id, activeMovie?.logoUrl) {
        mutableStateOf(activeMovie?.logoUrl)
    }
    LaunchedEffect(artworkAllowed, activeMovie?.id, screenMode) {
        val movie = activeMovie ?: return@LaunchedEffect
        if (!artworkAllowed || screenMode != ProfileScreenMode.LIST || !movie.logoUrl.isNullOrBlank()) return@LaunchedEffect
        HomeStartupGate.awaitBrowsingIdle()
        kotlinx.coroutines.delay(300)
        activeMovieLogoUrl = viewModel.resolveMovieLogo(movie)
    }
    LaunchedEffect(profileMovies, screenMode, artworkAllowed) {
        if (!artworkAllowed || profileMovies.isEmpty() || screenMode != ProfileScreenMode.LIST) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            HomeStartupGate.awaitBrowsingIdle()
            currentMovieIndex = (currentMovieIndex + 1) % profileMovies.size
        }
    }
    Box(Modifier.fillMaxSize()) {
        // 1. Right-side artwork. Bounded decoding lets the cinematic drift
        // run on the image layer without competing with initial focus.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterEnd)
        ) {
            val movie = activeMovie
            if (artworkAllowed && movie != null && movie.backdropUrl.isNotBlank()) {
                val context = LocalContext.current
                val density = LocalDensity.current
                // Coil size is in pixels. 1920.dp scaled by density could
                // allocate a 3840x2160 bitmap on a 2x-density TV.
                val backdropSize = remember(density, configuration.screenWidthDp, configuration.screenHeightDp, lowMemory) {
                    with(density) {
                        configuration.screenWidthDp.dp.toPx().toInt().coerceIn(1, if (lowMemory) 960 else 1280) to
                            configuration.screenHeightDp.dp.toPx().toInt().coerceIn(1, if (lowMemory) 540 else 720)
                    }
                }
                val backdropUrl = remember(movie.backdropUrl, backdropSize.first) {
                    TvImagePolicy.artworkUrl(movie.backdropUrl, backdropSize.first, TvArtworkKind.BACKDROP)
                }
                val imageRequest = remember(backdropUrl, context, backdropSize) {
                    ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .size(backdropSize.first, backdropSize.second)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .crossfade(false)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .allowHardware(true)
                        .build()
                }

                var backdropLoaded by remember(backdropUrl) { mutableStateOf(false) }
                val animatedBackdropAlpha by animateFloatAsState(
                    targetValue = if (backdropLoaded) 1f else 0f,
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    label = "backdropFadeIn"
                )
                // Start the pan only when the bitmap exists; while the PIN
                // overlay is open there is no hidden animation to render.
                val backdropPanState = if (backdropLoaded && screenMode == ProfileScreenMode.LIST) {
                    val transition = rememberInfiniteTransition(label = "backdrop_pan")
                    transition.animateFloat(
                        initialValue = 0f,
                        targetValue = -0.04f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(24000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "backdrop_pan_offset"
                    )
                } else null

                ReadyArtwork(
                    request = imageRequest,
                    contentDescription = "Show Backdrop",
                    animateReplacement = !lowMemory,
                    onReady = { backdropLoaded = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = animatedBackdropAlpha
                            scaleX = 1.08f
                            scaleY = 1.08f
                            translationX = size.width * (backdropPanState?.value ?: 0f)
                        }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }

            // Netflix Gradient Overlay: Solid black on the left for the profiles column, fading seamlessly into the backdrop on right
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val leftScrim = Brush.horizontalGradient(
                            0.0f to Color.Black,
                            0.30f to Color.Black,
                            0.42f to Color.Black.copy(alpha = 0.85f),
                            0.58f to Color.Black.copy(alpha = 0.40f),
                            0.75f to Color.Black.copy(alpha = 0.10f),
                            0.90f to Color.Transparent,
                            1.0f to Color.Transparent
                        )
                        val verticalScrim = Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.65f),
                            0.12f to Color.Black.copy(alpha = 0.20f),
                            0.30f to Color.Transparent,
                            0.60f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.60f),
                            0.92f to Color.Black.copy(alpha = 0.95f),
                            1.0f to Color.Black
                        )
                        onDrawBehind {
                            drawRect(leftScrim)
                            drawRect(verticalScrim)
                        }
                    }
            )
        }

        // 3. Bottom-Right Movie Title, Logo & Netflix Tags (Exact match with reference screenshots!)
        activeMovie?.let { movie ->
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = 52.dp)
                    .width(500.dp)
            ) {
                // Reserve the title area so a late logo cannot move the
                // tags and badge while someone is choosing a profile.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    if (artworkAllowed && !activeMovieLogoUrl.isNullOrBlank()) {
                        val context = LocalContext.current
                        val density = LocalDensity.current
                        val logoSize = remember(density, activeMovieLogoUrl) {
                            with(density) {
                                500.dp.toPx().toInt().coerceIn(1, 960) to
                                    120.dp.toPx().toInt().coerceIn(1, 240)
                            }
                        }
                        val logoRequest = remember(activeMovieLogoUrl, movie.id, context, logoSize) {
                            ImageRequest.Builder(context)
                                .data(TvImagePolicy.artworkUrl(
                                    activeMovieLogoUrl.orEmpty(), logoSize.first, TvArtworkKind.LOGO
                                ))
                                .size(logoSize.first, logoSize.second)
                                .bitmapConfig(Bitmap.Config.ARGB_8888)
                                .crossfade(false)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .allowHardware(true)
                                .build()
                        }
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = movie.title,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomEnd
                        )
                    } else {
                        Text(
                            text = movie.title.uppercase(),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp
                            ),
                            color = NetflixWhite,
                            maxLines = 2,
                            textAlign = TextAlign.End,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tagline / Genre Tags (e.g. "Slapstick • Raunchy • Comedy")
                val genreTags = remember(movie) {
                    if (movie.description.isNotBlank()) {
                        val words = movie.description.split(" ", ",", ".").filter { it.length in 4..12 }.take(3)
                        if (words.isNotEmpty()) words.joinToString("  •  ") { it.replaceFirstChar { char -> char.uppercase() } }
                        else if (movie.type.isNotBlank()) "${movie.type}  •  Popular" else "Exciting  •  Suspenseful"
                    } else "Must-Watch  •  Top Rated"
                }

                Text(
                    text = genreTags,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color(0xFFDDDDDD),
                    textAlign = TextAlign.End
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Top 10 or Coming Date Badge Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE50914), RoundedCornerShape(2.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TOP\n10",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                fontWeight = FontWeight.Black
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }

                    Text(
                        text = "No. ${((movie.id.hashCode() % 10) + 1).coerceIn(1, 10)} in Films Today",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }

    }
}

@Composable
internal fun ProfileSelectionRow(
    profile: Profile,
    index: Int,
    isCurrentFocused: Boolean,
    isCurrentPencilFocused: Boolean,
    cardFocusRequester: FocusRequester,
    pencilFocusRequester: FocusRequester,
    previousFocusRequester: FocusRequester?,
    nextFocusRequester: FocusRequester?,
    onAvatarFocused: () -> Unit,
    onPencilFocused: () -> Unit,
    onProfileClick: () -> Unit,
    onEditClick: () -> Unit
) {
    val rowStepHeight = 74.dp
    // Polish #3: per-row "pressed" state. DPAD_CENTER fires click + this
    // boolean; a 100ms tween scales the avatar down to 0.97, then we
    // animate back to 1.0 so the spring physics recovers the rest of
    // the way. The actual navigation still happens in onClick.
    var pressed by remember(profile.id) { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AnimationSpecs.PressDown,
        label = "avatarPress_$index"
    )
    val focusScale by animateFloatAsState(
        targetValue = if (isCurrentFocused) 1.04f else 1f,
        animationSpec = AnimationSpecs.FocusMicro,
        label = "avatarFocusScale_$index"
    )

    Row(
        modifier = Modifier
            .height(rowStepHeight)
            .wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Pencil Icon (Appears when this profile is active/focused)
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrentFocused) {
                // Polish #9: animated underline width fraction (0 → 1) when focused.
                val underlineWidthFraction by animateFloatAsState(
                    targetValue = if (isCurrentPencilFocused) 1f else 0f,
                    animationSpec = AnimationSpecs.UnderlineGrow,
                    label = "pencilUnderline_$index"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Surface(
                        onClick = onEditClick,
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isCurrentPencilFocused) NetflixWhite else Color.Transparent,
                            focusedContainerColor = NetflixWhite
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.10f),
                        modifier = Modifier
                            .size(48.dp) // a11y minimum tap target
                            .semantics {
                                contentDescription = "Edit ${profile.name} profile"
                                role = Role.Button
                            }
                            .focusRequester(pencilFocusRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onPencilFocused()
                                }
                            }
                            .focusProperties {
                                right = cardFocusRequester
                            }
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            try {
                                                cardFocusRequester.requestFocus()
                                                true
                                            } catch (_: Exception) { false }
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Polish #9: animated icon color (dim → white on focus).
                            val pencilIconColor by animateColorAsState(
                                targetValue = if (isCurrentPencilFocused) Color.Black else Color.White.copy(alpha = 0.60f),
                                animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
                                label = "pencilIconColor_$index"
                            )
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null, // Decorative — handled by Surface's semantics
                                tint = pencilIconColor,
                                // Polish #14: 20dp pencil icon.
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // Polish #9: animated underline (1.5dp, white) 4dp below the icon.
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .height(1.5.dp)
                            .width(20.dp)
                            .graphicsLayer { scaleX = underlineWidthFraction }
                            .background(NetflixWhite)
                    )
                }
            }
        }

        // Profile Avatar Tile
            val isLocked = !profile.pin.isNullOrBlank()
        val isKids = profile.isKid
        Surface(
            onClick = { onProfileClick() },
            shape = ClickableSurfaceDefaults.shape(AvatarShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            modifier = Modifier
                .graphicsLayer {
                    // Press and focus scale without changing the hitbox.
                    scaleX = pressScale * focusScale
                    scaleY = pressScale * focusScale
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(profile.name)
                        append(" profile")
                        if (isKids) append(", kids")
                        if (isLocked) append(", PIN locked")
                    }
                    role = Role.RadioButton
                    stateDescription = if (isLocked) "PIN locked" else "Selectable"
                }
                .focusRequester(cardFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        onAvatarFocused()
                    }
                }
                .focusProperties {
                    left = pencilFocusRequester
                    // Up: previous profile (or stay on first)
                    up = previousFocusRequester ?: FocusRequester.Default
                    // Down: next profile or Add Profile
                    down = nextFocusRequester ?: FocusRequester.Default
                }
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                try {
                                    pencilFocusRequester.requestFocus()
                                    true
                                } catch (_: Exception) { false }
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            val animatedItemScale by animateFloatAsState(
                targetValue = if (isCurrentFocused) 1f else AvatarSize.value / AvatarFocusedSize.value,
                animationSpec = tween(TvMotion.duration(200), easing = LinearOutSlowInEasing),
                label = "avatarSizeAnim_$index"
            )
            // Keep the visual press in step with the shared motion duration.
            LaunchedEffect(pressed) {
                if (pressed) {
                    kotlinx.coroutines.delay(TvMotion.duration(100).toLong())
                    pressed = false
                }
            }

            Box(
                modifier = Modifier.size(AvatarFocusedSize),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(AvatarFocusedSize)
                        .graphicsLayer {
                            scaleX = animatedItemScale
                            scaleY = animatedItemScale
                        }
                        .clip(AvatarShape)
                        .background(profile.avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (!profile.avatarUrl.isNullOrBlank()) {
                        val context = LocalContext.current
                        val request = remember(profile.avatarUrl, context) {
                            profileAvatarRequest(context, profile.avatarUrl.orEmpty())
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = profile.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = profile.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                    }

                    // Kids Badge on bottom of avatar
                    if (profile.isKid) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(KidsAccent)
                                .padding(vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "KIDS",
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Lock Icon in top right if PIN Protected
                    if (!profile.pin.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.75f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null, // Decorative — surfaced via stateDescription
                                tint = NetflixWhite,
                                modifier = Modifier.size(9.dp)
                            )
                        }
                    }
                }
            }
        }

        // Profile Name (Displayed only for the focused profile to the right!)
        // Polish #4: animate the name's color alpha from 0.75 → 1.0 and
        // weight from Normal → SemiBold, with the same 180-200ms easing
        // the rest of the file uses.
        val nameColor by animateColorAsState(
            targetValue = if (isCurrentFocused) NetflixWhite else ProfileNameDim,
            animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing),
            label = "profileNameColor_$index"
        )
        AnimatedVisibility(
            visible = isCurrentFocused,
            enter = fadeIn(tween(TvMotion.duration(200))) + expandHorizontally(tween(TvMotion.duration(200))),
            exit = fadeOut(tween(TvMotion.duration(150))) + shrinkHorizontally(tween(TvMotion.duration(150)))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp)
            ) {
                Text(
                    text = profile.name,
                    fontSize = 14.sp,
                    // Polish #4: weight animates SemiBold → Bold (slightly
                    // punchier than the spec's SemiBold because the row
                    // already lives in a dark column — needs more presence).
                    fontWeight = if (isCurrentFocused) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = (-0.1).sp,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
