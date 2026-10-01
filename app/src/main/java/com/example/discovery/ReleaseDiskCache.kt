package com.example.discovery

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only bounded public TMDB metadata; no user or playback information. */
class ReleaseDiskCache(private val file: File) {
    fun read(): List<ReleaseTitle> = try {
        if (!file.isFile || file.length() > 1_000_000) emptyList() else {
            val data = JSONArray(file.readText())
            (0 until data.length().coerceAtMost(240)).map { index ->
                val row = data.getJSONObject(index)
                val genres = row.optJSONArray("genres") ?: JSONArray()
                ReleaseTitle(row.getString("id"), row.getString("kind"), row.getString("title"), row.optString("overview"),
                    row.optString("poster").takeIf { it.isNotBlank() }, row.optString("backdrop").takeIf { it.isNotBlank() },
                    row.optString("date").takeIf { it.isNotBlank() }, (0 until genres.length()).map { genres.getInt(it) },
                    row.optDouble("rating", 0.0), row.optInt("votes"), row.optDouble("popularity", 0.0), row.optBoolean("ott"))
            }
        }
    } catch (_: Exception) { emptyList() }
    fun write(items: List<ReleaseTitle>) {
        val rows = JSONArray()
        items.take(240).forEach { title -> rows.put(JSONObject().put("id",title.id).put("kind",title.kind).put("title",title.title)
            .put("overview",title.overview).put("poster",title.posterPath.orEmpty()).put("backdrop",title.backdropPath.orEmpty())
            .put("date",title.date.orEmpty()).put("genres",JSONArray(title.genreIds)).put("rating",title.voteAverage)
            .put("votes",title.voteCount).put("popularity",title.popularity).put("ott",title.majorOtt)) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.outputStream().use { it.write(rows.toString().toByteArray()); it.fd.sync() }
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Exception) { temporary.delete() }
    }
}
