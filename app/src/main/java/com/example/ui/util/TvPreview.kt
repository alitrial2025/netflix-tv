package com.example.ui.util

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** End the preview's ownership scope on completion, failure, timeout or cancellation. */
internal suspend fun awaitPreviewCompletion(player: Player, timeoutMs: Long = 60_000L) {
    var listener: Player.Listener? = null
    try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val observer = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                listener = observer
                player.addListener(observer)
                if ((player.playbackState == Player.STATE_ENDED || player.playerError != null) &&
                    continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    } finally {
        listener?.let { player.removeListener(it) }
    }
}
