package com.example.data

/** Short-lived, single-use handoff of resolver-verified VOD manifests to Media3.
 * Exact signed URL, request headers and cooldown revision scope every entry;
 * a player refresh always returns to the server after the first read.
 */
internal object VerifiedManifestHandoff {
    private data class Key(val url: String, val headers: Map<String, String>, val newTv: Boolean)
    private data class Entry(val body: ByteArray, val createdAt: Long, val revision: Long)
    private val entries = LinkedHashMap<Key, Entry>()
    private fun key(url: String, headers: Map<String, String>, sourceId: String?) = Key(url,
        headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }.toSortedMap(), sourceId?.contains("[NewTV]", true) == true)
    @Synchronized fun offer(url: String, headers: Map<String, String>, body: String, validatedVod: Boolean = false,
                            sourceId: String? = null) {
        if (!body.trimStart().startsWith("#EXTM3U") || StreamSessionPolicy.isWaitingVideo(body)) return
        val lines = body.lineSequence().map(String::trim).toList()
        if ("#EXT-X-ENDLIST" !in lines && !(validatedVod && lines.any { it.startsWith("#EXT-X-STREAM-INF:") })) return
        val now = android.os.SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.createdAt > 30_000L ||
            !it.key.newTv && it.value.revision != PlaybackServiceGate.sourceRevision }
        if (entries.size >= 16) entries.keys.firstOrNull()?.let(entries::remove)
        entries[key(url, headers, sourceId)] = Entry(body.toByteArray(Charsets.UTF_8), now, PlaybackServiceGate.sourceRevision)
    }
    @Synchronized fun take(url: String, headers: Map<String, String>, sourceId: String? = null): ByteArray? {
        val identity = key(url, headers, sourceId)
        val entry = entries.remove(identity) ?: return null
        if (identity.newTv) NewTvPlaybackGate.check() else PlaybackServiceGate.check()
        val age = android.os.SystemClock.elapsedRealtime() - entry.createdAt
        return entry.body.takeIf { age in 0..30_000L && (identity.newTv || entry.revision == PlaybackServiceGate.sourceRevision) }
    }
    @Synchronized fun clear() = entries.clear()
}
