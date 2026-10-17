package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** Read the published title model as data, never execute the page's JavaScript. */
internal object NetflixPublicMetadata {
    data class Title(val id: String, val title: String, val type: String, val year: String?, val seasons: Map<Int, String>)
    private val nativeId = Regex("[0-9]{5,20}")
    private val script = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val assignment = Regex("""(?:window\.)?netflix\.reactContext\s*=\s*(\{[\s\S]*\})\s*;?\s*$""")
    private fun id(value: Any?): String? = when (value) {
        is String -> value.takeIf(nativeId::matches)
        is Int, is Long -> value.toString().takeIf(nativeId::matches)
        else -> null
    }
    private fun jsonText(raw: String): String {
        val result = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '\\' && raw.getOrNull(index + 1) == '\\') {
                result.append("\\\\"); index += 2
            } else if (raw[index] == '\\' && raw.getOrNull(index + 1) == 'x' && index + 3 < raw.length &&
                raw.substring(index + 2, index + 4).all { it.digitToIntOrNull(16) != null }) {
                result.append("\\u00").append(raw.substring(index + 2, index + 4)); index += 4
            } else { result.append(raw[index]); index++ }
        }
        return result.toString()
    }
    fun parse(html: String, expectedId: String? = null): Title? {
        if (html.length > 6 * 1024 * 1024 || expectedId != null && !nativeId.matches(expectedId)) return null
        return try { parseModel(html, expectedId) } catch (_: org.json.JSONException) { null }
    }
    private fun parseModel(html: String, expectedId: String?): Title? {
        val contexts = script.findAll(html).mapNotNull { assignment.find(it.groupValues[1])?.groupValues?.get(1) }.toList()
        if (contexts.size != 1) return null
        val tokener = JSONTokener(jsonText(contexts.single()))
        val root = tokener.nextValue() as? JSONObject ?: return null
        if (tokener.nextClean() != '\u0000') return null
        val models = root.optJSONObject("models") ?: return null
        val meta = models.optJSONObject("nmTitleGQL")?.optJSONObject("data")?.optJSONObject("metaData") ?: return null
        val selectedId = id(meta.opt("videoId")) ?: return null
        if (expectedId != null && selectedId != expectedId || id(meta.opt("topLevelVideoId")) != selectedId) return null
        val kind = meta.optString("type").takeIf { it in listOf("Movie", "Show") } ?: return null
        val canonical = meta.optString("nextPage").toHttpUrlOrNull() ?: return null
        if (!canonical.isHttps || canonical.host !in listOf("www.netflix.com", "netflix.com") || canonical.port != 443 ||
            canonical.username.isNotEmpty() || canonical.password.isNotEmpty() || canonical.query != null || canonical.fragment != null ||
            !Regex("^/(?:[A-Za-z]{2}(?:-[A-Za-z]{2})?/)?title/$selectedId/?$").matches(canonical.encodedPath)) return null
        val batches = models.optJSONObject("graphqlClcs")?.optJSONObject("data") ?: return null
        if (batches.length() > 64) return null
        val found = mutableListOf<Title>()
        for (key in batches.keys()) {
            val query = try { JSONObject(key) } catch (_: org.json.JSONException) { continue }
            val params = query.optJSONObject("variables")?.optJSONArray("parameters") ?: continue
            if (params.length() > 64) continue
            val selections = (0 until params.length()).mapNotNull(params::optJSONObject).filter { it.optString("name") == "titleId" }
            if (query.optString("query") != "clcsHookV2" || selections.size != 1 ||
                selections.single().optJSONObject("value")?.optString("stringValue") != selectedId) continue
            val nodes = batches.optJSONObject(key)?.optJSONObject("clcsHookV2")?.optJSONObject("result")
                ?.optJSONObject("screen")?.optJSONObject("componentTree")?.optJSONArray("nodes") ?: continue
            if (nodes.length() > 64) continue
            for (nodeIndex in 0 until nodes.length()) {
                val videos = nodes.optJSONObject(nodeIndex)?.optJSONObject("data")?.optJSONArray("videos") ?: continue
                if (videos.length() > 64) continue
                for (videoIndex in 0 until videos.length()) {
                    val video = videos.optJSONObject(videoIndex) ?: continue
                    if (id(video.opt("videoId")) != selectedId) continue
                    val title = (video.opt("title") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
                    if (video.optString("__typename") != kind) return null
                    val year = video.optString("latestYear").takeIf { it.matches(Regex("[0-9]{4}")) && it.toInt() in 1900..2200 }
                    if (kind == "Movie" && year == null) return null
                    val seasons = linkedMapOf<Int, String>()
                    val edges = video.optJSONObject("seasons")?.optJSONArray("edges")
                    if ((edges?.length() ?: 0) > 60) return null
                    if (kind == "Show" && edges != null) for (seasonIndex in 0 until edges.length()) {
                        val season = edges.optJSONObject(seasonIndex)?.optJSONObject("node") ?: return null
                        val number = Regex("(?i)^Season\\s+([0-9]+)$").matchEntire(season.optString("numberLabelV2"))
                            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
                        val seasonId = id(season.opt("videoId")) ?: return null
                        if (season.optString("__typename") != "Season" || season.optString("showMemberType") != "SEASON" ||
                            number !in 1..60 || number in seasons || seasonId in seasons.values) return null
                        seasons[number] = seasonId
                    }
                    found += Title(selectedId, title, if (kind == "Movie") "movie" else "tv", if (kind == "Movie") year else null, seasons)
                }
            }
        }
        return found.distinct().singleOrNull()
    }
}
