@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.example.data.NetMirrorStream
import com.example.ui.screens.player.captionMatchesLanguage
import com.example.ui.screens.player.getIso2LanguageCode
import com.example.ui.screens.player.streamMimeTypeForUrl

/** Identical source construction lets Details hand its prepared decoder to Player. */
fun playbackMediaSource(
    context: Context,
    stream: NetMirrorStream,
    mediaId: String,
    subtitleLanguage: String,
    url: String = stream.url
): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(stream.headers["User-Agent"] ?: "Mozilla/5.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(90_000)
        .setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) })
    val item = MediaItem.Builder().setUri(url).setMediaId(mediaId)
    streamMimeTypeForUrl(url)?.let(item::setMimeType)
    // HLS subtitle playlists belong to the manifest, not the single-file VTT loader.
    val subtitles = stream.captions.filter {
        it.isVerified && it.type != "thumbnails" && it.url.isNotBlank() &&
            !it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    }.distinctBy { it.url }.map { caption ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(caption.url))
            .setMimeType(if (caption.type.equals("srt", true) ||
                caption.url.substringBefore('?').endsWith(".srt", true))
                MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
            .setLanguage(getIso2LanguageCode(caption.languageCode.ifBlank { caption.language }))
            .setLabel(caption.language)
            .setSelectionFlags(if (captionMatchesLanguage(caption, subtitleLanguage)) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
    item.setSubtitleConfigurations(subtitles)
    return DefaultMediaSourceFactory(com.example.data.GuardedPlaybackDataSourceFactory(DefaultDataSource.Factory(context, http), manifestHeaders = stream.headers))
        .setLoadErrorHandlingPolicy(com.example.data.PlaybackLoadErrorPolicy())
        .createMediaSource(item.build())
}
