package com.example.data

import com.example.model.Episode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedNextEpisodeLookupTest {
    private fun episode(season: Int, number: Int, id: Long = number.toLong()) =
        Episode(id, number, season, "Episode $number", "", "", "")

    @Test fun usesTheLowestActualRemainingEpisodeInsteadOfGuessingPlusOne() = runBlocking {
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, _ -> listOf(episode(1, 6), episode(1, 3), episode(1, 5), episode(9, 4)) },
            seasons = { throw AssertionError("Same-season target needs no season request") }
        )
        assertEquals(1 to 5, lookup.find("42", 1, 3))
    }

    @Test fun choosesTheNearestVerifiedSeasonAndItsLowestRealEpisode() = runBlocking {
        val requested = mutableListOf<Int>()
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, season ->
                requested += season
                when (season) {
                    1 -> listOf(episode(1, 3))
                    2 -> listOf(episode(2, 7), episode(2, 4), episode(2, 1, id = 0), episode(3, 1))
                    else -> throw AssertionError("Skipped the nearest actual season")
                }
            },
            seasons = { listOf(5, 3, 2, 1) }
        )
        assertEquals(2 to 4, lookup.find("42", 1, 3))
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun aSeriesFinaleCannotProduceAnInventedEpisodeOrSeason() = runBlocking {
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, _ -> listOf(episode(1, 1), episode(1, 2)) },
            seasons = { listOf(1) }
        )
        assertNull(lookup.find("42", 1, 2))
    }

    @Test fun invalidMovieOrEpisodeIdentityNeverQueriesTheCatalog() = runBlocking {
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, _ -> throw AssertionError("Invalid identity queried") },
            seasons = { throw AssertionError("Invalid identity queried") }
        )
        for (id in listOf("", "provider-42", "0", "-1")) assertNull(lookup.find(id, 1, 1))
        assertNull(lookup.find("42", 0, 1))
        assertNull(lookup.find("42", 1, 0))
    }

    @Test fun missingCurrentCatalogDuringAnOutageDoesNotAuthorizeASeasonJump() = runBlocking {
        var now = 1_000L
        var available = false
        var calls = 0
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, _ -> calls++; if (available) listOf(episode(1, 1), episode(1, 2)) else emptyList() },
            seasons = { throw AssertionError("Empty catalog cannot prove season finale") },
            nowMs = { now }
        )
        assertNull(lookup.find("42", 1, 1))
        available = true
        assertNull(lookup.find("42", 1, 1))
        assertEquals(1, calls)
        now += 60_000L
        assertEquals(1 to 2, lookup.find("42", 1, 1))
        assertEquals(2, calls)
    }

    @Test fun transportFailureReturnsNoTransitionAndConcurrentUiCallsShareVerifiedProof() = runBlocking {
        val failed = VerifiedNextEpisodeLookup(episodes = { _, _ -> throw IOException("Offline") }, seasons = { emptyList() })
        assertNull(failed.find("42", 1, 1))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val lookup = VerifiedNextEpisodeLookup(
            episodes = { _, _ -> calls++; started.complete(Unit); finish.await(); listOf(episode(1, 1), episode(1, 2)) },
            seasons = { emptyList() }
        )
        val player = async { lookup.find("42", 1, 1) }
        started.await()
        val progress = async { lookup.find("42", 1, 1) }
        finish.complete(Unit)
        assertEquals(1 to 2, player.await())
        assertEquals(1 to 2, progress.await())
        assertEquals(1, calls)
    }
}
