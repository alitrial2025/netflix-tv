package com.example.ui.util

import androidx.media3.common.Player

/** Avoid sending redundant decoder/playlist commands on every tab focus event. */
internal fun Player.clearHomePreviewIfOwned(ownsPlayback: Boolean) {
    if (!ownsPlayback) return
    if (playbackState != Player.STATE_IDLE) stop()
    if (mediaItemCount != 0) clearMediaItems()
}
