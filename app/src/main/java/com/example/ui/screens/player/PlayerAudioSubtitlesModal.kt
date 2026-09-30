package com.example.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.data.NetMirrorStream
import com.example.ui.theme.NetflixRed

data class PlayerAudioTrackItem(
    val group: androidx.media3.common.TrackGroup,
    val trackIndex: Int,
    val label: String,
    val language: String,
    val isSelected: Boolean
)

@Composable
fun AudioSubtitlesModal(
    exoPlayer: ExoPlayer,
    playerTracks: Tracks,
    activeStream: NetMirrorStream?,
    selectedSubLang: String,
    onSelectSubtitle: (String) -> Unit,
    onClose: () -> Unit,
    onUserActivity: () -> Unit
) {
    val allSubtitleOptions = remember(activeStream) {
        availableSubtitleOptions(activeStream?.captions)
    }

    val actualSelectedSubOpt = remember(allSubtitleOptions, selectedSubLang) {
        resolveSelectedSubtitleOption(allSubtitleOptions, selectedSubLang)
    }

    val currentAudioTracks = remember(playerTracks) {
        val list = mutableListOf<PlayerAudioTrackItem>()
        for (group in playerTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language ?: "und"
                    val rawLabel = format.label?.ifBlank { null }
                    val displayLabel = rawLabel ?: getLanguageDisplayName(lang)
                    list.add(
                        PlayerAudioTrackItem(
                            group = group.mediaTrackGroup,
                            trackIndex = i,
                            label = displayLabel,
                            language = lang,
                            isSelected = group.isTrackSelected(i)
                        )
                    )
                }
            }
        }
        list
    }

    val closeButtonRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            androidx.compose.runtime.withFrameNanos { /* commit first frame */ }
            closeButtonRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .padding(horizontal = 60.dp, vertical = 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = NetflixRed,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Audio & Subtitles",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                var isCloseFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onClose,
                    modifier = Modifier
                        .focusRequester(closeButtonRequester)
                        .onFocusChanged { isCloseFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f))),
                        focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
                ) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = if (isCloseFocused) Color.Black else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // Audio Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Audio (${currentAudioTracks.size})",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = currentAudioTracks,
                            key = { i, track -> "${track.language}_${track.trackIndex}_$i" },
                            contentType = { _, _ -> "audio_option" }
                        ) { _, track ->
                            val isSelected = track.isSelected
                            var isAudioFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    onUserActivity()
                                    try {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
                                            .setPreferredAudioLanguages(track.language, getIso2LanguageCode(track.language), getIso3LanguageCode(track.language))
                                            .build()
                                    } catch (_: Exception) {}
                                    onClose()
                                },
                                modifier = Modifier.onFocusChanged { isAudioFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (isSelected) Color.White.copy(alpha = 0.4f) else Color.Transparent)),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                            ) {
                                val audioTextColor = if (isAudioFocused) Color.Black else Color.White
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = track.label,
                                        color = audioTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isAudioFocused) Color.Black else NetflixRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Subtitles Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Subtitles (${allSubtitleOptions.size})",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (allSubtitleOptions.size == 1) {
                        Text(
                            text = if (activeStream == null) "Captions appear when playback loads"
                                else "No subtitle tracks available for this stream",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = allSubtitleOptions,
                            key = { it },
                            contentType = { "subtitle_option" }
                        ) { opt ->
                            val isSelected = opt == actualSelectedSubOpt
                            var isSubFocused by remember { mutableStateOf(false) }

                            Surface(
                                onClick = {
                                    onSelectSubtitle(opt)
                                    onClose()
                                },
                                modifier = Modifier.onFocusChanged { isSubFocused = it.isFocused },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (isSelected) Color.White.copy(alpha = 0.4f) else Color.Transparent)),
                                    focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                            ) {
                                val subTextColor = if (isSubFocused) Color.Black else Color.White
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = opt,
                                        color = subTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isSubFocused) Color.Black else NetflixRed,
                                            modifier = Modifier.size(18.dp)
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
}
