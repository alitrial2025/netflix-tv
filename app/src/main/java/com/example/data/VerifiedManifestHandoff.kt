package com.example.data

/** Short-lived, single-use handoff of resolver-verified VOD manifests to Media3.
 * Exact signed URL, request headers and cooldown revision scope every entry;
 * a player refresh always returns to the server after the first read.
 */
internal object VerifiedManifestHandoff {
    private data class Key(val url: String, val headers: Map<String, String>)
    private data class Entry(val body: ByteArray, val createdAt: Long, val revision: Long)
    private val entries = LinkedHashMap<Key, Entry>()
    private fun key(url: String, headers: Map<String, String>) = Key(url,
        headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }.toSortedMap())
    @Synchronized fun offer(url: String, headers: Map<String, String>, body: String, validatedVod: Boolean = false) {
        if (!body.trimStart().startsWith("#EXTM3U") || StreamSessionPolicy.isWaitingVideo(body)) return
        val lines = body.lineSequence().map(String::trim).toList()
        if ("#EXT-X-ENDLIST" !in lines && !(validatedVod && lines.any { it.startsWith("#EXT-X-STREAM-INF:") })) return
        val now = android.os.SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.createdAt > 30_000L || it.value.revision != PlaybackServiceGate.sourceRevision }
        if (entries.size >= 16) entries.keys.firstOrNull()?.let(entries::remove)
        entries[key(url, headers)] = Entry(body.toByteArray(Charsets.UTF_8), now, PlaybackServiceGate.sourceRevision)
    }
    @Synchronized fun take(url: String, headers: Map<String, String>): ByteArray? {
        val entry = entries.remove(key(url, headers)) ?: return null
        PlaybackServiceGate.check()
        val age = android.os.SystemClock.elapsedRealtime() - entry.createdAt
        return entry.body.takeIf { age in 0..30_000L && entry.revision == PlaybackServiceGate.sourceRevision }
    }
    @Synchronized fun clear() = entries.clear()
}
