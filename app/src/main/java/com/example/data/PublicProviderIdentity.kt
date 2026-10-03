package com.example.data

import org.json.JSONObject
import java.text.Normalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Native IDs from public catalog metadata. This contains identities, never playback URLs or session tokens. */
internal object PublicProviderIdentity {
    fun seed(tmdbId: String, type: String, year: String): String? = when {
        type == "tv" && tmdbId == "1399" && year == "2011" -> "1971002880"
        type == "tv" && tmdbId == "95350" && year == "2026" -> "1271680756"
        else -> null
    }
    // Sources: https://www.wikidata.org/wiki/Q23572 and
    // https://www.airtelxstream.in/tv-shows/lanterns/HOTSTAR_DTH_TVSHOW_1271680756
    fun hotstarIds(json: String, entityId: String): List<String> {
        val claims = JSONObject(json).optJSONObject("entities")?.optJSONObject(entityId)
            ?.optJSONObject("claims") ?: return emptyList()
        val rows = claims.optJSONArray("P11049") ?: org.json.JSONArray()
        val websiteIds = (claims.optJSONArray("P856") ?: org.json.JSONArray()).let { sites ->
            (0 until sites.length()).mapNotNull { i ->
                val claim = sites.optJSONObject(i) ?: return@mapNotNull null
                if (claim.optString("rank") == "deprecated") return@mapNotNull null
                val value = claim.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value").orEmpty()
                val uri = try { java.net.URI(value) } catch (_: Exception) { return@mapNotNull null }
                if (uri.scheme != "https" || uri.host !in listOf("www.hotstar.com", "hotstar.com")) return@mapNotNull null
                uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.matches(Regex("\\d{5,20}")) }
            }
        }
        return (0 until rows.length()).mapNotNull { i ->
            val claim = rows.optJSONObject(i) ?: return@mapNotNull null
            if (claim.optString("rank") == "deprecated") return@mapNotNull null
            claim.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
                ?.takeIf { it.matches(Regex("\\d{5,20}")) }
        }.plus(websiteIds).distinct()
    }
    fun nativeIds(json: String, entityId: String): List<Pair<String, String>> {
        val claims = JSONObject(json).optJSONObject("entities")?.optJSONObject(entityId)?.optJSONObject("claims") ?: return emptyList()
        fun ids(property: String, pattern: Regex): List<String> {
            val rows = claims.optJSONArray(property) ?: org.json.JSONArray()
            return (0 until rows.length()).mapNotNull { i ->
                val claim = rows.optJSONObject(i) ?: return@mapNotNull null
                if (claim.optString("rank") == "deprecated") return@mapNotNull null
                claim.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.takeIf(pattern::matches)
            }
        }
        return (hotstarIds(json,entityId).map { it to "hs" } +
            ids("P1874",Regex("[0-9]{5,20}")).map { it to "nf" } +
            ids("P14440",Regex("[A-Z0-9]{10,30}")).map { it to "pv" }).distinct()
    }
    fun matchesAirtel(html: String, title: String, year: String, type: String = "movie"): Boolean {
        val displayedYear = Regex("""id=["']banner-content-release-year["'][^>]*>\s*([0-9]{4})\s*<""").find(html)?.groupValues?.get(1)
        if (type != "tv" && displayedYear != year) return false
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>",RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val obj = try { JSONObject(match.groupValues[1]) } catch (_: org.json.JSONException) { return@any false }
            obj.optString("@type") == "VideoObject" && normalize(obj.optString("name")) == normalize(title)
        }
    }
    /** Discover opaque IDs from actual public links, including releases absent from the feed. */
    fun partnerLinks(html: String, title: String, type: String): List<Pair<String, String>> {
        if (type !in listOf("movie", "tv")) return emptyList()
        val group = if (type == "tv") "tv-shows" else "movies"
        val namespace = if (type == "tv") "TVSHOW" else "MOVIE"
        val unescaped = html.replace("\\/", "/")
        return Regex("""["'](?:https://www\.airtelxstream\.in)?(/$group/([^/"'\s<>\\]+)/HOTSTAR_DTH_${namespace}_([0-9]{5,20}))["']""")
            .findAll(unescaped).mapNotNull {
                val url = ("https://www.airtelxstream.in" + it.groupValues[1]).toHttpUrlOrNull() ?: return@mapNotNull null
                if (normalize(url.pathSegments[1]) != normalize(title)) return@mapNotNull null
                it.groupValues[3] to url.encodedPath
            }.distinct().take(6).toList()
    }
    fun linkedHotstarIds(html: String): List<String> {
        val unescaped = html.replace("\\/","/")
        return Regex("""https://(?:www\.)?hotstar\.com/[^"\s\\<>]*?/([0-9]{5,20})(?=[/?"\s\\<>]|$)""")
            .findAll(unescaped).map { it.groupValues[1] }.distinct().toList()
    }
    fun matchesNetflix(html: String, title: String, year: String, type: String): Boolean {
        val scripts = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val data = try { org.json.JSONTokener(match.groupValues[1]).nextValue() } catch (_: org.json.JSONException) { null }
            val rows = if (data is JSONObject) listOf(data) else if (data is org.json.JSONArray)
                (0 until data.length()).mapNotNull(data::optJSONObject) else emptyList()
            rows.any { normalize(it.optString("name")) == normalize(title) &&
                it.optString("@type").contains(if (type == "tv") "TVSeries" else "Movie") &&
                (type == "tv" || it.optString("datePublished").take(4) == year) }
        }
    }
    fun matchesPrime(html: String, title: String, year: String, type: String): Boolean {
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val root = try { JSONObject(match.groupValues[1]) } catch (_: org.json.JSONException) { return@any false }
            val details = root.optJSONObject("init")?.optJSONObject("preparations")?.optJSONObject("body")
                ?.optJSONObject("atf")?.optJSONObject("state")?.optJSONObject("detail")?.optJSONObject("headerDetail") ?: return@any false
            details.keys().asSequence().mapNotNull(details::optJSONObject).count {
                val name = if (type == "tv") it.optString("title").replace(Regex("(?i)\\s*[-:]?\\s*Season\\s+\\d+$"), "") else it.optString("title")
                normalize(name) == normalize(title) && it.optString("titleType") == (if (type == "tv") "season" else "movie") &&
                    (type == "tv" || listOf("releaseYear", "releaseDate", "year").any { field -> it.optString(field).take(4) == year })
            } == 1
        }
    }
    fun matches(data: JSONObject, title: String, year: String, type: String): Boolean =
        data.optString("status") == "y" && normalize(if (type == "tv") data.optString("title").replace(Regex("(?i)\\s+(?:S|Season\\s+)\\d+$"), "") else data.optString("title")) == normalize(title) &&
            data.optString("type") == (if (type == "tv") "t" else "m") &&
            (type == "tv" || data.optString("year") == year)
    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
}
