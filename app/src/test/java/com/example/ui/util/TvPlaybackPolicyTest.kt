package com.example.ui.util

import org.junit.Assert.*
import org.junit.Test

class TvPlaybackPolicyTest {
    @Test fun oldChipsetsAndSmallHeapsUseCompatibilityMode() {
        assertTrue(TvPlaybackPolicy.needsCompatibilityMode(false, 24))
        assertTrue(TvPlaybackPolicy.needsCompatibilityMode(false, 25))
        assertTrue(TvPlaybackPolicy.needsCompatibilityMode(true, 33))
        assertFalse(TvPlaybackPolicy.needsCompatibilityMode(false, 33))
    }

    @Test fun premiumPlanDoesNotOverrideAnOlderTvsDecoderBudget() {
        assertEquals(TvPlaybackPolicy.VideoLimits(1280, 720, 3_000_000, 30),
            TvPlaybackPolicy.videoLimits(true, 2160))
        assertEquals(480, TvPlaybackPolicy.videoLimits(true, 480).height)
        assertEquals(720, TvPlaybackPolicy.videoLimits(true, 2160, trailer = true).height)
    }

    @Test fun capableTvsKeepTheirPlanResolutionAndTrailersKeepTheirOwnLimit() {
        assertEquals(TvPlaybackPolicy.VideoLimits(Int.MAX_VALUE, 2160, Int.MAX_VALUE, Int.MAX_VALUE),
            TvPlaybackPolicy.videoLimits(false, 2160))
        assertEquals(1080, TvPlaybackPolicy.videoLimits(false, 2160, trailer = true).height)
    }
}
