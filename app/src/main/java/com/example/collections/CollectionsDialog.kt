package com.example.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private enum class CollectionPanel { LIST, TITLES, ADD, CREATE, RENAME, DELETE }

/** A native touch/D-pad library; collection metadata never bypasses the caller's normal title gates. */
@Composable
fun CollectionsDialog(ownerUid: String?, profileId: String, availableTitles: List<CollectionTitle>,
    onOpenTitle: (CollectionTitle) -> Unit, onDismiss: () -> Unit, isTv: Boolean = false,
    controller: CollectionsController? = null) {
    val repository = remember(ownerUid, profileId, controller) { controller ?: CollectionsRepository(ownerUid, profileId) }
    val state by repository.state.collectAsState()
    DisposableEffect(repository) { repository.start(); onDispose { repository.close() } }
    var panel by remember(ownerUid, profileId) { mutableStateOf(CollectionPanel.LIST) }
    var selectedId by remember(ownerUid, profileId) { mutableStateOf<String?>(null) }
    var name by remember(ownerUid, profileId) { mutableStateOf("") }
    val selected = state.items.firstOrNull { it.id == selectedId }
    val choices = remember(availableTitles, selected) {
        val existing = selected?.items.orEmpty().map { it.key }.toSet()
        availableTitles.mapNotNull(CollectionTitle::sanitize).distinctBy { it.key }.filterNot { it.key in existing }
            .sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
    }
    val closeFocus = remember { FocusRequester() }
    fun back() {
        repository.dismissError()
        panel = when (panel) {
            CollectionPanel.ADD, CollectionPanel.RENAME, CollectionPanel.DELETE -> CollectionPanel.TITLES
            CollectionPanel.TITLES, CollectionPanel.CREATE -> CollectionPanel.LIST
            CollectionPanel.LIST -> { onDismiss(); CollectionPanel.LIST }
        }
    }
    LaunchedEffect(state.items, state.loading) {
        if (!state.loading && selectedId != null && selected == null && panel != CollectionPanel.LIST) panel = CollectionPanel.LIST
    }
    com.example.ui.components.AdaptiveFeaturePanel(isTv, "Collections", ::back, Modifier.testTag("collections_dialog")) {
        // The dialog owns a different Android window. Request only after this
        // window is attached/focused, and restore focus when its panel changes.
        val dialogView = LocalView.current
        val inputMode = LocalInputModeManager.current
        DisposableEffect(dialogView, panel, isTv, inputMode.inputMode) {
            val request = Runnable { if (isTv) inputMode.requestInputMode(InputMode.Keyboard); runCatching { closeFocus.requestFocus() } }
            val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused && isTv) dialogView.post(request) }
            if (isTv) { dialogView.viewTreeObserver.addOnWindowFocusChangeListener(observer); dialogView.post(request) }
            onDispose {
                dialogView.removeCallbacks(request)
                if (dialogView.viewTreeObserver.isAlive) dialogView.viewTreeObserver.removeOnWindowFocusChangeListener(observer)
            }
        }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(when (panel) {
                            CollectionPanel.CREATE -> "New collection"
                            CollectionPanel.RENAME -> "Rename collection"
                            CollectionPanel.DELETE -> "Delete collection?"
                            CollectionPanel.ADD -> "Add from My List"
                            CollectionPanel.TITLES -> selected?.name ?: "Collection"
                            CollectionPanel.LIST -> "Collections"
                        }, fontSize = if (isTv) 23.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (panel == CollectionPanel.LIST) Text("Your lists, saved for this profile", color = Color(0xFFB8BDC7), fontSize = 12.sp)
                    }
                    CollectionButton("Close", onDismiss, isTv, Modifier.focusRequester(closeFocus).testTag("collections_close"))
                }
                if (state.error != null) {
                    Text(state.error.orEmpty(), color = Color(0xFFFFB7B7), fontSize = 13.sp, modifier = Modifier.testTag("collections_error"))
                }
                if (state.offline || state.items.any { it.pendingSync }) {
                    Text(if (state.items.any { it.pendingSync }) "Saved on this device · syncing when connected"
                        else "Showing collections saved on this device", color = Color(0xFFB8BDC7), fontSize = 12.sp)
                }
                when (panel) {
                    CollectionPanel.CREATE, CollectionPanel.RENAME -> {
                        OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it }, singleLine = true,
                            label = { Text("Collection name") }, supportingText = { Text("${name.length}/40") },
                            modifier = Modifier.fillMaxWidth().testTag("collection_name_input"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollectionButton("Save", {
                                val saved = if (panel == CollectionPanel.CREATE) repository.create(name)
                                    else selected?.let { repository.rename(it.id, name) } == true
                                if (saved) panel = if (panel == CollectionPanel.CREATE) CollectionPanel.LIST else CollectionPanel.TITLES
                            }, isTv, enabled = CollectionPolicy.name(name) != null)
                            CollectionButton("Cancel", ::back, isTv)
                        }
                    }
                    CollectionPanel.DELETE -> {
                        Text("Delete “${selected?.name.orEmpty()}”? This removes the collection from this profile on your devices. My List and downloads stay saved.",
                            color = Color(0xFFD3D6DC), fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollectionButton("Delete collection", { selected?.let { if (repository.delete(it.id)) { selectedId = null; panel = CollectionPanel.LIST } } },
                                isTv, Modifier.testTag("collection_confirm_delete"))
                            CollectionButton("Cancel", ::back, isTv)
                        }
                    }
                    CollectionPanel.LIST -> {
                        CollectionButton("Create collection", { name = ""; repository.dismissError(); panel = CollectionPanel.CREATE }, isTv,
                            Modifier.testTag("collection_create"), enabled = ownerUid != null && !state.loading)
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!state.loading && state.items.isEmpty()) Text("Create a collection for movie nights, favourites, or your next series.",
                            color = Color(0xFFB8BDC7), modifier = Modifier.padding(vertical = 18.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.items, key = { it.id }) { collection ->
                                CollectionButton("${collection.name}  ·  ${collection.items.size} ${if (collection.items.size == 1) "title" else "titles"}", {
                                    selectedId = collection.id; repository.dismissError(); panel = CollectionPanel.TITLES
                                }, isTv, Modifier.fillMaxWidth().testTag("collection_${collection.id}"))
                            }
                        }
                    }
                    CollectionPanel.TITLES, CollectionPanel.ADD -> {
                        val adding = panel == CollectionPanel.ADD
                        if (selected != null) {
                            if (!adding) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CollectionButton("Add titles", { panel = CollectionPanel.ADD }, isTv, enabled = selected.items.size < CollectionPolicy.MAX_TITLES)
                                CollectionButton("Rename", { name = selected.name; panel = CollectionPanel.RENAME }, isTv)
                                CollectionButton("Delete", { panel = CollectionPanel.DELETE }, isTv)
                            }
                            val titles = if (adding) choices else selected.items
                            if (titles.isEmpty()) Text(if (adding) "Add more series or films to My List first. Titles already in this collection are hidden here."
                                else "No titles yet. Add titles from My List to start your collection.", color = Color(0xFFB8BDC7), modifier = Modifier.padding(vertical = 18.dp))
                            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(titles, key = { it.key }) { title ->
                                    Row(Modifier.fillMaxWidth().background(Color(0xFF25282F), RoundedCornerShape(14.dp)).padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (title.posterUrl.isNotEmpty()) AsyncImage(title.posterUrl, null, contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(if (isTv) 44.dp else 48.dp, if (isTv) 62.dp else 68.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(title.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text(if (title.kind == "tv") "Series" else "Film", color = Color(0xFFB8BDC7), fontSize = 12.sp)
                                        }
                                        if (adding) CollectionButton("Add", { repository.add(selected.id, title) }, isTv, Modifier.testTag("collection_add_${title.key}"))
                                        else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            CollectionButton("Open", { onDismiss(); onOpenTitle(title) }, isTv, Modifier.testTag("collection_open_${title.key}"))
                                            CollectionButton("Remove", { repository.remove(selected.id, title) }, isTv, Modifier.testTag("collection_remove_${title.key}"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (panel != CollectionPanel.LIST) CollectionButton("Back", ::back, isTv, Modifier.testTag("collection_back"))
            }
    }
}

@Composable
private fun CollectionButton(label: String, onClick: () -> Unit, isTv: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = if (isTv) 40.dp else 44.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (focused && isTv) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF5A606B)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (focused && isTv) Color.White else Color.Transparent,
            contentColor = if (focused && isTv) Color.Black else Color.White)) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = if (isTv) 14.sp else 13.sp)
    }
}
