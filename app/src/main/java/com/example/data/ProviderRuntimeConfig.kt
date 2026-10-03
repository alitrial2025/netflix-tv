package com.example.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Optional public request settings. A resolution uses one snapshot; issued media stays intact. */
internal class ProviderRuntimeConfig(context: Context, client: OkHttpClient,
    preferencesName: String = "public_playback_config",
    private val now: () -> Long = System::currentTimeMillis) {
    data class Snapshot(val baseUrl: String = DEFAULT_BASE, val masterHash: String = DEFAULT_HASH,
        val masterMode: String = "ek") {
        fun bootstrapDomains(fallbacks: List<String>): List<String> {
            val preferred = (validBase(baseUrl) ?: DEFAULT_BASE).toHttpUrlOrNull()!!.host
            return (listOf(preferred) + fallbacks.filter { validBase("https://$it") != null }).distinct()
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val queued = AtomicBoolean(false)
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).build()

    fun snapshot(): Snapshot {
        val saved = prefs.all
        return Snapshot(validBase(saved["remote_provider_base_url"] as? String) ?: DEFAULT_BASE,
            (saved["remote_master_hash1"] as? String)?.takeIf { HASH.matches(it) } ?: DEFAULT_HASH,
            (saved["remote_master_mode"] as? String)?.takeIf { MODE.matches(it) } ?: "ek")
    }

    fun refreshInBackground() {
        if (!queued.compareAndSet(false, true)) return
        scope.launch { try { refresh() } finally { queued.set(false) } }
    }

    /** Success refreshes every six hours; failed requests back off for thirty minutes. */
    suspend fun refresh() = withTimeoutOrNull(4_000L) {
        mutex.withLock {
            val timestamp = now()
            val attempted = prefs.getLong("public_config_attempted_at", 0L)
            val succeeded = prefs.getLong("public_config_succeeded_at", 0L)
            if (attempted > 0 && timestamp - attempted in 0 until RETRY_MS ||
                succeeded > 0 && timestamp - succeeded in 0 until REFRESH_MS) return@withLock
            prefs.edit().putLong("public_config_attempted_at", timestamp).apply()
            try {
                val response = http.fetchText(Request.Builder().url(URL)
                    .header("Accept", "application/json").build(), 64L * 1024)
                if (!response.isSuccessful) return@withLock
                val json = JSONObject(response.body)
                val updated = parse(json, snapshot())
                val editor = prefs.edit().putString("remote_provider_base_url", updated.baseUrl)
                    .putString("remote_master_hash1", updated.masterHash)
                    .putString("remote_master_mode", updated.masterMode)
                    .putString("remote_config_cache", json.toString())
                    .putLong("public_config_succeeded_at", timestamp)
                json.optString("quryParam").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
                    ?.let { editor.putString("remote_qury_param", it) }
                editor.apply()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { }
            catch (_: org.json.JSONException) { }
        }
    }

    companion object {
        internal const val DEFAULT_BASE = "https://net52.cc"
        internal const val DEFAULT_HASH = "235ca31540ab8d90fcef4a00de8a247c"
        internal const val URL = "https://npro-app.vercel.app/updates/streaming.json"
        private const val RETRY_MS = 30 * 60_000L
        private const val REFRESH_MS = 6 * 3_600_000L
        private val HASH = Regex("[a-fA-F0-9]{32}")
        private val MODE = Regex("[a-zA-Z0-9_-]{1,32}")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal fun sessionMatches(domain: String, requiredDomain: String?): Boolean =
            requiredDomain == null || domain == requiredDomain && validBase("https://$requiredDomain") != null

        internal fun validBase(value: String?): String? {
            val url = value?.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedPath != "/" || url.query != null || url.fragment != null ||
                !url.host.matches(Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) ||
                url.host.endsWith(".localhost") || url.host.endsWith(".local")) return null
            return url.toString().trimEnd('/')
        }

        internal fun parse(json: JSONObject, previous: Snapshot = Snapshot()): Snapshot {
            val hint = json.optJSONObject("tokenHint")
            return Snapshot(validBase(json.optString("providerBaseUrl")) ?: previous.baseUrl,
                hint?.optString("hash1")?.takeIf { HASH.matches(it) } ?: previous.masterHash,
                hint?.optString("masterMode")?.takeIf { MODE.matches(it) } ?: previous.masterMode)
        }
    }
}
