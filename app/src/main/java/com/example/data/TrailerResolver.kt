package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TrailerStream(
    val url: String,
    val type: String, // 'mp4', 'hls', 'dash'
    val headers: Map<String, String> = emptyMap()
)

interface TrailerResolverCallback {
    fun onResolved(stream: TrailerStream)
    fun onError(error: String)
    fun onExternalTrailer(url: String) { onError("The trailer is available in YouTube.") }
}

class TrailerResolver(
    private val context: Context,
    private val tmdbId: String,
    private val mediaType: String, // "movie" or "tv"
    private val callback: TrailerResolverCallback
) {
    companion object {
        private data class CachedTrailer(val stream: TrailerStream, val timestampMs: Long)
        private const val STREAM_CACHE_TTL_MS = 15 * 60 * 1000L
        // URLs from external providers are short-lived, so this cache has a bounded lifetime.
        private val streamCache = ConcurrentHashMap<String, CachedTrailer>()

        fun clearCache() {
            streamCache.clear()
        }

        private fun getKnownTmdbId(id: String): String? {
            return when (id.lowercase().trim()) {
                "despicable_me_4" -> "519182"
                "inside_out_2" -> "1022789"
                "pokemon_horizons" -> "200876"
                "one_piece_kids" -> "37854"
                "jujutsu_kaisen" -> "95479"
                "101" -> "66732"
                "102" -> "693134"
                "103" -> "94605"
                "104" -> "872585"
                else -> null
            }
        }

        private fun getKnownImdbId(id: String): String? {
            return when (id.lowercase().trim()) {
                "despicable_me_4" -> "tt7510222"
                "inside_out_2" -> "tt22022452"
                "pokemon_horizons" -> "tt24227388"
                "one_piece_kids" -> "tt0388629"
                "jujutsu_kaisen" -> "tt12343534"
                "101" -> "tt4574334"
                "102" -> "tt1160419"
                "103" -> "tt11126994"
                "104" -> "tt15398776"
                else -> null
            }
        }
    }

    private val TAG = "TrailerResolver"
    private val TMDB_API_KEY = "8baba8ab6b8bbe247645bcae7df63d0d"
    private val MAX_RETRIES = 3


    private var webView: WebView? = null
    private var imdbId: String? = null
    private var realTmdbId: String? = null
    @Volatile private var officialTrailerUrl: String? = null
    private var phase: Phase = Phase.IDLE
    @Volatile private var hasResolved = false
    private var retryCount = 0

    @Synchronized
    private fun claimCompletion(): Boolean {
        if (hasResolved) return false
        hasResolved = true
        phase = Phase.DONE
        return true
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable {
        if (!hasResolved) {
            Log.w(TAG, "Trailer resolution timeout (45s)")
            handleError("Trailer resolution timed out")
        }
    }

    enum class Phase { IDLE, TITLE, EMBED, DONE }

    // STEP 1 INJECTED JS (Optimized Fallback only)
    private val TITLE_PAGE_JS = """
        (function() {
            try {
                var html = document.documentElement.innerHTML;
                var patterns = [
                    /\/video\/(vi\d+)/,
                    /"video":\s*"(vi\d+)"/,
                    /"videoId":\s*"(vi\d+)"/,
                    /data-video-id="(vi\d+)"/,
                    /href="\/video\/(vi\d+)/
                ];
                var videoId = null;
                for (var i = 0; i < patterns.length; i++) {
                    var m = html.match(patterns[i]);
                    if (m) { videoId = m[1]; break; }
                }
                window.AndroidJS.postMessage(JSON.stringify({
                    type: 'IMDB_VIDEO_ID',
                    videoId: videoId
                }));
            } catch(e) {
                window.AndroidJS.postMessage(JSON.stringify({
                    type: 'IMDB_ERROR',
                    error: e.message
                }));
            }
        })();
    """.trimIndent()

    // STEP 2 INJECTED JS (Optimized Fallback only)
    private val EMBED_PAGE_JS = """
        (function() {
            try {
                var html = document.documentElement.innerHTML;
                var candidates = [];
                var mp4 = html.match(/https:[^"' ]+\.mp4[^"' ]*/g);
                if (mp4) mp4.forEach(function(u) { candidates.push({ url: u, type: 'mp4' }); });
                var m3u8 = html.match(/https:[^"' ]+\.m3u8[^"' ]*/g);
                if (m3u8) m3u8.forEach(function(u) { candidates.push({ url: u, type: 'hls' }); });
                var mpd = html.match(/https:[^"' ]+\\.mpd[^"' ]*/g);
                if (mpd) mpd.forEach(function(u) { candidates.push({ url: u, type: 'dash' }); });
                window.AndroidJS.postMessage(JSON.stringify({
                    type: 'IMDB_STREAMS',
                    candidates: candidates
                }));
            } catch(e) {
                window.AndroidJS.postMessage(JSON.stringify({
                    type: 'IMDB_ERROR',
                    error: e.message
                }));
            }
        })();
    """.trimIndent()

    fun start() {
        val cacheKey = "$mediaType:$tmdbId"
        val cached = streamCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestampMs < STREAM_CACHE_TTL_MS) {
            Log.d(TAG, "🎯 Stream Cache HIT for TMDB ID: $tmdbId")
            hasResolved = true
            handler.post { callback.onResolved(cached.stream) }
            return
        }

        handler.postDelayed(timeoutRunnable, 45_000L)
        scope.launch {
            fetchImdbIdAndResolve()
        }
    }

    private suspend fun fetchImdbIdAndResolve() {
        withContext(Dispatchers.IO) {
            try {
                // Bundled catalog IDs such as 101 are not TMDB identifiers.
                var validNumericTmdbId = getKnownTmdbId(tmdbId) ?: tmdbId.toIntOrNull()?.toString()

                if (validNumericTmdbId == null) {
                    // Try TMDB Search API to find real TMDB ID
                    val cleanQuery = tmdbId.replace("_", " ").replace("-", " ")
                    val searchUrl = "https://api.themoviedb.org/3/search/$mediaType?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(cleanQuery, "UTF-8")}"
                    val searchConn = URL(searchUrl).openConnection() as HttpURLConnection
                    searchConn.requestMethod = "GET"
                    searchConn.connectTimeout = 4000
                    searchConn.readTimeout = 4000
                    if (searchConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val body = searchConn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            validNumericTmdbId = results.getJSONObject(0).optInt("id").toString()
                        }
                    }
                }

                realTmdbId = validNumericTmdbId

                // Try Youtube Trailer extraction via TMDB Videos API
                if (!validNumericTmdbId.isNullOrEmpty()) {
                    Log.d(TAG, "🎬 Checking TMDB Videos for TMDB ID: $validNumericTmdbId...")
                    val ytStream = attemptTmdbYoutubeTrailer(validNumericTmdbId)
                    if (ytStream != null) {
                        Log.d(TAG, "⚡ TMDB YouTube Trailer Stream Succeeded!")
                        finishSuccess(ytStream)
                        return@withContext
                    }
                }

                // Get IMDb ID
                var id = getKnownImdbId(tmdbId)
                if (id == null && !validNumericTmdbId.isNullOrEmpty()) {
                    val url = URL("https://api.themoviedb.org/3/$mediaType/$validNumericTmdbId/external_ids?api_key=$TMDB_API_KEY")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObject = JSONObject(response)
                        val fetchedImdb = jsonObject.optString("imdb_id", "")
                        if (fetchedImdb.isNotEmpty()) id = fetchedImdb
                    }
                }

                if (!id.isNullOrEmpty()) {
                    Log.d(TAG, "🎬 Resolved IMDb ID: $id")
                    imdbId = id

                    // Try Direct HTTP Scraper
                    Log.d(TAG, "🚀 Attempting Direct HTTP Scraper...")
                    val directStream = attemptDirectScrape(id)
                    if (directStream != null) {
                        Log.d(TAG, "⚡ Direct HTTP Scraping Succeeded!")
                        finishSuccess(directStream)
                        return@withContext
                    }

                    // Fallback to WebView Scraper
                    Log.w(TAG, "⚠️ Direct scraping failed. Falling back to WebView...")
                    withContext(Dispatchers.Main) {
                        if (!hasResolved) {
                            phase = Phase.TITLE
                            setupWebView()
                            webView?.loadUrl("https://www.imdb.com/title/$id/")
                        }
                    }
                } else {
                    Log.w(TAG, "No IMDb trailer ID for $tmdbId")
                    withContext(Dispatchers.Main) { handleError("No IMDb ID found") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ TMDB fetch failed:", e)
                withContext(Dispatchers.Main) { handleError("Failed to fetch IMDb ID") }
            }
        }
    }

    private suspend fun attemptTmdbYoutubeTrailer(numericTmdbId: String): TrailerStream? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.themoviedb.org/3/$mediaType/$numericTmdbId/videos?api_key=$TMDB_API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: JSONArray()

                var ytKey: String? = null
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val site = item.optString("site")
                    val type = item.optString("type")
                    if (site.equals("YouTube", ignoreCase = true) &&
                        (type.equals("Trailer", ignoreCase = true) || type.equals("Teaser", ignoreCase = true)) &&
                        Regex("^[A-Za-z0-9_-]{11}$").matches(item.optString("key"))) {
                        if (ytKey == null || (item.optBoolean("official") && type.equals("Trailer", true))) {
                            ytKey = item.optString("key")
                            if (item.optBoolean("official") && type.equals("Trailer", true)) break
                        }
                    }
                }

                if (ytKey != null) {
                    officialTrailerUrl = "https://www.youtube.com/watch?v=$ytKey"
                    Log.d(TAG, "🎥 Found YouTube Trailer Key: $ytKey")
                    // Invidious / Piped Mirrors for direct mp4 progressive stream key
                    val mirrors = listOf(
                        "https://inv.tux.pizza/latest_version?id=$ytKey&itag=22",
                        "https://yewtu.be/latest_version?id=$ytKey&itag=22",
                        "https://vid.puffyan.us/latest_version?id=$ytKey&itag=22"
                    )
                    for (mUrl in mirrors) {
                        try {
                            val testConn = URL(mUrl).openConnection() as HttpURLConnection
                            testConn.requestMethod = "HEAD"
                            testConn.connectTimeout = 3000
                            testConn.readTimeout = 3000
                            testConn.instanceFollowRedirects = true
                            val code = testConn.responseCode
                            val contentType = testConn.contentType.orEmpty().lowercase()
                            testConn.disconnect()
                            if (code in 200..299 && contentType.startsWith("video/")) {
                                return@withContext TrailerStream(url = mUrl, type = "mp4")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "attemptTmdbYoutubeTrailer failed: ${e.message}")
            null
        }
    }

    private suspend fun attemptDirectScrape(id: String): TrailerStream? = withContext(Dispatchers.IO) {
        try {
            val titleHtml = fetchUrlHtml("https://www.imdb.com/title/$id/")
            if (titleHtml.isEmpty()) return@withContext null

            // Find videoId (viXXXXX)
            val patterns = listOf(
                Regex("""/video/(vi\d+)"""),
                Regex("""\"video\":\s*\"(vi\d+)\""""),
                Regex("""\"videoId\":\s*\"(vi\d+)\""""),
                Regex("""data-video-id=\"(vi\d+)\""""),
                Regex("""href=\"/video/(vi\d+)""")
            )

            var videoId: String? = null
            for (pattern in patterns) {
                val match = pattern.find(titleHtml)
                if (match != null) {
                    videoId = match.groupValues[1]
                    break
                }
            }

            if (videoId == null) {
                Log.w(TAG, "🔍 Direct Scraper: Could not find videoId in title page HTML.")
                return@withContext null
            }

            Log.d(TAG, "🔍 Direct Scraper: Found Video ID: $videoId. Loading embed page...")
            val embedHtml = fetchUrlHtml("https://www.imdb.com/videoembed/$videoId")
            if (embedHtml.isEmpty()) return@withContext null

            // Find stream candidates
            val rawMp4s = Regex("""https:[^"' ]+\.mp4[^"' ]*""").findAll(embedHtml).map { it.value }.toList()
            val rawM3u8s = Regex("""https:[^"' ]+\.m3u8[^"' ]*""").findAll(embedHtml).map { it.value }.toList()
            val rawMpds = Regex("""https:[^"' ]+\\.mpd[^"' ]*""").findAll(embedHtml).map { it.value }.toList()

            val candidates = mutableListOf<TrailerStream>()
            rawMp4s.forEach { candidates.add(TrailerStream(cleanUrl(it), "mp4")) }
            rawM3u8s.forEach { candidates.add(TrailerStream(cleanUrl(it), "hls")) }
            rawMpds.forEach { candidates.add(TrailerStream(cleanUrl(it), "dash")) }

            val best = candidates.find { it.type == "hls" }
                ?: candidates.find { it.type == "mp4" }
                ?: candidates.find { it.type == "dash" }

            return@withContext best
        } catch (e: Exception) {
            Log.e(TAG, "❌ Direct scrape exception: ", e)
            null
        }
    }

    private fun cleanUrl(url: String): String {
        return url.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
    }

    private suspend fun fetchUrlHtml(urlString: String): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                instanceFollowRedirects = true
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUrlHtml failed for $urlString: ${e.message}")
            ""
        } finally {
            connection?.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (webView != null) return

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // CRITICAL resource loading optimizations to stop device lag/overheating
                loadsImagesAutomatically = false // Prevent downloading heavy poster assets
                blockNetworkImage = true // Explicitly block any image over the network
                databaseEnabled = false
            }

            addJavascriptInterface(WebViewInterface(), "AndroidJS")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "📄 Page loaded (phase: $phase)")

                    val delayMs = if (retryCount > 0) 1500L else 400L
                    scope.launch {
                        delay(delayMs)
                        if (phase == Phase.TITLE) {
                            view?.evaluateJavascript(TITLE_PAGE_JS, null)
                        } else if (phase == Phase.EMBED) {
                            view?.evaluateJavascript(EMBED_PAGE_JS, null)
                        }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "❌ WebView error: ${error?.description}")
                }
            }
        }
    }

    private inner class WebViewInterface {
        @JavascriptInterface
        fun postMessage(data: String) {
            if (hasResolved) return

            // Marshall back to main thread to safely update WebView/State
            handler.post {
                try {
                    val msg = JSONObject(data)
                    val type = msg.optString("type")

                    if (type == "IMDB_VIDEO_ID") {
                        val videoId = msg.optString("videoId")
                        if (videoId.isNotEmpty() && videoId != "null") {
                            Log.d(TAG, "✅ Video ID: $videoId")
                            retryCount = 0
                            phase = Phase.EMBED
                            webView?.loadUrl("https://www.imdb.com/videoembed/$videoId")
                        } else if (retryCount < MAX_RETRIES) {
                            retryCount++
                            Log.d(TAG, "🔄 Retry $retryCount/$MAX_RETRIES (WAF challenge)")
                            scope.launch {
                                delay(2000)
                                if (!hasResolved && imdbId != null) {
                                    webView?.loadUrl("about:blank")
                                    delay(100)
                                    webView?.loadUrl("https://www.imdb.com/title/$imdbId/")
                                }
                            }
                        } else {
                            Log.w(TAG, "❌ No video ID after all retries")
                            handleError("No trailer video found")
                        }
                    } else if (type == "IMDB_STREAMS") {
                        val candidates = mutableListOf<TrailerStream>()
                        val jsonCandidates = msg.optJSONArray("candidates") ?: JSONArray()

                        for (i in 0 until jsonCandidates.length()) {
                            val c = jsonCandidates.getJSONObject(i)
                            candidates.add(TrailerStream(cleanUrl(c.getString("url")), c.getString("type")))
                        }

                        Log.d(TAG, "🔗 Found ${candidates.size} stream candidates")

                        val best = candidates.find { it.type == "hls" }
                            ?: candidates.find { it.type == "mp4" }
                            ?: candidates.find { it.type == "dash" }

                        if (best != null) {
                            finishSuccess(best)
                        } else {
                            Log.w(TAG, "❌ No playable streams in embed")
                            handleError("No playable trailer streams found")
                        }
                    } else if (type == "IMDB_ERROR") {
                        Log.e(TAG, "❌ JS Error: ${msg.optString("error")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Message parse error", e)
                }
            }
        }
    }

    private fun finishSuccess(stream: TrailerStream) {
        if (claimCompletion()) {
            streamCache["$mediaType:$tmdbId"] = CachedTrailer(stream, System.currentTimeMillis())
            handler.post {
                cleanUp()
                callback.onResolved(stream)
            }
        }
    }

    private fun handleError(message: String) {
        if (claimCompletion()) {
            handler.post {
                cleanUp()
                val external = officialTrailerUrl
                if (external != null) callback.onExternalTrailer(external) else callback.onError(message)
            }
        }
    }

    fun cancel() {
        claimCompletion()
        handler.post { cleanUp() }
        scope.cancel()

    }
    fun cleanUp() {
        handler.removeCallbacks(timeoutRunnable)
        webView?.let {
            it.removeJavascriptInterface("AndroidJS")
            it.stopLoading()
            it.clearCache(true)
            it.destroy()
        }
        webView = null
    }
}
