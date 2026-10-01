package com.example.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class ReleaseQuery(val kind: String, val parameters: Map<String, String>, val majorOtt: Boolean)
object ReleaseQueries {
    // The same global editorial baseline on both apps. Availability differs by region/provider.
    const val PROVIDERS = "8|9|119|337|350|384|15|531|387|386"
    const val NETWORKS = "213|49|1024|2739|2552|453|4330|3353"
    fun plan(today: String): List<ReleaseQuery> = buildList {
        for (upcoming in listOf(false, true)) {
            val from = if (upcoming) today else ReleasePolicy.shiftMonths(today, -1)
            val until = if (upcoming) ReleasePolicy.shiftMonths(today, 3) else today
            val common = mapOf("include_adult" to "false", "sort_by" to "popularity.desc", "language" to "en-US")
            val movie = common + mapOf("primary_release_date.gte" to from, "primary_release_date.lte" to until)
            add(ReleaseQuery("movie", movie + mapOf("with_watch_providers" to PROVIDERS, "watch_region" to "US", "with_watch_monetization_types" to "flatrate"), true))
            // Announced theatrical titles often have no streaming-provider metadata yet.
            add(ReleaseQuery("movie", movie, false))
            add(ReleaseQuery("tv", common + mapOf("first_air_date.gte" to from, "first_air_date.lte" to until,
                "with_networks" to NETWORKS, "include_null_first_air_dates" to "false"), true))
        }
    }
}

/** Bounded discovery, cached snapshots, cancellable requests, and date revalidation on every emission. */
class LiveReleaseDiscovery(
    private val fetch: suspend (ReleaseQuery, Int) -> List<ReleaseTitle>,
    private val readCache: suspend () -> List<ReleaseTitle> = { emptyList() },
    private val writeCache: suspend (List<ReleaseTitle>) -> Unit = {},
    private val parallelism: Int = 2
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(ReleaseFeed())
    val feed: StateFlow<ReleaseFeed> = state
    private var candidates = emptyList<ReleaseTitle>()
    private var lastRefresh = 0L
    private var cacheRead = false
    suspend fun refresh(now: Long = System.currentTimeMillis(), online: Boolean = true, force: Boolean = false) = mutex.withLock {
        if (!cacheRead) { candidates = readCache(); cacheRead = true }
        val today = ReleasePolicy.day(now)
        state.value = ReleaseCurator.curate(candidates, today)
        if (!online || (!force && lastRefresh > 0 && now - lastRefresh in 0 until 6 * 60 * 60 * 1000L)) return@withLock
        val fresh = withTimeoutOrNull(25_000) {
            coroutineScope {
                val slots = Semaphore(parallelism.coerceIn(1, 2))
                ReleaseQueries.plan(today).flatMap { query -> (1..2).map { page -> async {
                    slots.withPermit {
                        try { fetch(query, page).map { it.copy(majorOtt = query.majorOtt) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    }
                } } }.awaitAll().flatten()
            }
        }
        // A partial request failure must not erase other successfully cached release candidates.
        if (!fresh.isNullOrEmpty()) {
            candidates = (fresh + candidates).groupBy { it.key }.values.map { group ->
                val latest = group.first()
                latest.copy(majorOtt = group.any { it.majorOtt })
            }.filter { ReleasePolicy.isNew(it.date, today) || ReleasePolicy.isUpcoming(it.date, today) }.take(240)
            writeCache(candidates); lastRefresh = now
            state.value = ReleaseCurator.curate(candidates, today)
        }
    }
}
