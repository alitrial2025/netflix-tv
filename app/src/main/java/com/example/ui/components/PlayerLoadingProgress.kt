package com.example.ui.components

import androidx.compose.runtime.*
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

internal object PlayerBufferingProgress {
    fun percentage(bufferedMs: Long, targetMs: Long, speed: Float): Int? {
        if (bufferedMs < 0 || targetMs <= 0 || !speed.isFinite() || speed <= 0) return null
        return (bufferedMs.toDouble() * 100 / (targetMs * speed.toDouble())).coerceIn(0.0, 100.0).toInt()
    }
}

/** Observe actual buffer duration against the configured startup/rebuffer target. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun rememberPlayerLoadingPercentage(player: ExoPlayer, resolving: Boolean, buffering: Boolean,
    startupBufferMs: Long = 2_500, rebufferMs: Long = 5_000): Int? {
    var hasPlayed by remember(player) { mutableStateOf(runCatching { player.isPlaying }.getOrDefault(false)) }
    var percentage by remember(player) { mutableStateOf<Int?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) hasPlayed = true }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { hasPlayed = false }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) hasPlayed = false
            }
        }
        runCatching { player.addListener(listener) }
        onDispose { runCatching { player.removeListener(listener) } }
    }
    LaunchedEffect(player, resolving, buffering, hasPlayed, startupBufferMs, rebufferMs) {
        percentage = null
        if (!resolving && buffering) while (true) {
            try {
                percentage = PlayerBufferingProgress.percentage(player.totalBufferedDuration,
                    if (hasPlayed) rebufferMs else startupBufferMs, player.playbackParameters.speed)
            } catch (_: IllegalStateException) { percentage = null; break }
            delay(250)
        }
    }
    return if (!resolving && buffering) percentage else null
}
