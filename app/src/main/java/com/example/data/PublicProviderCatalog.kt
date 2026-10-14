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
    internal val episodeCatalog by lazy { ProviderEpisodeCatalog(app) }
    private val file = AtomicFile(File(app.filesDir, "verified_provider_identities_v3.json"))
    private val discoveryFile = AtomicFile(File(app.filesDir, "discovered_provider_identities.json"))
    private val feedFile = AtomicFile(File(app.filesDir, "public_provider_feed_v2.json"))
    private val refreshMutex = Mutex()
    private val refreshQueued = AtomicBoolean(false)
    @Volatile private var lastRefreshAttempt = 0L
    @Volatile private var liveFeed: Feed? = null
    // Resolver construction happens during UI startup; parse disk metadata on first IO lookup.
    private val persistedFeed: Feed? by lazy {
        try { feedFile.openRead().use { parseFeed(JSONObject(it.bufferedReader().readText()), System.currentTimeMillis(), allowStale = true) } }
        catch (_: Exception) { null }
    }
    private val routeSeeds by lazy {
        try { app.assets.open("public-provider-route-seeds.json").bufferedReader().use {
            JSONObject(it.readText()).takeIf { root -> root.optInt("schemaVersion") == 1 }
        } ?: JSONObject() } catch (_: Exception) { JSONObject() }
    }
    private fun routeCandidates(type: String, tmdb: String): List<Pair<String, String>> {
        val rows = routeSeeds.optJSONArray("rows") ?: return emptyList()
        return (0 until minOf(rows.length(), 1000)).mapNotNull { index ->
            val row = rows.optJSONArray(index) ?: return@mapNotNull null
            val ott = row.optString(2); val id = row.optString(3)
            (id to ott).takeIf { row.optString(0) == type && row.optString(1) == tmdb && valid(ott, id) }
        }.distinct()
    }
    /** Previously verified metadata is a candidate; the live episode endpoint confirms it. */
    fun seasonCandidates(origin: String, ott: String, showId: String, titles: List<String>): Map<Int, String> {
        val base = ProviderRuntimeConfig.validBase(origin) ?: return emptyMap()
        val rows = routeSeeds.optJSONArray("seasonRows") ?: return emptyMap()
        val result = linkedMapOf<Int, String>()
        for (index in 0 until minOf(rows.length(), 3600)) {
            val row = rows.optJSONArray(index) ?: continue
            if (row.optString(0) != base || row.optString(1) != ott || row.optString(2) != showId ||
                titles.none { PublicIdentityDiscovery.sameTitle(it, row.optString(5)) }) continue
            val season = row.optInt(3); val id = row.optString(4)
            if (season !in 1..60 || !valid(ott, showId) || !valid(ott, id)) continue
            if (result.containsKey(season) && result[season] != id || result.any { it.key != season && it.value == id }) return emptyMap()
            result[season] = id
        }
        return result
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
        val normalized = PublicIdentityDiscovery.normalize(title)
        if (normalized.isEmpty()) return emptyList()
        return (currentFeed()?.partner?.get("$type:$normalized").orEmpty() + hotstarTitles["$type:$normalized"].orEmpty()).distinct()
    }
    private val verified by lazy {
        try { file.openRead().use { JSONObject(it.bufferedReader().readText()) } }
        catch (_: Exception) { JSONObject() }
    }
    fun candidates(type: String, tmdb: String): List<Pair<String, String>> =
        (currentFeed()?.native?.get("$type:$tmdb").orEmpty() + routeCandidates(type, tmdb) + candidates["$type:$tmdb"].orEmpty()).distinct()
    private fun currentFeed() = liveFeed ?: persistedFeed
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
                if (parsed.generatedAt < (currentFeed()?.generatedAt ?: 0L)) return@withLock
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
    @Synchronized fun save(key: String, id: String, ott: String, now: Long, aliases: List<String> = emptyList()) {
        if (!valid(ott,id)) return
        if (verified.length() >= 20000) {
            val expired = verified.keys().asSequence().filter { (verified.optJSONObject(it)?.optLong("expiresAt", 0L) ?: 0L) <= now }.toList()
            expired.forEach(verified::remove)
            if (verified.length() >= 20000) verified.keys().asSequence().firstOrNull()?.let(verified::remove)
        }
        verified.put(key, JSONObject().put("id",id).put("ott",ott).put("expiresAt",now + 7 * 86_400_000L).put("aliases", JSONArray(aliases.filter { it.isNotBlank() && it.length <= 256 }.distinct().take(24))))
        persist()
    }
    @Synchronized fun verifiedAliases(key: String): List<String> = strings(verified.optJSONObject(key)?.optJSONArray("aliases"))
    internal data class Discovered(val ids: List<Pair<String, String>>, val aliases: List<String>, val typedIds: Set<Pair<String, String>>)
    private val discovered by lazy {
        try { discoveryFile.openRead().use { JSONObject(it.bufferedReader().readText()) } }
        catch (_: Exception) { JSONObject() }
    }
    @Synchronized fun discovered(key: String, now: Long): Discovered? {
        val row = discovered.optJSONObject(key) ?: return null
        if (row.optLong("expiresAt") <= now) return null
        val rows = row.optJSONArray("ids") ?: return null
        val ids = (0 until rows.length()).mapNotNull { i ->
            val candidate = rows.optJSONArray(i) ?: return@mapNotNull null
            val id = candidate.optString(0); val ott = candidate.optString(1)
            (id to ott).takeIf { valid(ott, id) }
        }.distinct().take(24)
        val typedRows = row.optJSONArray("typedIds") ?: JSONArray()
        val typedIds = (0 until typedRows.length()).mapNotNull { i ->
            val candidate = typedRows.optJSONArray(i) ?: return@mapNotNull null
            val value = candidate.optString(0) to candidate.optString(1)
            value.takeIf { it in ids }
        }.toSet()
        return Discovered(ids, strings(row.optJSONArray("aliases")), typedIds).takeIf { ids.isNotEmpty() }
    }
    @Synchronized fun saveDiscovered(key: String, ids: List<Pair<String, String>>, aliases: List<String>, now: Long, typedIds: List<Pair<String, String>> = emptyList()) {
        val validIds = ids.filter { valid(it.second, it.first) }.distinct().take(24)
        if (validIds.isEmpty() || !key.matches(Regex("(?:movie|tv):[1-9][0-9]*"))) return
        if (discovered.length() >= 20000) {
            discovered.keys().asSequence().filter { (discovered.optJSONObject(it)?.optLong("expiresAt") ?: 0L) <= now }
                .toList().forEach(discovered::remove)
            if (discovered.length() >= 20000) discovered.keys().asSequence().firstOrNull()?.let(discovered::remove)
        }
        discovered.put(key, JSONObject().put("expiresAt", now + 86_400_000L)
            .put("ids", JSONArray(validIds.map { JSONArray(listOf(it.first, it.second)) }))
            .put("typedIds", JSONArray(typedIds.filter { it in validIds }.distinct().map { JSONArray(listOf(it.first, it.second)) }))
            .put("aliases", JSONArray(aliases.filter { it.isNotBlank() && it.length <= 256 }.distinct().take(24))))
        var output: java.io.FileOutputStream? = null
        try { output = discoveryFile.startWrite(); output.write(discovered.toString().toByteArray(Charsets.UTF_8)); discoveryFile.finishWrite(output) }
        catch (_: Exception) { output?.let(discoveryFile::failWrite) }
    }
    private fun strings(rows: JSONArray?): List<String> = if (rows == null) emptyList() else
        (0 until rows.length()).map { rows.optString(it) }.filter { it.isNotBlank() && it.length <= 256 }.distinct().take(24)
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
        internal fun parseFeed(root: JSONObject, now: Long, allowStale: Boolean = false): Feed {
            val generated = root.optLong("generatedAt")
            if (root.optInt("schemaVersion") != 2 || generated <= 0 || generated > now + 300_000L || !allowStale && now - generated > 14 * 86_400_000L)
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
