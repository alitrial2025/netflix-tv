package com.example.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleTimingTest {
    @Test fun positiveOffsetsDelayAndNegativeOffsetsAdvanceOnlyTheCaptionClock() {
        assertEquals(1_750_000L, SubtitleTiming.captionPositionUs(2_000_000L, 250))
        assertEquals(2_250_000L, SubtitleTiming.captionPositionUs(2_000_000L, -250))
        assertEquals(2_000_000L, SubtitleTiming.captionPositionUs(2_000_000L, 0))
    }
    @Test fun offsetsAreBoundedAndCannotCreateNegativeTimestamps() {
        assertEquals(10_000, SubtitleTiming.clamp(Int.MAX_VALUE))
        assertEquals(-10_000, SubtitleTiming.clamp(Int.MIN_VALUE))
        assertEquals(0L, SubtitleTiming.captionPositionUs(125_000, 250))
        assertEquals(0L, SubtitleTiming.captionPositionUs(2_000_000, Int.MAX_VALUE))
        assertEquals(12_000_000L, SubtitleTiming.captionPositionUs(2_000_000, Int.MIN_VALUE))
    }
    @Test fun mediaSentinelsAndVeryLargeClocksDoNotOverflow() {
        assertEquals(C.TIME_UNSET, SubtitleTiming.captionPositionUs(C.TIME_UNSET, 1000))
        assertEquals(C.TIME_END_OF_SOURCE, SubtitleTiming.captionPositionUs(C.TIME_END_OF_SOURCE, -1000))
        assertEquals(Long.MAX_VALUE, SubtitleTiming.captionPositionUs(Long.MAX_VALUE, -250))
        assertEquals(Long.MAX_VALUE - 250_000, SubtitleTiming.captionPositionUs(Long.MAX_VALUE, 250))
    }
    @Test fun rendererReadsNewOffsetsWithoutChangingElapsedClockOrItsDelegateLifecycle() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            when (method.name) {
                "getTrackType" -> C.TRACK_TYPE_TEXT
                "getName" -> "caption-fixture"
                "isReady" -> true
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 1f
                    else -> null
                }
            }
        } as Renderer
        var offset = 250
        val renderer = SubtitleTimingRenderer(delegate) { offset }
        renderer.render(2_000_000, 7_000_000)
        offset = -500
        renderer.render(2_000_000, 7_500_000)
        renderer.resetPosition(5_000_000)
        assertEquals(C.TRACK_TYPE_TEXT, renderer.trackType)
        assertEquals("caption-fixture", renderer.name)
        assertTrue(renderer.isReady)
        val renderCalls = calls.filter { it.first == "render" }.map { it.second }
        assertEquals(listOf(listOf(1_750_000L, 7_000_000L), listOf(2_500_000L, 7_500_000L)), renderCalls)
        assertEquals(listOf(5_000_000L), calls.single { it.first == "resetPosition" }.second)
    }
}
