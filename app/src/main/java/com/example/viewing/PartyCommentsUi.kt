@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.viewing

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** A short, non-focusable peek at the safe top edge. Never covers subtitles or takes remote input. */
@Composable
fun PartyCommentPeek(room: WatchParty?, visible: Boolean, serverNowMs: Long, modifier: Modifier = Modifier) {
    val newest = room?.messages?.lastOrNull()
    var showing by remember(room?.code) { mutableStateOf(false) }
    LaunchedEffect(room?.code, newest?.id, visible, room?.commentsEnabled) {
        showing = visible && room?.commentsEnabled == true && newest != null && serverNowMs - newest.createdAtMs in 0..10_000
        if (showing) { delay(6_000); showing = false }
    }
    val fresh = newest != null && serverNowMs - newest.createdAtMs in 0..10_000
    if (showing && fresh && visible && room != null && room.commentsEnabled) {
        Column(modifier.widthIn(max = 300.dp).background(Color.Black.copy(alpha = .68f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("party_comment_peek"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            room.messages.filter { serverNowMs - it.createdAtMs in 0..10_000 }.takeLast(2).forEach {
                Text("${it.name}: ${it.text}", color = Color.White, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PartyChatDialog(coordinator: ViewingCoordinator, onDismiss: () -> Unit) {
    val room by coordinator.party.collectAsStateWithLifecycle()
    val busy by coordinator.chatBusy.collectAsStateWithLifecycle()
    val error by coordinator.error.collectAsStateWithLifecycle()
    val visible by coordinator.commentsVisible.collectAsStateWithLifecycle()
    val sent by coordinator.sentComment.collectAsStateWithLifecycle()
    val current = room ?: return
    PartyChatPanel(current, busy, error, visible, coordinator::sendComment, coordinator::showComments,
        coordinator::setCommentsEnabled, coordinator::deleteComment, onDismiss, sent)
}

/** Stateless panel keeps keyboard/remote behavior testable without a Firebase session. */
@Composable
fun PartyChatPanel(room: WatchParty, busy: Boolean, error: String?, commentsVisible: Boolean,
    onSend: (String) -> Unit, onVisible: (Boolean) -> Unit, onEnabled: (Boolean) -> Unit,
    onDelete: (String) -> Unit, onDismiss: () -> Unit, sentText: Pair<String, String>? = null) {
    val television = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val closeFocus = remember { FocusRequester() }
    var draft by rememberSaveable(room.code) { mutableStateOf("") }
    LaunchedEffect(sentText) { if (sentText != null && draft.trim() == sentText.second) draft = "" }
    val list = rememberLazyListState()
    LaunchedEffect(room.messages.lastOrNull()?.id) { if (room.messages.isNotEmpty()) list.scrollToItem(room.messages.lastIndex) }
    com.example.ui.components.AdaptiveFeaturePanel(television, "Party comments", onDismiss) {
        val keyboardShown = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!keyboardShown) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Party comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    ViewingDialogFocus(closeFocus, television, room.code)
                    TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).viewingFocus(television).testTag("party_chat_close")) { Text("Back") }
                }
                if (!keyboardShown) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onVisible(!commentsVisible) }, modifier = Modifier.viewingFocus(television).testTag("party_comments_visibility")) {
                        Text(if (commentsVisible) "Hide on video" else "Show on video")
                    }
                    if (room.isHost) TextButton(onClick = { onEnabled(!room.commentsEnabled) }, enabled = !busy,
                        modifier = Modifier.viewingFocus(television).testTag("party_comments_moderate")) {
                        Text(if (room.commentsEnabled) "Pause comments" else "Enable comments")
                    }
                }
                if (!keyboardShown && room.messages.isEmpty()) Text("Say hello or send a quick reaction.", color = Color.LightGray)
                if (!keyboardShown) LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f, fill = true).testTag("party_messages"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(room.messages, key = { it.id }) { message ->
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .06f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(message.name, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                Text(message.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (room.isHost) TextButton(onClick = { onDelete(message.id) }, enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.viewingFocus(television).testTag("party_delete_${message.id}")) { Text("Remove") }
                        }
                    }
                }
                if (!keyboardShown && !room.commentsEnabled) Text("Comments are paused by the host.", color = Color.LightGray)
                if (!keyboardShown) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("👏", "😂", "❤️").forEach { reaction ->
                        OutlinedButton(onClick = { onSend(reaction) }, enabled = room.commentsEnabled && !busy && room.connected,
                            modifier = Modifier.viewingFocus(television).testTag("party_reaction_$reaction")) { Text(reaction) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it.filter { c -> c.code >= 32 && c.code != 127 }.take(160) },
                        singleLine = true, enabled = room.commentsEnabled && !busy && room.connected, label = { Text("Message · ${draft.length}/160") },
                        modifier = Modifier.weight(1f).viewingFocus(television).testTag("party_message_input"))
                    Button(onClick = { onSend(draft) }, enabled = draft.isNotBlank() && room.commentsEnabled && !busy && room.connected,
                        modifier = Modifier.padding(top = 8.dp).viewingFocus(television).testTag("party_message_send")) { Text(if (busy) "…" else "Send") }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
    }
}
