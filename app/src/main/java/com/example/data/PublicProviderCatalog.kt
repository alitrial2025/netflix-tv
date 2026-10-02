package com.example.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Catalog metadata only. Playback URLs, cookies and membership data are never persisted. */
internal class PublicProviderCatalog(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "verified_provider_identities.json"))
    private val candidates by lazy {
        try {
            val root = JSONObject(app.assets.open("public-provider-catalog.json").bufferedReader().use { it.readText() })
            val rows = root.optJSONArray("rows") ?: JSONArray()
            val result = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val type = row.optString(0); val tmdb = row.optString(1)
                val ott = row.optString(2); val id = row.optString(3)
                if (type !in listOf("movie", "tv") || !tmdb.matches(Regex("[1-9][0-9]*")) || !valid(ott, id)) continue
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
                    !path.matches(Regex("/(?:movies|tv-shows)/[a-z0-9-]+/HOTSTAR_DTH_(?:MOVIE|TVSHOW)_[0-9]{5,20}"))) continue
                result.getOrPut("$type:$title") { mutableListOf() }.add(id to path)
            }
            result.mapValues { it.value.distinct() }
        } catch (_: Exception) { emptyMap() }
    }
    fun hotstarTitles(type: String, title: String): List<Pair<String, String>> {
        val normalized = java.text.Normalizer.normalize(title,java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"),"").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"),"")
        return hotstarTitles["$type:$normalized"].orEmpty()
    }
    private val verified by lazy {
        try { file.openRead().use { JSONObject(it.bufferedReader().readText()) } }
        catch (_: Exception) { JSONObject() }
    }
    fun candidates(type: String, tmdb: String): List<Pair<String, String>> = candidates["$type:$tmdb"].orEmpty()
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
        "nf", "hs" -> id.matches(Regex("[0-9]{5,20}"))
        "pv" -> id.matches(Regex("[A-Z0-9]{10,30}"))
        else -> false
    }
}
