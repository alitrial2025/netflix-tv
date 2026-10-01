package com.example.discovery

import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    @Test fun invalidAndUnknownDatesNeverBecomeReleaseClaims() {
        listOf(null,"","2026","2026-02-29","2026-04-31","2026-13-01","0000-01-01").forEach {
            assertFalse(ReleasePolicy.valid(it)); assertFalse(ReleasePolicy.isNew(it,"2026-10-01")); assertFalse(ReleasePolicy.isUpcoming(it,"2026-10-01"))
        }
        assertTrue(ReleasePolicy.valid("2024-02-29")); assertFalse(ReleasePolicy.valid("1900-02-29"))
    }
    @Test fun windowsUseCalendarMonthsAndAreInclusive() {
        assertEquals("2027-01-31",ReleasePolicy.shiftMonths("2026-10-31",3))
        assertEquals("2024-02-29",ReleasePolicy.shiftMonths("2024-03-31",-1))
        assertTrue(ReleasePolicy.isNew("2026-09-01","2026-10-01"))
        assertFalse(ReleasePolicy.isNew("2026-08-31","2026-10-01"))
        assertTrue(ReleasePolicy.isUpcoming("2026-10-01","2026-10-01"))
        assertTrue(ReleasePolicy.isUpcoming("2027-01-01","2026-10-01"))
        assertFalse(ReleasePolicy.isUpcoming("2027-01-02","2026-10-01"))
    }
    @Test fun majorOttAndAnticipationAreRequiredWithoutCollidingMovieAndTvIds() {
        val movie=ReleaseTitle("1","movie","Film",posterPath="/p.jpg",date="2026-11-01",popularity=11.0)
        val tv=movie.copy(kind="tv",title="Series",majorOtt=true)
        val obscure=movie.copy(id="2",popularity=.1)
        val old=tv.copy(id="3",date="2026-06-01")
        val feed=ReleaseCurator.curate(listOf(movie,tv,obscure,old,movie.copy(majorOtt=true)),"2026-10-01")
        assertEquals(setOf("movie:1","tv:1"),feed.upcoming.map { it.key }.toSet())
        assertTrue(feed.upcoming.first { it.kind=="movie" }.majorOtt)
        assertTrue(feed.recent.isEmpty())
    }
    @Test fun revalidatingADiskSnapshotMovesDatesAndDropsExpiredReleases() {
        val next=ReleaseTitle("1","movie","Tomorrow",posterPath="/p",date="2026-10-02",majorOtt=true)
        val recent=next.copy(id="2",date="2026-09-01")
        val before=ReleaseCurator.curate(listOf(next,recent),"2026-10-01")
        assertEquals(listOf("movie:2"),before.recent.map { it.key })
        val after=ReleaseCurator.curate(listOf(next,recent),"2026-10-03")
        assertTrue(after.upcoming.isEmpty());assertEquals(listOf("movie:1"),after.recent.map { it.key })
    }
    @Test fun discoveryQueriesAllCarryExactWindowsAndUseTheSameOttBaseline() {
        val queries=ReleaseQueries.plan("2026-10-01")
        assertEquals(6,queries.size)
        queries.forEach { assertEquals("false",it.parameters["include_adult"]) }
        assertEquals(2,queries.count { it.kind=="tv" && it.majorOtt })
        assertTrue(queries.filter { it.kind=="tv" }.all { it.parameters["with_networks"]==ReleaseQueries.NETWORKS })
    }
}
