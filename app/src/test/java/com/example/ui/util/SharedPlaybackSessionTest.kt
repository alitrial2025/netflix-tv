package com.example.ui.util

import org.junit.Assert.*
import org.junit.Test

class SharedPlaybackSessionTest {
    @Test fun handoffKeepsPlaybackOwnedUntilTheDestinationClaimsIt() {
        val session = SharedPlaybackSession()
        session.claim("player")
        assertTrue(session.handoff("player", "handoff"))
        assertFalse(session.release("player"))
        session.claim("details")
        assertFalse(session.release("handoff"))
        assertTrue(session.owns("details"))
    }

    @Test fun obsoleteScreensCannotStartAHandoff() {
        val session = SharedPlaybackSession()
        session.claim("new-player")
        assertFalse(session.handoff("old-player", "handoff"))
        assertTrue(session.owns("new-player"))
    }

    @Test fun departingPreviewCannotReleasePlayer() {
        val session = SharedPlaybackSession()
        session.claim("details")
        session.recordPreviewStart("details", "movie:42", 120_000L)
        session.claim("player")
        assertFalse(session.release("details"))
        assertTrue(session.owns("player"))
        assertEquals(120_000L, session.takePreviewStart("movie:42"))
        assertNull(session.takePreviewStart("movie:42"))
    }

    @Test fun anotherEpisodeCannotInheritPreviewPosition() {
        val session = SharedPlaybackSession()
        session.claim("details")
        session.recordPreviewStart("details", "tv:42:1:1", 90_000L)
        session.claim("player")
        assertNull(session.takePreviewStart("tv:42:1:2"))
    }

    @Test fun obsoleteResolverCannotOverwriteNewPreviewStart() {
        val session = SharedPlaybackSession()
        session.claim("new-details")
        session.recordPreviewStart("new-details", "movie:8", 0L)
        session.recordPreviewStart("old-details", "movie:42", 999L)
        assertEquals(0L, session.takePreviewStart("movie:8"))
    }
}
