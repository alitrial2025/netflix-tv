package com.example.discovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveReleaseDiscoveryTest {
    private val now=1790812800000L
    @Test fun requestsStayBoundedAndRepeatedRefreshUsesTheSixHourCache()=runTest {
        var active=0;var peak=0;var calls=0
        val service=LiveReleaseDiscovery(fetch={q,page ->
            active++;peak=maxOf(peak,active);calls++;delay(100);active--
            listOf(ReleaseTitle("${q.kind.hashCode().toLong().let { kotlin.math.abs(it) }+page}",q.kind,"Title",posterPath="/p",date=ReleasePolicy.day(now),majorOtt=true))
        })
        service.refresh(now=now);assertEquals(12,calls);assertTrue(peak<=2)
        service.refresh(now=now+1000);assertEquals(12,calls);assertTrue(service.feed.value.recent.isNotEmpty())
    }
    @Test fun timeoutCancelsRequestsAndKeepsCachedPublicMetadata()=runTest {
        var cancellations=0
        val cached=listOf(ReleaseTitle("1","tv","Cached",posterPath="/p",date=ReleasePolicy.day(now),majorOtt=true))
        val service=LiveReleaseDiscovery(fetch={_,_->try{delay(60_000);emptyList()}finally{cancellations++}},readCache={cached})
        service.refresh(now=now)
        assertEquals(25_000L,testScheduler.currentTime);assertTrue(cancellations>=2)
        assertEquals("Cached",service.feed.value.recent.single().title)
    }
    @Test fun offlineRefreshReadsCacheWithoutNetworkAndRevalidatesDates()=runTest {
        var calls=0
        val cached=listOf(ReleaseTitle("1","movie","Old",posterPath="/p",date="2026-09-01",majorOtt=true))
        val service=LiveReleaseDiscovery(fetch={_,_->calls++;emptyList()},readCache={cached})
        service.refresh(now=now,online=false);assertEquals(0,calls)
        service.refresh(now=now+3*86_400_000L,online=false);assertTrue(service.feed.value.recent.isEmpty())
    }
}
