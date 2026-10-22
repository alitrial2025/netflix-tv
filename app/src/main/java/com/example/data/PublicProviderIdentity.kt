package com.example.data

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Native IDs from public catalog metadata. This contains identities, never playback URLs or session tokens. */
internal object PublicProviderIdentity {
    fun seed(tmdbId: String, type: String, year: String): String? = when {
        // Public partner catalog + live title/season/episode metadata rechecked 2026-10-16.
        // The Wikidata ID 1260110208 now returns empty provider metadata.
        type == "tv" && tmdbId == "94997" && year == "2022" -> "1971002877"
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
                PublicIdentityDiscovery.homepageIds(value).singleOrNull { it.second == "hs" }?.first
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
        if (!matchesAirtelCandidate(html, title, year, type)) return false
        return type != "tv" || typedMapping || airtelTitles(html, title).any { hasFirstAirYear(it, year) }
    }
    /** A matching partner page supplies candidates, never proof of a series' premiere year. */
    fun matchesAirtelCandidate(html: String, title: String, year: String, type: String): Boolean {
        if (type !in listOf("movie", "tv")) return false
        val displayedYear = Regex("""id=["']banner-content-release-year["'][^>]*>\s*([0-9]{4})\s*<""").find(html)?.groupValues?.get(1)
        if (type != "tv" && displayedYear != year) return false
        return airtelTitles(html, title).isNotEmpty()
    }
    private fun airtelTitles(html: String, title: String): List<JSONObject> {
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>",RegexOption.IGNORE_CASE)
        return scripts.findAll(html).mapNotNull { match ->
            val obj = try { JSONObject(match.groupValues[1]) } catch (_: org.json.JSONException) { return@mapNotNull null }
            obj.takeIf { it.optString("@type") in listOf("VideoObject", "TVSeries") && PublicIdentityDiscovery.sameTitle(it.optString("name"), title) }
        }.toList()
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
        return Regex("""https://[^\s"'\\<>]+""").findAll(unescaped).mapNotNull { match ->
            val url = match.value.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.host !in listOf("hotstar.com", "www.hotstar.com") || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
            // Partner pages also publish /<native ID>/watch, without a locale/title path.
            Regex("""^/([0-9]{5,20})/watch/?$""").matchEntire(url.encodedPath)?.groupValues?.get(1)
                ?: Regex("""^/(?:[A-Za-z]{2}/)?(?:movies|shows|tv)/[^/]+/([0-9]{5,20})(?:/watch)?/?$""")
                    .matchEntire(url.encodedPath)?.groupValues?.get(1)
        }.distinct().toList()
    }
    fun matchesNetflixAny(html: String, aliases: Collection<String>, year: String, type: String, typedMapping: Boolean, expectedId: String): Boolean {
        val modern = NetflixPublicMetadata.parse(html, expectedId)
        if (modern != null) return modern.type == type && aliases.any { PublicIdentityDiscovery.sameTitle(modern.title, it) } &&
            (if (type == "tv") typedMapping else modern.year == year)
        return aliases.any { matchesNetflix(html, it, year, type, typedMapping, expectedId, modern) }
    }
    fun matchesNetflix(html: String, title: String, year: String, type: String, typedMapping: Boolean = false, expectedId: String? = null,
        modern: NetflixPublicMetadata.Title? = NetflixPublicMetadata.parse(html, expectedId)): Boolean {
        if (modern != null) return PublicIdentityDiscovery.sameTitle(modern.title, title) && modern.type == type &&
            (if (type == "tv") typedMapping else modern.year == year)
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
        ?: premiereEpisodeYear(data)

    // Hotstar omits a series first-air field but dates its explicitly labelled pilot.
    // Latest-season dates, generic show years and unlabelled rows cannot establish identity.
    private fun premiereEpisodeYear(data: JSONObject): String? {
        if (data.optString("status") != "y" || data.optString("type") != "t") return null
        val episodes = data.optJSONArray("episodes") ?: return null
        if (episodes.length() > 1000) return null
        val pilots = (0 until episodes.length()).mapNotNull(episodes::optJSONObject).filter { row ->
            val season = row.optString("s").ifBlank { row.optString("season") }
            val episode = row.optString("ep").ifBlank { row.optString("episode") }
            Regex("(?i)(?:s|season)?\\s*0*1").matches(season.trim()) &&
                Regex("(?i)(?:s0*1\\s*)?(?:e|ep|episode)?\\s*0*1").matches(episode.trim())
        }
        if (pilots.isEmpty()) return null
        val identities = pilots.map { row ->
            val id = row.optString("id")
            if (!id.matches(Regex("[0-9]{5,20}"))) return null
            val date = premiereDate(row.optString("date").trim()) ?: return null
            id to date
        }.distinct()
        return identities.singleOrNull()?.second?.take(4)
    }
    // GregorianCalendar keeps this path compatible with Android 7 (API 24).
    private fun premiereDate(raw: String): String? {
        val iso = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})").matchEntire(raw)
        val named = Regex("([0-9]{1,2}) ([A-Z][a-z]{2}) ([0-9]{4})").matchEntire(raw)
        val year: Int; val month: Int; val day: Int
        if (iso != null) {
            year = iso.groupValues[1].toInt(); month = iso.groupValues[2].toInt(); day = iso.groupValues[3].toInt()
        } else if (named != null) {
            year = named.groupValues[3].toInt(); day = named.groupValues[1].toInt()
            month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(named.groupValues[2]) + 1
        } else return null
        if (year !in 1900..2100 || month !in 1..12) return null
        try {
            GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                isLenient = false; clear(); set(year, month - 1, day)
            }.timeInMillis
        } catch (_: IllegalArgumentException) { return null }
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }
}
