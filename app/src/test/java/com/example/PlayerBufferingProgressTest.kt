package com.example

import com.example.ui.components.PlayerBufferingProgress
import org.junit.Assert.*
import org.junit.Test

class PlayerBufferingProgressTest {
    @Test fun percentageTracksBufferFillIncludingPlaybackSpeedInsteadOfElapsedTime() {
        assertEquals(0, PlayerBufferingProgress.percentage(0, 2500, 1f))
        assertEquals(20, PlayerBufferingProgress.percentage(500, 2500, 1f))
        assertEquals(50, PlayerBufferingProgress.percentage(2500, 2500, 2f))
        assertEquals(100, PlayerBufferingProgress.percentage(5000, 2500, 2f))
        assertEquals(100, PlayerBufferingProgress.percentage(10000, 2500, 1f))
    }

    @Test fun UnknownOrInvalidMeasurementsStayIndeterminate() {
        assertNull(PlayerBufferingProgress.percentage(-1, 2500, 1f))
        assertNull(PlayerBufferingProgress.percentage(500, 0, 1f))
        assertNull(PlayerBufferingProgress.percentage(500, 2500, Float.NaN))
        assertNull(PlayerBufferingProgress.percentage(500, 2500, 0f))
    }
}
