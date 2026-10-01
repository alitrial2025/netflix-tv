@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.screens.details

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.Caption
import com.example.data.ContinueWatchingEntity
import com.example.data.NetMirrorStream
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isSeriesContent
import com.example.ui.NetflixViewModel
import com.example.ui.util.playbackMediaSource
import com.example.ui.screens.player.updateExoPlayerTrackSelection
import com.example.data.toNetMirrorStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

suspend fun resolveAndPlayStream(
    context: Context,
    movie: Movie,
    currentSeason: Int,
    currentEpisode: Int,
    targetMediaId: String,
    viewModel: NetflixViewModel,
    exoPlayer: ExoPlayer,
    playbackOwner: String,
    trailerOnly: Boolean,
    selectedSubLang: String,
    continueWatchingData: ContinueWatchingEntity?,
    onCaptions: (List<Caption>) -> Unit
): NetMirrorStream? {
    if (!viewModel.ownsSharedPlayback(playbackOwner)) return null
    onCaptions(emptyList())
    try { exoPlayer.volume = 0.25f } catch (_: Exception) {}

    // The caller has already reused a valid prepared source when possible.
    // An expired source must not keep playing during replacement resolution.
    exoPlayer.stop()
    exoPlayer.clearMediaItems()

    val stream = withContext(Dispatchers.IO) {
        if (trailerOnly) kotlinx.coroutines.withTimeoutOrNull(55_000L) {
            viewModel.resolveTrailerStream(movie)?.toNetMirrorStream(movie.title, "trailer_${movie.id}")
        }
        else viewModel.resolveStream(movie, currentSeason, currentEpisode)
    }
    currentCoroutineContext().ensureActive()
    if (stream == null || !viewModel.ownsSharedPlayback(playbackOwner)) return null
    val cw = (continueWatchingData ?: withContext(Dispatchers.IO) { viewModel.getContinueWatching(movie.id) })
        ?.takeIf { it.title.equals(movie.title, true) && it.toMovie().catalogMediaKind() == movie.catalogMediaKind() }
    currentCoroutineContext().ensureActive()
    if (!viewModel.ownsSharedPlayback(playbackOwner)) return null
    val startMs = if (!trailerOnly && cw != null &&
        (!movie.isSeriesContent() || (cw.season == currentSeason && cw.episode == currentEpisode)))
        cw.playbackPositionMs.coerceAtLeast(0L) else 0L
    onCaptions(stream.captions)
    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
        .setMaxVideoSize(Int.MAX_VALUE, if (trailerOnly) 1080 else viewModel.userSubscription.value.maxVideoHeight.coerceAtLeast(480))
        .setMaxVideoBitrate(Int.MAX_VALUE)
        .setMaxVideoFrameRate(Int.MAX_VALUE)
        .setForceLowestBitrate(false)
        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        .setPreferredAudioLanguages("en", "eng")
        .build()
    updateExoPlayerTrackSelection(exoPlayer, selectedSubLang)
    exoPlayer.setMediaSource(playbackMediaSource(context, stream, targetMediaId, selectedSubLang), startMs)
    if (!trailerOnly) viewModel.recordDetailsPlaybackStart(playbackOwner, targetMediaId, startMs)
    exoPlayer.prepare()
    exoPlayer.play()
    return stream
}

fun buildMediaItemFromTrailer(
    url: String,
    headers: Map<String, String>,
    targetMediaId: String
): MediaItem {
    val mimeType = streamMimeTypeForUrl(url)
    val builder = MediaItem.Builder().setUri(url).setMediaId(targetMediaId)
    if (mimeType != null) builder.setMimeType(mimeType)
    return builder.build()
}

fun streamMimeTypeForUrl(url: String): String? {
    val cleanUrl = url.lowercase().substringBefore('?').substringBefore('#')
    return when {
        cleanUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        cleanUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        cleanUrl.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}
