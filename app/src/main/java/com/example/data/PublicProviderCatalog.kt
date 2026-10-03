package com.example.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Catalog metadata only. Playback URLs, cookies and membership data are never persisted. */
internal class PublicProviderCatalog(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "verified_provider_identities.json"))
    private val feedFile = AtomicFile(File(app.filesDir, "public_provider_feed_v2.json"))
    private val refreshMutex = Mutex()
    private val refreshQueued = AtomicBoolean(false)
    @Volatile private var lastRefreshAttempt = 0L
    @Volatile private var liveFeed: Feed? = null
    // Resolver construction happens during UI startup; parse disk metadata on first IO lookup.
    private val persistedFeed: Feed? by lazy {
        try { feedFile.openRead().use { parseFeed(JSONObject(it.bufferedReader().readText()), System.currentTimeMillis()) } }
        catch (_: Exception) { null }
    }
    private val candidates by lazy {
        try {
            val root = JSONObject(app.assets.open("public-provider-catalog.json").bufferedReader().use { it.readText() })
            val rows = root.optJSONArray("rows") ?: JSONArray()
            val result = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val type = row.optString(0); val tmdb = row.optString(1)
                val ott = row.optString(2); val id = row.optString(3)
                if (type !in listOf("movie", "tv") || !tmdb.matches(TMDB_ID) || !valid(ott, id)) continue
                result.getOrPut("$type:$tmdb") { mutableListOf() }.add(id to ott)
            }
            result.mapValues { it.value.distinct() }
        } catch (_: Exception) { emptyMap() }
    }
    private val hotstarTitles by lazy {
        try {
            val root = JSONObject(app.assets.open("public-hotstar-catalog.json").bufferedReader().use { it.readText() })
            val rows = root.optJSONArray("rows") ?: JSONArray()
            val result = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val type = row.optString(0); val title = row.optString(1)
                val id = row.optString(2); val path = row.optString(3)
                if (type !in listOf("movie","tv") || title.isBlank() || !valid("hs",id) ||
                    !path.matches(PARTNER_PATH)) continue
                result.getOrPut("$type:$title") { mutableListOf() }.add(id to path)
            }
            result.mapValues { it.value.distinct() }
        } catch (_: Exception) { emptyMap() }
    }
    fun hotstarTitles(type: String, title: String): List<Pair<String, String>> {
        val normalized = java.text.Normalizer.normalize(title,java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"),"").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"),"")
        return (currentFeed()?.partner?.get("$type:$normalized").orEmpty() + hotstarTitles["$type:$normalized"].orEmpty()).distinct()
    }
    private val verified by lazy {
        try { file.openRead().use { JSONObject(it.bufferedReader().readText()) } }
        catch (_: Exception) { JSONObject() }
    }
    fun candidates(type: String, tmdb: String): List<Pair<String, String>> =
        (currentFeed()?.native?.get("$type:$tmdb").orEmpty() + candidates["$type:$tmdb"].orEmpty()).distinct()
    private fun currentFeed() = (liveFeed ?: persistedFeed)?.takeIf { System.currentTimeMillis() - it.generatedAt <= 14 * 86_400_000L }
    fun refreshInBackground(client: OkHttpClient, force: Boolean = false) {
        if (!refreshQueued.compareAndSet(false,true)) return
        refreshScope.launch { try { refresh(client,force) } finally { refreshQueued.set(false) } }
    }

    /** The feed contains identity candidates only. Live provider metadata remains authoritative. */
    suspend fun refresh(client: OkHttpClient, force: Boolean = false) = withTimeoutOrNull(8_000L) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (now - lastRefreshAttempt < 30 * 60_000L ||
                    currentFeed()?.let { now - it.fetchedAt < 6 * 3_600_000L } == true)) return@withLock
            lastRefreshAttempt = now
            try {
                val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
                    .followRedirects(false).followSslRedirects(false).build()
                val response = http.fetchText(Request.Builder().url(FEED_URL)
                    .header("Accept", "application/json").build(), 8L * 1024 * 1024)
                if (!response.isSuccessful) return@withLock
                val root = JSONObject(response.body)
                val parsed = parseFeed(root, now).copy(fetchedAt = now)
                root.put("fetchedAt", now)
                var output: java.io.FileOutputStream? = null
                try { output = feedFile.startWrite(); output.write(root.toString().toByteArray(Charsets.UTF_8)); feedFile.finishWrite(output) }
                catch (e: Exception) { output?.let(feedFile::failWrite); throw e }
                liveFeed = parsed
            } catch (_: IOException) { }
            catch (_: org.json.JSONException) { }
        }
    }
    @Synchronized fun verified(key: String, now: Long): Pair<String, String>? {
        val row = verified.optJSONObject(key) ?: return null
        val id = row.optString("id"); val ott = row.optString("ott")
        return (id to ott).takeIf { valid(ott,id) && row.optLong("expiresAt") > now }
    }
    @Synchronized fun save(key: String, id: String, ott: String, now: Long) {
        if (!valid(ott,id)) return
        if (verified.length() >= 20000) {
            val expired = verified.keys().asSequence().filter { (verified.optJSONObject(it)?.optLong("expiresAt", 0L) ?: 0L) <= now }.toList()
            expired.forEach(verified::remove)
            if (verified.length() >= 20000) verified.keys().asSequence().firstOrNull()?.let(verified::remove)
        }
        verified.put(key, JSONObject().put("id",id).put("ott",ott).put("expiresAt",now + 7 * 86_400_000L))
        persist()
    }
    @Synchronized fun evict(key: String) { if (verified.remove(key) != null) persist() }
    private fun persist() {
        var output: java.io.FileOutputStream? = null
        try { output = file.startWrite(); output.write(verified.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (_: Exception) { output?.let(file::failWrite) }
    }
    private fun valid(ott: String, id: String) = when (ott) {
        "nf", "hs" -> id.matches(NATIVE_ID)
        "pv" -> id.matches(PRIME_ID)
        else -> false
    }

    internal data class Feed(val generatedAt: Long, val fetchedAt: Long,
        val native: Map<String, List<Pair<String, String>>>, val partner: Map<String, List<Pair<String, String>>>)
    companion object {
        private const val PARTNER_SLUG = "(?:[a-z0-9-]|%[0-9A-Fa-f]{2})+"
        private val PARTNER_PATH = Regex("/(?:movies|tv-shows)/$PARTNER_SLUG/HOTSTAR_DTH_(?:MOVIE|TVSHOW)_[0-9]{5,20}")
        private val TMDB_ID = Regex("[1-9][0-9]*")
        private val NATIVE_ID = Regex("[0-9]{5,20}")
        private val PRIME_ID = Regex("[A-Z0-9]{10,30}")
        private val NORMALIZED_TITLE = Regex("[a-z0-9]+")
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        internal const val FEED_URL = "https://raw.githubusercontent.com/alitrial2025/netflix-tv/main/catalogs/provider-identities.json"
        internal fun parseFeed(root: JSONObject, now: Long): Feed {
            val generated = root.optLong("generatedAt")
            if (root.optInt("schemaVersion") != 2 || generated <= 0 || generated > now + 300_000L || now - generated > 14 * 86_400_000L)
                throw IOException("Invalid or stale provider feed")
            val nativeRows = root.optJSONArray("nativeRows") ?: throw IOException("Missing native identities")
            val partnerRows = root.optJSONArray("partnerRows") ?: throw IOException("Missing partner identities")
            if (nativeRows.length() !in 1000..200000 || partnerRows.length() !in 1000..100000) throw IOException("Incomplete provider feed")
            val native = mutableMapOf<String, MutableList<Pair<String, String>>>()
            val partner = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for (i in 0 until nativeRows.length()) {
                val row = nativeRows.optJSONArray(i) ?: throw IOException("Invalid native identity")
                val type = row.optString(0); val tmdb = row.optString(1); val ott = row.optString(2); val id = row.optString(3)
                if (type !in listOf("movie", "tv") || !tmdb.matches(TMDB_ID) ||
                    !(if (ott == "pv") id.matches(PRIME_ID) else ott in listOf("nf", "hs") && id.matches(NATIVE_ID)))
                    throw IOException("Invalid native identity")
                native.getOrPut("$type:$tmdb") { mutableListOf() }.add(id to ott)
            }
            for (i in 0 until partnerRows.length()) {
                val row = partnerRows.optJSONArray(i) ?: throw IOException("Invalid partner identity")
                val type = row.optString(0); val title = row.optString(1); val id = row.optString(2); val path = row.optString(3)
                val expected = if (type == "tv") "TVSHOW" else "MOVIE"
                if (type !in listOf("movie", "tv") || !title.matches(NORMALIZED_TITLE) || !id.matches(NATIVE_ID) ||
                    !path.matches(PARTNER_PATH) || !path.endsWith("/HOTSTAR_DTH_${expected}_$id") ||
                    (type == "tv") != path.startsWith("/tv-shows/")) throw IOException("Invalid partner identity")
                partner.getOrPut("$type:$title") { mutableListOf() }.add(id to path)
            }
            return Feed(generated, root.optLong("fetchedAt", 0L).coerceAtMost(now),
                native.mapValues { it.value.distinct().take(24) }, partner.mapValues { it.value.distinct().take(24) })
        }
    }
}
