package com.example.ui.util

/** A departing screen must never stop a player already claimed by its successor. */
class SharedPlaybackSession {
    private var owner: String? = null
    private var previewStart: Pair<String, Long>? = null

    @Synchronized fun claim(nextOwner: String) { owner = nextOwner }
    @Synchronized fun owns(candidate: String): Boolean = owner == candidate

    @Synchronized fun handoff(candidate: String, nextOwner: String): Boolean {
        if (owner != candidate) return false
        owner = nextOwner
        return true
    }

    @Synchronized fun release(candidate: String): Boolean {
        if (owner != candidate) return false
        owner = null
        previewStart = null
        return true
    }

    @Synchronized fun recordPreviewStart(candidate: String, mediaId: String, positionMs: Long) {
        if (owner == candidate) previewStart = mediaId to positionMs.coerceAtLeast(0L)
    }

    /** Restore the viewer's saved position, not time spent watching the Details preview. */
    @Synchronized fun takePreviewStart(mediaId: String): Long? {
        val recorded = previewStart
        previewStart = null
        return recorded?.takeIf { it.first == mediaId }?.second
    }
}
