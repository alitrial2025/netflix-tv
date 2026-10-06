package com.example.data

/** Short, one-use handoff of validated HLS to Media3; never caches segments or live refreshes. */
internal object StartupManifestCache {
    private data class Entry(val bytes: ByteArray, val storedAt: Long, val revision: Long)
    private val entries = linkedMapOf<String, Entry>()
    private const val TTL_MS = 15_000L
    private const val MAX_BYTES = 4 * 1024 * 1024

    @Synchronized fun put(url: String, body: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.storedAt >= TTL_MS }
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (!url.startsWith("https://") || !body.trimStart().startsWith("#EXTM3U") || bytes.size > MAX_BYTES) return
        entries.remove(url)
        while (entries.isNotEmpty() && (entries.size >= 16 || entries.values.sumOf { it.bytes.size } + bytes.size > MAX_BYTES)) {
            entries.remove(entries.keys.first())
        }
        entries[url] = Entry(bytes, now, PlaybackServiceGate.sourceRevision)
    }

    @Synchronized fun take(url: String): ByteArray? {
        val entry = entries.remove(url) ?: return null
        return entry.bytes.takeIf {
            android.os.SystemClock.elapsedRealtime() - entry.storedAt < TTL_MS &&
                entry.revision == PlaybackServiceGate.sourceRevision
        }
    }
}
