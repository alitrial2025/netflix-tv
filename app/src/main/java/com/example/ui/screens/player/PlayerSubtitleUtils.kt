package com.example.ui.screens.player

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import coil.request.ImageRequest
import com.example.data.Caption
import com.example.ui.screens.ThumbnailCue
import com.example.ui.util.TvImagePolicy
import com.example.ui.util.TvArtworkKind
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

fun buildPlayerImageRequest(
    context: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    memoryCacheKey: String,
    artworkKind: TvArtworkKind = TvArtworkKind.BACKDROP
): ImageRequest {
    val preserveAlpha = artworkKind == TvArtworkKind.LOGO
    val size = TvImagePolicy.backdropSize(widthPx, heightPx, TvImagePolicy.isLowMemoryDevice(context))
    val placeholderDrawable = ColorDrawable(if (preserveAlpha) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)
    return ImageRequest.Builder(context)
        .data(TvImagePolicy.artworkUrl(url, size.first, artworkKind))
        .size(size.first, size.second)
        .bitmapConfig(if (preserveAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        .crossfade(false)
        .memoryCacheKey(memoryCacheKey)
        .placeholder(placeholderDrawable)
        .error(placeholderDrawable)
        .fallback(placeholderDrawable)
        .build()
}

fun streamMimeTypeForUrl(url: String): String? {
    val lower = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        lower.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

// Subtitle and Audio Language Matching Helpers
fun getIso2LanguageCode(language: String): String {
    return captionLanguageIso2(language) ?: "und"
}

fun getIso3LanguageCode(language: String): String {
    val iso2 = captionLanguageIso2(language) ?: return "und"
    return runCatching { Locale(iso2).isO3Language.lowercase(Locale.ROOT) }.getOrDefault("und")
}

fun getLanguageDisplayName(code: String): String {
    val lower = code.lowercase().trim()
    return when {
        lower == "en" || lower == "eng" || lower.startsWith("en-") -> "English"
        lower == "es" || lower == "spa" || lower.startsWith("es-") -> "Spanish"
        lower == "fr" || lower == "fre" || lower == "fra" || lower.startsWith("fr-") -> "French"
        lower == "de" || lower == "ger" || lower == "deu" || lower.startsWith("de-") -> "German"
        lower == "it" || lower == "ita" || lower.startsWith("it-") -> "Italian"
        lower == "pt" || lower == "por" || lower.startsWith("pt-") -> "Portuguese"
        lower == "ja" || lower == "jpn" -> "Japanese"
        lower == "zh" || lower == "zho" || lower == "chi" -> "Chinese"
        lower == "ru" || lower == "rus" -> "Russian"
        lower == "ar" || lower == "ara" -> "Arabic"
        lower == "hi" || lower == "hin" -> "Hindi"
        lower == "ko" || lower == "kor" -> "Korean"
        lower == "tr" || lower == "tur" -> "Turkish"
        lower == "nl" || lower == "dut" || lower == "nld" -> "Dutch"
        lower == "pol" || lower == "pl" -> "Polish"
        lower == "id" || lower == "ind" -> "Indonesian"
        lower == "th" || lower == "tha" -> "Thai"
        lower == "vi" || lower == "vie" -> "Vietnamese"
        lower == "sv" || lower == "swe" -> "Swedish"
        lower == "el" || lower == "ell" || lower == "gre" -> "Greek"
        lower == "he" || lower == "heb" -> "Hebrew"
        lower == "cs" || lower == "ces" || lower == "cze" -> "Czech"
        lower == "ro" || lower == "ron" || lower == "rum" -> "Romanian"
        else -> code.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/** Keep the quick row and modal in sync, including streams with no caption tracks. */
fun availableSubtitleOptions(captions: List<Caption>?): List<String> =
    listOf("Off") + captions.orEmpty().asSequence()
        .filter { it.type != "thumbnails" && it.url.isNotBlank() && it.isVerified }
        .map { it.language.trim() }
        .filter { it.isNotEmpty() && !it.equals("Off", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()

private val captionLanguageAliases: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale(code)
            put(code.lowercase(Locale.ROOT), code)
            put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
            runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrNull()?.let { put(it, code) }
        }
        put("fre", "fr"); put("ger", "de"); put("dut", "nl")
        put("chi", "zh"); put("gre", "el"); put("cze", "cs")
        put("rum", "ro"); put("esp", "es")
        put("español", "es"); put("français", "fr"); put("deutsch", "de")
    }
}

private fun captionLanguageIso2(value: String): String? {
    val clean = value.trim().lowercase(Locale.ROOT)
        .substringBefore('[').substringBefore('(').trim()
    if (clean.isEmpty() || clean == "und") return null
    return captionLanguageAliases[clean]
        ?: captionLanguageAliases[clean.substringBefore('-').substringBefore('_')]
}

/** Resolve one visible subtitle choice without the playback language helper's English fallback. */
fun resolveSelectedSubtitleOption(options: List<String>, selectedLanguage: String): String? {
    val selected = selectedLanguage.trim()
    if (selected.isEmpty() || selected.equals("Off", ignoreCase = true)) {
        return options.firstOrNull { it.equals("Off", ignoreCase = true) }
    }

    options.firstOrNull { it.trim().equals(selected, ignoreCase = true) }?.let { return it }

    val selectedCode = captionLanguageIso2(selected) ?: return null
    return options.firstOrNull { option ->
        !option.equals("Off", ignoreCase = true) &&
            captionLanguageIso2(option) == selectedCode
    }
}

fun trackMatchesLanguage(format: Format, selectedSubLang: String): Boolean {
    if (selectedSubLang.isBlank() || selectedSubLang.equals("Off", ignoreCase = true)) return false
    val label = format.label.orEmpty().trim()
    if (label.equals(selectedSubLang.trim(), ignoreCase = true)) return true
    val target = captionLanguageIso2(selectedSubLang) ?: return false
    val labelled = captionLanguageIso2(label)
    if (labelled != null) return labelled == target
    return captionLanguageIso2(format.language.orEmpty()) == target
}

fun captionMatchesLanguage(caption: Caption, selectedSubLang: String): Boolean {
    if (selectedSubLang.isBlank() || selectedSubLang.equals("Off", ignoreCase = true)) return false
    if (caption.language.trim().equals(selectedSubLang.trim(), ignoreCase = true)) return true
    val target = captionLanguageIso2(selectedSubLang) ?: return false
    val labelled = captionLanguageIso2(caption.language)
    if (labelled != null) return labelled == target
    return captionLanguageIso2(caption.languageCode) == target
}

fun updateExoPlayerTrackSelection(exoPlayer: ExoPlayer, selectedSubLang: String) {
    if (selectedSubLang.equals("Off", ignoreCase = true) || selectedSubLang.isBlank()) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        return
    }

    val iso2 = captionLanguageIso2(selectedSubLang)
    val preferredLanguages = if (iso2 != null) {
        listOf(iso2, getIso3LanguageCode(iso2), selectedSubLang.lowercase(Locale.ROOT))
            .filter { it != "und" }.distinct()
    } else {
        listOf(selectedSubLang.lowercase(Locale.ROOT))
    }
    val builder = exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setPreferredTextLanguages(*preferredLanguages.toTypedArray())
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)

    val currentTracks = try { exoPlayer.currentTracks } catch (_: Exception) { null }
    if (currentTracks != null) {
        var overrideAdded = false
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSupported) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        if (trackMatchesLanguage(format, selectedSubLang)) {
                            builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                            overrideAdded = true
                            break
                        }
                    }
                }
            }
            if (overrideAdded) break
        }
    }
    exoPlayer.trackSelectionParameters = builder.build()
}

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleCueParser {
    val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val textCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun fetchSubtitleText(rawUrl: String, customHeaders: Map<String, String>? = null): String? = withContext(Dispatchers.IO) {
        if (rawUrl.isBlank()) return@withContext null
        val url = rawUrl.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
        textCache[url]?.let { return@withContext it }

        // Attempt 0: Direct request with customHeaders (if available from stream)
        if (!customHeaders.isNullOrEmpty()) {
            try {
                val b = Request.Builder().url(url)
                customHeaders.forEach { (k, v) -> b.header(k, v) }
                client.newCall(b.build()).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            textCache[url] = body
                            return@withContext body
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Attempt 1: Direct clean GET
        try {
            val req1 = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .build()
            client.newCall(req1).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        textCache[url] = body
                        return@withContext body
                    }
                }
            }
        } catch (_: Exception) {}

        // Attempt 2: NetMirror / net52 spoofed request
        try {
            val req2 = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://net52.cc/")
                .header("Origin", "https://net52.cc")
                .header("X-Requested-With", "app.netmirror.netmirrornew")
                .build()
            client.newCall(req2).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        textCache[url] = body
                        return@withContext body
                    }
                }
            }
        } catch (_: Exception) {}

        null
    }

    suspend fun parseCuesFromTextOrM3u8(body: String, parentUrl: String): List<SubtitleCue> {
        val trimmed = body.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) return emptyList()

        if (trimmed.contains("-->") || trimmed.contains("WEBVTT") || trimmed.startsWith("1\n") || trimmed.startsWith("1\r\n")) {
            val cues = parseCues(trimmed)
            if (cues.isNotEmpty()) return cues
        }

        if (trimmed.contains("#EXTM3U") && (trimmed.contains(".vtt") || trimmed.contains(".webvtt") || trimmed.contains("EXTINF"))) {
            val baseUrl = if (parentUrl.contains("/")) parentUrl.substringBeforeLast("/") + "/" else ""
            val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            val allCues = mutableListOf<SubtitleCue>()
            for (segLine in lines.take(120)) {
                val segUrl = if (segLine.startsWith("http://") || segLine.startsWith("https://")) segLine else "$baseUrl$segLine"
                val segText = fetchSubtitleText(segUrl)
                if (!segText.isNullOrBlank()) {
                    val segCues = parseCues(segText)
                    allCues.addAll(segCues)
                }
            }
            if (allCues.isNotEmpty()) {
                return allCues.sortedBy { it.startMs }
            }
        }
        return emptyList()
    }

    fun parseCues(srtOrVttText: String): List<SubtitleCue> {
        val normalized = srtOrVttText.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        val cues = mutableListOf<SubtitleCue>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val times = line.split("-->").map { it.trim() }
                if (times.size == 2) {
                    val startMs = parseTimestampToMs(times[0])
                    val endMs = parseTimestampToMs(times[1])
                    if (startMs >= 0 && endMs > startMs) {
                        val textLines = mutableListOf<String>()
                        var j = i + 1
                        while (j < lines.size) {
                            val nextLine = lines[j].trim()
                            if (nextLine.isEmpty() || nextLine.contains("-->")) {
                                break
                            }
                            if (nextLine.all { it.isDigit() } && j + 1 < lines.size && lines[j + 1].contains("-->")) {
                                break
                            }
                            textLines.add(nextLine)
                            j++
                        }
                        val rawText = textLines.joinToString("\n")
                        val cleanText = cleanSubtitleText(rawText)
                        if (cleanText.isNotBlank()) {
                            cues.add(SubtitleCue(startMs, endMs, cleanText))
                        }
                        i = j - 1
                    }
                }
            }
            i++
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestampToMs(timeStr: String): Long {
        try {
            val cleanStr = timeStr.split(Regex("\\s+"))[0].replace(',', '.').trim()
            val parts = cleanStr.split(":")
            return when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secondsAndMs = parts[2].toDouble()
                    ((hours * 3600 + minutes * 60 + secondsAndMs) * 1000).toLong()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secondsAndMs = parts[1].toDouble()
                    ((minutes * 60 + secondsAndMs) * 1000).toLong()
                }
                1 -> {
                    val secondsAndMs = parts[0].toDouble()
                    (secondsAndMs * 1000).toLong()
                }
                else -> -1L
            }
        } catch (e: Exception) {
            return -1L
        }
    }

    private fun cleanSubtitleText(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .trim()
    }
}

fun getIsoLanguageCode(language: String): String {
    return getIso2LanguageCode(language)
}

fun formatTime(sec: Int): String {
    val safeSec = sec.coerceAtLeast(0)
    val hrs = safeSec / 3600
    val mins = (safeSec % 3600) / 60
    val secs = safeSec % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

fun findCue(cues: List<ThumbnailCue>, timeMs: Long): ThumbnailCue? {
    if (cues.isEmpty()) return null
    var low = 0
    var high = cues.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (timeMs < cue.startMs) {
            high = mid - 1
        } else if (timeMs > cue.endMs) {
            low = mid + 1
        } else {
            return cue
        }
    }
    return cues.getOrNull(low.coerceIn(0, cues.size - 1))
}

fun findActiveCueAt(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
    if (cues.isEmpty()) return null
    for (i in cues.indices) {
        val cue = cues[i]
        if (timeMs in cue.startMs..cue.endMs) {
            return cue
        }
        if (cue.startMs > timeMs + 10000L) {
            break
        }
    }
    return null
}
