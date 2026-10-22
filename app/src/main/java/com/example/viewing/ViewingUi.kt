@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.viewing

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Bound only after normal account admission. Closing a dialog does not end a party. */
@Composable
fun ViewingSessionHost(coordinator: ViewingCoordinator, uid: String?, profileId: String, name: String,
    ready: Boolean, onIncoming: (IncomingViewing) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val incoming by coordinator.incoming.collectAsStateWithLifecycle()
    val latestIncoming by rememberUpdatedState(onIncoming)
    LaunchedEffect(uid, profileId, name, ready) { coordinator.bind(uid.takeIf { ready }, profileId, name) }
    DisposableEffect(coordinator, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) coordinator.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) coordinator.setForeground(false)
        }
        coordinator.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); coordinator.setForeground(false) }
    }
    LaunchedEffect(incoming?.id, ready) {
        if (ready) incoming?.let { latestIncoming(it); coordinator.acknowledgeIncoming(it.id) }
    }
}

@Composable
fun WatchTogetherDialog(coordinator: ViewingCoordinator, eligible: Boolean = true, onDismiss: () -> Unit) {
    val party by coordinator.party.collectAsStateWithLifecycle()
    val busy by coordinator.busy.collectAsStateWithLifecycle()
    val error by coordinator.error.collectAsStateWithLifecycle()
    val status by coordinator.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val commentsVisible by coordinator.commentsVisible.collectAsStateWithLifecycle()
    var showChat by rememberSaveable { mutableStateOf(false) }
    if (showChat && party != null) { PartyChatDialog(coordinator) { showChat = false }; return }
    var code by rememberSaveable { mutableStateOf("") }
    val television = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val closeFocus = remember { FocusRequester() }
    com.example.ui.components.AdaptiveFeaturePanel(television, "Watch together", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Watch together", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 12.dp))
            ViewingDialogFocus(closeFocus, television, party?.code)
            TextButton(onClick = { coordinator.dismissError(); onDismiss() }, modifier = Modifier.focusRequester(closeFocus).viewingFocus(television).testTag("party_close")) { Text("Close") }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val room = party
            if (room == null) {
                Text("Watch the same movie or episode with up to 8 people on phones and TVs. Each viewer needs Basic, Standard or Premium.")
                if (!eligible) Text("Watch parties are included with Basic, Standard and Premium. Choose a plan in Membership & Plans.")
                Button(onClick = coordinator::create, enabled = eligible && !busy, modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_host")) { Text("Host the title playing now") }
                HorizontalDivider()
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase().filterNot(Char::isWhitespace).take(10) },
                    singleLine = true, label = { Text("Party code") }, supportingText = { Text("10 characters: A–F and 0–9") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_code_input"))
                Button(onClick = { coordinator.join(code) }, enabled = eligible && !busy && code.matches(Regex("[A-F0-9]{10}")), modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_join")) { Text("Join party") }
            } else {
                Text(room.target.title, fontWeight = FontWeight.Bold)
                if (room.target.kind == "tv") Text("Season ${room.target.season} · Episode ${room.target.episode}")
                Text(room.code.chunked(5).joinToString(" "), style = MaterialTheme.typography.headlineSmall)
                Text(status)
                OutlinedButton(onClick = coordinator::reopenPartyTitle, enabled = !busy, modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_open_title")) { Text("Open party title") }
                Text(if (room.isHost) "Your play, pause, seek and next episode controls sync to everyone. Leaving ends this party."
                    else "The host controls play, pause, seeking and episodes. Your audio and subtitle choices stay yours.")
                OutlinedButton(onClick = { coordinator.dismissError(); showChat = true }, modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_open_chat")) { Text("Comments & reactions") }
                TextButton(onClick = { coordinator.showComments(!commentsVisible) }, modifier = Modifier.viewingFocus(television)) { Text(if (commentsVisible) "Hide comments on video" else "Show comments on video") }
                room.members.forEach { Text("${it.name}${if (it.host) " · Host" else ""}") }
                TextButton(modifier = Modifier.viewingFocus(television).testTag("party_share"), onClick = {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join my NetflixPro watch party: ${room.code}\nOpen Watch together in NetflixPro and enter this code. Each viewer needs Basic, Standard or Premium.")
                    }, "Invite to party")) }
                }) { Text("Share invitation") }
                OutlinedButton(onClick = coordinator::leave, enabled = !busy, modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("party_leave")) { Text(if (room.isHost) "End party" else "Leave party") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }

}

@Composable
fun ContinueOnTvDialog(coordinator: ViewingCoordinator, onDismiss: () -> Unit) {
    val devices by coordinator.devices.collectAsStateWithLifecycle()
    val error by coordinator.error.collectAsStateWithLifecycle()
    val status by coordinator.status.collectAsStateWithLifecycle()
    val busy by coordinator.busy.collectAsStateWithLifecycle()
    val television = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(coordinator) { coordinator.dismissError(); coordinator.refreshDevices() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Continue on TV") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Start a title on this phone. Open NetflixPro on a TV signed into the same account, then select it below.")
            if (devices.isEmpty() && !busy) Text("No signed-in TVs found. Keep the TV app open and refresh.")
            devices.forEach { device -> OutlinedButton(onClick = { coordinator.sendToTv(device.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().viewingFocus(television).testTag("continue_tv_${device.id}")) { Text(device.name) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status == "Sent to your TV") Text(status)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        ViewingDialogFocus(closeFocus, television, null)
        TextButton(onClick = onDismiss,
        modifier = Modifier.focusRequester(closeFocus).viewingFocus(television).testTag("continue_tv_close")) { Text("Close") } },
        dismissButton = { TextButton(onClick = coordinator::refreshDevices, enabled = !busy,
            modifier = Modifier.viewingFocus(television).testTag("continue_tv_refresh")) { Text("Refresh") } })
}

/** Keyboard focus remains explicit on television while touch styling stays unchanged on phones. */
@Composable
internal fun Modifier.viewingFocus(television: Boolean): Modifier {
    if (!television) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
}

/** Run inside the dialog composition so the Android window is the correct one. */
@Composable
internal fun ViewingDialogFocus(requester: FocusRequester, television: Boolean, panel: String?) {
    val view = LocalView.current
    val inputMode = LocalInputModeManager.current
    DisposableEffect(view, requester, television, panel, inputMode.inputMode) {
        val request = Runnable { if (television) inputMode.requestInputMode(InputMode.Keyboard); runCatching { requester.requestFocus() } }
        val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused && television) view.post(request) }
        if (television) { view.viewTreeObserver.addOnWindowFocusChangeListener(observer); view.post(request) }
        onDispose {
            view.removeCallbacks(request)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)
        }
    }
}
