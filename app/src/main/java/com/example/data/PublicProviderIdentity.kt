package com.example.data

import org.json.JSONObject
import java.text.Normalizer

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
    fun matches(data: JSONObject, title: String, year: String, type: String): Boolean =
        data.optString("status") == "y" && normalize(if (type == "tv") data.optString("title").replace(Regex("(?i)\\s+(?:S|Season\\s+)\\d+$"), "") else data.optString("title")) == normalize(title) &&
            data.optString("type") == (if (type == "tv") "t" else "m") &&
            (type == "tv" || data.optString("year") == year)
    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
}
