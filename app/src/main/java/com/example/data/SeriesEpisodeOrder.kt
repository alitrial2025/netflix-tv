package com.example.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Explicit alternate ordering, verified against TMDB's episode group and Netflix's four season IDs.
 * Episode numbers remain absolute, preserving existing progress and downloaded episode identities.
 * See docs/black-clover-episode-order.md. Never infer content IDs or join by array position.
 */
internal object SeriesEpisodeOrder {
    private val ranges = listOf(1..51, 52..102, 103..154, 155..170)
    fun applies(tmdbId: String) = tmdbId == "73223"
    fun seasons(tmdbId: String): List<Int>? = if (applies(tmdbId)) listOf(1, 2, 3, 4) else null
    fun firstEpisode(tmdbId: String, season: Int): Int = if (applies(tmdbId)) ranges.getOrNull(season - 1)?.first ?: 1 else 1
    fun sourceSeason(tmdbId: String, season: Int): Int = if (applies(tmdbId) && season in 1..4) 1 else season
    fun contains(tmdbId: String, season: Int, episode: Int): Boolean =
        !applies(tmdbId) || ranges.getOrNull(season - 1)?.contains(episode) == true
    fun displaySeason(tmdbId: String, season: Int, episode: Int): Int =
        if (applies(tmdbId) && season == 1) ranges.indexOfFirst { episode in it }.takeIf { it >= 0 }?.plus(1) ?: season else season
    fun providerSeason(tmdbId: String, ott: String, showId: String, season: Int, episode: Int): Int? {
        if (!applies(tmdbId) || ott != "nf" || showId != "80238012") return null
        val selected = ranges.indexOfFirst { episode in it }.takeIf { it >= 0 }?.plus(1)
            ?: throw IOException("Requested episode is outside the verified series order")
        if (season != 1 && season != selected) throw IOException("Requested episode belongs to a different season")
        return selected
    }
    data class EpisodeIdentity(val number: Int, val id: Long, val title: String)
    data class Mapping(val number: Int, val id: String, val providerSeason: Int, val providerEpisode: Int)
    private data class Alias(val tmdbEpisodeId: Long, val canonical: String, val title: String, val nativeId: String)
    // Reviewed translation/typographic aliases, bound to both opaque episode identities.
    private val aliases = listOf(
        Alias(1378799L, "A Young Man's Vow", "The Boys' Promise", "80238227"),
        Alias(1378975L, "To the Royal Capital!", "To the Royal Capital of the Clover Kingdom!", "80238228"),
        Alias(1387474L, "The Road to the Wizard King", "The Path to the Wizard King", "80238230"),
        Alias(1390857L, "Another New Member", "The Other New Recruit", "80238232"),
        Alias(1392855L, "Go! Go! My First Mission", "Go! Go! First Mission", "80238233"),
        Alias(1401846L, "Guardians", "Those Who Protect", "80238235"),
        Alias(1404851L, "What Happened One Day in the Castle Town", "What Happened on a Certain Day in the Castle Town", "80238236"),
        Alias(1444656L, "Wild Magic Dance", "Wild Magic Danc", "80238247"),
        Alias(1444716L, "The King of the Crimson Lions", "The Crimson Lion King", "80238248"),
        Alias(1607999L, "The Battlefield Decision", "Battlefield Decision", "81313238"),
        Alias(1639300L, "Bettering One Another", "Those Who Boost Each Other Up", "81313242"),
        Alias(2173598L, "Nero Reminiscences… (1)", "Nero Reminisces... Part One", "81399582"),
        Alias(2179680L, "Nero Reminiscences… (2)", "Nero Reminisces... Part Two", "81399583"),
        Alias(2206961L, "The Devil Megicula", "Devil Megicula", "81399588"),
        Alias(2223054L, "The New Magic Knights Captain Conference", "The New Magic Knight Captain Conference", "81399589"),
        Alias(2448655L, "Two Things to Look for", "Two Things We Need to Find", "81399608")
    )
    fun metadata(data: JSONObject): List<EpisodeIdentity> {
        if (data.optLong("id") != 91272L) throw IOException("Episode ordering metadata identity mismatch")
        val rows = data.optJSONArray("episodes") ?: throw IOException("Episode ordering metadata unavailable")
        if (rows.length() != 170) throw IOException("Episode ordering metadata is incomplete")
        val result = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: throw IOException("Invalid episode ordering metadata")
            val number = row.optInt("episode_number")
            val title = row.optString("name")
            if (row.optInt("season_number") != 1 || number !in 1..170 || row.optLong("id") <= 0 || title.isBlank() || title.length > 256)
                throw IOException("Invalid episode ordering metadata")
            EpisodeIdentity(number, row.getLong("id"), title)
        }
        if (result.map { it.number }.toSet().size != 170 || result.map { it.id }.toSet().size != 170)
            throw IOException("Episode ordering metadata is ambiguous")
        return result
    }
    fun join(rows: JSONArray, metadata: List<EpisodeIdentity>, season: Int): List<Mapping> {
        val eligible = metadata.filter { contains("73223", season, it.number) }
        val result = mutableListOf<Mapping>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val label = row.optString("s").ifBlank { row.optString("season") }.ifBlank { row.optString("s_num") }
            val labelledSeason = Regex("(?i)(?:season|s)?\\s*([0-9]+)").matchEntire(label.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (labelledSeason != season) throw IOException("Provider returned a different season")
            val nativeId = row.optString("id").ifBlank { row.optString("Id") }.ifBlank { row.optString("sid") }
            val title = row.optString("t").ifBlank { row.optString("title") }
            val episodeLabel = row.optString("ep").ifBlank { row.optString("episode") }.ifBlank { row.optString("e") }
            val providerEpisode = Regex("(?i)(?:s[0-9]+\\s*)?(?:episode|ep|e)?\\s*([0-9]+)")
                .matchEntire(episodeLabel.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (!nativeId.matches(Regex("[0-9]{5,20}")) || providerEpisode !in 1..500) throw IOException("Invalid provider episode identity")
            val matched = eligible.filter { episode ->
                PublicIdentityDiscovery.sameTitle(episode.title, title) || aliases.any {
                    it.tmdbEpisodeId == episode.id && PublicIdentityDiscovery.sameTitle(it.canonical, episode.title) &&
                        PublicIdentityDiscovery.sameTitle(it.title, title) && it.nativeId == nativeId
                }
            }
            if (matched.size > 1) throw IOException("Provider episode identity is ambiguous")
            matched.singleOrNull()?.let { result += Mapping(it.number, nativeId, season, providerEpisode!!) }
        }
        if (result.map { it.number }.distinct().size != result.size || result.map { it.id }.distinct().size != result.size)
            throw IOException("Provider episode identity is ambiguous")
        return result
    }
}
