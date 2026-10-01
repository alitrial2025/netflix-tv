package com.example.discovery

import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {
    private val now=1790812800000L
    private fun title(id: Int, genre: Int=28)=RecommendationTitle("movie:$id",setOf(genre),10.0,7.5,200,"2020-01-01")
    @Test fun meaningfulRecentViewingAndPreferencesOutrankUnrelatedPopularity() {
        val action=title(1);val comedy=title(2,35).copy(popularity=100.0)
        val event=TasteSignal("movie:99",setOf(28),now,1200,1800,true)
        assertEquals(action.key,RecommendationEngine.rank(listOf(action,comedy),"profile",history=listOf(event),now=now).first())
        assertEquals(comedy.key,RecommendationEngine.rank(listOf(action,comedy),"profile",preferred=setOf(35),now=now).first())
    }
    @Test fun accidentalClicksDoNotChangeTasteAndOldViewingDecays() {
        val pool=listOf(title(1),title(2,35))
        val baseline=RecommendationEngine.rank(pool,"profile",now=now)
        assertEquals(baseline,RecommendationEngine.rank(pool,"profile",history=listOf(TasteSignal("movie:9",setOf(35),now,30,600,true)),now=now))
        val events=listOf(TasteSignal("movie:8",setOf(35),now-365L*86_400_000,500,600,true),TasteSignal("movie:9",setOf(28),now,500,600,true))
        assertEquals("movie:1",RecommendationEngine.rank(pool,"profile",history=events,now=now).first())
    }
    @Test fun dislikesCompletedMoviesAndFutureTitlesStayOutButEpisodeCompletionKeepsTheSeries() {
        val pool=listOf(title(1),title(2),title(3).copy(key="tv:3"),title(4).copy(releaseDate="2099-01-01"))
        val history=listOf(TasteSignal("movie:1",setOf(28),now,960,1000,true),TasteSignal("tv:3",setOf(28),now,960,1000,false))
        val result=RecommendationEngine.rank(pool,"profile",history=history,ratings=mapOf("movie:2" to "DISLIKE"),now=now)
        assertEquals(listOf("tv:3"),result)
    }
    @Test fun rotationIsStablePerProfileAndDayButChangesAcrossDays() {
        val pool=(1..100).map { title(it) }
        val first=RecommendationEngine.rank(pool,"p",now=now,limit=20)
        assertEquals(first,RecommendationEngine.rank(pool.reversed(),"p",now=now,limit=20))
        assertEquals(first,RecommendationEngine.rank(pool,"p",now=now+1000,limit=20))
        assertNotEquals(first,RecommendationEngine.rank(pool,"p",now=now+86_400_000,limit=20))
        assertNotEquals(first,RecommendationEngine.rank(pool,"other",now=now,limit=20))
    }
    @Test fun communityHasABoundedInfluenceAndQualityIsShrunkForTinyVoteCounts() {
        val quality=title(1).copy(rating=9.0,votes=5000)
        val low=title(2).copy(rating=1.0,votes=5000)
        val result=RecommendationEngine.rank(listOf(quality,low),"p",community=mapOf(low.key to Long.MAX_VALUE),now=now)
        assertEquals(quality.key,result.first())
        assertEquals(emptyList<String>(),RecommendationEngine.rank(listOf(quality),"p",limit=0))
    }
    @Test fun diversityAndLargePoolsPreserveTypedIdentityAndBoundedOutput() {
        val pool=(1..1000).map { title(it,if (it % 2 == 0) 28 else 35) }+title(1,35).copy(key="tv:1")
        val started=System.nanoTime()
        val rank=RecommendationEngine.rank(pool,"p",now=now,limit=1001)
        println("RecommendationBenchmark candidates=${pool.size} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
        assertEquals(1001,rank.size);assertEquals(rank.size,rank.toSet().size)
        assertTrue("movie:1" in rank && "tv:1" in rank)
        assertTrue(rank.take(10).map { key -> pool.first { it.key==key }.genres }.distinct().size>=2)
    }
}
