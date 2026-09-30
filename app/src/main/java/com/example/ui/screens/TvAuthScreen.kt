package com.example.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.example.R
import com.example.ui.NetflixViewModel
import com.example.ui.components.KeyboardKeyButton
import com.example.ui.theme.NetflixBlack
import com.example.ui.theme.NetflixWhite

private enum class AuthTargetField {
    EMAIL,
    PASSWORD
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAuthScreen(
    viewModel: NetflixViewModel,
    onAuthenticated: (email: String) -> Unit,
    onSkipToGuest: () -> Unit
) {
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf(AuthTargetField.EMAIL) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }

    // Focus requesters for full bidirectional D-pad navigation
    val emailFieldFocusRequester = remember { FocusRequester() }
    val passwordFieldFocusRequester = remember { FocusRequester() }
    val signInButtonFocusRequester = remember { FocusRequester() }
    val guestButtonFocusRequester = remember { FocusRequester() }

    val spaceKeyFocusRequester = remember { FocusRequester() }
    val backspaceKeyFocusRequester = remember { FocusRequester() }
    val clearKeyFocusRequester = remember { FocusRequester() }
    val firstKeyFocusRequester = remember { FocusRequester() }
    val domainFirstFocusRequester = remember { FocusRequester() }

    val keyboardKeys = remember {
        listOf(
            "a", "b", "c", "d", "e", "f",
            "g", "h", "i", "j", "k", "l",
            "m", "n", "o", "p", "q", "r",
            "s", "t", "u", "v", "w", "x",
            "y", "z", "1", "2", "3", "4",
            "5", "6", "7", "8", "9", "0",
            "@", ".", "_", "-", "+", "!"
        )
    }

    fun handleCharacterInput(char: String) {
        authError = null
        when (activeField) {
            AuthTargetField.EMAIL -> {
                emailInput += char
            }
            AuthTargetField.PASSWORD -> {
                passwordInput += char
            }
        }
    }

    fun handleBackspace() {
        authError = null
        when (activeField) {
            AuthTargetField.EMAIL -> {
                if (emailInput.isNotEmpty()) {
                    emailInput = emailInput.dropLast(1)
                }
            }
            AuthTargetField.PASSWORD -> {
                if (passwordInput.isNotEmpty()) {
                    passwordInput = passwordInput.dropLast(1)
                }
            }
        }
    }

    fun handleClear() {
        authError = null
        when (activeField) {
            AuthTargetField.EMAIL -> emailInput = ""
            AuthTargetField.PASSWORD -> passwordInput = ""
        }
    }

    // Auto-focus email field on launch
    LaunchedEffect(Unit) {
        try {
            emailFieldFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tv_auth_screen_surface")
            .background(NetflixBlack)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar
                    if (unicodeChar > 31 && unicodeChar != 127) {
                        handleCharacterInput(unicodeChar.toChar().toString())
                        return@onPreviewKeyEvent true
                    } else if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL) {
                        handleBackspace()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ==========================================
            // TOP BAR: Netflix Logo, PRO badge & Subtitle
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_netflix_logo),
                    contentDescription = "Netflix Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .width(120.dp)
                        .height(34.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "PRO TV",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.width(28.dp))
                Column {
                    Text(
                        text = "Sign in to watch on TV",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select a field and use the on-screen keyboard to enter your details.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // MAIN CONTENT: Left Inputs & Right Keyboard (Aligned at Top)
            // ==========================================
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.Top
            ) {
                // ==========================================
                // LEFT COLUMN: Input Boxes & Action Buttons
                // ==========================================
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Top
                ) {
                    // Error Message Banner
                    AnimatedVisibility(visible = authError != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .background(Color(0xFF2A1517), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics { contentDescription = "Sign in error: ${authError.orEmpty()}" }
                        ) {
                            Text(
                                text = authError.orEmpty(),
                                color = Color(0xFFFF8585),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // TV Input Field: Email Address (Starts right at the top!)
                    TvInputField(
                        label = "Email Address",
                        value = emailInput,
                        placeholder = "Enter your email address",
                        icon = Icons.Default.Email,
                        isActiveField = activeField == AuthTargetField.EMAIL,
                        focusRequester = emailFieldFocusRequester,
                        onFocus = { activeField = AuthTargetField.EMAIL },
                        onDpadDown = {
                            try { passwordFieldFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        },
                        onDpadRight = {
                            try { spaceKeyFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // TV Input Field: Password
                    TvInputField(
                        label = "Password",
                        value = passwordInput,
                        placeholder = "Enter your password",
                        icon = Icons.Default.Lock,
                        isPassword = true,
                        isPasswordVisible = isPasswordVisible,
                        isActiveField = activeField == AuthTargetField.PASSWORD,
                        focusRequester = passwordFieldFocusRequester,
                        onFocus = { activeField = AuthTargetField.PASSWORD },
                        onTogglePasswordVisibility = { isPasswordVisible = !isPasswordVisible },
                        onDpadUp = {
                            try { emailFieldFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        },
                        onDpadDown = {
                            try { signInButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        },
                        onDpadRight = {
                            try { spaceKeyFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action Buttons Row (White Pill UI matching walkthrough)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Sign In Button (Walkthrough White Pill)
                        var isSignInFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                val email = emailInput.trim()
                                if (!isPlausibleEmail(email)) {
                                    authError = "Please enter a valid email address."
                                } else if (passwordInput.length < 6) {
                                    authError = "Password must be at least 6 characters."
                                } else {
                                    isAuthenticating = true
                                    viewModel.signInWithEmail(
                                        email = email,
                                        password = passwordInput,
                                        onSuccess = { signedInEmail ->
                                            isAuthenticating = false
                                            passwordInput = ""
                                            onAuthenticated(signedInEmail)
                                        },
                                        onError = { message ->
                                            isAuthenticating = false
                                            authError = message
                                        }
                                    )
                                }
                            },
                            enabled = !isAuthenticating,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White,
                                focusedContainerColor = Color.White
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .focusRequester(signInButtonFocusRequester)
                                .focusProperties {
                                    up = passwordFieldFocusRequester
                                    right = guestButtonFocusRequester
                                }
                                .handleTvDpadNavigation(
                                    onDpadUp = {
                                        try { passwordFieldFocusRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    },
                                    onDpadRight = {
                                        try { guestButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    }
                                )
                                .onFocusChanged { isSignInFocused = it.isFocused }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                            ) {
                                if (isAuthenticating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = if (isAuthenticating) "Signing In..." else "Sign In on TV",
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Browse as Guest Button (Walkthrough Secondary Pill)
                        var isGuestFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                viewModel.setGuestMode()
                                onSkipToGuest()
                            },
                            enabled = !isAuthenticating,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF2A2A2A),
                                focusedContainerColor = Color.White
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .focusRequester(guestButtonFocusRequester)
                                .focusProperties {
                                    left = signInButtonFocusRequester
                                    up = passwordFieldFocusRequester
                                    right = spaceKeyFocusRequester
                                }
                                .handleTvDpadNavigation(
                                    onDpadLeft = {
                                        try { signInButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    },
                                    onDpadUp = {
                                        try { passwordFieldFocusRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    },
                                    onDpadRight = {
                                        try { spaceKeyFocusRequester.requestFocus() } catch (_: Exception) {}
                                        true
                                    }
                                )
                                .onFocusChanged { isGuestFocused = it.isFocused }
                        ) {
                            Text(
                                text = "Browse as Guest",
                                color = if (isGuestFocused) Color.Black else Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp)
                            )
                        }
                    }
                }

                // ==========================================
                // RIGHT COLUMN: On-Screen TV Keyboard (Top Aligns with Email Input)
                // ==========================================
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Keyboard Header
                    Text(
                        text = "ON-SCREEN KEYBOARD",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )

                    // Top Action Row: Space, Backspace & Clear (matches Search screen layout)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Space Key
                        KeyboardKeyButton(
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
                                .focusRequester(spaceKeyFocusRequester)
                                .focusProperties {
                                    left = if (activeField == AuthTargetField.EMAIL) emailFieldFocusRequester else passwordFieldFocusRequester
                                    right = backspaceKeyFocusRequester
                                    down = firstKeyFocusRequester
                                },
                            onClick = {
                                handleCharacterInput(" ")
                            }
                        )

                        // Backspace Key
                        KeyboardKeyButton(
                            text = "",
                            icon = Icons.AutoMirrored.Default.Backspace,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(backspaceKeyFocusRequester)
                                .focusProperties {
                                    left = spaceKeyFocusRequester
                                    right = clearKeyFocusRequester
                                },
                            onClick = {
                                handleBackspace()
                            }
                        )

                        // Clear Key
                        KeyboardKeyButton(
                            text = "",
                            icon = Icons.Default.Clear,
                            modifier = Modifier
                                .weight(0.8f)
                                .focusRequester(clearKeyFocusRequester)
                                .focusProperties {
                                    left = backspaceKeyFocusRequester
                                },
                            onClick = {
                                handleClear()
                            }
                        )
                    }

                    // 6-Column Grid of Keys (a-z, 0-9, and symbols)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(keyboardKeys, key = { _, keyChar -> "auth_key_$keyChar" }) { index, keyChar ->
                            val isLeftEdge = index % 6 == 0
                            val isTopRow = index < 6
                            val isBottomRow = index >= (keyboardKeys.size - 6)

                            val keyModifier = when {
                                index == 0 -> Modifier.focusRequester(firstKeyFocusRequester).focusProperties {
                                    up = spaceKeyFocusRequester
                                    left = if (activeField == AuthTargetField.EMAIL) emailFieldFocusRequester else passwordFieldFocusRequester
                                }
                                isLeftEdge -> Modifier.focusProperties {
                                    left = if (activeField == AuthTargetField.EMAIL) emailFieldFocusRequester else passwordFieldFocusRequester
                                }
                                isTopRow -> Modifier.focusProperties {
                                    up = spaceKeyFocusRequester
                                }
                                isBottomRow -> Modifier.focusProperties {
                                    down = domainFirstFocusRequester
                                }
                                else -> Modifier
                            }

                            KeyboardKeyButton(
                                text = keyChar,
                                modifier = keyModifier,
                                onClick = {
                                    handleCharacterInput(keyChar)
                                }
                            )
                        }
                    }

                    // Quick domain shortcuts for fast TV email entry (Pills)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("@gmail.com", "@yahoo.com", ".com").forEachIndexed { index, shortcut ->
                            var isShortcutFocused by remember { mutableStateOf(false) }
                            val shortcutMod = if (index == 0) {
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .focusRequester(domainFirstFocusRequester)
                                    .focusProperties {
                                        left = guestButtonFocusRequester
                                    }
                            } else {
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                            }

                            Surface(
                                onClick = {
                                    if (activeField == AuthTargetField.EMAIL) {
                                        emailInput += shortcut
                                        authError = null
                                    } else {
                                        passwordInput += shortcut
                                        authError = null
                                    }
                                },
                                modifier = shortcutMod.onFocusChanged { isShortcutFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0xFF242428),
                                    focusedContainerColor = Color.White
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortcut,
                                        color = if (isShortcutFocused) Color.Black else Color.White.copy(alpha = 0.9f),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
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
    }
}

/**
 * TV-Native interactive input field that does NOT trigger the system software IME keyboard.
 * Uses clean white focus states and crisp border styling.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInputField(
    label: String,
    value: String,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActiveField: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    onDpadUp: (() -> Boolean)? = null,
    onDpadDown: (() -> Boolean)? = null,
    onDpadRight: (() -> Boolean)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isActiveField -> Color.White.copy(alpha = 0.6f)
            else -> Color(0xFF2A2A30)
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "fieldBorderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused || isActiveField) Color(0xFF1E1E24) else Color(0xFF16161A),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "fieldBgColor"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = if (isActiveField || isFocused) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            onClick = { onFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocus()
                    }
                }
                .handleTvDpadNavigation(
                    onDpadUp = onDpadUp ?: { false },
                    onDpadDown = onDpadDown ?: { false },
                    onDpadRight = onDpadRight ?: { false }
                ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = backgroundColor,
                focusedContainerColor = Color(0xFF25252E)
            ),
            border = ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
                    shape = RoundedCornerShape(12.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(12.dp)
                )
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused || isActiveField) Color.White else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )

                val displayText = when {
                    value.isEmpty() -> placeholder
                    isPassword && !isPasswordVisible -> "•".repeat(value.length)
                    else -> value
                }

                Text(
                    text = displayText,
                    color = if (value.isEmpty()) Color.Gray else Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (value.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Cursor indicator when active
                if (isActiveField) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(Color.White)
                    )
                }

                // Password toggle icon
                if (isPassword && onTogglePasswordVisibility != null && value.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onTogglePasswordVisibility() }
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle password visibility",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun isPlausibleEmail(input: String): Boolean {
    if (input.isBlank()) return false
    val at = input.indexOf('@')
    if (at <= 0 || at == input.length - 1) return false
    val local = input.substring(0, at)
    val domain = input.substring(at + 1)
    if (local.isBlank() || local.any { it.isWhitespace() }) return false
    if (domain.isBlank() || domain.any { it.isWhitespace() }) return false
    val lastDot = domain.lastIndexOf('.')
    if (lastDot <= 0 || lastDot == domain.length - 1) return false
    val tld = domain.substring(lastDot + 1)
    if (tld.length < 2) return false
    return true
}
