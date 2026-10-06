package com.example.data

import android.content.Context
import android.util.AtomicFile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File

/** Verified identities only: provider-scoped seasons and explicitly labelled episode mappings. */
internal class ProviderEpisodeCatalog(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "verified_provider_episodes_v1.json"))
    private val root by lazy {
        try {
            file.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_FILE_BYTES) return@use null
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8")).takeIf { it.optInt("schemaVersion") == 1 }
            }
        }
        catch (_: Exception) { null }
            ?: JSONObject().put("schemaVersion", 1).put("rows", JSONObject())
    }
    private fun rows(): JSONObject = root.optJSONObject("rows") ?: JSONObject().also { root.put("rows", it) }
    private fun scope(origin: String, ott: String, showId: String): String? {
        val url = origin.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || !validId(ott, showId)) return null
        return "${url.scheme}://${url.host}:${url.port}:$ott:$showId"
    }
    private fun row(origin: String, ott: String, showId: String, season: Int): JSONObject? =
        scope(origin, ott, showId)?.takeIf { season in 1..60 }?.let { rows().optJSONObject("$it:$season") }

    @Synchronized fun seasons(origin: String, ott: String, showId: String, now: Long): Map<Int, String> {
        val scope = scope(origin, ott, showId) ?: return emptyMap()
        return (1..60).mapNotNull { season ->
            val row = rows().optJSONObject("$scope:$season") ?: return@mapNotNull null
            val id = row.optString("seasonId")
            (season to id).takeIf { validId(ott, id) && fresh(row.optLong("seasonVerifiedAt"), now) }
        }.toMap()
    }

    @Synchronized fun episode(origin: String, ott: String, showId: String, season: Int, episode: Int, now: Long): String? {
        if (episode !in 1..500) return null
        val entry = row(origin, ott, showId, season)?.optJSONObject("episodes")?.optJSONObject(episode.toString()) ?: return null
        val id = entry.optString("id")
        return id.takeIf { validId(ott, id) && fresh(entry.optLong("verifiedAt"), now) }
    }

    @Synchronized fun saveSeasons(origin: String, ott: String, showId: String, seasons: Map<Int, String>, now: Long, source: String) {
        val scope = scope(origin, ott, showId) ?: return
        if (now <= 0 || source !in SOURCES || seasons.isEmpty() || seasons.size > 60 ||
            seasons.keys.any { it !in 1..60 } || seasons.values.any { !validId(ott, it) } || seasons.values.distinct().size != seasons.size) return
        seasons.forEach { (season, id) ->
            val key = "$scope:$season"
            val old = rows().optJSONObject(key)
            // A replaced season identity must not carry episodes from its previous incarnation.
            val row = old?.takeIf { it.optString("seasonId").isEmpty() || it.optString("seasonId") == id } ?: JSONObject()
            row.put("seasonId", id).put("seasonVerifiedAt", now).put("source", source)
            rows().put(key, row)
        }
        persist(now)
    }

    /** Caller must supply only exact season and episode labels from verified provider metadata. */
    @Synchronized fun saveEpisodes(origin: String, ott: String, showId: String, season: Int, episodes: Map<Int, String>, now: Long) {
        val scope = scope(origin, ott, showId) ?: return
        if (season !in 1..60 || now <= 0 || episodes.isEmpty() || episodes.size > 500 ||
            episodes.keys.any { it !in 1..500 } || episodes.values.any { !validId(ott, it) } || episodes.values.distinct().size != episodes.size) return
        val key = "$scope:$season"
        val row = rows().optJSONObject(key) ?: JSONObject()
        val saved = row.optJSONObject("episodes") ?: JSONObject()
        if ((saved.keys().asSequence().toSet() + episodes.keys.map(Int::toString)).size > 500) return
        // Conflicting numbering is discarded, never accepted by selecting the first row.
        val conflicts = episodes.keys.filter { number ->
            saved.optJSONObject(number.toString())?.let { fresh(it.optLong("verifiedAt"), now) && it.optString("id") != episodes[number] } == true
        }.toMutableSet()
        saved.keys().asSequence().toList().forEach { oldNumber ->
            val old = saved.optJSONObject(oldNumber) ?: return@forEach
            if (fresh(old.optLong("verifiedAt"), now)) {
                episodes.entries.filter { it.value == old.optString("id") && it.key.toString() != oldNumber }.forEach {
                    conflicts += it.key; oldNumber.toIntOrNull()?.let(conflicts::add)
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            conflicts.forEach { saved.remove(it.toString()) }
            row.put("episodes", saved); rows().put(key, row); persist(now); return
        }
        episodes.forEach { (number, id) -> saved.put(number.toString(), JSONObject().put("id", id).put("verifiedAt", now)) }
        row.put("episodes", saved).put("episodeSource", "provider-labelled")
        rows().put(key, row); persist(now)
    }

    @Synchronized fun evictSeason(origin: String, ott: String, showId: String, season: Int, now: Long) {
        scope(origin, ott, showId)?.let { rows().remove("$it:$season"); persist(now) }
    }

    @Synchronized fun evictEpisode(origin: String, ott: String, showId: String, season: Int, episode: Int, now: Long) {
        row(origin, ott, showId, season)?.optJSONObject("episodes")?.remove(episode.toString())
        persist(now)
    }

    private fun persist(now: Long) {
        val rows = rows()
        if (rows.length() > 2000) {
            rows.keys().asSequence().filter { key ->
                val row = rows.optJSONObject(key) ?: return@filter true
                !fresh(row.optLong("seasonVerifiedAt"), now) &&
                    (row.optJSONObject("episodes")?.let { entries -> entries.keys().asSequence().none { fresh(entries.optJSONObject(it)?.optLong("verifiedAt") ?: 0L, now) } } ?: true)
            }.toList().forEach(rows::remove)
            while (rows.length() > 2000) rows.keys().next().let(rows::remove)
        }
        var bytes = root.toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_FILE_BYTES && rows.length() > 1) {
            rows.keys().next().let(rows::remove)
            bytes = root.toString().toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > MAX_FILE_BYTES) return
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream) }
        catch (_: Exception) { stream?.let(file::failWrite) }
    }

    companion object {
        internal const val TTL_MS = 7 * 86_400_000L
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private val SOURCES = setOf("official-public", "provider-authorized", "provider-public")
        private fun fresh(verifiedAt: Long, now: Long) = verifiedAt > 0 && verifiedAt <= now + 300_000L && now - verifiedAt < TTL_MS
        private fun validId(ott: String, id: String) = when (ott) {
            "nf", "hs" -> id.matches(Regex("[0-9]{5,20}"))
            "pv" -> id.matches(Regex("[A-Z0-9]{10,30}"))
            else -> false
        }
    }
}
