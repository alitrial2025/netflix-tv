package com.example.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import com.example.ui.theme.NetflixBlack
import kotlinx.coroutines.delay

/** A compact TV keypad with every D-pad destination attached to a real button. */
@Composable
fun ProfilePinEntryScreen(
    profileName: String,
    title: String,
    subtitle: String,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    avatarUrl: String? = null,
    entryKey: Any = profileName,
    clearEntryToken: Int = 0,
    errorMessage: String? = null,
    inputEnabled: Boolean = true,
    onInputChanged: () -> Unit = {},
    onRemovePin: (() -> Unit)? = null
) {
    var enteredPin by remember(entryKey, clearEntryToken) { mutableStateOf("") }
    val keyLabels = remember { listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "Delete") }
    val keyRequesters = remember { List(keyLabels.size) { FocusRequester() } }
    val submitRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val removeRequester = remember { FocusRequester() }

    fun editEntry(label: String) {
        if (!inputEnabled) return
        onInputChanged()
        enteredPin = when (label) {
            "Clear" -> ""
            "Delete" -> enteredPin.dropLast(1)
            else -> if (enteredPin.length < 4) enteredPin + label else enteredPin
        }
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(entryKey, clearEntryToken) {
        withFrameNanos { }
        runCatching { keyRequesters[0].requestFocus() }
    }
    LaunchedEffect(enteredPin.length) {
        if (enteredPin.length == 4) {
            withFrameNanos { }
            runCatching { submitRequester.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(NetflixBlack)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
                    else -> null
                }
                val label = digit ?: when (code) {
                    KeyEvent.KEYCODE_DEL -> "Delete"
                    KeyEvent.KEYCODE_CLEAR -> "Clear"
                    else -> null
                }
                if (label != null) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) editEntry(label)
                    true
                } else {
                    // OK belongs to the focused keypad/action button.
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val compact = maxHeight < 440.dp
        val keyHeight = if (compact) 36.dp else 46.dp
        Row(
            Modifier.padding(horizontal = 32.dp, vertical = if (compact) 16.dp else 20.dp)
                .widthIn(max = 740.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(NetflixBlack)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                .padding(if (compact) 16.dp else 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val context = LocalContext.current
                val avatarRequest = remember(context, avatarUrl) {
                    avatarUrl?.takeIf { it.isNotBlank() }?.let { profileAvatarRequest(context, it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(if (compact) 48.dp else 64.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center
                    ) {
                        if (avatarRequest != null) {
                            AsyncImage(model = avatarRequest, contentDescription = profileName,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Text(profileName.take(1).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(profileName, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Text(title, color = Color.White, fontSize = if (compact) 24.sp else 28.sp,
                    lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.72f), fontSize = if (compact) 13.sp else 14.sp,
                    lineHeight = if (compact) 18.sp else 20.sp)
                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                Row(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "PIN entry"
                        stateDescription = "${enteredPin.length} of 4 digits entered"
                    }, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(4) { index ->
                        val filled = index < enteredPin.length
                        val active = index == enteredPin.length && inputEnabled
                        Box(
                            Modifier.size(if (compact) 40.dp else 44.dp, if (compact) 44.dp else 50.dp)
                                .background(NetflixBlack, RoundedCornerShape(9.dp))
                                .border(if (active) 2.dp else 1.dp,
                                    if (active) Color.White else Color.White.copy(alpha = 0.32f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (filled) Box(Modifier.size(10.dp).background(Color.White, CircleShape))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(if (compact) 44.dp else 56.dp).padding(top = 12.dp)) {
                    Text(errorMessage ?: "Use the number keys or the keypad.",
                        color = if (errorMessage == null) Color.White.copy(alpha = 0.6f) else Color(0xFFFFB8B8),
                        fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                }
            }
            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                repeat(4) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            PinKeyButton(
                                label = keyLabels[index],
                                onClick = { editEntry(keyLabels[index]) },
                                modifier = Modifier.size(68.dp, keyHeight).focusRequester(keyRequesters[index])
                                    .focusProperties {
                                        left = keyRequesters[row * 3 + (col + 2) % 3]
                                        right = keyRequesters[row * 3 + (col + 1) % 3]
                                        up = if (row == 0) backRequester else keyRequesters[index - 3]
                                        down = if (row == 3) submitRequester else keyRequesters[index + 3]
                                    },
                                dimmed = !inputEnabled
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                PinKeyButton(confirmLabel, onClick = {
                    if (inputEnabled && enteredPin.length == 4) onSubmit(enteredPin)
                }, modifier = Modifier.fillMaxWidth().height(keyHeight).focusRequester(submitRequester)
                    .focusProperties {
                        up = keyRequesters[10]; down = backRequester
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    },
                    dimmed = !inputEnabled || enteredPin.length != 4)
                PinKeyButton("Back", onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 36.dp).focusRequester(backRequester)
                        .focusProperties {
                            up = submitRequester
                            down = if (onRemovePin != null) removeRequester else keyRequesters[0]
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                        })
                if (onRemovePin != null) {
                    PinKeyButton("Turn off profile lock", onClick = onRemovePin,
                        modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 36.dp).focusRequester(removeRequester)
                            .focusProperties {
                                up = backRequester; down = keyRequesters[0]
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            })
                }
            }
        }
    }
}

@Composable
private fun PinKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    dimmed: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val pillShape = RoundedCornerShape(percent = 50)
    // Keep all destinations focusable during lockout; their actions are guarded.
    Surface(onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Color.Transparent, pillShape)
            .padding(3.dp).semantics {
            contentDescription = when (label) { "Delete" -> "Delete last digit"; "Clear" -> "Clear PIN entry"; else -> label }
            role = Role.Button
        },
        shape = ClickableSurfaceDefaults.shape(pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = NetflixBlack.copy(alpha = if (dimmed) 0.45f else 1f),
                fontSize = if (label.length == 1) 20.sp else 13.sp, lineHeight = if (label.length == 1) 24.sp else 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
    }
}

/** Shared verification for opening a locked profile and changing its existing PIN. */
@Composable
fun ProfilePinVerificationScreen(
    profileKey: String,
    profileName: String,
    avatarUrl: String? = null,
    subtitle: String = "Enter your 4-digit PIN to open this profile.",
    confirmLabel: String = "Unlock profile",
    verifyPin: (String) -> Boolean,
    onVerified: () -> Unit,
    onBack: () -> Unit
) {
    var error by remember(profileKey) { mutableStateOf<String?>(null) }
    var failures by remember(profileKey) { mutableIntStateOf(0) }
    var clearToken by remember(profileKey) { mutableIntStateOf(0) }
    var lockedUntil by remember(profileKey) { mutableLongStateOf(0L) }
    var now by remember(profileKey) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > SystemClock.elapsedRealtime()) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
        now = SystemClock.elapsedRealtime()
        if (lockedUntil != 0L) { lockedUntil = 0L; failures = 0; error = null }
    }
    val locked = lockedUntil > now
    ProfilePinEntryScreen(profileName = profileName, avatarUrl = avatarUrl,
        title = "Profile lock", subtitle = subtitle, confirmLabel = confirmLabel,
        entryKey = profileKey, clearEntryToken = clearToken, inputEnabled = !locked,
        errorMessage = if (locked) "Too many attempts. Try again in ${(lockedUntil - now + 999L) / 1000L}s." else error,
        onInputChanged = { error = null }, onBack = onBack,
        onSubmit = { pin ->
            if (!locked) {
                if (verifyPin(pin)) onVerified() else {
                    failures++
                    clearToken++
                    error = "Incorrect PIN. Please try again."
                    if (failures >= 3) {
                        now = SystemClock.elapsedRealtime()
                        lockedUntil = now + 30_000L
                    }
                }
            }
        })
}
