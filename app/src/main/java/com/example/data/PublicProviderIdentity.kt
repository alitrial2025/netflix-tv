package com.example.data

import org.json.JSONObject
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
    fun matchesAirtel(html: String, title: String, year: String, type: String = "movie", typedMapping: Boolean = false): Boolean {
        val displayedYear = Regex("""id=["']banner-content-release-year["'][^>]*>\s*([0-9]{4})\s*<""").find(html)?.groupValues?.get(1)
        if (type != "tv" && displayedYear != year) return false
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>",RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val obj = try { JSONObject(match.groupValues[1]) } catch (_: org.json.JSONException) { return@any false }
            obj.optString("@type") in listOf("VideoObject", "TVSeries") && PublicIdentityDiscovery.sameTitle(obj.optString("name"), title) &&
                (type != "tv" || typedMapping || hasFirstAirYear(obj, year))
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
                if (!PublicIdentityDiscovery.sameTitle(url.pathSegments[1], title)) return@mapNotNull null
                it.groupValues[3] to url.encodedPath
            }.distinct().take(6).toList()
    }
    fun linkedHotstarIds(html: String): List<String> {
        val unescaped = html.replace("\\/","/")
        return Regex("""https://(?:www\.)?hotstar\.com/[^"\s\\<>]*?/([0-9]{5,20})(?=[/?"\s\\<>]|$)""")
            .findAll(unescaped).map { it.groupValues[1] }.distinct().toList()
    }
    fun matchesNetflix(html: String, title: String, year: String, type: String, typedMapping: Boolean = false): Boolean {
        val scripts = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val data = try { org.json.JSONTokener(match.groupValues[1]).nextValue() } catch (_: org.json.JSONException) { null }
            val rows = if (data is JSONObject) listOf(data) else if (data is org.json.JSONArray)
                (0 until data.length()).mapNotNull(data::optJSONObject) else emptyList()
            rows.any { PublicIdentityDiscovery.sameTitle(it.optString("name"), title) &&
                it.optString("@type").contains(if (type == "tv") "TVSeries" else "Movie") &&
                (if (type == "tv") typedMapping || hasFirstAirYear(it, year) else movieYear(it) == year) }
        }
    }
    fun matchesPrime(html: String, title: String, year: String, type: String, typedMapping: Boolean = false): Boolean {
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        return scripts.findAll(html).any { match ->
            val root = try { JSONObject(match.groupValues[1]) } catch (_: org.json.JSONException) { return@any false }
            val details = root.optJSONObject("init")?.optJSONObject("preparations")?.optJSONObject("body")
                ?.optJSONObject("atf")?.optJSONObject("state")?.optJSONObject("detail")?.optJSONObject("headerDetail") ?: return@any false
            details.keys().asSequence().mapNotNull(details::optJSONObject).count {
                val name = if (type == "tv") it.optString("title").replace(Regex("(?i)\\s*[-:]?\\s*Season\\s+\\d+$"), "") else it.optString("title")
                PublicIdentityDiscovery.sameTitle(name, title) && it.optString("titleType") == (if (type == "tv") "season" else "movie") &&
                    (if (type == "tv") {
                        val season = Regex("(?i)Season\\s+([0-9]+)\\s*$").find(it.optString("title"))?.groupValues?.get(1)?.toIntOrNull()
                        typedMapping || hasFirstAirYear(it, year) || season == 1 && year.matches(Regex("[0-9]{4}")) && it.optString("releaseYear").take(4) == year
                    } else listOf("releaseYear", "releaseDate", "year").any { field -> it.optString(field).take(4) == year })
            } == 1
        }
    }
    fun matches(data: JSONObject, title: String, year: String, type: String, typedMapping: Boolean = false): Boolean =
        data.optString("status") == "y" && PublicIdentityDiscovery.sameTitle(if (type == "tv") data.optString("title").replace(Regex("(?i)\\s+(?:S|Season\\s+)\\d+$"), "") else data.optString("title"), title) &&
            data.optString("type") == (if (type == "tv") "t" else "m") &&
            (if (type == "tv") typedMapping || hasFirstAirYear(data, year) else data.optString("year") == year)
    // Netflix publishes dateCreated rather than datePublished for some movies, including live-event films.
    // Read only the matching top-level Movie; a nested trailer's upload date is never a release date.
    private fun movieYear(data: JSONObject): String? = listOf("datePublished", "dateCreated")
        .firstNotNullOfOrNull { field -> data.optString(field).take(4).takeIf { it.matches(Regex("[0-9]{4}")) } }
    private fun hasFirstAirYear(data: JSONObject, year: String) = year.matches(Regex("[0-9]{4}")) && firstAirYear(data) == year
    // Generic dateCreated/startDate/year can describe a later season. Only explicit first-air fields anchor a title-only series match.
    private fun firstAirYear(data: JSONObject): String? = listOf("first_air_date", "firstAirDate", "seriesFirstAirDate")
        .firstNotNullOfOrNull { field -> data.optString(field).take(4).takeIf { it.matches(Regex("[0-9]{4}")) } }
}
