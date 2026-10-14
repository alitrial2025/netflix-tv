@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import android.util.Log
import com.example.data.PlaybackUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ThumbnailCue(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    /** Zero width and height mean the cue uses the complete image. */
    val w: Int,
    val h: Int
)

object ThumbnailParser {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun timeToMs(raw: String): Long? {
        val parts = raw.replace(',', '.').split(':')
        if (parts.size !in 1..3) return null
        val seconds = parts.last().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        if (parts.size > 1 && seconds >= 60.0) return null
        val minutes = if (parts.size > 1) parts[parts.size - 2].toLongOrNull()?.takeIf { it >= 0L } ?: return null else 0L
        if (parts.size == 3 && minutes >= 60L) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null else 0L
        val total = (hours.toDouble() * 3600 + minutes.toDouble() * 60 + seconds) * 1000
        return total.takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }

    /** Parse advertised VTT cue URLs; intervals are [startMs, endMs). */
    fun parseText(vttUrl: String, text: String): List<ThumbnailCue> {
        val base = vttUrl.toHttpUrlOrNull() ?: return emptyList()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<ThumbnailCue>()
        for (block in normalized.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
            val heading = lines.firstOrNull().orEmpty()
            if (heading == "NOTE" || heading.startsWith("NOTE ") || heading == "STYLE" || heading == "REGION") continue
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) continue
            val times = lines[timeIndex].split("-->")
            if (times.size != 2) continue
            val start = timeToMs(times[0].trim()) ?: continue
            val end = timeToMs(times[1].trim().substringBefore(' ').substringBefore('\t')) ?: continue
            if (end <= start) continue
            val payload = lines.getOrNull(timeIndex + 1) ?: continue
            val resolved = try { PlaybackUrlPolicy.resolve(base, payload) } catch (_: IOException) { continue }
            val fragment = resolved.fragment
            val coordinates = if (fragment == null) listOf(0, 0, 0, 0) else {
                if (!fragment.startsWith("xywh=")) continue
                val raw = fragment.removePrefix("xywh=").removePrefix("pixel:")
                val numbers = raw.split(',').map { it.trim().toIntOrNull() }
                if (numbers.size != 4 || numbers.any { it == null }) continue
                val rect = numbers.filterNotNull()
                if (rect[0] < 0 || rect[1] < 0 || rect[2] <= 0 || rect[3] <= 0 ||
                    rect[0].toLong() + rect[2] > Int.MAX_VALUE || rect[1].toLong() + rect[3] > Int.MAX_VALUE) continue
                rect
            }
            cues += ThumbnailCue(start, end, resolved.newBuilder().fragment(null).build().toString(),
                coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        }
        return cues.sortedWith(compareBy<ThumbnailCue> { it.startMs }.thenBy { it.endMs })
    }

    suspend fun parseVtt(vttUrl: String, headers: Map<String, String> = emptyMap()): List<ThumbnailCue> =
        withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                val base = vttUrl.toHttpUrlOrNull() ?: return@withContext emptyList()
                val url = PlaybackUrlPolicy.resolve(base, vttUrl)
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
                val text = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    response.body?.string().orEmpty()
                }
                currentCoroutineContext().ensureActive()
                parseText(url.toString(), text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ThumbnailParser", "Thumbnail track unavailable", error)
                emptyList()
            }
        }
}
