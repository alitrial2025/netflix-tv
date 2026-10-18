package com.example.data

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

/** An explicit language choice must not fall back to an unrelated DEFAULT/FORCED text track. */
internal object SubtitleTrackSelection {
    data class Choice(val group: TrackGroup, val index: Int)
    fun choose(tracks: Tracks, selected: String): Choice? {
        if (selected.isBlank() || selected.equals("Off", true)) return null
        data class Candidate(val choice: Choice, val exact: Boolean, val selected: Boolean)
        val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).mapNotNull { index ->
                val format = group.getTrackFormat(index)
                if (!group.isTrackSupported(index) || !SubtitleLanguage.matches(format.label.orEmpty(), format.language.orEmpty(), selected)) null
                else Candidate(Choice(group.mediaTrackGroup, index), format.label.orEmpty().equals(selected, true), group.isTrackSelected(index))
            }
        }
        return candidates.sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.selected }).firstOrNull()?.choice
    }
    fun apply(player: ExoPlayer, selected: String) {
        val tracks = player.currentTracks
        val choice = choose(tracks, selected)
        val off = selected.isBlank() || selected.equals("Off", true)
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(SubtitleLanguage.code(selected))
            .setSelectUndeterminedTextLanguage(false)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, off || tracks.groups.isNotEmpty() && choice == null)
        choice?.let { builder.setOverrideForType(TrackSelectionOverride(it.group, it.index)) }
        val parameters = builder.build()
        if (player.trackSelectionParameters != parameters) player.trackSelectionParameters = parameters
    }
}
