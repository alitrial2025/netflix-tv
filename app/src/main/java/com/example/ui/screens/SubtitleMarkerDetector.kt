@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PlaybackMarkers(
    val introStart: Long? = null,
    val introEnd: Long? = null,
    val outroStart: Long? = null
)

object SubtitleMarkerDetector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun timeToMs(timeStr: String): Long {
        try {
            val clean = timeStr.replace(',', '.').trim()
            val parts = clean.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsAndMs = parts[2].toDouble()
                return ((hours * 3600 + minutes * 60 + secondsAndMs) * 1000).toLong()
            } else if (parts.size == 2) {
                val minutes = parts[0].toLong()
                val seconds = parts[1].toDouble()
                return ((minutes * 60 + seconds) * 1000).toLong()
            } else if (parts.size == 1) {
                val seconds = parts[0].toDouble()
                return (seconds * 1000).toLong()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    suspend fun detectMarkers(captionUrl: String?, durationMs: Long, isTvShow: Boolean = true): PlaybackMarkers = withContext(Dispatchers.IO) {
        Log.d("MarkerDetector", "🔍 Starting subtitle analysis for markers. URL: $captionUrl, duration: ${durationMs}ms, isTv: $isTvShow")
        if (!captionUrl.isNullOrBlank()) {
            try {
                val request = Request.Builder().url(captionUrl).build()
                // bugfix: use response.use{} to guarantee the body is closed even on
                // exception (avoids socket/connection-pool leaks on slow networks).
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: ""
                        if (text.isNotBlank()) {
                            val detected = parseAndDetect(text, durationMs, isTvShow)
                            return@withContext detected
                        }
                    } else {
                        Log.w("MarkerDetector", "Subtitle fetch failed: HTTP ${response.code} for $captionUrl")
                    }
                }
            } catch (e: Exception) {
                Log.w("MarkerDetector", "⚠️ Subtitle analysis failed: ${e.message}")
            }
        }

        // Guaranteed fallback when no captions or analysis fails
        val fallbackIntroStart = if (isTvShow && durationMs >= 10 * 60 * 1000L) 45000L else null
        val fallbackIntroEnd = if (fallbackIntroStart != null) 115000L else null
        val fallbackOutroStart = if (durationMs > 60000L) {
            if (isTvShow) maxOf(0L, durationMs - 55000L) else maxOf(0L, durationMs - 150000L)
        } else null

        Log.d("MarkerDetector", "⚡ Using smart fallback markers (intro: $fallbackIntroStart..$fallbackIntroEnd, outro: $fallbackOutroStart)")
        return@withContext PlaybackMarkers(
            introStart = fallbackIntroStart,
            introEnd = fallbackIntroEnd,
            outroStart = fallbackOutroStart
        )
    }

    private fun parseAndDetect(srtText: String, durationMs: Long, isTvShow: Boolean): PlaybackMarkers {
        // Normalize line breaks
        val normalized = srtText.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\\n\\s*\\n"))

        var introStart: Long? = null
        var introEnd: Long? = null
        var outroStart: Long? = null

        val introKeywords = listOf(
            "theme music", "title theme", "main title", "intro music",
            "theme song", "main theme", "music playing", "theme plays",
            "opening theme", "title music", "intro", "theme", "opening",
            "música de tema", "générique", "titellied", "canción temática",
            "♪", "♫", "[theme]", "(theme)", "[music]", "(music)"
        )

        val creditKeywords = listOf(
            "credit", "credits", "directed by", "created by", "executive producer",
            "theme plays", "music playing", "theme music", "ending theme", "outro",
            "crédits", "abspann", "créditos", "productor", "regie"
        )

        val parsedCues = mutableListOf<Cue>()

        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) continue

            var timeLine = ""
            var startIndex = 1

            if (lines[0].contains("-->")) {
                timeLine = lines[0]
                startIndex = 1
            } else if (lines.size >= 2 && lines[1].contains("-->")) {
                timeLine = lines[1]
                startIndex = 2
            }

            if (timeLine.isNotEmpty()) {
                val times = timeLine.split("-->").map { it.trim() }
                if (times.size == 2) {
                    val startMs = timeToMs(times[0])
                    val endMs = timeToMs(times[1])
                    val textContent = lines.subList(startIndex, lines.size).joinToString(" ")
                    parsedCues.add(Cue(startMs, endMs, textContent))
                }
            }
        }

        Log.d("MarkerDetector", "Parsed ${parsedCues.size} subtitle cues")

        // 1. Detect Intro: Look for theme/music indicators in first 25 minutes
        for (cue in parsedCues) {
            val lowerText = cue.text.lowercase()

            if (cue.startMs in 10000L..(25 * 60 * 1000L)) {
                val isIntroCue = introKeywords.any { kw ->
                    lowerText.contains(kw)
                }
                if (isIntroCue) {
                    Log.d("MarkerDetector", "🎯 Found intro cue match at ${cue.startMs}ms: \"${cue.text}\"")
                    introStart = cue.startMs
                    introEnd = cue.endMs + 30000L
                    break
                }
            }
        }

        // 2. Dialogue gap detection in first 4 minutes for TV shows
        if (introStart == null && isTvShow && parsedCues.size >= 5) {
            for (i in 0 until minOf(parsedCues.size - 1, 30)) {
                val currentCue = parsedCues[i]
                val nextCue = parsedCues[i + 1]
                val gap = nextCue.startMs - currentCue.endMs
                // If there's a 15s-90s silence gap between 30s and 4 minutes, it's very likely the opening theme
                if (currentCue.endMs in 20000L..240000L && gap in 15000L..90000L) {
                    introStart = currentCue.endMs
                    introEnd = nextCue.startMs
                    Log.d("MarkerDetector", "🎯 Found intro dialogue gap match at ${introStart}ms..${introEnd}ms (gap: ${gap}ms)")
                    break
                }
            }
        }

        // 3. Fallback intro for TV shows
        if (introStart == null && isTvShow && durationMs >= 10 * 60 * 1000L) {
            introStart = 45000L
            introEnd = 115000L
            Log.d("MarkerDetector", "⚡ Using TV show fallback intro (45s..115s)")
        }

        // 4. Detect Outro: Scan last 3 minutes of content for ending credits keywords
        val scanWindowStart = maxOf(0L, durationMs - (3 * 60 * 1000L))
        for (i in parsedCues.indices.reversed()) {
            val cue = parsedCues[i]
            if (cue.startMs > scanWindowStart) {
                val lowerText = cue.text.lowercase()
                val isOutroCue = creditKeywords.any { kw -> lowerText.contains(kw) }
                if (isOutroCue) {
                    val candidateOutro = cue.startMs
                    // Ensure outro is not ridiculously early (at most 25s before end of video)
                    outroStart = if (durationMs > 30000L) {
                        candidateOutro.coerceAtLeast(durationMs - 25000L)
                    } else candidateOutro
                    Log.d("MarkerDetector", "🎬 Found outro credit match at ${outroStart}ms: \"${cue.text}\"")
                    break
                }
            }
        }

        // 5. Outro Fallback: Last subtitle cue end if within last 20 seconds, or standard 16s credits window
        if (outroStart == null && parsedCues.isNotEmpty() && durationMs > 30000L) {
            val lastCue = parsedCues.last()
            if (lastCue.endMs in (durationMs - 20000L)..(durationMs - 4000L)) {
                outroStart = lastCue.endMs
                Log.d("MarkerDetector", "🎬 Outro fallback (last subtitle cue end) at ${outroStart}ms")
            }
        }

        if (outroStart == null && durationMs > 30000L) {
            outroStart = if (isTvShow) maxOf(0L, durationMs - 16000L) else maxOf(0L, durationMs - 35000L)
            Log.d("MarkerDetector", "⚡ Using duration fallback outro at ${outroStart}ms")
        }

        // Normalize intro duration (max 90s, min 10s)
        val finalIntroStart = introStart
        val finalIntroEnd = if (introStart != null && introEnd != null) {
            val duration = introEnd - introStart
            if (duration > 90000L || duration < 10000L) {
                introStart + 45000L
            } else {
                introEnd
            }
        } else null

        return PlaybackMarkers(
            introStart = finalIntroStart,
            introEnd = finalIntroEnd,
            outroStart = outroStart
        )
    }

    private data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )
}
