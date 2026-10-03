package com.example.data

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class PublicPlaybackResult(
    val url: String, val headers: Map<String, String>, val captions: List<Caption>,
    val contentId: String, val ott: String, val expiresAt: Long
)

/** Verified public title/season/episode identities -> cookie-free playback without warming. */
internal class PublicPlaybackResolver(client: OkHttpClient, baseUrl: String = "https://net52.cc", private val catalog: PublicProviderCatalog? = null,
    private val backgroundCatalogRefresh: Boolean = true, private val runtimeConfig: ProviderRuntimeConfig? = null) {
    private val defaultBase = baseUrl.toHttpUrl()
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).build()
    private data class SeasonCache(val expiresAt: Long, val seasons: Map<Int, String>)
    private val netflixSeasons = ConcurrentHashMap<String, SeasonCache>()

    suspend fun resolve(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String = ""): PublicPlaybackResult = withTimeout(28_000L) {
        if (normalize(title).isEmpty() || type !in listOf("movie", "tv") || type == "tv" && (season < 1 || episode < 1))
            throw IOException("Invalid title or episode selection")
        if (backgroundCatalogRefresh) runtimeConfig?.refreshInBackground()
        Run().resolve(title, year, type, season, episode, tmdbId)
    }

    private inner class Run {
        private val settings = runtimeConfig?.snapshot() ?: ProviderRuntimeConfig.Snapshot(baseUrl = defaultBase.toString())
        private val base = settings.baseUrl.toHttpUrl()
        private val requestCounter = java.util.concurrent.atomic.AtomicInteger()
        private val requests get() = requestCounter.get()
        private val attempted = mutableSetOf<Pair<String, String>>()
        private val unavailable = mutableSetOf<Pair<String, String>>()
        private val typedCandidates = mutableSetOf<Pair<String, String>>()
        private val publicPages = mutableMapOf<Pair<String, String>, String>()
        private val providerDetails = mutableMapOf<Pair<String, String>, JSONObject>()
        private val searchRows = linkedMapOf<String, JSONArray>()
        private var liveBrowseChecked = false
        private var discoveryChecked = false
        private val titleAliases = linkedSetOf<String>()
        private var forcedRefresh = false
        private var selected: Pair<String, String>? = null
        private var cachedEpisodeUsed = false
        private var cachedEpisodeRefreshed = false
        private var retryIdentity: Pair<String, String>? = null
        private fun key(tmdb: String, type: String, title: String, year: String) = "$base:$type:$tmdb:${normalize(title)}:$year"
        private val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Origin" to base.toString().trimEnd('/'),
            "Referer" to base.toString(), "X-Requested-With" to "app.netmirror.netmirrornew")

        private suspend fun text(url: HttpUrl, publicNetflix: Boolean = false): HttpTextResponse {
            if (requestCounter.incrementAndGet() > 32) throw IOException("Playback request budget exceeded")
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

        private fun requirePublicSuccess(response: HttpTextResponse) {
            if (!response.isSuccessful) throw IOException("Public title metadata unavailable (HTTP ${response.code})")
        }

        private suspend fun json(path: String, vararg params: Pair<String, String>): Any {
            val url = base.newBuilder().encodedPath(path).query(null).apply { params.forEach { addQueryParameter(it.first, it.second) } }.build()
            val response = text(url); requireSuccess(response)
            return try { JSONTokener(response.body).nextValue().also {
                if (it !is JSONObject && it !is JSONArray) throw IOException("Invalid playback metadata")
            } } catch (e: org.json.JSONException) { throw IOException("Invalid playback metadata", e) }
        }

        private suspend fun publicIdentity(tmdbId: String, type: String, title: String, year: String, includeDiscovery: Boolean = true): Pair<String, String>? {
            if (!tmdbId.matches(Regex("\\d+"))) return null
            val discoveryKey = "$type:$tmdbId"
            catalog?.discovered(discoveryKey, System.currentTimeMillis())?.let { cached ->
                val before = titleAliases.size
                titleAliases += cached.aliases
                typedCandidates.addAll(cached.typedIds)
                if (titleAliases.size != before) attempted.retainAll(unavailable)
                validateCandidates(cached.ids.filter { it !in attempted && (includeDiscovery || type != "movie" || it.second == "hs") }, title, year, type)?.let { return it }
            }
            val seeded = PublicProviderIdentity.seed(tmdbId, type, year)
            if (seeded != null) typedCandidates.add(seeded to "hs")
            typedCandidates.addAll(catalog?.candidates(type, tmdbId).orEmpty())
            suspend fun partnerIdentity(partnerCandidates: List<Pair<String, String>>): Pair<String, String>? {
              for ((partnerId, path) in partnerCandidates.distinct().take(6)) {
                // A short partner ID may translate to a separate native ID on the title page.
                try {
                    val html = publicPages[path to "partner"] ?: text(("https://www.airtelxstream.in" + path).toHttpUrl(), publicNetflix = true)
                        .also(::requirePublicSuccess).body.also { publicPages[path to "partner"] = it }
                    if (titleAliases.none { PublicProviderIdentity.matchesAirtel(html,it,year,type, (partnerId to "hs") in typedCandidates) }) continue
                    val nativeIds = (if (partnerId.length >= 10) listOf(partnerId) else emptyList()) +
                        PublicProviderIdentity.linkedHotstarIds(html)
                    validateCandidates(nativeIds.distinct().map { it to "hs" }.filter { it !in attempted },title,year,type)?.let { return it }
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: IOException) { if (e.message == "Playback metadata requires authorization") throw e }
              }
              return null
            }
            if (seeded != null) {
                typedCandidates.add(seeded to "hs")
                validateCandidates(listOf(seeded to "hs").filter { it !in attempted }, title, year, type)?.let { return it }
            }
            val indexed = catalog?.candidates(type, tmdbId).orEmpty()
            typedCandidates.addAll(indexed)
            validateCandidates(indexed.filter { it !in attempted && (includeDiscovery || type != "movie" || it.second == "hs") }, title, year, type)?.let { return it }
            if (includeDiscovery || type != "movie") partnerIdentity(catalog?.hotstarTitles(type,title).orEmpty())?.let { return it }
            if (!includeDiscovery) return null
            discoverIdentity(tmdbId, type, title, year)?.let { return it }
            if (!liveBrowseChecked) {
                liveBrowseChecked = true
                val partnerCandidates = mutableListOf<Pair<String, String>>()
                val section = if (type == "tv") "tv-shows" else "movies"
                val languagePage = if (type == "tv") "english-tv-shows" else "english-movies"
                for (path in listOf("/$section", "/$section/$languagePage")) {
                    try {
                        val page = text(("https://www.airtelxstream.in" + path).toHttpUrl(), publicNetflix = true)
                        requirePublicSuccess(page)
                        partnerCandidates += PublicProviderIdentity.partnerLinks(page.body, title, type)
                        partnerIdentity(partnerCandidates)?.let { return it }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: IOException) { if (e.message == "Playback metadata requires authorization") throw e }
                }
                partnerIdentity(partnerCandidates)?.let { return it }
            }
            return null
        }

        private suspend fun discoverIdentity(tmdbId: String, type: String, title: String, year: String): Pair<String, String>? = coroutineScope {
            if (discoveryChecked) return@coroutineScope null
            discoveryChecked = true
            val discoveryKey = "$type:$tmdbId"
            suspend fun publicJson(url: HttpUrl): JSONObject? = try {
                val response = text(url, publicNetflix = true)
                if (!response.isSuccessful) null else JSONObject(response.body)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IOException) { null }
            catch (_: org.json.JSONException) { null }
            data class DiscoveryBatch(val ids: List<Pair<String, String>>, val typed: Boolean)
            val pending = mutableListOf<Deferred<DiscoveryBatch>>()
            val publicCandidates = mutableListOf<Pair<String, String>>()
            try {
                // Typed Wikidata lookup and TMDB aliases are independent; do not serialize their latency.
                PublicIdentityDiscovery.wikidataQuery(tmdbId, type)?.let { query ->
                    pending += async {
                        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
                            .addQueryParameter("query", query).addQueryParameter("format", "json").build()
                        DiscoveryBatch(publicJson(url)?.let { PublicIdentityDiscovery.joinedIds(it, tmdbId, type) }.orEmpty(), true)
                    }
                }
                val metadataUrl = "https://api.themoviedb.org/3/$type/$tmdbId".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", com.example.BuildConfig.TMDB_API_KEY)
                    .addQueryParameter("append_to_response", "external_ids,alternative_titles").build()
                val metadata = publicJson(metadataUrl)?.let { PublicIdentityDiscovery.metadata(it, tmdbId, type, year) }
                if (metadata != null) {
                    val before = titleAliases.size
                    titleAliases += metadata.aliases
                    if (titleAliases.size != before) attempted.retainAll(unavailable)
                    cachedSearchIdentity(title, year, type)?.let { return@coroutineScope it }
                    typedCandidates.addAll(metadata.homepageIds)
                    if (metadata.homepageIds.isNotEmpty()) {
                        catalog?.saveDiscovered(discoveryKey, metadata.homepageIds, titleAliases.toList(), System.currentTimeMillis(), metadata.homepageIds)
                        validateCandidates(metadata.homepageIds.filter { it !in attempted }, title, year, type)?.let { return@coroutineScope it }
                    }
                    validateCandidates(catalog?.candidates(type, tmdbId).orEmpty().filter { it !in attempted }, title, year, type)?.let { return@coroutineScope it }
                    pending += async {
                        val searchUrl = "https://www.primevideo.com/-/en/search".toHttpUrl().newBuilder()
                            .addQueryParameter("phrase", title).build()
                        val ids = try {
                            val response = text(searchUrl, publicNetflix = true)
                            if (response.isSuccessful) PublicIdentityDiscovery.primeSearchIds(response.body, titleAliases.toList(), year, type) else emptyList()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: IOException) { emptyList() }
                        DiscoveryBatch(ids, false)
                    }
                    if (metadata.wikidataId.isNotEmpty()) pending += async {
                        val entity = publicJson("https://www.wikidata.org/wiki/Special:EntityData/${metadata.wikidataId}.json".toHttpUrl())
                        DiscoveryBatch(entity?.let { PublicProviderIdentity.nativeIds(it.toString(), metadata.wikidataId) }.orEmpty(), true)
                    }
                }
                while (pending.isNotEmpty()) {
                    val (completed, batch) = select<Pair<Deferred<DiscoveryBatch>, DiscoveryBatch>> {
                        pending.forEach { task -> task.onAwait { task to it } }
                    }
                    pending.remove(completed)
                    if (batch.typed) typedCandidates.addAll(batch.ids)
                    publicCandidates += batch.ids
                    if (batch.ids.isEmpty()) continue
                    catalog?.saveDiscovered(discoveryKey, publicCandidates, titleAliases.toList(), System.currentTimeMillis(), typedCandidates.toList())
                    validateCandidates(batch.ids.filter { it !in attempted }, title, year, type)?.let { return@coroutineScope it }
                }
                null
            } finally {
                // A working direct identity cancels slower, unneeded metadata jobs.
                pending.forEach { it.cancel() }
            }
        }

        // Complete public search records can establish movie identity. NF/PV
        // post.php is gated, so never probe it in the zero-cookie flow.
        private fun providerMovieMatches(id: String, ott: String, year: String): Boolean? {
            val details = providerDetails[id to ott] ?: return null
            return titleAliases.any { PublicProviderIdentity.matches(details, it, year, "movie") }
        }

        private suspend fun validateCandidates(candidates: List<Pair<String, String>>, title: String, year: String, type: String): Pair<String, String>? {
            for ((id, ott) in candidates.distinct().take(6)) {
                if (!PublicIdentityDiscovery.validId(id, ott)) continue
                attempted.add(id to ott)
                try {
                    val providerMatch = if (type == "movie" && ott in listOf("nf", "pv"))
                        providerMovieMatches(id, ott, year) else null
                    val matches = providerMatch ?: when (ott) {
                        "nf" -> (type == "tv" && netflixSeasons["$base:$id:${normalize(title)}"]?.let { it.expiresAt > System.currentTimeMillis() } == true) ||
                            titleAliases.any { PublicProviderIdentity.matchesNetflix(netflixPage(id), it, year, type, (id to ott) in typedCandidates) }
                        "pv" -> {
                            val html = publicPages[id to ott] ?: text("https://www.primevideo.com/detail/$id".toHttpUrl(), publicNetflix = true)
                                .also(::requirePublicSuccess).body.also { publicPages[id to ott] = it }
                            titleAliases.any { PublicProviderIdentity.matchesPrime(html, it, year, type, (id to ott) in typedCandidates) }
                        }
                        "hs" -> (json("/mobile/hs/post.php", "id" to id) as? JSONObject)?.let { details ->
                            providerDetails[id to ott] = details
                            titleAliases.any { PublicProviderIdentity.matches(details, it, year, type, (id to ott) in typedCandidates) }
                        } == true
                        else -> false
                    }
                    if (matches) return id to ott
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: IOException) { if (e.message == "Playback metadata requires authorization") throw e }
                catch (_: org.json.JSONException) { }
            }
            return null
        }

        private suspend fun search(title: String, year: String, type: String, tmdbId: String): Pair<String, String> {
            titleAliases += title
            titleAliases += catalog?.verifiedAliases(key(tmdbId, type, title, year)).orEmpty()
            catalog?.verified(key(tmdbId, type, title, year), System.currentTimeMillis())
                ?.takeIf { it !in attempted }?.let { return it }
            // Try existing candidates and public provider search before slower identity discovery.
            publicIdentity(tmdbId, type, title, year, includeDiscovery = false)?.let { return it }
            for (ott in listOf("nf", "pv")) {
                val paths = if (ott == "nf") listOf("/search.php") else listOf("${prefix(ott)}/search.php")
                for (path in paths) {
                    val data = try { json(path, "s" to title) } catch (e: IOException) {
                        // A missing catalog route must not become a search in an unrelated catalog.
                        if (e.message == "Playback endpoint unavailable (HTTP 404)") continue else throw e
                    }
                    val obj = data as? JSONObject
                    if (obj?.optString("head") == "Top Searches" || obj?.optString("status") == "n") continue
                    val rows = (data as? JSONArray) ?: obj?.optJSONArray("searchResult") ?: JSONArray()
                    searchRows[ott] = rows
                    cachedSearchIdentity(title, year, type)?.let { return it }
                }
            }
            publicIdentity(tmdbId, type, title, year)?.let { return it }
            throw IOException("No verified public provider identity is available for this title")
        }

        // Authoritative aliases arrive after the first provider search. Reuse its
        // records so provider-native IDs are retained without another search.
        private suspend fun cachedSearchIdentity(title: String, year: String, type: String): Pair<String, String>? {
            for ((ott, rows) in searchRows) {
                val matches = objects(rows).filter { row ->
                    val raw = field(row, "t", "title", "T", "Title")
                    val suffix = Regex("\\s*\\((\\d{4})\\)\\s*$").find(raw)
                    val comparable = if (suffix?.groupValues?.get(1) == year) raw.substring(0, suffix.range.first) else raw
                    val rowYear = field(row, "y", "year", "Y", "Year")
                    val rowType = field(row, "type", "media_type").lowercase(java.util.Locale.ROOT)
                    val rightType = when (rowType) { "t", "tv", "series" -> type == "tv"; "m", "movie" -> type == "movie"; else -> true }
                    titleAliases.any { PublicIdentityDiscovery.sameTitle(comparable, it) } && rightType &&
                        (type == "tv" || rowYear.isBlank() || rowYear == year) && (id(row) to ott) !in attempted
                }
                if (matches.size > 1 && matches.any { !id(it).matches(Regex(if (ott == "pv") "[A-Z0-9]{10,30}" else "[0-9]{5,20}")) })
                    throw IOException("Provider title identity is ambiguous")
                if (type == "movie") matches.forEach { row ->
                    val kind = field(row, "type", "media_type").lowercase(java.util.Locale.ROOT)
                    val release = field(row, "y", "year", "Y", "Year")
                    if (kind in listOf("m", "movie") && release.matches(Regex("[0-9]{4}"))) {
                        providerDetails[id(row) to ott] = JSONObject().put("status", "y")
                            .put("id", id(row)).put("title", field(row, "t", "title", "T", "Title").removeSuffix(" ($release)"))
                            .put("type", "m").put("year", release)
                    }
                }
                validateCandidates(matches.map { id(it) to ott }, title, year, type)?.let { return it }
            }
            return null
        }

        private suspend fun publicSeasons(showId: String, title: String, requestedSeason: Int): Map<Int, String> {
            val now = System.currentTimeMillis()
            val key = "$base:$showId:${normalize(title)}"
            netflixSeasons[key]?.takeIf { it.expiresAt > now && requestedSeason in it.seasons }?.let { return it.seasons }
            val stored = catalog?.episodeCatalog?.seasons(base.toString(), "nf", showId, now).orEmpty()
            if (requestedSeason in stored) return stored
            if (netflixSeasons.containsKey(key)) publicPages.remove(showId to "nf")
            if (!showId.matches(Regex("[0-9]{5,20}"))) throw IOException("Invalid Netflix show ID")
            val public = try {
                val html = netflixPage(showId)
                val matchedTitle = titleAliases.firstOrNull { PublicProviderIdentity.matchesNetflix(html, it, "", "tv", typedMapping = true) } ?: title
                parseNetflixSeasons(html, matchedTitle)
            } catch (error: IOException) {
                if (error.message !in listOf("Netflix public seasons unavailable", "Netflix public season labels are unavailable")) throw error
                emptyMap()
            }
            if (public.isNotEmpty()) catalog?.episodeCatalog?.saveSeasons(base.toString(), "nf", showId, public, now, "official-public")
            val seasons = public
            if (requestedSeason !in seasons) throw IOException("Requested season is unavailable")
            if (netflixSeasons.size >= 100) netflixSeasons.keys.firstOrNull()?.let(netflixSeasons::remove)
            netflixSeasons[key] = SeasonCache(System.currentTimeMillis() + 3_600_000L, seasons)
            return seasons
        }

        private suspend fun netflixPage(showId: String): String {
            publicPages[showId to "nf"]?.let { return it }
            if (!showId.matches(Regex("[0-9]{5,20}"))) throw IOException("Invalid Netflix show ID")
            var url = "https://www.netflix.com/title/$showId".toHttpUrl()
            repeat(3) {
                val response = text(url, publicNetflix = true)
                if (response.code in listOf(301, 302, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve) ?: throw IOException("Invalid Netflix redirect")
                    if (!next.isHttps || next.host != "www.netflix.com" || !next.encodedPath.endsWith("/title/$showId")) throw IOException("Invalid Netflix redirect")
                    url = next
                } else {
                    requirePublicSuccess(response)
                    publicPages[showId to "nf"] = response.body
                    return response.body
                }
            }
            throw IOException("Netflix public season metadata unavailable")
        }

        private fun cacheLabelledEpisodes(showId: String, ott: String, season: Int, rows: JSONArray, verifiedSeason: Boolean = false) {
            val labelled = objects(rows).filter {
                val label = field(it, "s", "season", "s_num").trim()
                val embedded = episodeSeason(field(it, "ep", "episode", "e", "episode_number"))
                (embedded == null || embedded == season) && ((verifiedSeason && label.isBlank()) ||
                    embedded == season && label.isBlank() ||
                    Regex("(?i)(?:season|s)?\\s*([0-9]+)").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull() == season)
            }
            val grouped = labelled.mapNotNull { row ->
                episodeNumber(field(row, "ep", "episode", "e", "episode_number"))?.let { it to id(row) }
            }.groupBy({ it.first }, { it.second })
            if (grouped.values.any { it.distinct().size > 1 }) throw IOException("Provider episode identity is ambiguous")
            val episodes = grouped.mapValues { it.value.first() }
            if (episodes.values.distinct().size != episodes.size) throw IOException("Provider episode identity is ambiguous")
            catalog?.episodeCatalog?.saveEpisodes(base.toString(), ott, showId, season, episodes, System.currentTimeMillis())
        }

        private suspend fun episodeId(showId: String, ott: String, title: String, season: Int, episode: Int): String {
            cachedEpisodeUsed = false
            catalog?.episodeCatalog?.episode(base.toString(), ott, showId, season, episode, System.currentTimeMillis())?.let {
                cachedEpisodeUsed = true; return it
            }
            val cachedSeason = catalog?.episodeCatalog?.seasons(base.toString(), ott, showId, System.currentTimeMillis())?.get(season)
                ?: if (ott != "hs") catalog?.seasonCandidates(base.toString(), ott, showId, titleAliases.toList())?.get(season) else null
            val seasonId = cachedSeason ?: if (ott == "nf") {
                publicSeasons(showId, title, season)[season] ?: throw IOException("Requested season is unavailable")
            } else if (ott == "pv" && showId == PRIME_MR_ROBOT.first()) {
                PRIME_MR_ROBOT.getOrNull(season - 1) ?: throw IOException("Requested season is unavailable")
            } else if (ott == "pv") {
                val html = publicPages[showId to ott] ?: text("https://www.primevideo.com/detail/$showId".toHttpUrl(), publicNetflix = true).also(::requirePublicSuccess).body
                val seasons = parsePrimeSeasons(html, titleAliases.firstOrNull { PublicProviderIdentity.matchesPrime(html, it, year = "", type = "tv", typedMapping = true) } ?: title, showId)
                catalog?.episodeCatalog?.saveSeasons(base.toString(), ott, showId, seasons, System.currentTimeMillis(), "official-public")
                seasons[season] ?: throw IOException("Requested Prime season is unavailable in the public catalog")
            } else {
                val data = providerDetails[showId to ott] ?: (json("${prefix(ott)}/post.php", "id" to showId) as? JSONObject) ?: throw IOException("Season catalog unavailable")
                if (titleAliases.none { PublicProviderIdentity.matches(data, it, "", "tv", typedMapping = true) }) throw IOException("Provider show identity mismatch")
                val directRows = data.optJSONArray("episodes") ?: JSONArray()
                cacheLabelledEpisodes(showId, ott, season, directRows)
                findEpisode(directRows, season, episode, requireSeasonLabel = true)?.let { return it }
                val rows = objects(data.optJSONArray("season") ?: data.optJSONArray("seasons") ?: JSONArray())
                    .filter { number(field(it, "s", "season", "name", "title")) == season }
                rows.singleOrNull()?.let(::id)?.takeIf(String::isNotBlank)?.also { id ->
                    catalog?.episodeCatalog?.saveSeasons(base.toString(), ott, showId, mapOf(season to id), System.currentTimeMillis(), "provider-public")
                } ?: throw IOException("Requested season ID is unavailable")
            }
            for (page in 1..5) {
                val params = mutableListOf("s" to seasonId, "series" to showId)
                if (page > 1) params += "page" to page.toString()
                val data = json("${prefix(ott)}/episodes.php", *params.toTypedArray()) as? JSONObject ?: throw IOException("Episode catalog unavailable")
                val rows = data.optJSONArray("episodes") ?: JSONArray()
                objects(rows).forEach { row ->
                    val label = field(row, "s", "season", "s_num")
                    val embedded = episodeSeason(field(row, "ep", "episode", "e", "episode_number"))
                    if (label.isNotBlank() && number(label) != season || embedded != null && embedded != season) {
                        catalog?.episodeCatalog?.evictSeason(base.toString(), ott, showId, season, System.currentTimeMillis())
                        throw IOException("Provider returned a different season")
                    }
                }
                if (rows.length() > 0) catalog?.episodeCatalog?.saveSeasons(base.toString(), ott, showId,
                    mapOf(season to seasonId), System.currentTimeMillis(), "provider-public")
                cacheLabelledEpisodes(showId, ott, season, rows, verifiedSeason = true)
                findEpisode(rows, season, episode)?.let { return it }
                if (data.optString("nextPageShow") != "1") break
            }
            throw IOException("Requested episode S${season}E${episode} is unavailable")
        }

        suspend fun resolve(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String): PublicPlaybackResult {
            if (backgroundCatalogRefresh) catalog?.refreshInBackground(http)
            val identityKey = key(tmdbId, type, title, year)
            var last: IOException? = null
            repeat(3) {
                try {
                    val result = resolveOne(title, year, type, season, episode, tmdbId)
                    selected?.let { (id, ott) -> catalog?.save(identityKey, id, ott, System.currentTimeMillis(), titleAliases.toList()) }
                    return result
                } catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (error: IOException) {
                    // A media route failure does not disprove the verified show identity.
                    if (selected == null || error.message == "Provider show identity mismatch") catalog?.evict(identityKey)
                    val mediaFailure = error.message.orEmpty().let { it.startsWith("Issued") || it.startsWith("Playback manifest") ||
                        it.startsWith("Playback endpoint unavailable") || it.startsWith("Provider returned an expired") }
                    if (type == "tv" && cachedEpisodeUsed && !cachedEpisodeRefreshed && mediaFailure && selected != null && requests < 28) {
                        cachedEpisodeRefreshed = true
                        selected?.let { (id, ott) -> catalog?.episodeCatalog?.evictEpisode(base.toString(), ott, id, season, episode, System.currentTimeMillis()) }
                        retryIdentity = selected
                        last = error
                        return@repeat
                    }
                    if (selected == null && error.message == "No verified public provider identity is available for this title") last?.let { throw it }
                    last = error
                    if (selected == null || requests >= 28 || !(error.message.orEmpty().startsWith("Issued") ||
                        error.message.orEmpty().startsWith("Playback manifest") ||
                        error.message.orEmpty().startsWith("Playback endpoint unavailable") ||
                        error.message.orEmpty().startsWith("Provider returned an expired") ||
                        error.message.orEmpty().startsWith("Requested season") ||
                        error.message.orEmpty().startsWith("Requested Prime season") ||
                        error.message == "Prime public season metadata unavailable" ||
                        error.message.orEmpty().startsWith("Requested episode") ||
                        error.message.orEmpty().startsWith("Provider returned a different season") ||
                        error.message.orEmpty().startsWith("Provider show identity mismatch"))) throw error
                    if (!forcedRefresh && backgroundCatalogRefresh) { forcedRefresh = true; catalog?.refreshInBackground(http, force = true) }
                }
            }
            throw last ?: IOException("No available public playback source")
        }

        private suspend fun resolveOne(title: String, year: String, type: String, season: Int, episode: Int, tmdbId: String): PublicPlaybackResult {
            selected = null
            val (showId, ott) = retryIdentity?.also { retryIdentity = null } ?: search(title, year, type, tmdbId)
            selected = showId to ott
            attempted.add(showId to ott)
            unavailable.add(showId to ott)
            val contentId = if (type == "tv") episodeId(showId, ott, title, season, episode) else showId
            val data = try { json("${prefix(ott)}/playlist.php", "id" to contentId, "t" to title, "tm" to (System.currentTimeMillis() / 1000).toString()) }
                catch (rate: PlaybackRateLimitedException) { throw rate }
                catch (error: IOException) {
                    if (error.message != "Playback endpoint unavailable (HTTP 404)") throw error
                    null
                }
            val item = (data as? JSONObject) ?: (data as? JSONArray)?.optJSONObject(0) ?: JSONObject()
            val sources = objects(item.optJSONArray("sources") ?: JSONArray())
            val source = sources.firstOrNull { it.optString("label") == "Auto" } ?: sources.firstOrNull { it.optString("file").isNotBlank() }
            val issued = source?.optString("file")?.takeIf(String::isNotBlank)?.let(base::resolve)
            val route = if (issued == null) ProviderMasterRequest.direct(contentId, ott, base.toString(), settings)
                else ProviderMasterRequest.resolve(issued.toString(), contentId, base.toString(), settings)
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
        // Exact season IDs previously verified against official Prime season links.
        private val PRIME_MR_ROBOT = listOf("0L52QDYY6OG738LB7ILP0VB7R4", "0SJJSQE04USSW0CM5BMESSR1IG", "0IZIIF0YZ4HGFICLLYB4SAHQDN", "0FGILMYR4HOOKYY2K9NH7UE378")
        private fun prefix(ott: String) = if (ott == "nf") "/mobile" else "/mobile/$ott"
        private fun normalize(s: String) = PublicIdentityDiscovery.normalize(s)
        private fun objects(rows: JSONArray) = (0 until rows.length()).mapNotNull(rows::optJSONObject)
        private fun field(obj: JSONObject, vararg names: String) = names.firstNotNullOfOrNull { obj.optString(it).takeIf(String::isNotBlank) }.orEmpty()
        private fun id(obj: JSONObject) = field(obj, "id", "Id", "sid")
        private fun number(s: String) = Regex("\\d+").find(s)?.value?.toIntOrNull()
        private fun episodeNumber(label: String): Int? = Regex("(?i)(?:s[0-9]+\\s*)?(?:episode|ep|e)?\\s*([0-9]+)")
            .matchEntire(label.trim())?.groupValues?.get(1)?.toIntOrNull()
        private fun episodeSeason(label: String): Int? = Regex("(?i)s([0-9]+)\\s*(?:episode|ep|e)\\s*[0-9]+")
            .matchEntire(label.trim())?.groupValues?.get(1)?.toIntOrNull()
        private fun findEpisode(rows: JSONArray, season: Int, episode: Int, requireSeasonLabel: Boolean = false): String? {
            val ids = objects(rows).filter {
                val label = field(it, "s", "season", "s_num")
                val episodeLabel = field(it, "ep", "episode", "e", "episode_number")
                val episodeNumber = episodeNumber(episodeLabel)
                val embedded = episodeSeason(episodeLabel)
                ((!requireSeasonLabel && label.isBlank()) || number(label) == season || label.isBlank() && embedded == season) &&
                    (embedded == null || embedded == season) && episodeNumber == episode
            }.map(::id).filter(String::isNotBlank).distinct()
            if (ids.size > 1) throw IOException("Provider episode identity is ambiguous")
            return ids.singleOrNull()
        }

        internal fun parsePrimeSeasons(html: String, title: String, currentId: String = ""): Map<Int, String> {
            val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
            for (script in scripts.findAll(html)) {
                val root = try { JSONObject(script.groupValues[1]) } catch (_: org.json.JSONException) { continue }
                val state = root.optJSONObject("init")?.optJSONObject("preparations")?.optJSONObject("body")
                    ?.optJSONObject("atf")?.optJSONObject("state") ?: continue
                val details = state.optJSONObject("detail")?.optJSONObject("headerDetail") ?: continue
                val matches = details.keys().asSequence().filter { key ->
                    val header = details.optJSONObject(key) ?: return@filter false
                    header.optString("titleType") == "season" && PublicIdentityDiscovery.sameTitle(PublicIdentityDiscovery.seriesTitle(header.optString("title"), "tv"), title)
                }.toList()
                if (matches.size != 1) continue
                val seasons = state.optJSONObject("seasons") ?: continue
                val ids = mutableMapOf<Int, String>()
                for (key in matches) {
                    for (row in objects(seasons.optJSONArray(key) ?: JSONArray())) {
                        val number = row.optInt("sequenceNumber", 0)
                        val link = "https://www.primevideo.com".toHttpUrl().resolve(row.optString("seasonLink")) ?: continue
                        if (!link.isHttps || link.host != "www.primevideo.com") continue
                        val id = Regex("^/(?:-/[a-zA-Z_-]+/)?detail/([A-Z0-9]{10,30})$").find(link.encodedPath)?.groupValues?.get(1) ?: continue
                        if (number !in 1..30 || ids.containsKey(number) && ids[number] != id) throw IOException("Ambiguous Prime season metadata")
                        ids[number] = id
                    }
                    val header = details.getJSONObject(key)
                    val labelled = Regex("(?i)Season\\s+([0-9]+)\\s*$").find(header.optString("title"))?.groupValues?.get(1)?.toIntOrNull()
                    val currentSeason = header.optInt("seasonNumber", 0).takeIf { it in 1..30 }
                    if (currentSeason != null && labelled != null && currentSeason != labelled) throw IOException("Ambiguous Prime season metadata")
                    // The verified detail URL is itself a season identity. Its
                    // selector may link to another regional edition unavailable
                    // in the provider, so preserve the provider-published ID.
                    if (PublicIdentityDiscovery.validId(currentId, "pv") && currentSeason != null && currentSeason in 1..30) {
                        if (ids.any { (number, id) -> number != currentSeason && id == currentId }) throw IOException("Ambiguous Prime season metadata")
                        ids[currentSeason] = currentId
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
            if (identities.none { PublicIdentityDiscovery.sameTitle(it.optString("name"), title) && it.optString("@type").contains("TVSeries") }) throw IOException("Netflix show identity mismatch")
            val selector = Regex("""<select\b[^>]*name=["']seasonSelect["'][^>]*>([\s\S]*?)</select>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: throw IOException("Netflix public seasons unavailable")
            val options = Regex("""<option\b[^>]*value=["']([0-9]{5,20})["'][^>]*>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE)
                .findAll(selector).map { it.groupValues[1] to it.groupValues[2].replace(Regex("<[^>]+>"), "").trim() }.toList()
            val pairs = options.map { (id, label) ->
                val explicit = Regex("(?i)^Season\\s+([0-9]+)$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                val branded = if (PublicIdentityDiscovery.sameTitle(label, title)) 1 else {
                    Regex("^(.*?)([0-9]+)$").matchEntire(label)?.takeIf { PublicIdentityDiscovery.sameTitle(it.groupValues[1], title) }
                        ?.groupValues?.get(2)?.toIntOrNull()
                }
                // Do not assign arbitrary named seasons by position; selectors may be newest-first.
                val number = explicit ?: branded
                    ?: throw IOException("Netflix public season labels are unavailable")
                number to id
            }
            if (pairs.isEmpty() || pairs.size > 60 || pairs.any { it.first !in 1..60 } || pairs.map { it.first }.distinct().size != pairs.size || pairs.map { it.second }.distinct().size != pairs.size) throw IOException("Invalid Netflix season metadata")
            return pairs.toMap()
        }
    }
}
