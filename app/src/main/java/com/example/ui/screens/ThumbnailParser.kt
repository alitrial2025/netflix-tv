@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.screens

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ThumbnailCue(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

object ThumbnailParser {

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
                val seconds = parts[2].toDouble()
                return ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
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

    suspend fun parseVtt(vttUrl: String): List<ThumbnailCue> = withContext(Dispatchers.IO) {
        Log.d("ThumbnailParser", "🔍 Starting thumbnail VTT parsing. URL: $vttUrl")
        val list = mutableListOf<ThumbnailCue>()
        try {
            val request = Request.Builder().url(vttUrl).build()
            // bugfix: use response.use{} to guarantee body is closed even on exception
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ThumbnailParser", "⚠️ Thumbnail VTT download failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                response.body?.string() ?: ""
            }

            val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
            val blocks = normalized.split(Regex("\\n\\s*\\n"))

            for (block in blocks) {
                val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size < 2) continue

                var timeLine = ""
                var payloadLine = ""

                if (lines[0].contains("-->")) {
                    timeLine = lines[0]
                    payloadLine = lines.getOrNull(1) ?: ""
                } else if (lines.size >= 2 && lines[1].contains("-->")) {
                    timeLine = lines[1]
                    payloadLine = lines.getOrNull(2) ?: ""
                }

                if (timeLine.isNotEmpty() && payloadLine.isNotEmpty()) {
                    val times = timeLine.split("-->").map { it.trim() }
                    if (times.size == 2) {
                        val startMs = timeToMs(times[0])
                        val endMs = timeToMs(times[1])

                        if (payloadLine.contains("#xywh=")) {
                            val parts = payloadLine.split("#xywh=")
                            if (parts.size == 2) {
                                val rawImageUrl = parts[0].trim()
                                val imageUrl = if (rawImageUrl.startsWith("http://") || rawImageUrl.startsWith("https://")) {
                                    rawImageUrl
                                } else {
                                    val baseUrl = if (vttUrl.contains("/")) vttUrl.substringBeforeLast('/') + "/" else ""
                                    if (rawImageUrl.startsWith("/")) {
                                        val rootUrl = vttUrl.substringBefore("://") + "://" + vttUrl.substringAfter("://").substringBefore('/')
                                        rootUrl + rawImageUrl
                                    } else {
                                        baseUrl + rawImageUrl
                                    }
                                }
                                val coords = parts[1].split(",").map { it.trim().toIntOrNull() ?: 0 }
                                if (coords.size == 4) {
                                    list.add(
                                        ThumbnailCue(
                                            startMs = startMs,
                                            endMs = endMs,
                                            imageUrl = imageUrl,
                                            x = coords[0],
                                            y = coords[1],
                                            w = coords[2],
                                            h = coords[3]
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Log.d("ThumbnailParser", "✅ Successfully parsed ${list.size} thumbnail cues")
        } catch (e: Exception) {
            Log.w("ThumbnailParser", "⚠️ Thumbnail parsing failed: ${e.message}")
        }
        return@withContext list
    }
}
