package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.example.BuildConfig
import com.example.model.Movie
import com.example.model.catalogMediaKind

class AppCookieJar : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val current = cookieStore.getOrPut(host) { mutableListOf() }
        synchronized(current) {
            for (newCookie in cookies) {
                current.removeAll { it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path }
                current.add(newCookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val matching = mutableListOf<Cookie>()
        cookieStore.values.forEach { list ->
            synchronized(list) {
                list.removeAll { it.expiresAt <= System.currentTimeMillis() }
                matching.addAll(list.filter { it.matches(url) })
            }
        }
        return matching.distinctBy { Triple(it.name, it.domain, it.path) }
            .sortedByDescending { it.path.length }
    }

    fun getCookieValue(host: String, name: String): String? {
        val cookies = cookieStore[host] ?: return null
        synchronized(cookies) {
            return cookies.firstOrNull {
                it.name == name && it.expiresAt > System.currentTimeMillis()
            }?.value
        }
    }

    fun addManualCookie(host: String, name: String, value: String) {
        val current = cookieStore.getOrPut(host) { mutableListOf() }
        synchronized(current) {
            current.removeAll { it.name == name }
            val cookie = Cookie.Builder()
                .hostOnlyDomain(host)
                .secure()
                .name(name)
                .value(value)
                .path("/")
                .expiresAt(System.currentTimeMillis() + 86400000L)
                .build()
            current.add(cookie)
        }
    }

    fun clear() {
        cookieStore.clear()
    }
}

/** Resolves playback with bounded, cancellable session renewal and expiring token caches. */
class DirectCDNResolver(private val context: Context, clientOverride: OkHttpClient? = null) {

    private val FREECDN_HASH1 = "235ca31540ab8d90fcef4a00de8a247c"
    private val FREECDN_SUFFIX = "myes"
    private val NONCE_TTL_MS = StreamSessionPolicy.TTL_MS
    private val ROUTE_TTL_MS = 10L * 60 * 60 * 1000L // 10 hours
    private val NONCE_KEY = "freecdn_nonce"
    private val ROUTING_KEY = "freecdn_routing_v1"
    private val LAST_QURY_KEY = "last_qury_param"
    private val LAST_TOKEN_MODE_KEY = "last_token_mode"
    private val LAST_TOKEN_SUFFIX_KEY = "last_token_suffix"

    private val cookieJar = AppCookieJar()

    private val client = clientOverride ?: OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val publicConfig = ProviderRuntimeConfig(context, client, "directcdn_prefs")
    private val publicPlayback = PublicPlaybackResolver(client, catalog = PublicProviderCatalog(context),
        backgroundCatalogRefresh = clientOverride == null, runtimeConfig = publicConfig)
    val requiresWarmSession: Boolean get() = false

    private val SEC_CH_UA = "\"Not(A:Brand\";v=\"99\", \"Android WebView\";v=\"133\", \"Chromium\";v=\"133\""
    private val X_REQUESTED_WITH = "app.netmirror.netmirrornew"

    private val metadataClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private val fastProbeClient = (clientOverride ?: OkHttpClient()).newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val streamCache = ConcurrentHashMap<String, NetMirrorStream>()
    private val tmdbInfoCache = ConcurrentHashMap<String, TmdbInfo>()
    private val contentIdCache = ConcurrentHashMap<String, SearchResult>() // tmdbId -> SearchResult
    private val episodeListCache = ConcurrentHashMap<String, List<Triple<Int, Int, String>>>() // showId -> [(season, episode, contentId)]
    private val showHostCache = ConcurrentHashMap<String, String>() // showId -> host
    private val masterManifestCache = ConcurrentHashMap<String, String>() // contentId -> master m3u8 body
    private val contentSubtitlesCache = ConcurrentHashMap<String, List<Caption>>()
    private fun <K, V> putTransientCache(cache: ConcurrentHashMap<K, V>, key: K, value: V, limit: Int) {
        cache[key] = value
        if (cache.size <= limit) return
        // These are disposable network results; keep a small recent working set
        // instead of retaining every manifest and episode list for the TV session.
        for (oldKey in cache.keys) {
            if (cache.size <= limit) break
            if (oldKey != key) cache.remove(oldKey)
        }
    }
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    private val sessionDemand = SessionDemand()
    suspend fun <T> withForegroundSessionDemand(block: suspend () -> T): T = sessionDemand.withRequest(block)
    fun playbackCooldownMillis(): Long = PlaybackServiceGate.remainingMs()
    fun recordPlaybackRateLimit(retryAfterMs: Long? = null) { PlaybackServiceGate.recordLimit(retryAfterMs) }
    fun checkPlaybackCooldown() { PlaybackServiceGate.check() }
    val playbackSourceRevision: Long get() = PlaybackServiceGate.sourceRevision
    @Volatile private var cachedSourceRevision = PlaybackServiceGate.sourceRevision
    private class PreviewResolutionContext : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<PreviewResolutionContext>
    }
    fun isSessionGenerationInProgress(): Boolean = sessionMutex.isLocked
    private val sessionStateLock = Any()
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var lastAmbiguousRenewalAtMs = 0L
    @Volatile private var lastSuccessfulVerifyAtMs = 0L
    val sessionVersion: Long get() = sessionGeneration.get()

    fun isSessionRecentlyVerified(): Boolean {
        val now = System.currentTimeMillis()
        // Restore from disk if in-memory value was lost (process killed during sleep)
        if (lastSuccessfulVerifyAtMs == 0L) {
            lastSuccessfulVerifyAtMs = getPrefs().getLong("last_verify_at_ms", 0L)
        }
        val elapsed = now - lastSuccessfulVerifyAtMs
        return lastSuccessfulVerifyAtMs > 0L && elapsed >= 0L && elapsed < 30 * 60_000L && hasValidSession()
    }

    fun sessionVersionFor(stream: NetMirrorStream): Long = stream.sessionVersion ?: -1L

    private class SessionRejectedException(val cookie: String? = null) :
        java.io.IOException("Playback session expired. Please retry.")
    private class CdnRouteRejectedException : java.io.IOException("Media route expired or unavailable")
    private class SessionChangedException : java.io.IOException("Playback session changed")
    private class AuthenticatedEndpointUnavailableException :
        java.io.IOException("Playback service returned an unexpected page")

    private fun rethrowControlFailure(error: Exception) {
        if (error is CancellationException || error is SessionRejectedException ||
            error is SessionChangedException || error is CdnRouteRejectedException || error is PlaybackRateLimitedException ||
            error is AuthenticatedEndpointUnavailableException) throw error
    }

    private suspend fun OkHttpClient.fetch(request: Request): HttpTextResponse {
        // The provider's metadata queue must not hold a warm CDN request behind
        // another title's search, a handshake poll, or a slow TMDB response.
        val paced = ProviderRequestPolicy.needsPacing(request, activeDomain, DOMAIN_POOL)
        val queuedAt = com.example.ui.util.RuntimeTiming.start()
        val stage = if (paced) "provider" else if (request.url.host == "api.themoviedb.org") "tmdb" else "cdn"
        val execute: suspend () -> HttpTextResponse = {
            if (paced) com.example.ui.util.RuntimeTiming.elapsed("provider_queue_wait", queuedAt)
            checkPlaybackCooldown()
            val generation = sessionGeneration.get()
            val networkStartedAt = com.example.ui.util.RuntimeTiming.start()
            val response = try { fetchText(request) } finally {
                com.example.ui.util.RuntimeTiming.elapsed("${stage}_http", networkStartedAt)
            }
            currentCoroutineContext().ensureActive()
            if (generation != sessionGeneration.get()) throw SessionChangedException()
            PlaybackServiceGate.checkResponse(response.code, response.body, response.header("Retry-After"), request.url.toString())
            val cookie = request.header("Cookie")
            val usesSession = cookie?.contains("t_hash_t=") == true || request.url.queryParameter("in") != null
            val authPage = cookie?.contains("t_hash_t=") == true && StreamSessionPolicy.isAuthLandingPage(
                request.url.encodedPath, response.request.url.encodedPath, response.code, response.body
            )
            if (usesSession && (StreamSessionPolicy.isSessionRejected(response.code, response.body) || authPage)) {
                if (cookie?.contains("t_hash_t=") == true) throw SessionRejectedException(cookie)
                // A rejected CDN signature is not evidence that the ten-hour login cookie expired.
                throw CdnRouteRejectedException()
            }
            if (cookie?.contains("t_hash_t=") == true && StreamSessionPolicy.isUnexpectedAuthenticatedHtml(
                    request.url.encodedPath, response.code, response.body
                )) throw AuthenticatedEndpointUnavailableException()
            response
        }
        return if (paced) {
            PlaybackServiceGate.request(currentCoroutineContext()[PreviewResolutionContext] != null, execute)
        } else execute()
    }

    private val OTT_SEARCH_ORDER = listOf("nf", "pv", "hs", "dp", "hb", "atp", "pm", "pc", "hlu")

    private val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 " +
                "Mobile Safari/537.36 /OS.Gatu v3.0"

    private fun getPrefs(): SharedPreferences =
        context.getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE)

    private fun canUseWebViewFallback(): Boolean = try {
        // Background warming stays native-only. An active viewer still needs
        // the recovery path on low-RAM TVs when the native handshake fails.
        android.os.Build.VERSION.SDK_INT < 26 || WebView.getCurrentWebViewPackage() != null
    } catch (_: Exception) {
        false
    }

    private var activeDomain: String = "net52.cc"
    private val DOMAIN_POOL = listOf("net52.cc", "netmirror.app", "netmirror.gg", "mobidetect.art")

    private suspend fun resolveActiveDomain(): String = withContext(Dispatchers.IO) {
        // Capture the configured origin and fallback pool once for this attempt.
        val domainCandidates = publicConfig.snapshot().bootstrapDomains(DOMAIN_POOL)
        val preferred = domainCandidates.first()

        // Fast probe preferred domain (max ~2.5s)
        try {
            val req = Request.Builder()
                .url("https://$preferred/mobile/home?app=1")
                .header("User-Agent", MOBILE_UA)
                .build()
            val res = fastProbeClient.fetch(req)
            val finalHost = res.request.url.host.ifBlank { preferred }
            val isOk = res.isSuccessful && (
                extractSetCookie(res.headers, "addhash").isNotEmpty() ||
                    res.body.contains("addhash", ignoreCase = true)
            )

            if (isOk) {
                activeDomain = finalHost
                getPrefs().edit().putString("active_domain", finalHost).apply()
                return@withContext finalHost
            }
        } catch (error: Exception) { rethrowControlFailure(error);}

        // Concurrent race across candidates
        val candidates = domainCandidates.drop(1)
        val resultChannel = kotlinx.coroutines.channels.Channel<String>(capacity = candidates.size)
        val jobs = candidates.map { cand ->
            launch {
                try {
                    val req = Request.Builder()
                        .url("https://$cand/mobile/home?app=1")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    val res = fastProbeClient.fetch(req)
                    val finalHost = res.request.url.host.ifBlank { cand }
                    val isOk = res.isSuccessful && (
                        extractSetCookie(res.headers, "addhash").isNotEmpty() ||
                            res.body.contains("addhash", ignoreCase = true)
                    )

                    if (isOk) {
                        resultChannel.trySend(finalHost)
                    }
                } catch (e: Exception) { rethrowControlFailure(e);
                    Log.d("DirectCDN", "⚠️ Domain $cand unreachable: ${e.message}")
                }
            }
        }

        val winner = kotlinx.coroutines.withTimeoutOrNull(3500L) {
            resultChannel.receiveCatching().getOrNull()
        }
        jobs.forEach { it.cancel() }

        // A slow TV connection may exceed the fast probe's three-second budget;
        // the full handshake has longer timeouts and gets the final say.
        val finalChosen = winner ?: preferred
        activeDomain = finalChosen
        getPrefs().edit().putString("active_domain", finalChosen).apply()
        Log.d("DirectCDN", "🌐 Resolved active domain: $finalChosen")
        finalChosen
    }

    /**
     * Read the session cookies from SharedPreferences.
     * This is the cookie set that lasts 10 hours. If it's missing
     * or stale, return null.
     */
    data class StoredSession(
        val domain: String,
        val addhashRaw: String,
        val addhashEncoded: String,
        val tHashTEncoded: String,
        val cookieHeader: String,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private fun isCompleteSession(session: StoredSession): Boolean =
        session.addhashRaw.isNotBlank() && session.addhashEncoded.isNotBlank() &&
            session.tHashTEncoded.isNotBlank()

    private fun loadStoredSessions(): List<StoredSession> {
        val directPrefs = getPrefs()
        val list = mutableListOf<StoredSession>()
        var hadStoredEntries = false
        val s = directPrefs.getString("directcdn_sessions", null)
        if (s != null) {
            try {
                val array = JSONArray(s)
                hadStoredEntries = array.length() > 0
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    val fetchedAt = json.optLong("fetchedAt", 0L)
                    if (StreamSessionPolicy.isFresh(fetchedAt, System.currentTimeMillis())) {
                        val domain = json.optString("domain", "net52.cc")
                        val addhashRaw = json.optString("addhashRaw")
                        val addhashEncoded = json.optString("addhashEncoded")
                        val tHashTEncoded = json.optString("tHashTEncoded", "")
                        if (addhashRaw.isNotBlank() && addhashEncoded.isNotBlank() && tHashTEncoded.isNotBlank()) {
                            val cookieHeader = "addhash=$addhashEncoded; t_hash_t=$tHashTEncoded; lang=eng"
                            list.add(StoredSession(domain, addhashRaw, addhashEncoded, tHashTEncoded, cookieHeader, fetchedAt))
                        }
                    }
                }
            } catch (e: Exception) { rethrowControlFailure(e);}
        }
        
        // Migrate only when the new pool has never been written. An empty pool must stay empty.
        if (s == null) {
            val legacy = directPrefs.getString("directcdn_session", null)
            if (legacy != null) {
                hadStoredEntries = true
                try {
                    val json = JSONObject(legacy)
                    val fetchedAt = json.optLong("fetchedAt", 0L)
                    if (StreamSessionPolicy.isFresh(fetchedAt, System.currentTimeMillis())) {
                        val domain = json.optString("domain", "net52.cc")
                        val addhashRaw = json.optString("addhashRaw")
                        val addhashEncoded = json.optString("addhashEncoded")
                        val tHashTEncoded = json.optString("tHashTEncoded", "")
                        if (addhashRaw.isNotBlank() && addhashEncoded.isNotBlank() && tHashTEncoded.isNotBlank()) {
                            val cookieHeader = "addhash=$addhashEncoded; t_hash_t=$tHashTEncoded; lang=eng"
                            list.add(StoredSession(domain, addhashRaw, addhashEncoded, tHashTEncoded, cookieHeader, fetchedAt))
                        }
                    }
                } catch (e: Exception) { rethrowControlFailure(e);}
            }
        }
        
        if (hadStoredEntries && list.isEmpty()) {
            // Natural TTL expiry must retire dependent tokens too, just like
            // server revocation. Otherwise newer manifests can outlive cookies.
            clearSessionState()
        } else if (s == null && directPrefs.contains("directcdn_session")) saveStoredSessions(list)
        if (list.isNotEmpty()) activeDomain = list.first().domain
        com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "loadStoredSessions loaded ${list.size} sessions. Active Domain: $activeDomain")
        return list
    }

    private fun saveStoredSessions(sessions: List<StoredSession>) {
        val array = JSONArray()
        for (session in sessions) {
            if (!isCompleteSession(session)) continue
            val json = JSONObject().apply {
                put("domain", session.domain)
                put("addhashRaw", session.addhashRaw)
                put("addhashEncoded", session.addhashEncoded)
                put("tHashTEncoded", session.tHashTEncoded)
                put("fetchedAt", session.fetchedAt)
            }
            array.put(json)
        }
        getPrefs().edit().putString("directcdn_sessions", array.toString())
            .remove("directcdn_session").apply()
    }

    fun hasValidSession(): Boolean {
        return synchronized(sessionStateLock) { loadStoredSessions().isNotEmpty() }
    }

    suspend fun ensureSessionWarm(): Boolean = withContext(Dispatchers.IO) {
        if (isSessionRecentlyVerified()) return@withContext true

        // Fast path: if the session is fresh AND we have a fresh nonce AND
        // a cached route, we can trust the persisted state. The CDN response
        // during actual playback will reject a revoked session anyway.
        val stored = synchronized(sessionStateLock) {
            loadStoredSessions().maxByOrNull { it.fetchedAt }
        }
        if (stored != null) {
            val nonce = loadNonce()
            val hasRoute = loadRoutingTable().isNotEmpty()
            if (nonce != null && nonceIsFresh(nonce) && hasRoute) {
                Log.d("DirectCDN", "⚡ Fast warm: session + nonce + route all fresh from disk")
                lastSuccessfulVerifyAtMs = System.currentTimeMillis()
                getPrefs().edit().putLong("last_verify_at_ms", lastSuccessfulVerifyAtMs).apply()
                return@withContext true
            }
        }

        try {
            withTimeoutOrNull(70_000L) {
                // Prewarming runs while the user browses. Keep it native-only so
                // a Chromium WebView cannot contend with Home on a low-RAM TV.
                val session = stored ?: ensureSession(allowWebViewFallback = false)
                try {
                    verifyStoredSession(session, renewOnAmbiguity = stored != null)
                } catch (rejected: SessionRejectedException) {
                    // A cookie can be revoked well before its local ten-hour TTL.
                    invalidateSessionByCookie(rejected.cookie ?: session.cookieHeader)
                    if (stored == null) throw rejected
                    verifyStoredSession(ensureSession(allowWebViewFallback = false), renewOnAmbiguity = false)
                }
                true
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("DirectCDN", "Session warmup failed: ${e.message}")
            false
        }
    }

    suspend fun warmupSessionAsync(): Boolean = ensureSessionWarm()

    private suspend fun verifyStoredSession(session: StoredSession, renewOnAmbiguity: Boolean) {
        var lastFailure: Exception? = null
        var ambiguousQueries = 0
        var hadTransientFailure = false
        for (query in listOf("avatar", "wednesday")) {
            var ambiguousResponse = false
            for (path in ottSearchPaths("nf")) {
                try {
                    val request = Request.Builder()
                        .url("https://${session.domain}$path?s=$query&t=${System.currentTimeMillis() / 1000}")
                        .header("User-Agent", MOBILE_UA)
                        .header("Cookie", session.cookieHeader)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://${session.domain}/")
                        .build()
                    val response = client.fetch(request)
                    if (response.code == 404) {
                        ambiguousResponse = true
                        continue
                    }
                    if (!response.isSuccessful) throw java.io.IOException("Session check unavailable (HTTP ${response.code})")
                    val body = response.body.trim()
                    if (body.isBlank()) {
                        ambiguousResponse = true
                        continue
                    }
                    val parsed = try {
                        if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
                    } catch (error: org.json.JSONException) {
                        throw java.io.IOException("Session check returned invalid data", error)
                    }
                    if (parsed is JSONObject && !parsed.has("searchResult") && !parsed.has("status")) {
                        throw java.io.IOException("Session check returned an unknown response")
                    }
                    val results = extractResults(parsed)
                    for (index in 0 until results.length()) {
                        val item = results.optJSONObject(index) ?: continue
                        val id = item.optString("id").ifBlank { item.optString("Id") }
                        val title = item.optString("t").ifBlank { item.optString("title") }
                            .ifBlank { item.optString("T") }.ifBlank { item.optString("Title") }
                        if (id.isNotBlank() && title.isNotBlank()) {
                            synchronized(sessionStateLock) { lastAmbiguousRenewalAtMs = 0L }
                            lastSuccessfulVerifyAtMs = System.currentTimeMillis()
                            getPrefs().edit().putLong("last_verify_at_ms", lastSuccessfulVerifyAtMs).apply()
                            return
                        }
                    }
                    ambiguousResponse = true
                } catch (error: Exception) {
                    rethrowControlFailure(error)
                    hadTransientFailure = true
                    lastFailure = error
                }
            }
            if (ambiguousResponse) ambiguousQueries++
        }
        // Both unrelated searches being empty is a useful expiry signal. A single
        // empty result or a transport/server failure is not enough to discard a cookie.
        if (ambiguousQueries == 2 && !hadTransientFailure && renewOnAmbiguity) {
            val now = System.currentTimeMillis()
            val shouldRenew = synchronized(sessionStateLock) {
                val sinceLastRenewal = now - lastAmbiguousRenewalAtMs
                if (lastAmbiguousRenewalAtMs > 0L && sinceLastRenewal >= 0L &&
                    sinceLastRenewal < 30 * 60_000L) {
                    false
                } else {
                    lastAmbiguousRenewalAtMs = now
                    true
                }
            }
            if (shouldRenew) throw SessionRejectedException(session.cookieHeader)
        }
        throw java.io.IOException("Stored playback session could not be verified", lastFailure)
    }

    private suspend fun ensureSession(allowWebViewFallback: Boolean? = null, requiredDomain: String? = null): StoredSession = withContext(Dispatchers.IO) {
        val mayUseWebView = allowWebViewFallback ?: (currentCoroutineContext()[PreviewResolutionContext] == null)
        // One foreground session; no detached pool top-up competing with playback or home entry.
        val lockStartedAt = com.example.ui.util.RuntimeTiming.start()
        sessionMutex.withLock {
            com.example.ui.util.RuntimeTiming.elapsed("session_wait_for_generation", lockStartedAt)
            if (requiredDomain != null && !ProviderRuntimeConfig.sessionMatches(requiredDomain, requiredDomain))
                throw java.io.IOException("Invalid session origin")
            val available = synchronized(sessionStateLock) { loadStoredSessions() }
            val cached = available.filter { ProviderRuntimeConfig.sessionMatches(it.domain, requiredDomain) }.maxByOrNull { it.fetchedAt }
            if (cached != null) {
                com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "ensureSession retrieved cached session for domain: ${cached.domain} (t_hash_t cookie is present)")
                return@withLock cached
            }
            com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "No cached session found. Initiating dynamic session warmup/generation...")
            val generation = sessionGeneration.get()
            // A rejected cookie must never ride along in a new handshake.
            cookieJar.clear()
            val session = withTimeoutOrNull(58_000L) { generateNewSession(mayUseWebView, requiredDomain) }
                ?: throw java.io.IOException("Session warmup timed out. Please retry.")
            currentCoroutineContext().ensureActive()
            if (!ProviderRuntimeConfig.sessionMatches(session.domain, requiredDomain))
                throw java.io.IOException("Generated session does not match the playback origin")
            synchronized(sessionStateLock) {
                if (generation != sessionGeneration.get()) throw SessionChangedException()
                saveStoredSessions(listOf(session) + available.filter { it.domain != session.domain })
            }
            com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "Successfully generated and saved new session for domain: ${session.domain}")
            session
        }
    }

    private fun extractSetCookie(headers: okhttp3.Headers, name: String): String {
        val setCookies = headers.values("Set-Cookie")
        for (h in setCookies) {
            val trimmed = h.trim()
            if (trimmed.startsWith("$name=")) {
                return trimmed.substring(name.length + 1).split(";")[0].trim()
            }
        }
        return ""
    }

    private suspend fun triggerUserver(
        domain: String,
        addhashRaw: String,
        quryParam: String = "hee5",
        vsiteSubdomain: String = "userver"
    ) = withContext(Dispatchers.IO) {
        val ffr = URLEncoder.encode(addhashRaw, "UTF-8").replace("+", "%20")
        val t = Math.random().toString()
        val url = "https://$vsiteSubdomain.$domain/?$quryParam=$ffr&a=y&t=$t"
        Log.d("DirectCDN", "📡 Step 2: Triggering $vsiteSubdomain with param $quryParam")

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .header("sec-ch-ua", SEC_CH_UA)
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("Referer", "https://$domain/")
                .build()
            client.fetch(req)
            Log.d("DirectCDN", "✅ userver triggered")
        } catch (e: Exception) {
            rethrowControlFailure(e)
            Log.d("DirectCDN", "⚠️ userver request failed; verify polling will continue")
        }
    }

    private suspend fun pollVerify2(
        domain: String,
        addhashEncoded: String,
        verifyEndpoint: String = "/mobile/verify2.php",
        maxAttempts: Int = 40,
        delayMs: Long = 1_200
    ): String? = withContext(Dispatchers.IO) {
        Log.d("DirectCDN", "🔑 Step 3: Polling $verifyEndpoint (up to $maxAttempts attempts)...")
        val url = "https://$domain$verifyEndpoint"

        for (i in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val reqBody = "verify=$addhashEncoded".toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .post(reqBody)
                    .header("User-Agent", MOBILE_UA)
                    .header("sec-ch-ua", SEC_CH_UA)
                    .header("sec-ch-ua-mobile", "?1")
                    .header("sec-ch-ua-platform", "\"Android\"")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", "https://$domain")
                    .header("Referer", "https://$domain/mobile/home?app=1")
                    .header("Cookie", "addhash=$addhashEncoded")
                    .build()

                val res = client.fetch(req)
                val status = res.code
                val setCookieHash = extractSetCookie(res.headers, "t_hash_t")
                Log.d("DirectCDN", "🔑 $verifyEndpoint #$i: HTTP $status; t_hash_t present=${setCookieHash.isNotEmpty()}")

                var tHashT = setCookieHash
                if (tHashT.isEmpty()) {
                    tHashT = cookieJar.getCookieValue(domain, "t_hash_t") ?: ""
                }
                if (tHashT.isNotEmpty()) {
                    Log.d("DirectCDN", "✅ t_hash_t received on attempt $i")
                    return@withContext tHashT
                }
                if (i < maxAttempts) {
                    delay(delayMs)
                }
            } catch (e: Exception) {
                rethrowControlFailure(e)
                Log.d("DirectCDN", "⚠️ $verifyEndpoint #$i error: ${e.message}")
                if (i < maxAttempts) {
                    delay(delayMs)
                }
            }
        }
        Log.d("DirectCDN", "ℹ️ $verifyEndpoint polling ended without t_hash_t")
        return@withContext null
    }

    private suspend fun silentWebViewWarmup(domain: String): Pair<String, String>? = withContext(Dispatchers.Main) {
        Log.d("DirectCDN", "🌐 Invoking Silent WebView Fallback for $domain...")
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val cookieUrl = "https://$domain"
        // WebView has a separate persistent jar. An old t_hash_t combined with
        // a new addhash would look complete but cannot authenticate.
        for (name in listOf("addhash", "t_hash_t")) {
            for (scope in listOf("", "; Domain=$domain")) {
                val cleared = CompletableDeferred<Unit>()
                cookieManager.setCookie(cookieUrl, "$name=; Max-Age=0; Path=/$scope") {
                    cleared.complete(Unit)
                }
                withTimeoutOrNull(2_000L) { cleared.await() }
            }
        }
        val remainingCookies = cookieManager.getCookie(cookieUrl).orEmpty()
        if (Regex("(?:^|;\\s*)(?:addhash|t_hash_t)=").containsMatchIn(remainingCookies)) {
            Log.w("DirectCDN", "Silent WebView fallback could not clear its prior session")
            return@withContext null
        }

        val deferred = CompletableDeferred<Pair<String, String>?>()
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = MOBILE_UA
        }
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        var completed = false
        val checkCookies = object : Runnable {
            override fun run() {
                if (completed) return
                val cookies = cookieManager.getCookie(cookieUrl) ?: ""
                val addhashMatch = Regex("""addhash=([^;]+)""").find(cookies)
                val tHashMatch = Regex("""t_hash_t=([^;]+)""").find(cookies)

                if (addhashMatch != null && tHashMatch != null) {
                    completed = true
                    val addhashEnc = addhashMatch.groupValues[1]
                    val tHashEnc = tHashMatch.groupValues[1]
                    Log.d("DirectCDN", "✅ Silent WebView retrieved both session cookies")
                    deferred.complete(Pair(addhashEnc, tHashEnc))
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.post(checkCookies)
            }
        }

        try {
            webView.loadUrl("$cookieUrl/mobile/home?app=1")
            val result = withTimeoutOrNull(25_000L) { deferred.await() }
            if (result != null) {
                com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "Silent WebView retrieved both session cookies")
            } else {
                com.example.ui.util.AppDiagnosticsLogger.event("DirectCDN", "Silent WebView cookie warmup timed out (25s limit reached)")
            }
            result
        } finally {
            completed = true
            handler.removeCallbacks(checkCookies)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun generateNewSession(allowWebViewFallback: Boolean, requiredDomain: String? = null): StoredSession = withContext(Dispatchers.IO) {
        Log.d("DirectCDN", "🔑 Performing standalone session warmup for DirectCDN...")
        val domain = requiredDomain ?: resolveActiveDomain()

        var finalDomain = domain
        var addhashEncoded = ""
        var addhashRaw = ""
        var tHashTEncoded = ""
        var nativeFailure: Exception? = null
        try {
            // 1. Fetch addhash
            val homeUrl = "https://$domain/mobile/home?app=1"
            val homeReq = Request.Builder()
                .url(homeUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("sec-ch-ua", SEC_CH_UA)
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("x-requested-with", "")
                .build()
            val homeRes = client.fetch(homeReq)
            finalDomain = homeRes.request.url.host.ifBlank { domain }
            val homeHeaders = homeRes.headers
            val homeBody = homeRes.body

            addhashEncoded = extractSetCookie(homeHeaders, "addhash")
            if (addhashEncoded.isEmpty()) {
                addhashEncoded = cookieJar.getCookieValue(finalDomain, "addhash") ?: ""
            }

            if (addhashEncoded.isNotEmpty()) {
                addhashRaw = java.net.URLDecoder.decode(addhashEncoded, "UTF-8")
            } else {
                val m = Regex("""data-(?:hash|addhash|token)=["']([^"']+)["']""").find(homeBody)
                    ?: Regex("""(?:var|window\.)addhash\s*=\s*["']([^"']+)["']""").find(homeBody)
                    ?: Regex("""\b([A-Za-z0-9+/=]{10,}::[A-Za-z0-9+/=]{4,}::[A-Za-z0-9+/=]{4,})\b""").find(homeBody)
                if (m != null) {
                    addhashRaw = m.groupValues[1]
                    addhashEncoded = URLEncoder.encode(addhashRaw, "UTF-8").replace("+", "%20")
                } else {
                    Log.d("DirectCDN", "⚠️ No addhash in home HTML, checking fallback...")
                }
            }

            if (addhashRaw.split("::").size >= 3) {
                // 2. Extract Qury, Vsite, verify endpoint dynamically
                val quryMatch = Regex("""var\s+Qury\s*=\s*["']([^"']+)["']""").find(homeBody)
                    ?: Regex("""(?:window\.)?Qury\s*=\s*["']([^"']+)["']""").find(homeBody)
                    ?: Regex("""\?([a-zA-Z0-9_]{3,10})=\s*\+\s*encodeURIComponent""").find(homeBody)
                // Fall back to last-known param instead of stale hardcoded "hee5"
                val quryDefault = getPrefs().getString(LAST_QURY_KEY, null)
                    ?: getPrefs().getString("remote_qury_param", "hee5") ?: "hee5"
                val quryParam = quryMatch?.groupValues?.get(1) ?: quryDefault

                val vsiteMatch = Regex("""var\s+Vsite2?\s*=\s*["']([^"']+)["']""").find(homeBody)
                    ?: Regex("""(?:window\.)?Vsite2?\s*=\s*["']([^"']+)["']""").find(homeBody)
                val vsiteSubdomain = vsiteMatch?.groupValues?.get(1) ?: "userver"

                val verifyMatch = Regex("""["']/(?:mobile/)?(verify[0-9]*\.php)["']""").find(homeBody)
                val verifyEndpoint = if (verifyMatch != null) "/mobile/${verifyMatch.groupValues[1]}" else "/mobile/verify2.php"

                // Persist extracted params so future sessions use the latest
                // even if the home page HTML format changes.
                if (quryMatch != null) {
                    getPrefs().edit().putString(LAST_QURY_KEY, quryParam).apply()
                }

                try {
                    triggerUserver(finalDomain, addhashRaw, quryParam, vsiteSubdomain)
                } catch (e: Exception) {
                    rethrowControlFailure(e)
                    Log.d("DirectCDN", "Userver trigger non-fatal: ${e.message}")
                }

                // The postback often arrives well after the userver response.
                delay(1_000L)
                val polled = pollVerify2(finalDomain, addhashEncoded, verifyEndpoint)
                if (polled != null) {
                    tHashTEncoded = polled
                }
            }

        } catch (error: Exception) {
            rethrowControlFailure(error)
            nativeFailure = error
            Log.w("DirectCDN", "Native handshake failed (${error.javaClass.simpleName}); checking foreground recovery")
        }

        // A native addhash without t_hash_t is not an authenticated session.
        if ((allowWebViewFallback || sessionDemand.isRequested) && canUseWebViewFallback() &&
            (addhashEncoded.isEmpty() || tHashTEncoded.isEmpty())) {
            Log.d("DirectCDN", "⚠️ Native handshake incomplete — trying silent WebView for $finalDomain...")
            val fallback = if (allowWebViewFallback) silentWebViewWarmup(finalDomain)
                else sessionDemand.whileRequested { silentWebViewWarmup(finalDomain) }
            if (fallback != null) {
                addhashEncoded = fallback.first
                addhashRaw = java.net.URLDecoder.decode(addhashEncoded, "UTF-8")
                tHashTEncoded = fallback.second
            }
        }

        if (addhashEncoded.isEmpty() || tHashTEncoded.isEmpty()) {
            throw java.io.IOException("DirectCDN warmup failed: complete session cookies not received", nativeFailure)
        }

        val cookieHeader = "addhash=$addhashEncoded; t_hash_t=$tHashTEncoded; lang=eng"

        // 4. Refresh returning session
        try {
            cookieJar.addManualCookie(finalDomain, "addhash", addhashEncoded)
            if (tHashTEncoded.isNotEmpty()) {
                cookieJar.addManualCookie(finalDomain, "t_hash_t", tHashTEncoded)
            }
            cookieJar.addManualCookie(finalDomain, "lang", "eng")

            val refReq = Request.Builder()
                .url("https://$finalDomain/mobile/home?app=1")
                .header("User-Agent", MOBILE_UA)
                .header("sec-ch-ua", SEC_CH_UA)
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("Cookie", cookieHeader)
                .build()
            client.fetch(refReq)
        } catch (error: Exception) { rethrowControlFailure(error); }

        val session = StoredSession(
            domain = finalDomain,
            addhashRaw = addhashRaw,
            addhashEncoded = addhashEncoded,
            tHashTEncoded = tHashTEncoded,
            cookieHeader = cookieHeader,
            fetchedAt = System.currentTimeMillis()
        )
        Log.d("DirectCDN", "✅ DirectCDN session established successfully for $finalDomain")
        session
    }

        // ---- Nonce cache (10-hour TTL) ----

    data class Nonce(
        val ts: String,
        val hash2: String,
        val fetchedAt: Long,
        val hash1: String = "235ca31540ab8d90fcef4a00de8a247c",
        val suffix: String = "",
        val mode: String = "ek"
    )

    private fun loadNonce(): Nonce? {
        val s = getPrefs().getString(NONCE_KEY, null) ?: return null
        return try {
            val json = JSONObject(s)
            Nonce(
                json.getString("ts"),
                json.getString("hash2"),
                json.getLong("fetchedAt"),
                json.optString("hash1", FREECDN_HASH1),
                json.optString("suffix", "").takeUnless { !json.has("mode") && it == "ek" }.orEmpty(),
                json.optString("mode", "ek")
            )
        } catch (e: Exception) { rethrowControlFailure(e); null }
    }

    private fun saveNonce(n: Nonce) {
        val json = JSONObject().apply {
            put("ts", n.ts)
            put("hash2", n.hash2)
            put("fetchedAt", n.fetchedAt)
            put("hash1", n.hash1)
            put("suffix", n.suffix)
            put("mode", n.mode)
        }
        // Keep diagnostics for provider-issued modes without treating them
        // as a global version or as the provider-master request mode.
        val editor = getPrefs().edit().putString(NONCE_KEY, json.toString())
        if (n.mode.isNotEmpty() && n.hash2.isNotEmpty()) {
            editor.putString(LAST_TOKEN_MODE_KEY, n.mode)
            editor.putString(LAST_TOKEN_SUFFIX_KEY, n.suffix)
        }
        editor.apply()
    }

    private fun nonceIsFresh(n: Nonce?): Boolean {
        if (n == null) return false
        return StreamSessionPolicy.isFresh(
            StreamSessionPolicy.tokenIssuedAt(n.ts, n.fetchedAt), System.currentTimeMillis()
        )
    }

    private fun buildFreecdnInToken(ts: String, hash2: String, hash1: String = FREECDN_HASH1, suffix: String = "", mode: String = "ek"): String =
        CdnRoutePolicy.Token(hash1, hash2, ts, mode, suffix).value

    private fun routeNonce(url: String, fetchedAt: Long): Nonce? = CdnRoutePolicy.token(url)?.let {
        Nonce(it.timestamp, it.hash2, fetchedAt, it.hash1, it.suffix, it.mode)
    }

    private fun manifestNonce(body: String): Nonce? = CdnRoutePolicy.tokenInManifest(body)?.let {
        Nonce(it.timestamp, it.hash2, System.currentTimeMillis(), it.hash1, it.suffix, it.mode)
    }

    private fun nonceForRoute(route: RouteEntry): Nonce {
        // A mode belongs to this signature, not every title or audio rendition.
        routeNonce(route.freecdnUrl, route.fetchedAt)?.let {
            if (!nonceIsFresh(it)) throw CdnRouteRejectedException()
            return it
        }
        // Opaque and unsigned provider routes stay unchanged. Never attach a
        // nonce learned from another title or start a speculative handshake.
        return Nonce((route.fetchedAt / 1000).toString(), "", route.fetchedAt)
    }

    private fun masterTokenFor(contentId: String): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val md = MessageDigest.getInstance("MD5")
        val h2 = md.digest((t + contentId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        // Master request mode and CDN signature mode are separate protocols.
        val mode = getPrefs().getString("remote_master_mode", "ek") ?: "ek"
        val hash1 = getPrefs().getString("remote_master_hash1", FREECDN_HASH1) ?: FREECDN_HASH1
        return "$hash1::$h2::$t::$mode::m"
    }

    // ---- Remote config (zero-update resilience) ----

    /** Settings refresh independently of warming and never revoke issued media. */
    suspend fun checkRemoteConfig() = withContext(Dispatchers.IO) {
        publicConfig.refresh()
    }

    // ---- Routing table (contentId -> freecdn host) ----

    data class RouteEntry(val host: String, val freecdnUrl: String, val fetchedAt: Long)

    private fun loadRoutingTable(): Map<String, RouteEntry> {
        val s = getPrefs().getString(ROUTING_KEY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(s)
            val out = mutableMapOf<String, RouteEntry>()
            for (key in json.keys()) {
                val entry = json.getJSONObject(key)
                val host = entry.getString("host")
                val freecdnUrl = entry.optString("freecdnUrl", "")
                val fetchedAt = entry.optLong("fetchedAt", 0L)
                val isFresh = CdnRoutePolicy.isFresh(freecdnUrl, fetchedAt, System.currentTimeMillis())
                if (isFresh && !host.contains("220884") && !freecdnUrl.contains("220884")) {
                    out[key] = RouteEntry(
                        host = host,
                        freecdnUrl = freecdnUrl,
                        fetchedAt = fetchedAt
                    )
                }
            }
            out
        } catch (e: Exception) { rethrowControlFailure(e); emptyMap() }
    }

    private fun saveRoutingEntry(contentId: String, entry: RouteEntry) = synchronized(sessionStateLock) {
        if (entry.host.contains("220884") || entry.freecdnUrl.contains("220884")) {
            Log.w("DirectCDN", "Refusing to save dummy 220884 routing entry for $contentId")
            return
        }
        val current = getPrefs().getString(ROUTING_KEY, "{}") ?: "{}"
        val json = try { JSONObject(current) } catch (e: Exception) { rethrowControlFailure(e); JSONObject() }
        json.put(contentId, JSONObject().apply {
            put("host", entry.host)
            put("freecdnUrl", entry.freecdnUrl)
            put("fetchedAt", entry.fetchedAt)
        })
        getPrefs().edit().putString(ROUTING_KEY, json.toString()).apply()
    }

    private fun normalizeTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun wordOverlapScore(a: String, b: String): Double {
        val wordsA = a.split(" ").filter { it.length > 1 }.toSet()
        val wordsB = b.split(" ").filter { it.length > 1 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        var overlap = 0.0
        for (w in wordsA) {
            if (wordsB.contains(w)) overlap++
        }
        return overlap / minOf(wordsA.size, wordsB.size)
    }

    private fun sanitizeSearchQuery(title: String): String {
        return title.replace(Regex("Tyler Perry's\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+S\\d+E\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Season\\s+\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Episode\\s+\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun searchVariants(cleanTitle: String): List<String> {
        val variants = mutableListOf(cleanTitle)
        val words = cleanTitle.split(" ")
        // Only add one fallback variant (first 3 words) if the title is long
        // to prevent combinatorial explosion of search requests.
        if (words.size >= 4) variants.add(words.take(3).joinToString(" "))
        return variants.distinct()
    }

    private fun ottPathPrefix(ott: String): String {
        return when (ott) {
            "nf" -> "/mobile"
            "pv" -> "/mobile/pv"
            "hs" -> "/mobile/hs"
            "dp" -> "/mobile/dp"
            "hb" -> "/mobile/hb"
            "atp" -> "/mobile/atp"
            "pm" -> "/mobile/pm"
            "pc" -> "/mobile/pc"
            "hlu" -> "/mobile/hlu"
            else -> "/mobile"
        }
    }

    private fun ottSearchPaths(ott: String): List<String> {
        val prefix = ottPathPrefix(ott)
        return if (prefix == "/mobile") {
            listOf("/mobile/search.php", "/search.php")
        } else {
            listOf("$prefix/search.php")
        }
    }

    private fun extractResults(parsed: Any?): JSONArray {
        if (parsed is JSONArray) return parsed
        if (parsed is JSONObject) {
            if (parsed.has("searchResult")) {
                val searchResult = parsed.optJSONArray("searchResult")
                if (searchResult != null && searchResult.length() > 0) {
                    if (parsed.optString("status") == "n") return JSONArray()
                    return searchResult
                }
            }
        }
        return JSONArray()
    }

    private fun findBestMatch(results: JSONArray, searchTitle: String, searchYear: String, ott: String): SearchResult? {
        if (results.length() == 0) return null
        val searchNorm = normalizeTitle(searchTitle)
        if (searchNorm.isEmpty()) return null

        data class ScoredResult(val r: JSONObject, val score: Int, val reason: String)
        val scored = mutableListOf<ScoredResult>()

        val searchWords = searchNorm.split(" ").filter { it.length > 2 }
        val targetYear = searchYear.trim().toIntOrNull()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val rId = r.optString("id").takeIf { it.isNotEmpty() } ?: r.optString("Id")
            if (rId.isEmpty()) continue

            val rTitle = r.optString("t").takeIf { it.isNotEmpty() } ?: r.optString("title").takeIf { it.isNotEmpty() } ?: r.optString("T").takeIf { it.isNotEmpty() } ?: r.optString("Title")
            val rYearStr = r.optString("y").takeIf { it.isNotEmpty() } ?: r.optString("year").takeIf { it.isNotEmpty() } ?: r.optString("Y").takeIf { it.isNotEmpty() } ?: r.optString("Year")
            val rNorm = normalizeTitle(rTitle)
            val resultYear = rYearStr.trim().toIntOrNull()

            val isExactTitle = rNorm == searchNorm
            val yearDiff = if (targetYear != null && resultYear != null) Math.abs(targetYear - resultYear) else null

            val isExactYear = yearDiff != null && yearDiff == 0
            val isCloseYear = yearDiff != null && yearDiff == 1
            val isMismatchYear = yearDiff != null && yearDiff >= 2

            val rWords = rNorm.split(" ").filter { it.length > 2 }
            val missingSearchWords = searchWords.filter { !rWords.contains(it) }

            if (!isExactTitle && missingSearchWords.size >= 2) {
                continue
            }

            if (isExactTitle) {
                val score = when {
                    isExactYear -> 100
                    isCloseYear -> 95
                    targetYear == null || resultYear == null -> 80
                    isMismatchYear && yearDiff!! >= 3 -> 25 // Severely penalize so another OTT with exact year wins
                    else -> 40
                }
                scored.add(ScoredResult(r, score, "exact(yrDiff=${yearDiff ?: "none"})"))
                continue
            }

            if (rNorm.startsWith("$searchNorm ") || searchNorm.startsWith("$rNorm ") ||
                rNorm.startsWith(searchNorm) || searchNorm.startsWith(rNorm)) {
                val lenRatio = rNorm.length.toDouble() / maxOf(searchNorm.length, 1)
                if ((searchNorm.length > 6 || lenRatio <= 1.8) && missingSearchWords.isEmpty()) {
                    if (isMismatchYear && yearDiff!! >= 2) continue
                    val baseScore = when {
                        isExactYear -> 85
                        isCloseYear -> 80
                        targetYear == null || resultYear == null -> 65
                        else -> 30
                    }
                    scored.add(ScoredResult(r, baseScore, "prefix(yrDiff=${yearDiff ?: "none"})"))
                    continue
                }
            }

            val overlap = wordOverlapScore(searchNorm, rNorm)
            if (overlap >= 0.7 && missingSearchWords.isEmpty()) {
                if (isMismatchYear && yearDiff!! >= 2) continue
                val baseScore = (50 + overlap * 20).toInt() + (if (isExactYear) 20 else if (isCloseYear) 10 else 0)
                scored.add(ScoredResult(r, baseScore, "overlap(yrDiff=${yearDiff ?: "none"})"))
                continue
            }

            if (searchNorm.length > 5 && (rNorm.contains(searchNorm) || searchNorm.contains(rNorm)) && missingSearchWords.isEmpty()) {
                if (isMismatchYear && yearDiff!! >= 2) continue
                val baseScore = 40 + (if (isExactYear) 20 else if (isCloseYear) 10 else 0)
                scored.add(ScoredResult(r, baseScore, "contains(yrDiff=${yearDiff ?: "none"})"))
                continue
            }
        }

        if (scored.isEmpty()) return null
        scored.sortByDescending { it.score }
        val best = scored.first()
        if (best.score < 30) return null

        val rTitle = best.r.optString("t").takeIf { it.isNotEmpty() } ?: best.r.optString("title").takeIf { it.isNotEmpty() } ?: best.r.optString("T").takeIf { it.isNotEmpty() } ?: best.r.optString("Title")
        val rYear = best.r.optString("y").takeIf { it.isNotEmpty() } ?: best.r.optString("year").takeIf { it.isNotEmpty() } ?: best.r.optString("Y").takeIf { it.isNotEmpty() } ?: best.r.optString("Year")
        val rId = best.r.optString("id").takeIf { it.isNotEmpty() } ?: best.r.optString("Id")

        Log.d("DirectCDN", "✅ Search match [${ott.uppercase()}]: \"$rTitle\" ($rYear) ID: $rId [${best.reason}, score=${best.score}]")
        return SearchResult(rId, rTitle, rYear, ott, best.score)
    }

    private suspend fun searchContent(
        searchTitle: String,
        searchYear: String,
        ott: String = "nf"
    ): SearchResult? = withContext(Dispatchers.IO) {
        val session = ensureSession()
        val domain = session.domain
        val base = "https://$domain"
        val cleanTitle = sanitizeSearchQuery(searchTitle)
        val variants = searchVariants(cleanTitle)
        val paths = ottSearchPaths(ott)
        val cookie = session.cookieHeader + if (ott != "nf") "; ott=$ott" else ""

        for (query in variants) {
            for (searchPath in paths) {
                try {
                    val ts = System.currentTimeMillis() / 1000
                    val encQ = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                    val searchUrl = "$base$searchPath?s=$encQ&t=$ts"
                    val req = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", MOBILE_UA)
                        .header("Cookie", cookie)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "$base/")
                        .build()

                    val res = client.fetch(req)
                    val bodyText = res.body


                    if (bodyText.isBlank()) continue
                    val parsed = try {
                        if (bodyText.trim().startsWith("[")) JSONArray(bodyText) else JSONObject(bodyText)
                    } catch (e: Exception) { rethrowControlFailure(e);
                        continue
                    }

                    val results = extractResults(parsed)
                    if (results.length() == 0) continue

                    val match = findBestMatch(results, searchTitle, searchYear, ott)
                    if (match != null) return@withContext match
                } catch (e: Exception) { rethrowControlFailure(e);
                    Log.d("DirectCDN", "❌ $searchPath error: ${e.message}")
                }
            }
        }
        return@withContext null
    }

    private suspend fun searchAcrossOtts(
        searchTitle: String,
        searchYear: String
    ): SearchResult? = withContext(Dispatchers.IO) {
        var bestMatch: SearchResult? = null
        val targetYear = searchYear.trim().toIntOrNull()

        for (ott in OTT_SEARCH_ORDER) {
            val matchResult = searchContent(searchTitle, searchYear, ott)
            if (matchResult != null) {
                val resYear = matchResult.year.trim().toIntOrNull()
                val currentIsExactYear = targetYear != null && resYear == targetYear
                val bestResYear = bestMatch?.year?.trim()?.toIntOrNull()
                val bestIsExactYear = bestMatch != null && targetYear != null && bestResYear == targetYear

                if (bestMatch == null ||
                    (currentIsExactYear && !bestIsExactYear) ||
                    (matchResult.score > bestMatch.score && (!bestIsExactYear || currentIsExactYear))) {
                    bestMatch = matchResult
                }
                // Stop early only if we have high score AND exact year match (or no searchYear specified)
                if (bestMatch.score >= 85 && (targetYear == null || bestIsExactYear || currentIsExactYear)) {
                    break
                }
            }
        }
        bestMatch
    }

    /**
     * For TV shows: we need the per-episode contentId. The show's
     * contentId is the SERIES id; each episode has its own.
     */
    private suspend fun fetchAndCacheEpisodeList(
        showId: String,
        showTitle: String,
        ott: String = "nf",
        targetSeason: Int = 1,
        targetEpisode: Int = 1
    ): List<Triple<Int, Int, String>> = withContext(Dispatchers.IO) {
        val cacheKey = "${ott}_$showId"
        val existingCached = episodeListCache[cacheKey] ?: episodeListCache[showId]
        if (existingCached != null) {
            val hasTarget = if (targetEpisode > 0) {
                existingCached.any { it.first == targetSeason && it.second == targetEpisode }
            } else {
                existingCached.any { it.first == targetSeason }
            }
            if (hasTarget) {
                return@withContext existingCached
            }
        }
        Log.d("DirectCDN", "📺 Fetching episode list for show $showId [${ott.uppercase()}] S${targetSeason}E${targetEpisode}...")

        val session = ensureSession()
        val domain = session.domain
        val ts = System.currentTimeMillis() / 1000
        val cookie = session.cookieHeader + if (ott != "nf") "; ott=$ott" else ""
        val prefix = ottPathPrefix(ott)

        // Step 1: Get post.php for the show to find season list
        val postCandidates = if (prefix == "/mobile") {
            listOf("https://$domain/mobile/post.php?id=$showId&t=$ts", "https://$domain/post.php?id=$showId&t=$ts")
        } else {
            listOf("https://$domain$prefix/post.php?id=$showId&t=$ts", "https://$domain/mobile/post.php?id=$showId&t=$ts")
        }

        var postJson: JSONObject? = null
        for (postUrl in postCandidates) {
            try {
                val postReq = Request.Builder()
                    .url(postUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", cookie)
                    .header("Referer", "https://$domain/")
                    .build()
                val postRes = client.fetch(postReq)
                val postBody = postRes.body

                if (postBody.isNotBlank()) {
                    val j = JSONObject(postBody)
                    if (j.has("season") || j.has("seasons") || j.has("episodes")) {
                        postJson = j
                        break
                    }
                }
            } catch (error: Exception) { rethrowControlFailure(error);}
        }

        val json = postJson ?: JSONObject()
        val seasons = json.optJSONArray("season") ?: json.optJSONArray("seasons") ?: JSONArray()
        val directEpisodes = json.optJSONArray("episodes")
        val episodes = mutableListOf<Triple<Int, Int, String>>()

        // If direct episodes array exists in post.php (some series structure)
        if (directEpisodes != null && directEpisodes.length() > 0) {
            for (j in 0 until directEpisodes.length()) {
                val ep = directEpisodes.optJSONObject(j) ?: continue
                val epId = ep.optString("id").ifBlank { ep.optString("Id") }
                val rawS = ep.optString("s").ifBlank { ep.optString("season") }.ifBlank { ep.optString("s_num") }
                var sNum = rawS.lowercase().replace("season", "").replace("s", "").trim().toIntOrNull()
                if (sNum == null && rawS.isNotBlank()) {
                    val match = Regex("""(?:season|s|^|\b)(\d+)""", RegexOption.IGNORE_CASE).find(rawS)
                    sNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                val rawE = ep.optString("ep").ifBlank { ep.optString("episode") }.ifBlank { ep.optString("e") }
                var eNum = rawE.lowercase().replace("episode", "").replace("ep", "").replace("e", "").trim().toIntOrNull()
                if (eNum == null && rawE.isNotBlank()) {
                    val match = Regex("""(?:ep(?:isode)?\s*|e|^|\b)(\d+)(?:\b|\.|\s|$)""", RegexOption.IGNORE_CASE).find(rawE)
                    eNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                if (epId.isNotEmpty()) {
                    episodes.add(Triple(sNum ?: 1, eNum ?: (j + 1), epId))
                }
            }
        }

        // Targeted Season Selection: query only the requested season instead of looping through all seasons
        var targetSeasonObj: JSONObject? = null
        for (i in 0 until seasons.length()) {
            val sObj = seasons.optJSONObject(i) ?: continue
            val sVal = sObj.optString("s").ifBlank { sObj.optString("season") }.ifBlank { sObj.optString("name") }.ifBlank { sObj.optString("title") }
                .lowercase().replace("season", "").replace("s", "").trim()
            var sNum = sVal.toIntOrNull()
            if (sNum == null) {
                val match = Regex("""(?:season|s|^|\b)(\d+)""", RegexOption.IGNORE_CASE).find(sVal)
                sNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (i + 1)
            }
            if (sNum == targetSeason) {
                targetSeasonObj = sObj
                break
            }
        }

        val seasonsToFetch = if (targetSeasonObj != null) {
            listOf(targetSeasonObj)
        } else if (seasons.length() > 0) {
            listOfNotNull(seasons.optJSONObject(0))
        } else {
            emptyList()
        }

        for (sObj in seasonsToFetch) {
            val sVal = sObj.optString("s").ifBlank { sObj.optString("season") }.ifBlank { sObj.optString("name") }.ifBlank { sObj.optString("title") }
                .lowercase().replace("season", "").replace("s", "").trim()
            var seasonNum = sVal.toIntOrNull()
            if (seasonNum == null) {
                val match = Regex("""(?:season|s|^|\b)(\d+)""", RegexOption.IGNORE_CASE).find(sVal)
                seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: targetSeason
            }
            val rawSeasonId = sObj.optString("id").ifBlank { sObj.optString("Id") }
            if (rawSeasonId.isBlank()) continue
            val seasonId = rawSeasonId

            var currentPage = 1
            var hasMorePages = true
            val maxPages = 20

            while (hasMorePages && currentPage <= maxPages) {
                val pageQuery = if (currentPage == 1) "" else "&page=$currentPage"
                val epsCandidates = if (prefix == "/mobile") {
                    listOf(
                        "https://$domain/mobile/episodes.php?s=$seasonId&series=$showId&t=$ts$pageQuery",
                        "https://$domain/episodes.php?s=$seasonId&series=$showId&t=$ts$pageQuery"
                    )
                } else {
                    listOf(
                        "https://$domain$prefix/episodes.php?s=$seasonId&series=$showId&t=$ts$pageQuery",
                        "https://$domain/mobile/episodes.php?s=$seasonId&series=$showId&t=$ts$pageQuery"
                    )
                }

                var pageFoundEpisodes = false
                var hasNextPage = false
                var nextPageNumber = -1

                for (epsUrl in epsCandidates) {
                    try {
                        val epsReq = Request.Builder()
                            .url(epsUrl)
                            .header("User-Agent", MOBILE_UA)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Cookie", cookie)
                            .header("Referer", "https://$domain/")
                            .build()
                        val epsRes = client.fetch(epsReq)
                        val epsBody = epsRes.body

                        if (epsBody.isBlank() || !epsBody.trim().startsWith("{")) continue
                        val epsJson = JSONObject(epsBody)
                        val epsArr = epsJson.optJSONArray("episodes") ?: continue
                        if (epsArr.length() == 0) continue

                        for (j in 0 until epsArr.length()) {
                            val ep = epsArr.optJSONObject(j) ?: continue
                            val epId = ep.optString("id").ifBlank { ep.optString("Id") }
                            val rawEp = ep.optString("ep").ifBlank { ep.optString("episode") }.ifBlank { ep.optString("e") }
                            var epNum = rawEp.lowercase().replace("episode", "").replace("ep", "").replace("e", "").trim().toIntOrNull()
                            if (epNum == null && rawEp.isNotBlank()) {
                                val match = Regex("""(?:ep(?:isode)?\s*|e|^|\b)(\d+)(?:\b|\.|\s|$)""", RegexOption.IGNORE_CASE).find(rawEp)
                                epNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                            }
                            if (epNum == null) {
                                epNum = (currentPage - 1) * 10 + j + 1
                            }
                            if (epId.isNotEmpty()) {
                                episodes.add(Triple(seasonNum, epNum, epId))
                            }
                        }

                        pageFoundEpisodes = true
                        val nextPageShowStr = epsJson.optString("nextPageShow", "0")
                        val nextPageShowInt = epsJson.optInt("nextPageShow", 0)
                        hasNextPage = nextPageShowStr == "1" || nextPageShowInt == 1
                        nextPageNumber = epsJson.optInt("nextPage", -1)
                        break
                    } catch (error: Exception) { rethrowControlFailure(error);}
                }

                if (!pageFoundEpisodes) {
                    break
                }

                // Stop as soon as the requested episode is present. A preview of
                // episode 2 must not download the rest of a long season.
                if (episodes.any { it.first == targetSeason && it.second == targetEpisode }) break

                if (hasNextPage) {
                    currentPage = if (nextPageNumber > currentPage) nextPageNumber else (currentPage + 1)
                } else {
                    hasMorePages = false
                }
            }
        }

        val combined = ((existingCached ?: emptyList()) + episodes).distinctBy { "${it.first}_${it.second}" }
        putTransientCache(episodeListCache, cacheKey, combined, 24)
        putTransientCache(episodeListCache, showId, combined, 24)
        Log.d("DirectCDN", "✅ Cached ${combined.size} episodes for show $showId [${ott.uppercase()}]")
        combined
    }

    /**
     * Discover an exact provider route per content/episode. Reuse it directly
     * until its original token expires; one bounded rediscovery handles CDN changes.
     */
    private suspend fun discoverHost(
        contentId: String,
        title: String = "",
        showId: String = "",
        ott: String = "nf"
    ): RouteEntry = withContext(Dispatchers.IO) {
        val cached = loadRoutingTable()[contentId]
        if (cached != null && !cached.host.contains("220884") && !cached.freecdnUrl.contains("220884")) {
            Log.d("DirectCDN", "Host cached for $contentId: ${cached.host}")
            if (showId.isNotBlank()) showHostCache[showId] = cached.host
            return@withContext cached
        }

        // Episodes can use different hosts and layouts. Only cache a route the provider returned.

        Log.d("DirectCDN", "🔍 Discovering host for $contentId [${ott.uppercase()}]...")
        val session = ensureSession()
        val domain = session.domain
        val prefix = ottPathPrefix(ott)
        val ts = System.currentTimeMillis() / 1000
        val q = URLEncoder.encode(title.ifBlank { "video" }, "UTF-8").replace("+", "%20")
        val encId = URLEncoder.encode(contentId, "UTF-8").replace("+", "%20")

        val cookie = buildString {
            append(session.cookieHeader)
            if (showId.isNotBlank()) append("; SE$showId=$contentId")
            if (ott != "nf") append("; ott=$ott")
        }

        // 1. Try playlist.php candidate URLs
        val playlistCandidates = if (prefix == "/mobile") {
            listOf(
                "https://$domain/mobile/playlist.php?id=$encId&t=$q&tm=$ts",
                "https://$domain/playlist.php?id=$encId&t=$q&tm=$ts"
            )
        } else {
            listOf(
                "https://$domain$prefix/playlist.php?id=$encId&t=$q&tm=$ts",
                "https://$domain/mobile/playlist.php?id=$encId&t=$q&tm=$ts",
                "https://$domain/playlist.php?id=$encId&t=$q&tm=$ts"
            )
        }

        for (playlistUrl in playlistCandidates) {
            try {
                val req = Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://$domain/mobile/home?app=1")
                    .header("Cookie", cookie)
                    .build()
                val res = client.fetch(req)
                val bodyText = res.body


                if (bodyText.isNotEmpty()) {
                    val parsed = if (bodyText.trim().startsWith("[")) JSONArray(bodyText).optJSONObject(0) else JSONObject(bodyText)
                    if (parsed?.has("sources") == true) contentSubtitlesCache[contentId] = emptyList()
                    val tracks = parsed?.optJSONArray("tracks") ?: parsed?.optJSONArray("captions") ?: parsed?.optJSONArray("subtitles")
                    if (tracks != null) {
                        contentSubtitlesCache[contentId] = emptyList()
                    }
                    if (tracks != null && tracks.length() > 0) {
                        val parsedTracks = mutableListOf<Caption>()
                        for (ti in 0 until tracks.length()) {
                            val tObj = tracks.optJSONObject(ti) ?: continue
                            val rawFile = tObj.optString("file").ifBlank { tObj.optString("src") }.ifBlank { tObj.optString("url") }
                            if (rawFile.isNotBlank()) {
                                var fullUrl = rawFile
                                if (fullUrl.startsWith("//")) fullUrl = "https:$fullUrl"
                                else if (fullUrl.startsWith("/")) fullUrl = "https://$domain$fullUrl"
                                val label = tObj.optString("label").ifBlank { tObj.optString("language") }.ifBlank { tObj.optString("name") }.ifBlank { "English" }
                                val langCode = tObj.optString("language").ifBlank { tObj.optString("srclang") }.ifBlank { tObj.optString("lang") }
                                    .ifBlank { extractLanguageCode(label, rawFile) }
                                parsedTracks.add(Caption(fullUrl, label, if (fullUrl.contains(".srt", ignoreCase = true)) "srt" else "vtt", langCode))
                            }
                        }
                        if (parsedTracks.isNotEmpty()) {
                            contentSubtitlesCache[contentId] = parsedTracks
                        }
                    }
                    val sources = parsed?.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        var fileUrl = ""
                        for (si in 0 until sources.length()) {
                            val sObj = sources.optJSONObject(si) ?: continue
                            val f = sObj.optString("file")
                            if (f.isNotEmpty() && !f.contains("220884")) {
                                fileUrl = f
                                break
                            }
                        }
                        if (fileUrl.isNotEmpty()) {
                            val hostMatch = Regex("""https://([^/]+)""").find(fileUrl)
                            if (hostMatch != null && CdnRoutePolicy.isCdnSource(fileUrl, domain)) {
                                val host = hostMatch.groupValues[1]
                                val entry = RouteEntry(host, fileUrl, System.currentTimeMillis())
                                saveRoutingEntry(contentId, entry)
                                if (showId.isNotBlank()) showHostCache[showId] = host
                                routeNonce(fileUrl, entry.fetchedAt)?.let(::saveNonce)
                                Log.d("DirectCDN", "✅ Discovered host from playlist.php for $contentId: $host")
                                return@withContext entry
                            }

                            // If fileUrl is relative (e.g. /mobile/pv/hls/...)
                            val sourceHlsUrl = "https://$domain/".toHttpUrlOrNull()?.resolve(fileUrl)
                                ?: throw java.io.IOException("Invalid provider master source")
                            val token = masterTokenFor(contentId)
                            val sourceSignature = sourceHlsUrl.queryParameter("in")
                            val fullHlsUrl = if (sourceSignature.isNullOrBlank() || sourceSignature.startsWith("unknown")) {
                                // Replace the entire placeholder value, including
                                // future mode tails; retain unrelated query fields.
                                sourceHlsUrl.newBuilder().setQueryParameter("in", token).build().toString()
                            } else sourceHlsUrl.toString()

                            try {
                                val hlsReq = Request.Builder()
                                    .url(fullHlsUrl)
                                    .header("User-Agent", MOBILE_UA)
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .header("Referer", "https://$domain/")
                                    .header("Cookie", cookie)
                                    .build()
                                val hlsRes = client.fetch(hlsReq)
                                val hlsBody = hlsRes.body


                                if (hlsBody.contains("#EXTM3U") && !hlsBody.contains("Video ID Missing")) {
                                    if (!hlsBody.contains("220884")) {
                                        putTransientCache(masterManifestCache, contentId, hlsBody, 12)
                                    }
                                    val freecdnUrl = CdnRoutePolicy.videoRoute(hlsBody)
                                    if (freecdnUrl != null && !StreamSessionPolicy.isWaitingVideo(freecdnUrl)) {
                                        val host = freecdnUrl.toHttpUrlOrNull()?.host ?: throw java.io.IOException("Invalid provider CDN host")
                                        val entry = RouteEntry(host, freecdnUrl, System.currentTimeMillis())
                                        saveRoutingEntry(contentId, entry)
                                        if (showId.isNotBlank()) showHostCache[showId] = host

                                        (routeNonce(freecdnUrl, entry.fetchedAt) ?: manifestNonce(hlsBody))?.let(::saveNonce)
                                        Log.d("DirectCDN", "✅ Discovered host from playlist HLS response for $contentId: $host")
                                        return@withContext entry
                                    }
                                }
                            } catch (error: Exception) { rethrowControlFailure(error);}
                        }
                    }
                }
            } catch (e: Exception) { rethrowControlFailure(e);
                Log.w("DirectCDN", "playlist.php discovery for $contentId: ${e.message}")
            }
        }

        // 2. Fallback to master HLS call (with OTT prefix!)
        val token = masterTokenFor(contentId)
        val hlsPrefix = if (prefix == "/mobile") "/mobile" else prefix
        val url = "https://$domain$hlsPrefix/hls/$contentId.m3u8?in=$token&hd=off&lang=eng&hp=yes"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://$domain/")
            .header("Cookie", cookie)
        val res = client.fetch(reqBuilder.build())
        val body = res.body
        val code = res.code

        Log.d("DirectCDN", "discoverHost HTTP $code response for $contentId (${body.length} bytes)")
        if (body.contains("Video ID Missing") || !body.contains("#EXTM3U") || body.contains("in=unknown")) {
            throw java.io.IOException("Playback manifest unavailable (HTTP $code)")
        }

        if (!body.contains("220884")) {
            putTransientCache(masterManifestCache, contentId, body, 12)
        }

        val freecdnUrl = CdnRoutePolicy.videoRoute(body)
            ?: throw java.io.IOException("No valid provider video route")
        val host = freecdnUrl.toHttpUrlOrNull()?.host
            ?: throw java.io.IOException("Invalid provider CDN host")
        if (StreamSessionPolicy.isWaitingVideo(freecdnUrl)) throw PlaybackRateLimitedException()

        val entry = RouteEntry(host, freecdnUrl, System.currentTimeMillis())
        saveRoutingEntry(contentId, entry)
        if (showId.isNotBlank()) showHostCache[showId] = host

        (routeNonce(freecdnUrl, entry.fetchedAt) ?: manifestNonce(body))?.let(::saveNonce)
        Log.d("DirectCDN", "✅ Discovered host for $contentId: $host")
        entry
    }

    // ---- TMDB info cache (delegated to existing code) ----

    private fun getTmdbInfoCached(tmdbId: String, type: String): TmdbInfo {
        val key = "${type}_$tmdbId"
        tmdbInfoCache[key]?.let { return it }
        // Fall through to existing resolver
        return TmdbInfo("", "")
    }

    // ---- Subtitle generator & parser ----

    private val COMMON_SUB_LANGUAGES = listOf(
        Triple("en", "English", "eng"),
        Triple("es", "Spanish", "spa"),
        Triple("fr", "French", "fre"),
        Triple("de", "German", "ger"),
        Triple("it", "Italian", "ita"),
        Triple("pt", "Portuguese", "por"),
        Triple("ar", "Arabic", "ara"),
        Triple("ru", "Russian", "rus"),
        Triple("ja", "Japanese", "jpn"),
        Triple("ko", "Korean", "kor"),
        Triple("hi", "Hindi", "hin"),
        Triple("tr", "Turkish", "tur"),
        Triple("nl", "Dutch", "dut"),
        Triple("pl", "Polish", "pol"),
        Triple("id", "Indonesian", "ind"),
        Triple("th", "Thai", "tha"),
        Triple("vi", "Vietnamese", "vie"),
        Triple("cs", "Czech", "ces"),
        Triple("el", "Greek", "ell"),
        Triple("da", "Danish", "dan"),
        Triple("fi", "Finnish", "fin"),
        Triple("he", "Hebrew", "heb"),
        Triple("hu", "Hungarian", "hun"),
        Triple("no", "Norwegian", "nor"),
        Triple("ro", "Romanian", "ron"),
        Triple("sv", "Swedish", "swe"),
        Triple("uk", "Ukrainian", "ukr"),
        Triple("zh", "Chinese", "zho")
    )

    private fun extractLanguageCode(label: String, url: String): String {
        val cleanUrl = url.lowercase()
        val cleanLabel = label.lowercase()
        for ((code2, name, code3) in COMMON_SUB_LANGUAGES) {
            if (cleanLabel.contains(name.lowercase())) return code2
            if (cleanUrl.contains("-$code2.") || cleanUrl.contains("-$code2[") || cleanUrl.contains("-$code2%5b") ||
                cleanUrl.contains("-$code3.") || cleanUrl.contains("-$code3[") || cleanUrl.contains("-$code3%5b")) {
                return code2
            }
        }
        val m = Regex("""-([a-z]{2,3})(?:\.|\b|\[|%5b)""", RegexOption.IGNORE_CASE).find(cleanUrl)
        return m?.groupValues?.get(1)?.lowercase() ?: ""
    }

    /**
     * Optional metadata lookup: Fetch tracks array from playlist.php if available.
     * Non-blocking and fails gracefully.
     */
    private suspend fun fetchPlaylistSubtitles(
        contentId: String,
        title: String,
        showId: String = "",
        ott: String = "nf",
        maxCandidates: Int = Int.MAX_VALUE
    ): List<Caption> = withContext(Dispatchers.IO) {
        val cached = contentSubtitlesCache[contentId]
        if (cached != null) return@withContext cached

        try {
            val session = ensureSession()
            val domain = session.domain
            val prefix = ottPathPrefix(ott)
            val ts = System.currentTimeMillis() / 1000
            val q = URLEncoder.encode(title.ifBlank { "video" }, "UTF-8").replace("+", "%20")
            val encId = URLEncoder.encode(contentId, "UTF-8").replace("+", "%20")

            val cookie = buildString {
                append(session.cookieHeader)
                if (showId.isNotBlank()) append("; SE$showId=$contentId")
                if (ott != "nf") append("; ott=$ott")
                append("; lang=eng")
            }

            val candidateUrls = if (prefix == "/mobile") {
                listOf(
                    "https://$domain/mobile/playlist.php?id=$encId&t=$q&tm=$ts",
                    "https://$domain/playlist.php?id=$encId&t=$q&tm=$ts"
                )
            } else {
                listOf(
                    "https://$domain$prefix/playlist.php?id=$encId&t=$q&tm=$ts",
                    "https://$domain/mobile/playlist.php?id=$encId&t=$q&tm=$ts",
                    "https://$domain/playlist.php?id=$encId&t=$q&tm=$ts"
                )
            }

            for (url in candidateUrls.take(maxCandidates)) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", MOBILE_UA)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://$domain/mobile/home?app=1")
                        .header("Cookie", cookie)
                        .build()
                    val res = metadataClient.fetch(req)
                    val bodyText = res.body

                    if (bodyText.isBlank()) continue
                    val parsed = if (bodyText.trim().startsWith("[")) JSONArray(bodyText).optJSONObject(0) else JSONObject(bodyText)
                    val tracks = parsed?.optJSONArray("tracks") ?: parsed?.optJSONArray("captions") ?: parsed?.optJSONArray("subtitles")
                    if (parsed != null && (parsed.has("sources") || tracks != null)) {
                        // A valid empty caption list is a result, not permission to repeat every endpoint.
                        contentSubtitlesCache[contentId] = emptyList()
                        if (tracks == null || tracks.length() == 0) return@withContext emptyList()
                    }
                    if (tracks == null) continue
                    val captions = mutableListOf<Caption>()
                    for (i in 0 until tracks.length()) {
                        val t = tracks.optJSONObject(i) ?: continue
                        val kind = t.optString("kind").lowercase()
                        val rawFile = t.optString("file").ifBlank { t.optString("src") }.ifBlank { t.optString("url") }
                        if (rawFile.isNotBlank() && (kind.contains("sub") || kind.contains("cap") || kind == "vtt" || kind.isEmpty())) {
                            var fileUrl = rawFile
                            if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                            else if (fileUrl.startsWith("/")) fileUrl = "https://$domain$fileUrl"
                            val label = t.optString("label").ifBlank { t.optString("language") }.ifBlank { t.optString("name") }.ifBlank { "English" }
                            val langCode = t.optString("language").ifBlank { t.optString("srclang") }.ifBlank { t.optString("lang") }
                                .ifBlank { extractLanguageCode(label, fileUrl) }
                            captions.add(
                                Caption(
                                    url = fileUrl,
                                    language = label,
                                    type = if (fileUrl.contains(".srt", ignoreCase = true)) "srt" else "vtt",
                                    languageCode = langCode
                                )
                            )
                        }
                    }
                    if (captions.isNotEmpty()) {
                        contentSubtitlesCache[contentId] = captions
                        Log.d("DirectCDN", "✅ Fetched ${captions.size} subtitle tracks from playlist.php for content $contentId")
                        return@withContext captions
                    }
                } catch (e: Exception) { rethrowControlFailure(e);
                    Log.w("DirectCDN", "fetchPlaylistSubtitles candidate failed for $contentId: ${e.message}")
                }
            }
            emptyList()
        } catch (error: Exception) { rethrowControlFailure(error);
            emptyList()
        }
    }

    /**
     * Generate direct unauthenticated subtitles from subscdn.top:
     * Pattern A (Standard): https://subscdn.top/files/{contentId}/{contentId}-{lang}.srt
     * Pattern B (OTT/Mirror): https://subscdn.top/hssubs/hs-back01.nfmirrorcdn.top/files/{contentId}/{lang3}.srt
     */
    private fun generateDirectSubtitles(contentId: String): List<Caption> {
        val result = mutableListOf<Caption>()
        for ((lang2, langName, lang3) in COMMON_SUB_LANGUAGES) {
            // Pattern A: Standard Netflix / general library pattern
            result.add(
                Caption(
                    url = "https://subscdn.top/files/$contentId/$contentId-$lang2.srt",
                    language = langName,
                    type = "srt",
                    languageCode = lang2,
                    isVerified = false
                )
            )
            // Closed Captions variant for English
            if (lang2 == "en") {
                result.add(
                    Caption(
                        url = "https://subscdn.top/files/$contentId/$contentId-en.%5BCC%5D.srt",
                        language = "English [CC]",
                        type = "srt",
                        languageCode = "en-cc",
                        isVerified = false
                    )
                )
            }
        }
        return result
    }

    /**
     * Parse `#EXT-X-MEDIA TYPE=SUBTITLES` lines from a master m3u8 manifest.
     * Each line has `URI="..."`, `LANGUAGE="..."`, `NAME="..."`. Returns a list
     * of `Caption` objects compatible with `NetMirrorStream.captions`.
     */
    private fun parseCaptionsFromManifest(manifestBody: String, baseUrl: String = ""): List<Caption> {
        val captions = mutableListOf<Caption>()
        for (rawLine in manifestBody.split("\n")) {
            val line = rawLine.trim()
            if (!line.contains("TYPE=SUBTITLES")) continue
            var uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: continue
            if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
                if (uri.startsWith("//")) {
                    uri = "https:$uri"
                } else if (baseUrl.isNotBlank()) {
                    val base = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast("/") + "/"
                    uri = if (uri.startsWith("/")) {
                        val host = Regex("""https?://[^/]+""").find(baseUrl)?.value ?: ""
                        "$host$uri"
                    } else {
                        "$base$uri"
                    }
                }
            }
            val languageCode = Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""
            val name = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1) ?: languageCode
            captions.add(
                Caption(
                    url = uri,
                    language = name,
                    type = if (uri.contains(".srt", ignoreCase = true)) "srt" else "vtt",
                    languageCode = languageCode
                )
            )
        }
        return captions
    }

    // ---- search across OTT catalogs for new titles (cached by tmdbId) ----

    private suspend fun searchContentId(title: String, year: String): SearchResult? = withContext(Dispatchers.IO) {
        searchAcrossOtts(title, year)
    }

    // ---- Manifest fetching ----

    data class DirectCDNStream(
        val url: String,
        val headers: Map<String, String>,
        val captions: List<Caption>,
        val sourceId: String,
        val expiresAt: Long,
        val title: String,
        val rawVideoUrl: String = url
    )

    private suspend fun fetchManifest(
        host: String,
        contentId: String,
        nonce: Nonce,
        providerUrl: String,
        isRetry: Boolean = false,
        ott: String = "nf",
        title: String = "",
        showId: String = "",
        purpose: StreamPurpose = StreamPurpose.PLAYBACK
    ): DirectCDNStream = withContext(Dispatchers.IO) {
        val parsedUrl = providerUrl.toHttpUrlOrNull()
            ?: throw java.io.IOException("Invalid provider media route")
        if (!parsedUrl.isHttps || parsedUrl.host != host) throw java.io.IOException("Invalid provider media route")
        val inToken = parsedUrl.queryParameter("in").orEmpty()
        if (inToken.startsWith("unknown")) throw CdnRouteRejectedException()
        val videoUrl = providerUrl
        Log.d("DirectCDN", "📡 Fetching manifest for $contentId")
        val req = Request.Builder()
            .url(videoUrl)
            .header("User-Agent", MOBILE_UA)
            .header("Origin", "https://$activeDomain")
            .header("Referer", "https://$activeDomain/")
            .header("X-Requested-With", "app.netmirror.netmirrornew")
            .build()
        val res = client.fetch(req)
        val body = res.body

        // Detect CDN rejection: 44-byte body, no #EXTM3U, or sneaky rate-limit video
        val isRejected = !body.contains("#EXTM3U")
        val isRateLimitVideo = body.contains("#EXTM3U") && body.length < 500 && body.contains("220884")
        if (isRejected || isRateLimitVideo) {
            if (isRateLimitVideo) {
                Log.w("DirectCDN", "⚠️ Rate-limit video detected (${body.length}B). Throwing rate limit.")
                throw PlaybackRateLimitedException()
            }
            Log.w("DirectCDN", "⚠️ FreeCDN manifest rejected (HTTP ${res.code}, bytes: ${body.length}). Invalidating route & nonce...")
            // Invalidate route and nonce so we don't reuse a bad or stale host
            masterManifestCache.remove(contentId)
            synchronized(sessionStateLock) {
                val currentRoutes = loadRoutingTable().toMutableMap()
                currentRoutes.remove(contentId)
                val json = JSONObject()
                currentRoutes.forEach { (k, v) ->
                    json.put(k, JSONObject().apply {
                        put("host", v.host)
                        put("freecdnUrl", v.freecdnUrl)
                        put("fetchedAt", v.fetchedAt)
                    })
                }
                getPrefs().edit().putString(ROUTING_KEY, json.toString()).apply()
            }

            if (!isRetry) {
                val freshRoute = discoverHost(contentId, title, showId, ott)
                val freshNonce = nonceForRoute(freshRoute)
                return@withContext fetchManifest(freshRoute.host, contentId, freshNonce, freshRoute.freecdnUrl, isRetry = true, ott = ott, title = title, showId = showId, purpose = purpose)
            }
            throw Exception("DirectCDN manifest invalid (${body.length}B, HTTP ${res.code})")
        }
        val headers = mapOf(
            "User-Agent" to MOBILE_UA,
            "Origin" to "https://$activeDomain",
            "Referer" to "https://$activeDomain/",
            "X-Requested-With" to "app.netmirror.netmirrornew"
        )
        val parsedCaptions = parseCaptionsFromManifest(body, videoUrl.substringBeforeLast("/") + "/")
        val cachedCaptionLookup = contentSubtitlesCache[contentId]
        val cachedTracks = cachedCaptionLookup ?: emptyList()
        val previewOnly = purpose != StreamPurpose.PLAYBACK
        val playlistCaptions = if (cachedCaptionLookup == null && (!previewOnly || parsedCaptions.isEmpty())) {
            fetchPlaylistSubtitles(contentId, title, showId, ott, maxCandidates = if (previewOnly) 1 else Int.MAX_VALUE)
        } else emptyList()

        if (purpose == StreamPurpose.SILENT_PREVIEW) {
            // A muted row needs no audio master, audio HEAD probes, or list of
            // speculative subtitle URLs. Reuse verified tracks from discovery.
            val captions = (cachedTracks + playlistCaptions + parsedCaptions)
                .filter { it.languageCode.equals("en", true) || it.languageCode.equals("eng", true) || it.language.startsWith("English", true) }
                .map { it.copy(url = it.url.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")) }
                .distinctBy { it.url }
            return@withContext DirectCDNStream(
                url = videoUrl, headers = headers, captions = captions, sourceId = "DirectCDN",
                expiresAt = StreamSessionPolicy.tokenIssuedAt(nonce.ts, nonce.fetchedAt) + NONCE_TTL_MS,
                title = title, rawVideoUrl = videoUrl
            )
        }

        // Construct master multivariant playlist with all real audio tracks from net52
        var masterBody = masterManifestCache[contentId]?.takeIf { CdnRoutePolicy.manifestTokensAreFresh(it, System.currentTimeMillis()) }
        // A valid video-only master is complete metadata. Refetching it and
        // guessing a legacy audio path adds two requests without discovering
        // audio the provider actually declared.
        if (masterBody.isNullOrBlank()) {
            try {
                val session = ensureSession()
                val domain = session.domain
                val prefix = ottPathPrefix(ott)
                val hlsPrefix = if (prefix == "/mobile") "/mobile" else prefix
                val masterToken = masterTokenFor(contentId)
                val masterUrl = "https://$domain$hlsPrefix/hls/$contentId.m3u8?in=$masterToken&hd=off&lang=eng&hp=yes"
                val cookie = buildString {
                    append(session.cookieHeader)
                    if (showId.isNotBlank()) append("; SE$showId=$contentId")
                    if (ott != "nf") append("; ott=$ott")
                    append("; lang=eng")
                }
                val req = Request.Builder()
                    .url(masterUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://$domain/")
                    .header("Cookie", cookie)
                    .build()
                val res = metadataClient.fetch(req)
                val bodyText = res.body

                if (bodyText.trimStart().startsWith("#EXTM3U")) {
                    if (!CdnRoutePolicy.manifestTokensAreFresh(bodyText, System.currentTimeMillis()))
                        throw CdnRouteRejectedException()
                    masterBody = bodyText
                    putTransientCache(masterManifestCache, contentId, bodyText, 12)
                    Log.d("DirectCDN", "✅ Fetched provider master HLS metadata for $contentId")
                }
            } catch (e: Exception) { rethrowControlFailure(e);
                Log.w("DirectCDN", "Could not fetch master HLS from net52 for $contentId: ${e.message}")
            }
        }

        val masterCaptions = if (!masterBody.isNullOrBlank() && masterBody.contains("TYPE=SUBTITLES")) {
            try {
                val session = ensureSession()
                val domain = session.domain
                val prefix = ottPathPrefix(ott)
                val hlsPrefix = if (prefix == "/mobile") "/mobile" else prefix
                parseCaptionsFromManifest(masterBody, "https://$domain$hlsPrefix/")
            } catch (error: Exception) { rethrowControlFailure(error);
                emptyList()
            }
        } else emptyList()

        val directSubtitles = if (previewOnly) emptyList() else generateDirectSubtitles(contentId)
        val combinedCaptions = (cachedTracks + playlistCaptions + masterCaptions + parsedCaptions + directSubtitles)
            .map { it.copy(url = it.url.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")) }
            .distinctBy { it.url }

        var cleanedMaster: String? = null
        if (masterBody != null) PlaybackServiceGate.checkResponse(200, masterBody, null, videoUrl)
        if (masterBody != null && masterBody.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
            // Reuse the token just validated by the video request, including its original expiry.
            val effectiveToken = inToken

            var processed = masterBody

            // Ensure EVERY audio URI in masterBody points to the real host and has effectiveToken attached
            processed = processed.replace(Regex("""(#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*URI=")([^"]+)(")""")) { mr ->
                val prefix = mr.groupValues[1]
                val uri = mr.groupValues[2]
                val suffix = mr.groupValues[3]
                val fixedUri = makeAbsoluteCdnUrl(uri, host, contentId, effectiveToken, videoUrl)
                "$prefix$fixedUri$suffix"
            }

            // Ensure English audio track is DEFAULT=YES and AUTOSELECT=YES, others DEFAULT=NO
            if (processed.contains("TYPE=AUDIO")) {
                var englishSet = false
                processed = processed.replace(Regex("""(#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*)""")) { mr ->
                    val line = mr.groupValues[1]
                    val isEng = line.contains("LANGUAGE=\"eng\"", ignoreCase = true) ||
                            line.contains("LANGUAGE=\"en\"", ignoreCase = true) ||
                            line.contains("NAME=\"English\"", ignoreCase = true) ||
                            line.contains("1. English", ignoreCase = true) ||
                            line.contains("English", ignoreCase = true)

                    var updated = line
                    if (isEng && !englishSet) {
                        englishSet = true
                        updated = updated.replace("DEFAULT=NO", "DEFAULT=YES")
                        if (!updated.contains("DEFAULT=")) updated += ",DEFAULT=YES"
                        if (!updated.contains("AUTOSELECT=")) updated += ",AUTOSELECT=YES"
                    } else if (englishSet || !isEng) {
                        updated = updated.replace("DEFAULT=YES", "DEFAULT=NO")
                    }
                    updated
                }
            }

            // Ensure token on video URLs in masterBody is current and relative variant paths become absolute CDN URLs
            val lines = processed.lines()
            val sb = StringBuilder()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    sb.append(line).append("\n")
                    if (i + 1 < lines.size) {
                        i++
                        var variantLine = lines[i].trim()
                        if (variantLine.isEmpty()) throw java.io.IOException("Missing video variant")
                        PlaybackServiceGate.checkResponse(200, variantLine, null, videoUrl)
                        variantLine = makeAbsoluteCdnUrl(variantLine, host, contentId, effectiveToken, videoUrl)
                        sb.append(variantLine).append("\n")
                    }
                } else {
                    if (line.isNotEmpty()) {
                        sb.append(line).append("\n")
                    }
                }
                i++
            }

            // Signed audio URIs from the provider are authoritative. Some CDN
            // routes accept GET but reject HEAD; discarding the entire audio
            // group after probing its first (possibly non-default) track can
            // turn a valid video-only variant into silent playback.
            cleanedMaster = sb.toString()
        } else if (masterBody.isNullOrBlank()) {
            // Check if separate audio track exists on FreeCDN before synthesizing master playlist
            val candidateAudioUrl = "https://$host/files/$contentId/a/0/0.m3u8?in=$inToken"
            val hasSeparateAudio = checkAudioTrackExists(candidateAudioUrl)
            if (hasSeparateAudio) {
                val sb = StringBuilder()
                sb.append("#EXTM3U\n")
                sb.append("#EXT-X-VERSION:3\n")
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",LANGUAGE=\"eng\",NAME=\"English\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$candidateAudioUrl\"\n")
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=2500000,AUDIO=\"audio\",RESOLUTION=1280x720\n")
                sb.append("$videoUrl\n")
                cleanedMaster = sb.toString()
            } else {
                Log.d("DirectCDN", "No separate audio track found on CDN for $contentId, playing direct videoUrl with muxed audio")
                cleanedMaster = null
            }
        }

        var playUrl = videoUrl
        if (!cleanedMaster.isNullOrBlank()) {
            val masterFile = java.io.File(context.cacheDir, "master_${contentId}.m3u8")
            try {
                masterFile.writeText(cleanedMaster)
                if (masterFile.exists()) {
                    playUrl = android.net.Uri.fromFile(masterFile).toString()
                }
            } catch (e: Exception) { rethrowControlFailure(e);
                Log.w("DirectCDN", "Could not write master_${contentId}.m3u8: ${e.message}")
            }
        }

        val videoExpiry = StreamSessionPolicy.tokenIssuedAt(nonce.ts, nonce.fetchedAt) + NONCE_TTL_MS
        // Independent audio signatures can expire sooner than the video's.
        // A cached local master must refresh before its earliest signed track.
        val playbackExpiry = cleanedMaster?.let { CdnRoutePolicy.earliestManifestExpiry(it, nonce.fetchedAt) }
            ?.let { minOf(videoExpiry, it) } ?: videoExpiry
        DirectCDNStream(
            url = playUrl,
            headers = headers,
            captions = combinedCaptions,
            sourceId = "DirectCDN",
            expiresAt = playbackExpiry,
            title = "",
            rawVideoUrl = videoUrl
        )
    }

    private suspend fun checkAudioTrackExists(audioUrl: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(audioUrl)
                .head()
                .header("User-Agent", MOBILE_UA)
                .header("Origin", "https://$activeDomain")
                .header("Referer", "https://$activeDomain/")
                .header("X-Requested-With", "app.netmirror.netmirrornew")
                .build()
            val res = metadataClient.fetch(req)
            val ok = res.isSuccessful

            ok
        } catch (_: CdnRouteRejectedException) {
            false
        } catch (error: Exception) { rethrowControlFailure(error);
            false
        }
    }

    private fun makeAbsoluteCdnUrl(raw: String, host: String, contentId: String, token: String, mediaBaseUrl: String): String {
        var url = raw.trim()

        // Fix 3 slashes or missing host: https:///
        if (url.startsWith("https:///")) {
            url = url.substring("https:///".length)
            if (!url.startsWith("files/")) {
                url = if (url.startsWith("/")) "files/$contentId$url" else "files/$contentId/$url"
            }
            url = "https://$host/$url"
        } else if (url.startsWith("http:///")) {
            url = url.substring("http:///".length)
            if (!url.startsWith("files/")) {
                url = if (url.startsWith("/")) "files/$contentId$url" else "files/$contentId/$url"
            }
            url = "https://$host/$url"
        }

        // Fix relative or path-only URLs
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = when {
                url.startsWith("/files/") -> "https://$host$url"
                url.startsWith("files/") -> "https://$host/$url"
                url.startsWith("/a/") -> "https://$host/files/$contentId$url"
                url.startsWith("a/") -> "https://$host/files/$contentId/$url"
                url.startsWith("/720p/") || url.startsWith("/1080p/") || url.startsWith("/480p/") -> "https://$host/files/$contentId$url"
                url.startsWith("720p/") || url.startsWith("1080p/") || url.startsWith("480p/") -> "https://$host/files/$contentId/$url"
                url.startsWith("/") -> "https://$host$url"
                else -> mediaBaseUrl.toHttpUrlOrNull()?.resolve(url)?.toString() ?: throw java.io.IOException("Invalid relative media route")
            }
        }

        // Ensure host is correct if it points to a blank or malformed domain
        if (url.contains("https:///")) {
            url = url.replace("https:///", "https://$host/")
        }

        // A provider-issued audio/variant URI may have its own independent signature.
        val existing = url.toHttpUrlOrNull()?.queryParameter("in")
        return if (token.isBlank() || (!existing.isNullOrBlank() && !existing.startsWith("unknown"))) url
        else CdnRoutePolicy.playbackUrl(url, url.toHttpUrlOrNull()?.host ?: host, token)
    }

    // ---- Public API ----

    suspend fun resolveStream(movie: Movie, season: Int = 0, episode: Int = 0, purpose: StreamPurpose = StreamPurpose.PLAYBACK): NetMirrorStream =
        withContext(if (purpose == StreamPurpose.PLAYBACK) kotlin.coroutines.EmptyCoroutineContext else PreviewResolutionContext()) {
            resolveStreamForPurpose(movie, season, episode, purpose)
        }

    private suspend fun resolveStreamForPurpose(movie: Movie, season: Int, episode: Int, purpose: StreamPurpose): NetMirrorStream = withTimeoutOrNull(45_000L) {
        checkPlaybackCooldown()
        val cachedGeneration = sessionGeneration.get()
        if (cachedSourceRevision != PlaybackServiceGate.sourceRevision) {
            streamCache.clear()
            cachedSourceRevision = PlaybackServiceGate.sourceRevision
        }
        val type = movie.catalogMediaKind()
        val key = "${type}_${movie.id}_${season}_${episode}"
        streamCache[key]?.takeIf { it.sessionVersion == cachedGeneration && it.expiresAt - System.currentTimeMillis() > StreamSessionPolicy.EXPIRY_MARGIN_MS }?.let { return@withTimeoutOrNull it }
        val info = getTmdbInfo(movie.id, type, movie.title, movie.year)
        val source = publicPlayback.resolve(info.title, info.year, type, season, episode, movie.id)
        val generation = sessionGeneration.get()
        val stream = NetMirrorStream(source.url, source.headers, source.captions, "Public HLS [${source.ott.uppercase()}]", source.expiresAt, info.title, sessionVersion = generation)
        currentCoroutineContext().ensureActive()
        synchronized(sessionStateLock) {
            if (generation != sessionGeneration.get()) throw SessionChangedException()
            putTransientCache(streamCache, key, stream, 32)
        }
        stream
    } ?: throw java.io.IOException("Playback resolution timed out")

    // Legacy session implementation retained for migration diagnostics; playback uses publicPlayback.
    private suspend fun resolveStreamOnce(movie: Movie, season: Int, episode: Int, purpose: StreamPurpose): NetMirrorStream = withContext(Dispatchers.IO) {
        checkPlaybackCooldown()
        if (cachedSourceRevision != PlaybackServiceGate.sourceRevision) {
            streamCache.clear()
            masterManifestCache.clear()
            cachedSourceRevision = PlaybackServiceGate.sourceRevision
        }
        // Expired cookie state invalidates any dependent in-memory manifests.
        val hasSession = hasValidSession()
        val tmdbId = movie.id
        val type = movie.catalogMediaKind()
        val playbackKey = "${type}_${tmdbId}_${season}_${episode}"
        if (hasSession && purpose != StreamPurpose.PLAYBACK) streamCache[playbackKey]?.let { cached ->
            if (cached.expiresAt - System.currentTimeMillis() > StreamSessionPolicy.EXPIRY_MARGIN_MS) return@withContext cached
        }
        val cacheKey = if (purpose == StreamPurpose.PLAYBACK) playbackKey else "${purpose.name}_$playbackKey"
        if (hasSession) streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAt - System.currentTimeMillis() > StreamSessionPolicy.EXPIRY_MARGIN_MS) {
                Log.d("DirectCDN", "💾 Cached stream for $cacheKey")
                com.example.ui.util.AppDiagnosticsLogger.event("Stream", "💾 Stream cache hit for $cacheKey (valid for ${(cached.expiresAt - System.currentTimeMillis()) / 1000}s)")
                return@withContext cached
            }
        }

        // 1. Resolve TMDB -> title. Fall back to movie.title/year if TMDB call fails
        val tmdbInfo = getTmdbInfo(tmdbId, type, movie.title, movie.year)
        val effectiveTitle = tmdbInfo.title.ifBlank { movie.title }
        val effectiveYear = tmdbInfo.year.ifBlank { movie.year }
        if (effectiveTitle.isBlank()) {
            throw Exception("DirectCDN: No title for movie $tmdbId (TMDB and Movie.title both blank)")
        }

        // 2. Resolve title -> SearchResult (multi-OTT search, cached by titleKey)
        val titleKey = "${type}_${tmdbId}"
        val cachedSearch = contentIdCache[titleKey]
        val searchResult = cachedSearch ?: run {
            val res = searchContentId(effectiveTitle, effectiveYear)
            if (res == null) {
                // Empty searches can also be how a revoked cookie is reported.
                // Public Home reachability cannot validate a playback session.
                val session = ensureSession()
                verifyStoredSession(session, renewOnAmbiguity = true)
                throw Exception("DirectCDN: No net52 search results across OTTs for \"$effectiveTitle\" ($effectiveYear)")
            }
            contentIdCache[titleKey] = res
            com.example.ui.util.AppDiagnosticsLogger.event("Stream", "🔍 Search resolved for \"$effectiveTitle\" -> id=${res.id}, ott=${res.ott.uppercase()}")
            res
        }

        val showId = searchResult.id
        val ott = searchResult.ott
        var contentId = showId

        // 2b. For TV shows, resolve (showId, season, episode) -> contentId
        if (type == "tv" && season > 0 && episode > 0) {
            val episodes = fetchAndCacheEpisodeList(showId, effectiveTitle, ott, targetSeason = season, targetEpisode = episode)
            val match = episodes.find { it.first == season && it.second == episode }
            if (match != null) {
                contentId = match.third
                Log.d("DirectCDN", "🎯 Found episode contentId: $contentId for S${season}E${episode} [${ott.uppercase()}]")
                com.example.ui.util.AppDiagnosticsLogger.event("Stream", "📺 Found TV episode contentId: $contentId for S${season}E${episode}")
            } else {
                throw java.io.IOException("Requested episode S${season}E${episode} is unavailable")
            }
        }

        // 3. Resolve contentId -> host (routing table, 1 call, cached)
        val route = discoverHost(contentId, effectiveTitle, showId, ott)
        com.example.ui.util.AppDiagnosticsLogger.event("Stream", "🌐 Route resolved host: ${route.host} (contentId=$contentId)")

        // 4. Ensure nonce is fresh (1 call per 10h)
        val nonce = nonceForRoute(route)

        // 5. Fetch manifest from freecdn directly
        val manifest = fetchManifest(route.host, contentId, nonce, route.freecdnUrl, ott = ott, title = effectiveTitle, showId = showId, purpose = purpose)

        val result = NetMirrorStream(
            url = manifest.url,
            headers = manifest.headers,
            captions = manifest.captions,
            sourceId = "DirectCDN",
            expiresAt = manifest.expiresAt,
            title = effectiveTitle,
            rawVideoUrl = manifest.rawVideoUrl
        )
        Log.d("DirectCDN", "✅ Resolved [${ott.uppercase()}] content $contentId")
        result
    }

    fun evictCachedStream(tmdbId: String, type: String, season: Int = 0, episode: Int = 0) {
        publicPlayback.evict(tmdbId, type, season, episode)
        val key = "${type}_${tmdbId}_${season}_${episode}"
        streamCache.remove(key)
        streamCache.remove("${StreamPurpose.HERO_PREVIEW.name}_$key")
        streamCache.remove("${StreamPurpose.SILENT_PREVIEW.name}_$key")
    }

    fun invalidateStream(tmdbId: String, type: String, season: Int = 0, episode: Int = 0) {
        synchronized(sessionStateLock) {
            val search = contentIdCache["${type}_$tmdbId"]
            val contentId = if (type == "tv" && season > 0 && episode > 0) {
                search?.let { episodeListCache[it.id]?.find { ep -> ep.first == season && ep.second == episode }?.third }
            } else search?.id
            evictCachedStream(tmdbId, type, season, episode)
            // A failed title must not evict every other title's valid CDN route.
            contentId?.let {
                masterManifestCache.remove(it)
                contentSubtitlesCache.remove(it)
                val routes = try { JSONObject(getPrefs().getString(ROUTING_KEY, "{}") ?: "{}") }
                    catch (_: org.json.JSONException) { JSONObject() }
                routes.remove(it)
                getPrefs().edit().putString(ROUTING_KEY, routes.toString()).apply()
            }
        }
    }

    fun invalidateSessionByCookie(cookieString: String?) {
        synchronized(sessionStateLock) {
            val rejectedHash = StreamSessionPolicy.providerCookieHash(cookieString) ?: return
            val pool = loadStoredSessions()
            // Ignore a delayed failure from a cookie that has already been replaced.
            if (pool.isNotEmpty() && pool.none { it.tHashTEncoded == rejectedHash }) return
            clearSessionState()
        }
    }


    private fun clearSessionState() = synchronized(sessionStateLock) {
        sessionGeneration.incrementAndGet()
        lastSuccessfulVerifyAtMs = 0L
        cookieJar.clear()
        streamCache.clear()
        contentIdCache.clear()
        episodeListCache.clear()
        showHostCache.clear()
        masterManifestCache.clear()
        contentSubtitlesCache.clear()
        getPrefs().edit()
            .putString("directcdn_sessions", "[]")
            .remove("directcdn_session")
            .remove(NONCE_KEY)
            .remove(ROUTING_KEY)
            .remove("last_verify_at_ms")
            .apply()
    }

    suspend fun resolveStream(tmdbId: String, type: String, season: Int = 0, episode: Int = 0): NetMirrorStream = withContext(Dispatchers.IO) {
        val tmdbInfo = getTmdbInfo(tmdbId, type)
        val dummyMovie = Movie(
            id = tmdbId,
            title = tmdbInfo.title,
            description = "",
            backdropUrl = "",
            posterUrl = "",
            rating = "",
            year = tmdbInfo.year,
            type = if (type == "tv") "Series" else "Movie",
            duration = ""
        )
        resolveStream(dummyMovie, season, episode)
    }

    // ---- TMDB info ----

    private suspend fun getTmdbInfo(tmdbId: String, type: String, fallbackTitle: String = "", fallbackYear: String = ""): TmdbInfo {
        val key = "${type}_$tmdbId"
        tmdbInfoCache[key]?.let { cached ->
            if (cached.title.isNotBlank()) return cached
        }
        // Cards already contain the TMDB title/year; don't repeat that network call on Play.
        if (fallbackTitle.isNotBlank() && fallbackYear.matches(Regex("[0-9]{4}")) && fallbackTitle.any { it in 'A'..'Z' || it in 'a'..'z' })
            return TmdbInfo(fallbackTitle, fallbackYear)
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) {
            val info = TmdbInfo(fallbackTitle, fallbackYear)
            if (fallbackTitle.isNotBlank()) tmdbInfoCache[key] = info
            return info
        }
        return try {
            val url = "https://api.themoviedb.org/3/$type/$tmdbId?api_key=$apiKey"
            val req = Request.Builder().url(url).build()
            val res = metadataClient.fetch(req)
            val body = res.body

            val json = JSONObject(body)
            val title = json.optString("title").ifEmpty { json.optString("name") }.ifEmpty { fallbackTitle }
            val dateStr = json.optString("release_date").ifEmpty { json.optString("first_air_date") }
            val year = dateStr.split("-").firstOrNull()?.ifEmpty { fallbackYear } ?: fallbackYear
            val info = TmdbInfo(title, year)
            if (title.isNotBlank()) tmdbInfoCache[key] = info
            info
        } catch (e: Exception) { rethrowControlFailure(e);
            Log.w("DirectCDN", "TMDB lookup failed: ${e.message}")
            TmdbInfo(fallbackTitle, fallbackYear)
        }
    }

    suspend fun fetchSubtitlesForEpisode(movie: Movie, season: Int, episode: Int): List<Caption> =
        resolveStream(movie, season, episode).captions

}
