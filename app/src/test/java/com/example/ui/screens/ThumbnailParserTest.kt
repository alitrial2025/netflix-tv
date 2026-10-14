package com.example.ui.screens

import com.example.ui.screens.player.seekPreviewIndex
import org.junit.Assert.*
import org.junit.Test

class ThumbnailParserTest {
    private val base = "https://cdn.example/show/previews/index.vtt?manifest=signed"

    @Test fun identifiersSettingsAndMetadataBlocksPreserveHalfOpenFrameBoundaries() {
        val cues = ThumbnailParser.parseText(base, """
            WEBVTT

            NOTE These are thumbnails, not subtitles
            00:00:00.000 --> 00:00:05.000
            ignored.jpg

            STYLE
            ::cue { color: white }

            frame-1
            00:00:00.000 --> 00:00:10.000 align:start position:0%
            sprites.jpg#xywh=0,0,160,90

            frame-2
            00:00:10.000 --> 00:00:20.000
            sprites.jpg#xywh=pixel:160,0,160,90
        """.trimIndent())
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(10_000L, cues[0].endMs)
        assertEquals(160, cues[1].x)
        assertEquals(0, seekPreviewIndex(cues, 9_999))
        assertEquals(1, seekPreviewIndex(cues, 10_000))
    }

    @Test fun relativeRootAndQueryImageReferencesUseTheVttUrlAsTheirBase() {
        val cues = ThumbnailParser.parseText(base, """
            WEBVTT

            00:00.000 --> 00:10.000
            ../frames/frame.jpg?signature=issued#xywh=32,16,160,90

            00:10.000 --> 00:20.000
            /images/frame.jpg?signature=other

            00:20.000 --> 00:30.000
            ?image=frame3#xywh=0,0,160,90
        """.trimIndent())
        assertEquals("https://cdn.example/show/frames/frame.jpg?signature=issued", cues[0].imageUrl)
        assertEquals("https://cdn.example/images/frame.jpg?signature=other", cues[1].imageUrl)
        assertEquals("https://cdn.example/show/previews/index.vtt?image=frame3", cues[2].imageUrl)
    }

    @Test fun aStandaloneImageUsesItsWholeFrameAndCuesAreChronological() {
        val cues = ThumbnailParser.parseText(base, """
            WEBVTT

            00:10.500 --> 00:20.500
            https://images.example/frame2.webp?token=issued

            00:00.000 --> 00:10.500
            frame1.jpg
        """.trimIndent())
        assertEquals(2, cues.size)
        assertEquals(0L, cues.first().startMs)
        assertEquals(10_500L, cues.last().startMs)
        assertEquals(listOf(0, 0, 0, 0), cues.first().let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test fun malformedRectsTimesAndUnsafeImageUrlsAreRejected() {
        val payloads = listOf("frame.jpg#xywh=-1,0,160,90", "frame.jpg#xywh=0,0,0,90",
            "frame.jpg#xywh=oops,0,160,90", "frame.jpg#xywh=0,0,160", "frame.jpg#xywh=2147483647,0,160,90",
            "frame.jpg#xywh=percent:0,0,50,50", "http://cdn.example/frame.jpg", "https://user:secret@cdn.example/frame.jpg",
            "https://127.0.0.1/frame.jpg")
        for (payload in payloads) {
            assertTrue(payload, ThumbnailParser.parseText(base, "WEBVTT\n\n00:00.000 --> 00:10.000\n$payload").isEmpty())
        }
        for (range in listOf("invalid --> 00:10.000", "00:10.000 --> 00:10.000", "00:20.000 --> 00:10.000", "00:00.000 --> 00:99.000")) {
            assertTrue(range, ThumbnailParser.parseText(base, "WEBVTT\n\n$range\nframe.jpg").isEmpty())
        }
    }
}
