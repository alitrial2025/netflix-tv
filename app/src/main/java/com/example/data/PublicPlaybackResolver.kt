package com.example.data

import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class PublicPlaybackResult(
    val url: String, val headers: Map<String, String>, val captions: List<Caption>,
    val contentId: String, val ott: String, val expiresAt: Long
)

/** TMDB title -> exact provider identity -> requested episode -> issued HLS, without a session. */
internal class PublicPlaybackResolver(client: OkHttpClient, baseUrl: String = "https://net52.cc", private val catalog: PublicProviderCatalog? = null) {
    private val base = baseUrl.toHttpUrl()
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()
    private data class SeasonCache(val expiresAt: Long, val seasons: Map<Int, String>)
    private val netflixSeasons = ConcurrentHashMap<String, SeasonCache>()

    suspend fun resolve(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String = ""): PublicPlaybackResult = withTimeout(45_000L) {
        if (title.isBlank() || type !in listOf("movie", "tv") || type == "tv" && (season < 1 || episode < 1))
            throw IOException("Invalid title or episode selection")
        Run().resolve(title, year, type, season, episode, tmdbId)
    }

    private inner class Run {
        private var requests = 0
        private val attempted = mutableSetOf<Pair<String, String>>()
        private var selected: Pair<String, String>? = null
        private fun key(tmdb: String, type: String, title: String, year: String) = "$base:$type:$tmdb:${normalize(title)}:$year"
        private val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Origin" to base.toString().trimEnd('/'),
            "Referer" to base.toString(), "X-Requested-With" to "app.netmirror.netmirrornew")

        private suspend fun text(url: HttpUrl, publicNetflix: Boolean = false): HttpTextResponse {
            if (++requests > 32) throw IOException("Playback request budget exceeded")
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("Invalid playback URL")
            val request = Request.Builder().url(url).apply {
                if (publicNetflix) {
                    header("User-Agent", "Mozilla/5.0"); header("Accept", "text/html")
                    header("Accept-Language", "en-US,en;q=0.9")
                } else {
                    mediaHeaders.forEach { (name, value) -> header(name, value) }
                    if (url.host == base.host && url.encodedPath.endsWith(".php")) header("X-Requested-With", "XMLHttpRequest")
                }
            }.build()
            val execute: suspend () -> HttpTextResponse = {
                PlaybackServiceGate.check()
                val response = http.fetchText(request, if (publicNetflix) 6L * 1024 * 1024 else 1024L * 1024)
                if (!publicNetflix) PlaybackServiceGate.checkResponse(response.code, response.body, response.header("Retry-After"), url.toString())
                response
            }
            return if (url.host == base.host) PlaybackServiceGate.request(block = execute) else execute()
        }

        private fun requireSuccess(response: HttpTextResponse) {
            if (response.code in listOf(401, 403) || Regex("invalid user|login required|session expired|only valid users allowed", RegexOption.IGNORE_CASE).containsMatchIn(response.body))
                throw IOException("Playback metadata requires authorization")
            if (!response.isSuccessful) throw IOException("Playback endpoint unavailable (HTTP ${response.code})")
        }

        private suspend fun json(path: String, vararg params: Pair<String, String>): Any {
            val url = base.newBuilder().encodedPath(path).query(null).apply { params.forEach { addQueryParameter(it.first, it.second) } }.build()
            val response = text(url); requireSuccess(response)
            return try { JSONTokener(response.body).nextValue().also {
                if (it !is JSONObject && it !is JSONArray) throw IOException("Invalid playback metadata")
            } } catch (e: org.json.JSONException) { throw IOException("Invalid playback metadata", e) }
        }

        private suspend fun publicIdentity(tmdbId: String, type: String, title: String, year: String): Pair<String, String>? {
            if (!tmdbId.matches(Regex("\\d+"))) return null
            val seeded = PublicProviderIdentity.seed(tmdbId, type, year)
            val indexed = catalog?.candidates(type, tmdbId).orEmpty()
            validateCandidates(indexed.filter { it !in attempted }, title, year, type)?.let { return it }
            for ((partnerId, path) in catalog?.hotstarTitles(type,title).orEmpty().take(3)) {
                try {
                    val page = text(("https://www.airtelxstream.in" + path).toHttpUrl(), publicNetflix = true)
                    requireSuccess(page)
                    if (!PublicProviderIdentity.matchesAirtel(page.body,title,year)) continue
                    // Series partner IDs can retain older working native IDs; movie partner IDs often cannot.
                    val nativeIds = (if (partnerId.length >= 10) listOf(partnerId) else emptyList()) +
                        PublicProviderIdentity.linkedHotstarIds(page.body)
                    validateCandidates(nativeIds.distinct().map { it to "hs" }.filter { it !in attempted },title,year,type)?.let { return it }
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: IOException) { }
            }
            val ids = if (seeded != null) listOf(seeded) else {
                val externalUrl = "https://api.themoviedb.org/3/$type/$tmdbId/external_ids".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", com.example.BuildConfig.TMDB_API_KEY).build()
                val externalResponse = text(externalUrl, publicNetflix = true)
                requireSuccess(externalResponse)
                val wikidataId = JSONObject(externalResponse.body).optString("wikidata_id")
                if (!wikidataId.matches(Regex("Q[1-9][0-9]*"))) return null
                val entityResponse = text("https://www.wikidata.org/wiki/Special:EntityData/$wikidataId.json".toHttpUrl(), publicNetflix = true)
                requireSuccess(entityResponse)
                val candidates = PublicProviderIdentity.nativeIds(entityResponse.body, wikidataId)
                validateCandidates(candidates.filter { it !in attempted }, title, year, type)?.let { return it }
                emptyList()
            }
            return validateCandidates(ids.map { it to "hs" }.filter { it !in attempted }, title, year, type)
        }

        private suspend fun validateCandidates(candidates: List<Pair<String, String>>, title: String, year: String, type: String): Pair<String, String>? {
            for ((id, ott) in candidates.distinct().take(6)) {
                try {
                    val matches = when (ott) {
                        "nf" -> PublicProviderIdentity.matchesNetflix(netflixPage(id), title, year, type)
                        "pv" -> {
                            val page = text("https://www.primevideo.com/detail/$id".toHttpUrl(), publicNetflix = true)
                            requireSuccess(page)
                            PublicProviderIdentity.matchesPrime(page.body, title, year, type)
                        }
                        "hs" -> (json("/mobile/hs/post.php", "id" to id) as? JSONObject)?.let {
                            PublicProviderIdentity.matches(it, title, year, type)
                        } == true
                        else -> false
                    }
                    if (matches) return id to ott
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: IOException) { }
                catch (_: org.json.JSONException) { }
            }
            return null
        }

        private suspend fun search(title: String, year: String, type: String, tmdbId: String): Pair<String, String> {
            catalog?.verified(key(tmdbId, type, title, year), System.currentTimeMillis())
                ?.takeIf { it !in attempted }?.let { return it }
            // Known public mappings avoid cookie-gated search. Other titles use TMDB -> Wikidata identifiers.
            PublicProviderIdentity.seed(tmdbId, type, year)?.let {
                publicIdentity(tmdbId, type, title, year)?.let { match -> return match }
            }
            for (ott in listOf("nf", "pv")) {
                val paths = if (ott == "nf") listOf("/search.php", "/mobile/search.php") else listOf("${prefix(ott)}/search.php")
                for (path in paths) {
                    val data = try { json(path, "s" to title) } catch (e: IOException) {
                        // A missing catalog route must not become a search in an unrelated catalog.
                        if (e.message == "Playback endpoint unavailable (HTTP 404)") continue else throw e
                    }
                    val obj = data as? JSONObject
                    if (obj?.optString("head") == "Top Searches" || obj?.optString("status") == "n") continue
                    val rows = (data as? JSONArray) ?: obj?.optJSONArray("searchResult") ?: JSONArray()
                    val matches = objects(rows).filter { row ->
                        val raw = field(row, "t", "title", "T", "Title")
                        val suffix = Regex("\\s*\\((\\d{4})\\)\\s*$").find(raw)
                        val comparable = if (suffix?.groupValues?.get(1) == year) raw.substring(0, suffix.range.first) else raw
                        val rowYear = field(row, "y", "year", "Y", "Year")
                        normalize(comparable) == normalize(title) && (rowYear.isBlank() || rowYear == year) && (id(row) to ott) !in attempted
                    }
                    if (matches.size > 1) throw IOException("Provider title identity is ambiguous")
                    matches.singleOrNull()?.let { val id = id(it); if (id.isNotBlank()) return id to ott }
                }
            }
            publicIdentity(tmdbId, type, title, year)?.let { return it }
            throw IOException("No verified public provider identity is available for this title")
        }

        private suspend fun publicSeasons(showId: String, title: String): Map<Int, String> {
            val key = "$showId:${normalize(title)}"
            netflixSeasons[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.seasons }
            if (!showId.matches(Regex("\\d+"))) throw IOException("Invalid Netflix show ID")
            val seasons = parseNetflixSeasons(netflixPage(showId), title)
            if (netflixSeasons.size >= 100) netflixSeasons.keys.firstOrNull()?.let(netflixSeasons::remove)
            netflixSeasons[key] = SeasonCache(System.currentTimeMillis() + 3_600_000L, seasons)
            return seasons
        }

        private suspend fun netflixPage(showId: String): String {
            if (!showId.matches(Regex("[0-9]{5,20}"))) throw IOException("Invalid Netflix show ID")
            var url = "https://www.netflix.com/title/$showId".toHttpUrl()
            repeat(3) {
                val response = text(url, publicNetflix = true)
                if (response.code in listOf(301, 302, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve) ?: throw IOException("Invalid Netflix redirect")
                    if (!next.isHttps || next.host != "www.netflix.com" || !next.encodedPath.endsWith("/title/$showId")) throw IOException("Invalid Netflix redirect")
                    url = next
                } else {
                    requireSuccess(response)
                    return response.body
                }
            }
            throw IOException("Netflix public season metadata unavailable")
        }

        private suspend fun episodeId(showId: String, ott: String, title: String, season: Int, episode: Int): String {
            val seasonId = if (ott == "nf") {
                publicSeasons(showId, title)[season] ?: throw IOException("Requested season is unavailable")
            } else if (ott == "pv" && showId == PRIME_MR_ROBOT.first()) {
                PRIME_MR_ROBOT.getOrNull(season - 1) ?: throw IOException("Requested season is unavailable")
            } else if (ott == "pv") {
                val page = text("https://www.primevideo.com/detail/$showId".toHttpUrl(), publicNetflix = true)
                requireSuccess(page)
                parsePrimeSeasons(page.body, title)[season] ?: throw IOException("Requested Prime season is unavailable in the public catalog")
            } else {
                val data = json("${prefix(ott)}/post.php", "id" to showId) as? JSONObject ?: throw IOException("Season catalog unavailable")
                findEpisode(data.optJSONArray("episodes") ?: JSONArray(), season, episode)?.let { return it }
                val rows = objects(data.optJSONArray("season") ?: data.optJSONArray("seasons") ?: JSONArray())
                    .filter { number(field(it, "s", "season", "name", "title")) == season }
                rows.singleOrNull()?.let(::id)?.takeIf(String::isNotBlank) ?: throw IOException("Requested season ID is unavailable")
            }
            for (page in 1..5) {
                val params = mutableListOf("s" to seasonId, "series" to showId)
                if (page > 1) params += "page" to page.toString()
                val data = json("${prefix(ott)}/episodes.php", *params.toTypedArray()) as? JSONObject ?: throw IOException("Episode catalog unavailable")
                val rows = data.optJSONArray("episodes") ?: JSONArray()
                objects(rows).forEach { row ->
                    val label = field(row, "s", "season", "s_num")
                    if (label.isNotBlank() && number(label) != season) throw IOException("Provider returned a different season")
                }
                findEpisode(rows, season, episode)?.let { return it }
                if (data.optString("nextPageShow") != "1") break
            }
            throw IOException("Requested episode S${season}E${episode} is unavailable")
        }

        suspend fun resolve(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String): PublicPlaybackResult {
            val identityKey = key(tmdbId, type, title, year)
            var last: IOException? = null
            repeat(3) {
                try {
                    val result = resolveOne(title, year, type, season, episode, tmdbId)
                    selected?.let { (id, ott) -> catalog?.save(identityKey, id, ott, System.currentTimeMillis()) }
                    return result
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (error: IOException) {
                    catalog?.evict(identityKey)
                    last = error
                    if (selected == null || requests >= 28 || !(error.message.orEmpty().startsWith("Issued") ||
                        error.message.orEmpty().startsWith("Playback manifest") ||
                        error.message.orEmpty().startsWith("Playback endpoint unavailable") ||
                        error.message.orEmpty().startsWith("Provider returned an expired"))) throw error
                }
            }
            throw last ?: IOException("No available public playback source")
        }

        private suspend fun resolveOne(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String): PublicPlaybackResult {
            selected = null
            val (showId, ott) = search(title, year, type, tmdbId)
            selected = showId to ott
            attempted.add(showId to ott)
            val contentId = if (type == "tv") episodeId(showId, ott, title, season, episode) else showId
            val data = json("${prefix(ott)}/playlist.php", "id" to contentId, "t" to title, "tm" to (System.currentTimeMillis() / 1000).toString())
            val item = (data as? JSONObject) ?: (data as? JSONArray)?.optJSONObject(0) ?: throw IOException("Issued playlist unavailable")
            val sources = objects(item.optJSONArray("sources") ?: JSONArray())
            val source = sources.firstOrNull { it.optString("label") == "Auto" } ?: sources.firstOrNull { it.optString("file").isNotBlank() }
            val issued = source?.optString("file")?.let(base::resolve) ?: throw IOException("Issued HLS URL unavailable")
            val route = ProviderMasterRequest.resolve(issued.toString(), contentId, base.toString())
            if (route.toHttpUrl().queryParameter("in")?.startsWith("unknown") == true) throw IOException("Issued HLS authorization unavailable")
            var url = route.toHttpUrl(); var expiry = System.currentTimeMillis() + 3_600_000L
            repeat(4) {
                val response = text(url); requireSuccess(response)
                if (!response.body.trimStart().startsWith("#EXTM3U")) throw IOException("Playback manifest unavailable")
                CdnRoutePolicy.earliestManifestExpiry(response.body, System.currentTimeMillis())?.let { expiry = minOf(expiry, it) }
                if (expiry - System.currentTimeMillis() <= StreamSessionPolicy.EXPIRY_MARGIN_MS) throw IOException("Provider returned an expired playback link")
                val lines = response.body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
                if (index < 0) {
                    if (!response.body.contains("#EXTINF:")) throw IOException("Media playlist unavailable")
                    val tracks = item.optJSONArray("tracks") ?: item.optJSONArray("captions") ?: item.optJSONArray("subtitles") ?: JSONArray()
                    val captions = objects(tracks).mapNotNull { track ->
                        val file = field(track, "file", "src", "url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val absolute = base.resolve(file)?.takeIf { it.isHttps } ?: return@mapNotNull null
                        val kind = track.optString("kind")
                        if (kind.isNotBlank() && kind !in listOf("subtitles", "captions", "vtt", "thumbnails")) return@mapNotNull null
                        Caption(absolute.toString(), field(track, "label", "language", "name", "lang").ifBlank { "English" },
                            if (kind == "thumbnails") kind else if (file.contains(".srt")) "srt" else "vtt", field(track, "srclang", "language", "lang", "code").ifBlank { "en" })
                    }
                    // Keep the issued master so Media3 retains adaptive video and alternate audio.
                    return PublicPlaybackResult(route, mediaHeaders, captions, contentId, ott, expiry)
                }
                val child = lines.getOrNull(index + 1)?.takeIf { !it.startsWith('#') } ?: throw IOException("Missing playback video variant")
                url = url.resolve(child) ?: throw IOException("Invalid playback variant")
            }
            throw IOException("Playback manifest nesting is invalid")
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0"
        private val CATALOGS = listOf("nf", "pv", "hs", "dp", "hb", "atp", "pm", "pc", "hlu")
        // Exact season IDs previously verified against official Prime season links.
        private val PRIME_MR_ROBOT = listOf("0L52QDYY6OG738LB7ILP0VB7R4", "0SJJSQE04USSW0CM5BMESSR1IG", "0IZIIF0YZ4HGFICLLYB4SAHQDN", "0FGILMYR4HOOKYY2K9NH7UE378")
        private fun prefix(ott: String) = if (ott == "nf") "/mobile" else "/mobile/$ott"
        private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        private fun objects(rows: JSONArray) = (0 until rows.length()).mapNotNull(rows::optJSONObject)
        private fun field(obj: JSONObject, vararg names: String) = names.firstNotNullOfOrNull { obj.optString(it).takeIf(String::isNotBlank) }.orEmpty()
        private fun id(obj: JSONObject) = field(obj, "id", "Id", "sid")
        private fun number(s: String) = Regex("\\d+").find(s)?.value?.toIntOrNull()
        private fun findEpisode(rows: JSONArray, season: Int, episode: Int): String? = objects(rows).firstOrNull {
            val label = field(it, "s", "season", "s_num")
            (label.isBlank() || number(label) == season) && number(field(it, "ep", "episode", "e")) == episode
        }?.let(::id)?.takeIf(String::isNotBlank)

        internal fun parsePrimeSeasons(html: String, title: String): Map<Int, String> {
            val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
            for (script in scripts.findAll(html)) {
                val root = try { JSONObject(script.groupValues[1]) } catch (_: org.json.JSONException) { continue }
                val state = root.optJSONObject("init")?.optJSONObject("preparations")?.optJSONObject("body")
                    ?.optJSONObject("atf")?.optJSONObject("state") ?: continue
                val details = state.optJSONObject("detail")?.optJSONObject("headerDetail") ?: continue
                val matches = details.keys().asSequence().mapNotNull { details.optJSONObject(it) }.filter {
                    val baseTitle = it.optString("title").replace(Regex("(?i)\\s*[-:]?\\s*Season\\s+\\d+$"), "")
                    it.optString("titleType") == "season" && normalize(baseTitle) == normalize(title)
                }.toList()
                if (matches.size != 1) continue
                val seasons = state.optJSONObject("seasons") ?: continue
                val ids = mutableMapOf<Int, String>()
                for (key in seasons.keys()) {
                    for (row in objects(seasons.optJSONArray(key) ?: JSONArray())) {
                        val number = row.optInt("sequenceNumber", 0)
                        val link = "https://www.primevideo.com".toHttpUrl().resolve(row.optString("seasonLink")) ?: continue
                        if (link.host != "www.primevideo.com") continue
                        val id = Regex("^/detail/([A-Z0-9]{10,30})$").find(link.encodedPath)?.groupValues?.get(1) ?: continue
                        if (number !in 1..30 || ids.containsKey(number) && ids[number] != id) throw IOException("Ambiguous Prime season metadata")
                        ids[number] = id
                    }
                }
                if (ids.isNotEmpty()) return ids
            }
            throw IOException("Prime public season metadata unavailable")
        }

        internal fun parseNetflixSeasons(html: String, title: String): Map<Int, String> {
            val scripts = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            val identities = scripts.findAll(html).flatMap { match ->
                val data = try { JSONTokener(match.groupValues[1]).nextValue() } catch (_: org.json.JSONException) { null }
                when (data) { is JSONObject -> listOf(data); is JSONArray -> objects(data); else -> emptyList() }
            }
            if (identities.none { normalize(it.optString("name")) == normalize(title) && it.optString("@type").contains("TVSeries") }) throw IOException("Netflix show identity mismatch")
            val selector = Regex("""<select\b[^>]*name=["']seasonSelect["'][^>]*>([\s\S]*?)</select>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: throw IOException("Netflix public seasons unavailable")
            val pairs = Regex("""<option\b[^>]*value=["'](\d+)["'][^>]*>\s*Season\s+(\d+)\s*</option>""", RegexOption.IGNORE_CASE)
                .findAll(selector).map { (it.groupValues[2].toIntOrNull() ?: throw IOException("Invalid Netflix season metadata")) to it.groupValues[1] }.toList()
            if (pairs.isEmpty() || pairs.size > 30 || pairs.any { it.first !in 1..30 } || pairs.map { it.first }.distinct().size != pairs.size || pairs.map { it.second }.distinct().size != pairs.size) throw IOException("Invalid Netflix season metadata")
            return pairs.toMap()
        }
    }
}
