package com.example.data

import com.example.ui.screens.PlaybackMarkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Uses advertised chapter boundaries, then published markers for the exact title/episode. */
class PlaybackMarkerRepository(
    client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val publicSource: IntroDbMarkerSource? = IntroDbMarkerSource(client)
) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private data class Cached(val markers: PlaybackMarkers, val savedAt: Long, val ttlMs: Long)
    private val cache = linkedMapOf<String, Cached>()

    suspend fun markers(
        movieId: String,
        imdbId: String? = null,
        isTv: Boolean,
        season: Int,
        episode: Int,
        durationMs: Long,
        stream: NetMirrorStream?
    ): PlaybackMarkers {
        if (movieId.isBlank() || durationMs <= 0 || durationMs == Long.MAX_VALUE ||
            isTv && (season <= 0 || episode <= 0) ||
            !imdbId.isNullOrBlank() && !imdbId.matches(Regex("tt[0-9]{5,12}"))) return PlaybackMarkers()
        val chapters = stream?.captions.orEmpty().filter { it.type.equals("chapters", true) && it.isVerified }.take(3)
        if (chapters.isEmpty() && publicSource == null) return PlaybackMarkers()
        val key = listOf(movieId, imdbId.orEmpty(), isTv.toString(), season.toString(), episode.toString(),
            durationMs.toString(), stream?.sourceId.orEmpty(), chapters.joinToString { it.url }, stream?.headers.hashCode().toString()).joinToString("|")
        return withTimeoutOrNull(12_000L) {
            mutex.withLock {
                val now = nowMs()
                cache[key]?.takeIf { now >= it.savedAt && now - it.savedAt < it.ttlMs }?.let { return@withLock it.markers }
                var result = PlaybackMarkers()
                withTimeoutOrNull(6_000L) {
                    for (chapter in chapters) {
                        val base = chapter.url.toHttpUrlOrNull() ?: continue
                        val url = try { PlaybackUrlPolicy.resolve(base, chapter.url) } catch (_: IOException) { continue }
                        val request = try {
                            Request.Builder().url(url).apply {
                                stream?.headers.orEmpty().forEach { (name, value) -> header(name, value) }
                            }.build()
                        } catch (_: IllegalArgumentException) { continue }
                        val parsed = try {
                            val response = http.fetchText(request, 512L * 1024)
                            if (response.isSuccessful) PlaybackChapterParser.parseText(response.body, durationMs) else PlaybackMarkers()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: IOException) { PlaybackMarkers() }
                        if (result.introStart == null && parsed.introStart != null) {
                            result = result.copy(introStart = parsed.introStart, introEnd = parsed.introEnd)
                        }
                        if (result.outroStart == null && parsed.outroStart != null) result = result.copy(outroStart = parsed.outroStart)
                        if (result.introStart != null && result.outroStart != null) break
                    }
                }
                if (result.introStart == null || result.outroStart == null) {
                    val published = publicSource?.markers(movieId, imdbId, isTv, season, episode, durationMs) ?: PlaybackMarkers()
                    val existingCredits = result.outroStart
                    if (result.introStart == null && published.introStart != null && published.introEnd != null &&
                        (existingCredits == null || existingCredits >= published.introEnd)) {
                        result = result.copy(introStart = published.introStart, introEnd = published.introEnd)
                    }
                    val existingIntroEnd = result.introEnd
                    if (result.outroStart == null && published.outroStart != null &&
                        (existingIntroEnd == null || published.outroStart >= existingIntroEnd)) {
                        result = result.copy(outroStart = published.outroStart)
                    }
                }
                val introEnd = result.introEnd
                val outroStart = result.outroStart
                if (introEnd != null && outroStart != null && outroStart < introEnd) {
                    result = PlaybackMarkers()
                }
                if (cache.size >= 48) cache.keys.firstOrNull()?.let(cache::remove)
                val hasMarkers = result.introStart != null || result.outroStart != null
                cache[key] = Cached(result, nowMs(), if (hasMarkers) 3_600_000L else 30_000L)
                result
            }
        } ?: PlaybackMarkers()
    }
}

/** WebVTT chapters use exact publisher-supplied cue boundaries and explicit labels. */
internal object PlaybackChapterParser {
    private val introLabels = setOf("intro", "opening credits", "opening theme", "title sequence")
    private val creditsLabels = setOf("credits", "end credits", "ending credits", "closing credits", "outro")

    private fun timestamp(raw: String): Long? {
        val match = Regex("^(?:(\\d+):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})$").matchEntire(raw) ?: return null
        val hours = match.groupValues[1].ifBlank { "0" }.toLongOrNull() ?: return null
        if (hours > Long.MAX_VALUE / 3_600_000L - 1) return null
        return hours * 3_600_000L + match.groupValues[2].toLong() * 60_000L +
            match.groupValues[3].toLong() * 1_000L + match.groupValues[4].toLong()
    }

    fun parseText(text: String, durationMs: Long): PlaybackMarkers {
        if (durationMs <= 0 || durationMs == Long.MAX_VALUE) return PlaybackMarkers()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (!Regex("^WEBVTT(?:[ \\t].*)?$").matches(normalized.lineSequence().firstOrNull().orEmpty().trim())) return PlaybackMarkers()
        data class Chapter(val start: Long, val end: Long, val label: String)
        val chapters = mutableListOf<Chapter>()
        for (block in normalized.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
            val heading = lines.firstOrNull().orEmpty()
            if (heading == "NOTE" || heading.startsWith("NOTE ") || heading == "STYLE" || heading == "REGION") continue
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) continue
            val times = lines[index].split("-->")
            if (times.size != 2) continue
            val start = timestamp(times[0].trim()) ?: continue
            val end = timestamp(times[1].trim().substringBefore(' ').substringBefore('\t')) ?: continue
            if (start < 0 || end <= start || end > durationMs) continue
            val label = lines.drop(index + 1).joinToString(" ").replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            if (label in introLabels || label in creditsLabels) chapters += Chapter(start, end, label)
        }
        val intro = chapters.filter { it.label in introLabels }.minByOrNull { it.start }
        val credits = chapters.filter { it.label in creditsLabels }.minByOrNull { it.start }
        // Conflicting source chapters are discarded rather than adjusted or guessed.
        if (intro != null && credits != null && credits.start < intro.end) return PlaybackMarkers()
        return PlaybackMarkers(intro?.start, intro?.end, credits?.start)
    }
}
