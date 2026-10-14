package com.example.ui.screens.player

import com.example.ui.screens.ThumbnailCue
import com.example.ui.screens.spritePreviewCrop
import org.junit.Assert.*
import org.junit.Test

class SeekPreviewLayoutTest {
    private val cues = (0..5).map { ThumbnailCue(it * 10_000L, (it + 1) * 10_000L, "sprite.jpg", 0, 0, 160, 90) }

    @Test fun exactBoundarySelectsNextFrameAndEdgesStayInRange() {
        assertEquals(0, seekPreviewIndex(cues, -1))
        assertEquals(0, seekPreviewIndex(cues, 9_999))
        assertEquals(1, seekPreviewIndex(cues, 10_000))
        assertEquals(5, seekPreviewIndex(cues, 100_000))
        assertEquals(-1, seekPreviewIndex(emptyList(), 10_000))
    }

    @Test fun outlineSlidesAtEdgesAndStripGlidesThroughTheMiddle() {
        assertEquals(0f, seekPreviewWindowStart(0.5f, 6), 0f)
        assertEquals(0.5f, seekPreviewWindowStart(1.5f, 6), 0f)
        assertEquals(3f, seekPreviewWindowStart(4.5f, 6), 0f)
        assertEquals(0f, seekPreviewWindowStart(0.5f, 2), 0f)
    }

    @Test fun spriteTileKeepsItsOriginalCoordinatesAndAspectRatio() {
        val cue = cues.first().copy(x = 320, y = 180)
        val crop = spritePreviewCrop(cue, 1600, 900, 200f, 112.5f)!!
        assertEquals(320, crop.x)
        assertEquals(180, crop.y)
        assertEquals(160, crop.width)
        assertEquals(90, crop.height)
        val square = spritePreviewCrop(cue.copy(w = 160, h = 160), 1600, 900, 200f, 112.5f)!!
        assertEquals(90, square.height)
        assertEquals(215, square.y)
        assertNull(spritePreviewCrop(cue.copy(x = -1), 1600, 900, 200f, 112.5f))
        assertNull(spritePreviewCrop(cue.copy(x = 2000), 1600, 900, 200f, 112.5f))
    }
}
