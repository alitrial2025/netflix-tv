package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import android.util.Base64
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal enum class PlaybackRoute(val label: String) { NEWTV("NewTV"), DIRECT("Direct"), ISSUED("Issued") }

/** Speculative routes may not consume the existing playlist route's request window. */
internal class PlaybackProbeBudget(
    private val deadlineNanos: Long,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private fun availableMs(): Long = ((deadlineNanos - nowNanos()) / 1_000_000L - 18_000L).coerceAtLeast(0L)
    fun primaryMs(): Long = minOf(4_000L, availableMs())
    fun directMs(): Long = minOf(2_000L, availableMs())
}

/** NewTV is an independent service; its Retry-After must never poison the net52 queue. */
internal open class CookieFreePlaybackGate(nowMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val cooldown = PlaybackRequestCooldown(nowMs)
    private val queue = Mutex()
    fun remainingMs() = cooldown.remainingMs()
    fun recordLimit(retryAfterMs: Long? = null) { cooldown.onRateLimited(retryAfterMs) }
    fun check() { remainingMs().takeIf { it > 0 }?.let { throw PlaybackRateLimitedException(it) } }
    suspend fun <T> request(block: suspend () -> T): T = queue.withLock { check(); block() }
    fun checkResponse(code: Int, body: String, retryAfter: String?, url: String) {
        if (StreamSessionPolicy.isRateLimited(code, body) || StreamSessionPolicy.isWaitingVideo(url)) {
            recordLimit(StreamSessionPolicy.retryAfterDelayMs(retryAfter, System.currentTimeMillis()))
            throw PlaybackRateLimitedException(remainingMs())
        }
    }
}

internal object NewTvPlaybackGate : CookieFreePlaybackGate()

/** Only HTTPS URLs supplied by this route are followed, without cookies or credentials. */
internal object PlaybackUrlPolicy {
    fun resolve(base: HttpUrl, raw: String): HttpUrl {
        val value = raw.trim()
        if (value.isEmpty() || value.contains('\\') || Regex("(?i)^https?://(?:/|$)").containsMatchIn(value))
            throw IOException("Invalid playback URL")
        val url = base.resolve(value) ?: throw IOException("Invalid playback URL")
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.host.isBlank() ||
            url.host == "localhost" || url.host.endsWith(".localhost") || url.host == "::1" ||
            url.host.startsWith("127.") || url.host.startsWith("10.") || url.host.startsWith("192.168.") ||
            url.host.startsWith("169.254.") || Regex("^172\\.(?:1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(url.host))
            throw IOException("Invalid playback URL")
        return url
    }
}

/** Optional tracks are used only when the provider explicitly advertises their URLs. */
internal object PlaybackMetadataTracks {
    fun parse(data: JSONObject, base: HttpUrl): List<Caption> {
        val result = mutableListOf<Caption>()
        fun firstString(item: JSONObject, vararg names: String): String = names.firstNotNullOfOrNull {
            (item.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()
        fun append(raw: String, kind: String, label: String = "", languageCode: String = "", requireVttExtension: Boolean = false) {
            if (raw.isBlank()) return
            val url = try { PlaybackUrlPolicy.resolve(base, raw) } catch (_: IOException) { return }
            val thumbnail = kind in setOf("thumbnails", "thumbnail", "storyboard", "storyboards")
            val extension = url.encodedPath.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
            // A poster or sprite URL without a timed VTT is not a thumbnail track.
            if (thumbnail && (requireVttExtension && extension != "vtt" || extension in setOf("jpg", "jpeg", "png", "webp", "gif"))) return
            if (!thumbnail && kind.isNotBlank() && kind !in setOf("subtitles", "captions", "vtt", "srt", "chapters")) return
            result += Caption(url.toString(), label.ifBlank { if (thumbnail) "Thumbnails" else "English" },
                if (thumbnail) "thumbnails" else if (kind == "chapters") "chapters" else if (extension == "srt" || kind == "srt") "srt" else "vtt",
                if (thumbnail) "" else languageCode.ifBlank { "en" })
        }
        for (name in listOf("tracks", "captions", "subtitles")) {
            val rows = data.optJSONArray(name) ?: continue
            for (index in 0 until rows.length()) {
                val track = rows.optJSONObject(index) ?: continue
                append(firstString(track, "file", "src", "url"),
                    firstString(track, "kind", "type").lowercase(java.util.Locale.ROOT),
                    firstString(track, "label", "language", "name", "lang"),
                    firstString(track, "srclang", "language", "lang", "code"))
            }
        }
        for (name in listOf("thumbnails", "thumbnail", "thumb", "storyboard", "storyboards")) {
            when (val value = data.opt(name)) {
                is String -> append(value, "thumbnails", requireVttExtension = true)
                is JSONObject -> append(firstString(value, "file", "src", "url"), "thumbnails", requireVttExtension = true)
            }
        }
        return result.distinctBy { it.url to it.type }
    }
}

/** Checks every advertised URI and probes representative renditions, retaining the master. */
internal class CookieFreeManifestValidator(
    private val fetch: suspend (HttpUrl, Map<String, String>) -> HttpTextResponse
) {
    suspend fun validate(master: HttpUrl, headers: Map<String, String>, sourceId: String? = null): Long {
        val visited = mutableSetOf<String>()
        val verified = linkedMapOf<String, String>()
        var vod = true
        var expiry = System.currentTimeMillis() + 3_600_000L
        suspend fun visit(url: HttpUrl, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(url.toString())) return
            if (depth > 3 || visited.size > 20) throw IOException("Playback manifest validation budget exceeded")
            val response = fetch(url, headers)
            if (!response.isSuccessful || !response.body.trimStart().startsWith("#EXTM3U"))
                throw IOException("Playback manifest unavailable (HTTP ${response.code})")
            verified[url.toString()] = response.body
            CdnRoutePolicy.earliestManifestExpiry(response.body, System.currentTimeMillis())?.let { expiry = minOf(expiry, it) }
            val lines = response.body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            var videoCount = 0
            var mediaCount = 0
            val children = linkedSetOf<HttpUrl>()
            var audioSelected = false
            var subtitlesSelected = false
            for ((index, line) in lines.withIndex()) {
                // Reject empty hosts and unsafe URLs even in renditions that are not selected first.
                Regex("URI=\"([^\"]*)\"").findAll(line).forEach { match ->
                    val linked = PlaybackUrlPolicy.resolve(url, match.groupValues[1])
                    if (line.startsWith("#EXT-X-MEDIA:")) {
                        if (line.contains("TYPE=AUDIO") && !audioSelected) { children += linked; audioSelected = true }
                        if (line.contains("TYPE=SUBTITLES") && !subtitlesSelected) { children += linked; subtitlesSelected = true }
                    }
                }
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val child = lines.getOrNull(index + 1)?.takeUnless { it.startsWith('#') }
                        ?: throw IOException("Missing playback video variant")
                    val variant = PlaybackUrlPolicy.resolve(url, child)
                    if (videoCount == 0) children += variant
                    videoCount++
                } else if (line.startsWith("#EXTINF:")) mediaCount++
                else if (!line.startsWith('#')) PlaybackUrlPolicy.resolve(url, line)
            }
            if (videoCount == 0 && mediaCount == 0) throw IOException("Media playlist unavailable")
            if (mediaCount > 0 && "#EXT-X-ENDLIST" !in lines) vod = false
            for (child in children) visit(child, depth + 1)
        }
        visit(master, 0)
        if (expiry - System.currentTimeMillis() <= StreamSessionPolicy.EXPIRY_MARGIN_MS)
            throw IOException("Provider returned an expired playback link")
        if (vod) verified.forEach { (url, body) ->
            VerifiedManifestHandoff.offer(url, headers, body, validatedVod = true, sourceId = sourceId)
        }
        return expiry
    }
}

/** The discovered endpoint contains no credential and survives app restarts for its 24-hour TTL. */
internal class NewTvEndpointCache(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("newtv_endpoint_cache", android.content.Context.MODE_PRIVATE)
    fun read(nowMs: Long): Pair<String, Long>? {
        val savedAt = prefs.getLong("saved_at", 0L)
        val value = prefs.getString("base", null) ?: return null
        return if (savedAt > 0L && savedAt <= nowMs && nowMs - savedAt < 86_400_000L)
            value to (savedAt + 86_400_000L) else null
    }
    fun save(base: String, nowMs: Long) { prefs.edit().putString("base", base).putLong("saved_at", nowMs).apply() }
}

/** Bounded cookie-free API discovery. No host guessing, session handshake or token synthesis. */
internal class NewTvPlaybackResolver(
    client: OkHttpClient,
    private val discoveryUrl: String = "https://mobiledetect.app/checknewtv.php",
    private val initialBase: String = "https://tv.imgcdn.kim",
    private val trustedApiHosts: Set<String> = setOf("tv.imgcdn.kim"),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val gate: CookieFreePlaybackGate = NewTvPlaybackGate,
    private val routeTimeoutMs: Long = 12_000L,
    private val endpointCache: NewTvEndpointCache? = null
) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    private val discoveryMutex = Mutex()
    @Volatile private var cachedBase: Pair<HttpUrl, Long>? = null
    // A rejected player endpoint is an unavailable optional service, not a
    // provider rate limit. Keep it in memory so a fresh app start can retry.
    @Volatile private var apiUnavailableUntilMs = 0L
    private fun checkApiAvailability() {
        if (android.os.SystemClock.elapsedRealtime() < apiUnavailableUntilMs)
            throw IOException("NewTV API temporarily unavailable")
    }
    private val apiHeaders = mapOf(
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/133.0.0.0 Mobile Safari/537.36 /OS.GatuNewTV v1.0",
        "Accept" to "application/json, text/plain, */*"
    )
    private fun trustedBase(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("Invalid NewTV API base")
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.host !in trustedApiHosts ||
            url.port != 443 || url.encodedPath != "/" || url.query != null || url.fragment != null)
            throw IOException("Untrusted NewTV API base")
        return url
    }
    private suspend fun fetch(url: HttpUrl, headers: Map<String, String>, primaryApi: Boolean = false): HttpTextResponse = gate.request {
        // Recheck while serialized so queued titles do not repeat a rejected probe.
        if (primaryApi) checkApiAvailability()
        val request = Request.Builder().url(PlaybackUrlPolicy.resolve(url, url.toString())).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        http.fetchText(request, 1024L * 1024).also {
            gate.checkResponse(it.code, it.body, it.header("Retry-After"), url.toString())
            // Only the actual player API rejection disables this optional probe.
            // A title's CDN failure or timeout must not disable other titles.
            if (primaryApi && (it.code == 403 || it.code == 404)) {
                apiUnavailableUntilMs = android.os.SystemClock.elapsedRealtime() + 60_000L
            }
        }
    }
    private suspend fun apiBase(): HttpUrl = discoveryMutex.withLock {
        cachedBase?.takeIf { it.second > nowMs() }?.let { return@withLock it.first }
        endpointCache?.read(nowMs())?.let { (raw, expiresAt) ->
            try {
                val stored = trustedBase(raw)
                cachedBase = stored to expiresAt
                return@withLock stored
            } catch (_: IOException) { }
        }
        var base = trustedBase(initialBase)
        try {
            val discovery = discoveryUrl.toHttpUrl()
            if (!discovery.isHttps || discovery.host !in setOf("mobiledetect.app", "mobiledetects.com") ||
                discovery.username.isNotEmpty() || discovery.password.isNotEmpty() || discovery.port != 443 ||
                discovery.encodedPath != "/checknewtv.php" || discovery.query != null)
                throw IOException("Untrusted NewTV discovery endpoint")
            val response = fetch(discovery, apiHeaders)
            if (response.isSuccessful) {
                val encoded = JSONObject(response.body).optString("token_hash")
                if (encoded.isNotBlank()) base = trustedBase(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rate: PlaybackRateLimitedException) { throw rate }
        catch (_: IOException) { /* Explicit trusted base remains usable when discovery is offline. */ }
        catch (_: org.json.JSONException) { }
        catch (_: IllegalArgumentException) { }
        val savedAt = nowMs()
        cachedBase = base to (savedAt + 86_400_000L)
        endpointCache?.save(base.toString(), savedAt)
        base
    }
    suspend fun resolve(contentId: String, ott: String, season: Int = 0, episode: Int = 0): PublicPlaybackResult =
        withTimeoutOrNull(routeTimeoutMs) { resolveWithinBudget(contentId, ott, season, episode) }
            ?: throw IOException("NewTV playback timed out")

    private suspend fun resolveWithinBudget(contentId: String, ott: String, season: Int, episode: Int): PublicPlaybackResult {
        if (ott !in setOf("hs", "nf", "pv") || !contentId.matches(Regex("[A-Za-z0-9]{5,40}")))
            throw IOException("Invalid provider content identity")
        gate.check()
        checkApiAvailability()
        val base = apiBase()
        val url = base.newBuilder().encodedPath("/newtv/player.php").addQueryParameter("id", contentId).build()
        val response = fetch(url, apiHeaders + ("Ott" to ott), primaryApi = true)
        if (!response.isSuccessful) throw IOException("NewTV playback unavailable (HTTP ${response.code})")
        val data = try { JSONObject(response.body) } catch (error: org.json.JSONException) { throw IOException("Invalid NewTV playback metadata", error) }
        val status = data.optString("status").lowercase(java.util.Locale.ROOT)
        if (status.isNotBlank() && status !in setOf("otp", "y", "true", "success", "ok", "1") ||
            data.optString("error").isNotBlank() && data.optString("error") !in setOf("false", "0"))
            throw IOException("NewTV playback unavailable")
        val label = data.optString("ep")
        val responseSeason = Regex("(?i)\\bS([0-9]+)\\b").find(label)?.groupValues?.get(1)?.toIntOrNull()
        val responseEpisode = Regex("(?i)\\bE([0-9]+)\\b").find(label)?.groupValues?.get(1)?.toIntOrNull()
        if (season > 0 && responseSeason != null && responseSeason != season ||
            episode > 0 && responseEpisode != null && responseEpisode != episode)
            throw IOException("NewTV episode identity mismatch")
        if (data.optString("ott").isNotBlank() && data.optString("ott") != ott ||
            data.optString("id").isNotBlank() && data.optString("id") != contentId)
            throw IOException("NewTV content identity mismatch")
        val master = PlaybackUrlPolicy.resolve(base, data.optString("video_link"))
        if (!master.encodedPath.endsWith(".m3u8", ignoreCase = true)) throw IOException("Invalid NewTV HLS URL")
        val referer = data.optString("referer").ifBlank { "https://net52.cc/" }
        val refererUrl = PlaybackUrlPolicy.resolve("https://net52.cc/".toHttpUrl(), referer)
        if (refererUrl.host != "net52.cc") throw IOException("Invalid NewTV referer")
        val headers = mapOf("User-Agent" to apiHeaders.getValue("User-Agent"), "Referer" to refererUrl.toString())
        val expiry = CookieFreeManifestValidator { manifestUrl, manifestHeaders -> fetch(manifestUrl, manifestHeaders) }
            .validate(master, headers, sourceId = "[NewTV]")
        return PublicPlaybackResult(master.toString(), headers, PlaybackMetadataTracks.parse(data, url), contentId, ott, expiry, PlaybackRoute.NEWTV)
    }
}
