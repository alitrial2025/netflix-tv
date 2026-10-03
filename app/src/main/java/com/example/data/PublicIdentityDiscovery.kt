package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Public metadata candidates only; a title page and requested episode still need verification. */
internal object PublicIdentityDiscovery {
    data class Metadata(val aliases: List<String>, val wikidataId: String, val homepageIds: List<Pair<String, String>>)

    fun metadata(root: JSONObject, tmdbId: String, type: String, year: String): Metadata? {
        if (type !in listOf("tv", "movie") || root.optString("id") != tmdbId) return null
        val field = if (type == "tv") "name" else "title"
        val date = root.optString(if (type == "tv") "first_air_date" else "release_date")
        if (normalize(root.optString(field)).isEmpty() || year.matches(Regex("[0-9]{4}")) && date.take(4) != year) return null
        val aliases = mutableListOf(root.optString(field), root.optString("original_$field"))
        val alternatives = root.optJSONObject("alternative_titles")
        val rows = alternatives?.optJSONArray(if (type == "tv") "results" else "titles") ?: JSONArray()
        for (i in 0 until rows.length()) aliases += rows.optJSONObject(i)?.optString("title").orEmpty()
        return Metadata(aliases.filter { normalize(it).isNotEmpty() && it.length <= 256 }.distinct().take(24),
            root.optJSONObject("external_ids")?.optString("wikidata_id").orEmpty().takeIf { it.matches(Regex("Q[1-9][0-9]*")) }.orEmpty(),
            homepageIds(root.optString("homepage")))
    }

    /** A direct official OTT homepage can publish a new identity before any bulk index does. */
    fun homepageIds(homepage: String): List<Pair<String, String>> {
        val url = homepage.toHttpUrlOrNull() ?: return emptyList()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return emptyList()
        val candidate = when (url.host) {
            "netflix.com", "www.netflix.com" -> Regex("^/(?:[A-Za-z]{2}(?:-[A-Za-z]{2})?/)?title/([0-9]{5,20})/?$")
                .find(url.encodedPath)?.groupValues?.get(1)?.let { it to "nf" }
            "primevideo.com", "www.primevideo.com" -> Regex("^/(?:-/[A-Za-z_-]+/)?detail/([A-Z0-9]{10,30})/?$")
                .find(url.encodedPath)?.groupValues?.get(1)?.let { it to "pv" }
            "hotstar.com", "www.hotstar.com" -> Regex("^/(?:[A-Za-z]{2}/)?(?:movies|shows|tv)/[^?#]*/([0-9]{5,20})/?$")
                .find(url.encodedPath)?.groupValues?.get(1)?.let { it to "hs" }
            else -> null
        }
        return listOfNotNull(candidate)
    }

    fun wikidataQuery(tmdbId: String, type: String): String? {
        if (!tmdbId.matches(Regex("[1-9][0-9]*")) || type !in listOf("movie", "tv")) return null
        val property = if (type == "tv") "P4983" else "P4947"
        // Season entities can have Prime IDs even when the parent series has no native-ID claim.
        val seasons = if (type == "tv") "UNION { ?season wdt:P179 ?item; wdt:P31 wd:Q3464665; ?nativeProperty ?nativeId . FILTER(?ott = \"pv\") }" else ""
        return """SELECT ?type ?tmdb ?ott ?nativeId WHERE {
          VALUES (?type ?tmdb) { ("$type" "$tmdbId") }
          VALUES (?nativeProperty ?ott) { (wdt:P1874 "nf") (wdt:P14440 "pv") (wdt:P11049 "hs") }
          ?item wdt:$property ?tmdb . { ?item ?nativeProperty ?nativeId . } $seasons
        } LIMIT 24"""
    }

    fun joinedIds(root: JSONObject, tmdbId: String, type: String): List<Pair<String, String>> {
        val rows = root.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            fun value(name: String) = row.optJSONObject(name)?.optString("value").orEmpty()
            val ott = value("ott"); val id = value("nativeId")
            if (value("type") != type || value("tmdb") != tmdbId || !validId(id, ott)) null else id to ott
        }.distinct().take(24)
    }

    /** Read search result cards, never recommendation links or authentication actions. */
    fun primeSearchIds(html: String, aliases: List<String>, year: String, type: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (root in scripts(html)) {
            val containers = root.optJSONObject("init")?.optJSONObject("preparations")?.optJSONObject("body")
                ?.optJSONArray("containers") ?: continue
            for (i in 0 until containers.length()) {
                val rows = containers.optJSONObject(i)?.optJSONArray("entities") ?: continue
                for (j in 0 until rows.length()) {
                    val row = rows.optJSONObject(j) ?: continue
                    val title = row.optString("title").ifBlank { row.optString("displayTitle") }
                    val kind = row.optString("entityType").lowercase(Locale.ROOT)
                    if (kind !in (if (type == "tv") listOf("tvseason", "season", "tvseries", "series", "tv show") else listOf("movie"))) continue
                    if (aliases.none { sameTitle(seriesTitle(title, type), it) }) continue
                    // A later season's release year cannot establish the parent show's first-air year.
                    val season = Regex("(?i)\\bSeason\\s+(\\d+)\\s*$").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    if (type == "tv" && season != null && season != 1) continue
                    if (row.optString("releaseYear") != year) continue
                    val url = "https://www.primevideo.com".toHttpUrl().resolve(row.optJSONObject("link")?.optString("url").orEmpty()) ?: continue
                    if (!url.isHttps || url.host != "www.primevideo.com") continue
                    val id = Regex("^/(?:-/[a-zA-Z_-]+/)?detail/([A-Z0-9]{10,30})$").find(url.encodedPath)?.groupValues?.get(1) ?: continue
                    result += id to "pv"
                }
            }
        }
        return result.distinct().take(6)
    }

    fun scripts(html: String): List<JSONObject> = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        .findAll(html).mapNotNull { try { JSONObject(it.groupValues[1]) } catch (_: org.json.JSONException) { null } }.toList()

    fun seriesTitle(title: String, type: String) = if (type == "tv")
        title.replace(Regex("(?i)\\s*[-:]?\\s*(?:Season\\s+|S)(\\d+)\\s*$"), "") else title

    fun normalize(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun sameTitle(first: String, second: String): Boolean = normalize(first).let { it.isNotEmpty() && it == normalize(second) }

    fun validId(id: String, ott: String) = when (ott) {
        "nf", "hs" -> id.matches(Regex("[0-9]{5,20}"))
        "pv" -> id.matches(Regex("[A-Z0-9]{10,30}"))
        else -> false
    }
}
