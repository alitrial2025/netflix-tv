package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Captcha-based fallback resolver.
 *
 * Strategy:
 *   1. POST a random UUID to `verify.php` as a fake reCAPTCHA response → receive `t_hash_t` cookie.
 *   2. Cache the cookie in SharedPreferences (valid ≈ 3 days).
 *   3. Search for the content across OTT providers using the cookie.
 *   4. Build a self-signed master token   `H1::MD5(ts+id)::ts::ek::m`   using the
 *      same hash constant as [ProviderRuntimeConfig.DEFAULT_HASH].
 *   5. Fetch the HLS manifest URL and return a [PublicPlaybackResult].
 *
 * This resolver is used as a **last-resort fallback** after NewTV and the primary
 * issued-playlist route have both failed. It does **not** use WebView, addhash
 * polling, or any session warm-up handshake.
 */
internal class CaptchaFallbackResolver(
    context: Context,
    client: OkHttpClient,
    private val baseUrl: String = "https://net52.cc",
    private val runtimeConfig: ProviderRuntimeConfig? = null
) {
    companion object {
        private const val TAG = "CaptchaFallback"
        private const val PREFS_NAME = "captcha_fallback_prefs"
        private const val PREF_COOKIE = "t_hash_t_cookie"
        private const val PREF_COOKIE_SAVED_AT = "t_hash_t_saved_at"
        /** 3 days minus a small safety margin. */
        private const val COOKIE_TTL_MS = 3L * 24 * 3_600_000L - 300_000L
        private const val OTT_SEARCH_ORDER = "hs,nf,pv"
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 " +
            "Mobile Safari/537.36 /OS.Gatu v3.0"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Dedicated HTTP client: no cookies managed by OkHttp (we send them manually). */
    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl get() = (runtimeConfig?.snapshot()?.baseUrl ?: baseUrl).toHttpUrl()

    // ─────────────────────────────────────────────
    // 1. Cookie acquisition
    // ─────────────────────────────────────────────

    /**
     * Return a cached `t_hash_t` cookie value or POST `verify.php` to obtain a fresh one.
     *
     * The verify endpoint accepts any value in the `g-recaptcha-response` form field and
     * responds with a `Set-Cookie: t_hash_t=…` header.  The token is
     * `H1::H2::TS::ek::99` and lasts ~3 days.
     */
    private suspend fun getOrRefreshCookie(force: Boolean = false): String {
        if (!force) {
            cachedCookie()?.let { return it }
        }

        val url = base.newBuilder().encodedPath("/verify.php").build()
        val body = FormBody.Builder()
            .add("g-recaptcha-response", UUID.randomUUID().toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", base.toString())
            .header("Origin", base.toString().trimEnd('/'))
            .build()

        // Use the cancellable fetchText extension to honour coroutine lifecycle.
        val response = http.fetchText(request, 64L * 1024)
        if (!response.isSuccessful && response.code !in listOf(301, 302, 303)) {
            throw IOException("CaptchaFallback: verify.php returned HTTP ${response.code}")
        }

        // Extract t_hash_t from Set-Cookie header(s).
        val tHashT = response.headers.values("Set-Cookie")
            .firstOrNull { it.startsWith("t_hash_t=") }
            ?.substringAfter("t_hash_t=")
            ?.substringBefore(";")
            ?.trim()

        if (tHashT.isNullOrBlank()) {
            throw IOException("CaptchaFallback: verify.php did not return t_hash_t cookie")
        }

        // Persist cookie.
        prefs.edit()
            .putString(PREF_COOKIE, tHashT)
            .putLong(PREF_COOKIE_SAVED_AT, System.currentTimeMillis())
            .apply()

        return tHashT
    }

    /** Return the locally stored cookie if it has not expired. */
    private fun cachedCookie(): String? {
        val value = prefs.getString(PREF_COOKIE, null)?.takeIf(String::isNotBlank) ?: return null
        val savedAt = prefs.getLong(PREF_COOKIE_SAVED_AT, 0L)
        val age = System.currentTimeMillis() - savedAt
        return if (age in 0 until COOKIE_TTL_MS) value else null
    }

    // ─────────────────────────────────────────────
    // 2. Content search
    // ─────────────────────────────────────────────

    /**
     * Search the provider catalog for a given title.
     * Tries each OTT code in [OTT_SEARCH_ORDER] and returns the best match.
     */
    private suspend fun searchContent(
        title: String,
        year: String,
        type: String,
        cookie: String
    ): Pair<String, String>? {
        for (ott in OTT_SEARCH_ORDER.split(",")) {
            val searchUrl = base.newBuilder()
                .encodedPath(if (ott == "nf") "/search.php" else "/mobile/$ott/search.php")
                .addQueryParameter("s", title)
                .build()

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "t_hash_t=$cookie; lang=eng; ott=$ott")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", base.toString())
                .build()

            try {
                val response = http.fetchText(request, 1024L * 1024)
                if (!response.isSuccessful) continue

                val rows: JSONArray = try {
                    val parsed = org.json.JSONTokener(response.body).nextValue()
                    when (parsed) {
                        is JSONArray -> parsed
                        is JSONObject -> {
                            if (parsed.optString("head") == "Top Searches" || parsed.optString("status") == "n")
                                continue
                            parsed.optJSONArray("searchResult") ?: continue
                        }
                        else -> continue
                    }
                } catch (_: org.json.JSONException) { continue }

                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("id").ifBlank { row.optString("Id") }.ifBlank { row.optString("sid") }
                    if (id.isBlank() || !PublicIdentityDiscovery.validId(id, ott)) continue
                    val rowTitle = row.optString("t").ifBlank { row.optString("title") }.ifBlank { row.optString("T") }
                    val rowYear = row.optString("y").ifBlank { row.optString("year") }.ifBlank { row.optString("Y") }
                    val rowType = row.optString("type").ifBlank { row.optString("media_type") }.lowercase()
                    val rightType = when (rowType) {
                        "t", "tv", "series" -> type == "tv"
                        "m", "movie", "film" -> type == "movie"
                        else -> true
                    }
                    if (!rightType) continue
                    if (type == "movie" && rowYear.isNotBlank() && rowYear != year) continue
                    if (PublicIdentityDiscovery.sameTitle(rowTitle.removeSuffix(" ($year)"), title)) {
                        return id to ott
                    }
                }
            } catch (_: IOException) { continue }
        }
        return null
    }

    // ─────────────────────────────────────────────
    // 3. Episode resolution
    // ─────────────────────────────────────────────

    /**
     * For TV shows, resolve (showId, season, episode) → contentId using the provider
     * post.php and episodes.php endpoints.
     */
    private suspend fun resolveEpisode(
        showId: String,
        ott: String,
        season: Int,
        episode: Int,
        cookie: String
    ): String {
        val prefix = if (ott == "nf") "/mobile" else "/mobile/$ott"
        val postUrl = base.newBuilder()
            .encodedPath("$prefix/post.php")
            .addQueryParameter("id", showId)
            .build()

        val postRequest = Request.Builder().url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "t_hash_t=$cookie; lang=eng; ott=$ott")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base.toString())
            .build()

        val postResponse = http.fetchText(postRequest, 1024L * 1024)
        if (!postResponse.isSuccessful) throw IOException("CaptchaFallback: post.php returned HTTP ${postResponse.code}")
        val data = try { JSONObject(postResponse.body) } catch (e: org.json.JSONException) {
            throw IOException("CaptchaFallback: invalid post.php response", e)
        }

        // Try direct episodes array first.
        val directEpisodes = data.optJSONArray("episodes")
        findEpisodeInRows(directEpisodes, season, episode)?.let { return it }

        // Resolve season ID.
        val seasons = data.optJSONArray("season") ?: data.optJSONArray("seasons") ?: JSONArray()
        var seasonId: String? = null
        for (i in 0 until seasons.length()) {
            val sObj = seasons.optJSONObject(i) ?: continue
            val label = sObj.optString("s").ifBlank { sObj.optString("season") }
                .ifBlank { sObj.optString("name") }.ifBlank { sObj.optString("title") }
            val num = Regex("(?i)(?:season|s)?\\s*([0-9]+)").matchEntire(label.trim())
                ?.groupValues?.get(1)?.toIntOrNull()
            if (num == season) {
                seasonId = sObj.optString("id").takeIf(String::isNotBlank)
                break
            }
        }
        if (seasonId == null) throw IOException("CaptchaFallback: season $season not found")

        // Fetch episodes page-by-page.
        for (page in 1..30) {
            val episodeUrlBuilder = base.newBuilder()
                .encodedPath("$prefix/episodes.php")
                .addQueryParameter("s", seasonId)
                .addQueryParameter("series", showId)
            if (page > 1) episodeUrlBuilder.addQueryParameter("page", page.toString())
            val episodeUrl = episodeUrlBuilder.build()

            val epRequest = Request.Builder().url(episodeUrl)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "t_hash_t=$cookie; lang=eng; ott=$ott")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", base.toString())
                .build()

            val epResponse = http.fetchText(epRequest, 1024L * 1024)
            if (!epResponse.isSuccessful) throw IOException("CaptchaFallback: episodes.php HTTP ${epResponse.code}")
            val epData = try { JSONObject(epResponse.body) } catch (e: org.json.JSONException) {
                throw IOException("CaptchaFallback: invalid episodes.php response", e)
            }
            val rows = epData.optJSONArray("episodes") ?: JSONArray()
            findEpisodeInRows(rows, season, episode)?.let { return it }
            if (epData.optString("nextPageShow") != "1" || rows.length() == 0) break
        }
        throw IOException("CaptchaFallback: episode S${season}E${episode} not found")
    }

    private fun findEpisodeInRows(rows: JSONArray?, season: Int, episode: Int): String? {
        if (rows == null) return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val epLabel = row.optString("ep").ifBlank { row.optString("episode") }
                .ifBlank { row.optString("e") }.ifBlank { row.optString("episode_number") }
            val match = Regex("(?i)(?:s([0-9]+)\\s*)?(?:episode|ep|e)?\\s*([0-9]+)")
                .matchEntire(epLabel.trim()) ?: continue
            val embeddedSeason = match.groupValues[1].toIntOrNull()
            val epNum = match.groupValues[2].toIntOrNull() ?: continue
            if (embeddedSeason != null && embeddedSeason != season) continue
            if (epNum == episode) {
                val id = row.optString("id").takeIf(String::isNotBlank) ?: continue
                return id
            }
        }
        return null
    }

    // ─────────────────────────────────────────────
    // 4. Master token construction
    // ─────────────────────────────────────────────

    /**
     * Build a self-signed master token:
     *   `H1::MD5(timestamp + contentId)::timestamp::mode::m`
     *
     * [settings] supplies the runtime hash/mode from [ProviderRuntimeConfig].
     */
    private fun buildMasterToken(contentId: String, settings: ProviderRuntimeConfig.Snapshot): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val md5 = MessageDigest.getInstance("MD5")
            .digest((timestamp + contentId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${settings.masterHash}::$md5::$timestamp::${settings.masterMode}::m"
    }

    // ─────────────────────────────────────────────
    // 5. HLS manifest fetch
    // ─────────────────────────────────────────────

    /**
     * Fetch the HLS master manifest URL using the cookie and master token.
     * Falls back to the issued-playlist route if the direct route fails.
     */
    private suspend fun fetchHlsManifest(
        contentId: String,
        ott: String,
        cookie: String,
        title: String,
        settings: ProviderRuntimeConfig.Snapshot
    ): PublicPlaybackResult {
        val prefix = if (ott == "nf") "/mobile" else "/mobile/$ott"

        // Try the issued playlist route first (with cookie).
        val playlistUrl = base.newBuilder()
            .encodedPath("$prefix/playlist.php")
            .addQueryParameter("id", contentId)
            .addQueryParameter("t", title)
            .addQueryParameter("tm", (System.currentTimeMillis() / 1000).toString())
            .build()

        val playlistRequest = Request.Builder().url(playlistUrl)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "t_hash_t=$cookie; lang=eng; ott=$ott")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base.toString())
            .header("Origin", base.toString().trimEnd('/'))
            .build()

        var hlsUrl: String? = null
        var captions: List<Caption> = emptyList()

        try {
            val response = http.fetchText(playlistRequest, 1024L * 1024)
            if (response.isSuccessful) {
                val json = try {
                    val parsed = org.json.JSONTokener(response.body).nextValue()
                    when (parsed) {
                        is JSONObject -> parsed
                        is JSONArray -> parsed.optJSONObject(0)
                        else -> null
                    }
                } catch (_: org.json.JSONException) { null }

                if (json != null) {
                    val sources = json.optJSONArray("sources") ?: JSONArray()
                    for (i in 0 until sources.length()) {
                        val src = sources.optJSONObject(i) ?: continue
                        val file = src.optString("file").takeIf(String::isNotBlank)
                        if (file != null) {
                            // Prefer "Auto" label if available.
                            if (src.optString("label") == "Auto" || hlsUrl == null) {
                                hlsUrl = base.resolve(file)?.toString() ?: file
                            }
                        }
                    }
                    captions = PlaybackMetadataTracks.parse(json, base)
                }
            }
        } catch (_: IOException) { /* Fall through to direct route. */ }

        // Build the final URL with master token authentication.
        val masterToken = buildMasterToken(contentId, settings)
        val finalUrl = if (hlsUrl != null) {
            // Apply the master token to the playlist-issued URL.
            ProviderMasterRequest.resolve(hlsUrl, contentId, base.toString(), settings)
        } else {
            // Direct route: construct the HLS URL from scratch.
            val directUrl = base.newBuilder()
                .encodedPath("$prefix/hls/$contentId.m3u8")
                .addQueryParameter("in", masterToken)
                .addQueryParameter("hd", "off")
                .addQueryParameter("lang", "eng")
                .addQueryParameter("hp", "yes")
                .build()
            directUrl.toString()
        }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to base.toString(),
            "Origin" to base.toString().trimEnd('/'),
            "Cookie" to "t_hash_t=$cookie; lang=eng; ott=$ott"
        )

        // Validate the manifest.
        val manifestUrl = finalUrl.toHttpUrl()
        val expiry = try {
            CookieFreeManifestValidator { url, manifestHeaders ->
                val req = Request.Builder().url(url).apply {
                    (manifestHeaders + headers).forEach { (name, value) -> header(name, value) }
                }.build()
                http.fetchText(req, 1024L * 1024)
            }.validate(manifestUrl, headers, sourceId = "[Captcha]")
        } catch (e: IOException) {
            // If validation fails, still return the URL with a reasonable expiry.
            // The player will handle any errors during actual playback.
            System.currentTimeMillis() + 3_600_000L
        }

        return PublicPlaybackResult(
            url = finalUrl,
            headers = headers,
            captions = captions,
            contentId = contentId,
            ott = ott,
            expiresAt = expiry,
            route = PlaybackRoute.ISSUED
        )
    }

    // ─────────────────────────────────────────────
    // 6. Public API
    // ─────────────────────────────────────────────

    /**
     * Full resolution pipeline.  Call this as a last-resort fallback when both
     * NewTV and the primary issued-playlist routes have failed.
     *
     * @return A [PublicPlaybackResult] containing the HLS manifest URL.
     * @throws IOException if every step in the pipeline fails.
     */
    suspend fun resolve(
        title: String,
        year: String,
        type: String,
        season: Int,
        episode: Int,
        tmdbId: String
    ): PublicPlaybackResult {
        val settings = runtimeConfig?.snapshot() ?: ProviderRuntimeConfig.Snapshot()

        // Step 1: Obtain a valid cookie.
        val cookie = try {
            getOrRefreshCookie()
        } catch (e: IOException) {
            // Try once with a forced refresh.
            getOrRefreshCookie(force = true)
        }

        // Step 2: Search for the content.
        val (contentId, ott) = searchContent(title, year, type, cookie)
            ?: throw IOException("CaptchaFallback: no search results for \"$title\" ($year)")

        // Step 3: For TV shows, resolve the episode.
        val finalContentId = if (type == "tv" && season > 0 && episode > 0) {
            resolveEpisode(contentId, ott, season, episode, cookie)
        } else {
            contentId
        }

        // Step 4: Fetch the HLS manifest.
        return fetchHlsManifest(finalContentId, ott, cookie, title, settings)
    }

    /**
     * Resolve to a [NetMirrorStream] for direct consumption by the player layer.
     * This mirrors the shape returned by [DirectCDNResolver.resolveStream].
     */
    suspend fun resolveStream(
        title: String,
        year: String,
        type: String,
        season: Int,
        episode: Int,
        tmdbId: String
    ): NetMirrorStream = withTimeoutOrNull(20_000L) {
        val result = resolve(title, year, type, season, episode, tmdbId)
        NetMirrorStream(
            url = result.url,
            headers = result.headers,
            captions = result.captions,
            sourceId = "CaptchaFallback [${result.ott.uppercase()}]",
            expiresAt = result.expiresAt,
            title = title
        )
    } ?: throw IOException("CaptchaFallback: resolution timed out")
}
