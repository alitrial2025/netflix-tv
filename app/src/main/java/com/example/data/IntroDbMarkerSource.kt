package com.example.data

import com.example.ui.screens.PlaybackMarkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Community supplied timings, retrieved by exact media identity. Never derives timings
 * from subtitles, percentages, or a title search. Missing data leaves the pills hidden.
 *
 * Public schemas: https://theintrodb.org/docs and CloudStream IntroDbSkip/TheIntroDBSkip
 * at recloudstream/cloudstream commit 94e955836bdf2932bea248b2eba29652d761cc78.
 * These services do not promise that every streaming edition has the same timeline.
 */
class IntroDbMarkerSource(
    client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val blockedUntil = mutableMapOf<String, Long>()
    private data class Cached(val result: PlaybackMarkers, val savedAt: Long, val ttl: Long)
    private val cache = linkedMapOf<String, Cached>()

    suspend fun markers(
        movieId: String,
        imdbId: String?,
        isTv: Boolean,
        season: Int,
        episode: Int,
        durationMs: Long
    ): PlaybackMarkers {
        if (durationMs <= 0 || durationMs == Long.MAX_VALUE || isTv && (season <= 0 || episode <= 0)) return PlaybackMarkers()
        val tmdb = movieId.toLongOrNull()?.takeIf { it > 0 }
        val imdb = imdbId?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
        if (tmdb == null && (!isTv || imdb == null)) return PlaybackMarkers()
        val key = "$tmdb|$imdb|$isTv|$season|$episode|$durationMs"
        synchronized(lock) {
            val now = nowMs()
            cache[key]?.takeIf { now >= it.savedAt && now - it.savedAt < it.ttl }?.let { return it.result }
        }
        var receivedData = false
        var primary = PlaybackMarkers()
        val result = withTimeoutOrNull(5_000L) {
            if (isTv && imdb != null) {
                val url = "https://api.introdb.app/segments".toHttpUrl().newBuilder()
                    .addQueryParameter("imdb_id", imdb).addQueryParameter("season", season.toString())
                    .addQueryParameter("episode", episode.toString()).build()
                fetch(url)?.let {
                    receivedData = true
                    primary = IntroDbMarkerParser.parseIntroDb(it, imdb, season, episode, durationMs)
                }
            }
            if (tmdb == null || primary.introStart != null && primary.outroStart != null) return@withTimeoutOrNull primary
            val url = "https://api.theintrodb.org/v2/media".toHttpUrl().newBuilder()
                .addQueryParameter("tmdb_id", tmdb.toString()).apply {
                    if (isTv) {
                        addQueryParameter("season", season.toString())
                        addQueryParameter("episode", episode.toString())
                    }
                }.build()
            val fallback = fetch(url)?.let {
                receivedData = true
                IntroDbMarkerParser.parseTheIntroDb(it, tmdb, isTv, season, episode, durationMs)
            } ?: PlaybackMarkers()
            // Retain each provider's complete intro interval; never combine its start with another end.
            val combined = PlaybackMarkers(
                primary.introStart ?: fallback.introStart,
                primary.introEnd ?: fallback.introEnd,
                primary.outroStart ?: fallback.outroStart
            )
            if (combined.introEnd != null && combined.outroStart != null && combined.outroStart < combined.introEnd)
                primary else combined
        } ?: primary
        if (receivedData) synchronized(lock) {
            if (cache.size >= 48) cache.keys.firstOrNull()?.let(cache::remove)
            cache[key] = Cached(result, nowMs(), if (result != PlaybackMarkers()) 3_600_000L else 30_000L)
        }
        return result
    }

    private suspend fun fetch(url: HttpUrl): JSONObject? {
        synchronized(lock) { if ((blockedUntil[url.host] ?: 0L) > nowMs()) return null }
        return try {
            val response = http.fetchText(Request.Builder().url(url).header("Accept", "application/json").build(), 64L * 1024)
            if (response.code == 429 || response.code == 503) {
                val delay = StreamSessionPolicy.retryAfterDelayMs(response.header("Retry-After"), nowMs())
                    ?: if (response.code == 429) 60_000L else 30_000L
                synchronized(lock) { blockedUntil[url.host] = nowMs() + delay }
                null
            } else if (response.code == 200) JSONObject(response.body) else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
        catch (_: org.json.JSONException) { null }
    }
}

internal object IntroDbMarkerParser {
    private data class Interval(val start: Long, val end: Long)

    private fun interval(json: JSONObject?, duration: Long, requireConfidence: Boolean, creditsOnly: Boolean = false): Interval? {
        if (json == null) return null
        val start = (json.opt("start_ms") as? Number)?.toDouble() ?: return null
        // A credits start is sufficient to show Next Episode. Its optional end never
        // becomes a skip target; only an intro requires two explicit boundaries.
        val end = (json.opt("end_ms") as? Number)?.toDouble()
            ?: if (creditsOnly && (!json.has("end_ms") || json.isNull("end_ms"))) duration.toDouble() else return null
        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end > duration ||
            start % 1.0 != 0.0 || end % 1.0 != 0.0) return null
        // IntroDB publishes confidence and sample count; do not promote a weak submission.
        val confidence = (json.opt("confidence") as? Number)?.toDouble()
        if ((requireConfidence || json.has("confidence")) &&
            (confidence == null || !confidence.isFinite() || confidence !in 0.8..1.0)) return null
        if (json.has("submission_count")) {
            val samples = (json.opt("submission_count") as? Number)?.toDouble() ?: return null
            if (!samples.isFinite() || samples < 2 || samples % 1.0 != 0.0) return null
        }
        return Interval(start.toLong(), end.toLong())
    }

    private fun markers(intro: Interval?, credits: Interval?): PlaybackMarkers {
        if (intro != null && credits != null && credits.start < intro.end) return PlaybackMarkers()
        return PlaybackMarkers(intro?.start, intro?.end, credits?.start)
    }

    private fun exactInteger(root: JSONObject, key: String, expected: Long): Boolean {
        val value = (root.opt(key) as? Number)?.toDouble() ?: return false
        return value.isFinite() && value == expected.toDouble()
    }

    fun parseIntroDb(root: JSONObject, imdb: String, season: Int, episode: Int, duration: Long): PlaybackMarkers {
        if (root.optString("imdb_id") != imdb || !exactInteger(root, "season", season.toLong()) ||
            !exactInteger(root, "episode", episode.toLong())) return PlaybackMarkers()
        return markers(interval(root.optJSONObject("intro"), duration, true),
            interval(root.optJSONObject("outro"), duration, true))
    }

    fun parseTheIntroDb(root: JSONObject, tmdb: Long, isTv: Boolean, season: Int, episode: Int, duration: Long): PlaybackMarkers {
        if (!exactInteger(root, "tmdb_id", tmdb)) return PlaybackMarkers()
        // v2 identifies the resource by query. Some responses also echo episode fields.
        if (isTv && ((root.has("season") && !root.isNull("season") && !exactInteger(root, "season", season.toLong())) ||
                (root.has("episode") && !root.isNull("episode") && !exactInteger(root, "episode", episode.toLong())))) return PlaybackMarkers()
        if (!isTv && ((!root.isNull("season") && root.has("season")) || (!root.isNull("episode") && root.has("episode")))) return PlaybackMarkers()
        val type = root.optString("type").lowercase()
        if (isTv && type !in setOf("tv", "episode", "series") || !isTv && type != "movie") return PlaybackMarkers()
        // v2 may contain several editions. Ambiguous different intervals are not safe to select.
        fun single(array: JSONArray?, creditsOnly: Boolean = false): Interval? {
            if (array == null || array.length() == 0 || array.length() > 20) return null
            val intervals = (0 until array.length()).mapNotNull { interval(array.optJSONObject(it), duration, false, creditsOnly) }.distinct()
            return intervals.singleOrNull()
        }
        return markers(single(root.optJSONArray("intro")), single(root.optJSONArray("credits"), true))
    }
}
