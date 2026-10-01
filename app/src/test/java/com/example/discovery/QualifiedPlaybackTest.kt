package com.example.discovery

import org.junit.Assert.*
import org.junit.Test

class QualifiedPlaybackTest {
    @Test fun subSecondTicksAccumulateWholeAdvancingPlaybackAndOnlyQualifyOnce() {
        val tracker=QualifiedPlayback();var claims=0
        for(tick in 0..800) if(tracker.sample("movie:1",tick/4,true,tick*250L)) claims++
        assertEquals(1,claims)
    }
    @Test fun SeekingAndPausingCannotQualifyATitle() {
        val tracker=QualifiedPlayback()
        assertFalse(tracker.sample("movie:1",0,true,0))
        assertFalse(tracker.sample("movie:1",1000,true,1000))
        for(tick in 2..500) assertFalse(tracker.sample("movie:1",1000,false,tick*1000L))
        assertFalse(tracker.sample("movie:2",500,true,501_000))
    }
    @Test fun sparseTvProgressStillQualifiesAfterTwoMinutesOfActualPlayback() {
        val tracker=QualifiedPlayback();var claims=0
        for(tick in 0..12) if(tracker.sample("tv:1",tick*15,true,tick*15_000L)) claims++
        assertEquals(1,claims)
    }
}
