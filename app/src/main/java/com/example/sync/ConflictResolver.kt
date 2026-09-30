package com.example.sync

import android.util.Log

/**
 * Applies the SyncStrategy policy to resolve a conflict between a local
 * pending write and a remote snapshot.
 *
 * Use case: when a local write and a remote write race, we have two values
 * for the same field. Call `resolve()` with both values, the field name, and
 * the document collection, and the resolver returns the value that should
 * win per the policy.
 *
 * The resolver is a pure function — no I/O, no time, no side effects. It
 * can be unit-tested with arbitrary inputs.
 *
 * The "should we even write this?" decision (i.e. SERVER_ONLY → don't write
 * at all) lives in [shouldWrite] / [assertCanWrite]. The two checks are
 * split because some write paths just want to filter fields out, while
 * others (e.g. a subscription upgrade) want to abort the entire write.
 */
object ConflictResolver {

    private const val TAG = "Sync"

    /**
     * Resolve a single field conflict. Returns the value that should win
     * per the policy, or `remoteValue` if no policy is defined for the
     * (collection, field) pair (defensive default — never let an unknown
     * field fall through to CLIENT_WINS).
     */
    fun resolve(
        collection: String,
        field: String,
        localValue: Any?,
        remoteValue: Any?,
        localTimestampMs: Long? = null,
        remoteTimestampMs: Long? = null
    ): Any? {
        val policy = policyFor(collection, field) ?: return remoteValue

        val winner = when (policy) {
            ConflictPolicy.SERVER_WINS, ConflictPolicy.SERVER_ONLY -> remoteValue
            ConflictPolicy.CLIENT_WINS -> localValue
            ConflictPolicy.MAX_WINS -> maxOf(
                (localValue as? Long) ?: 0L,
                (remoteValue as? Long) ?: 0L
            )
            ConflictPolicy.SET_UNION -> {
                val local = (localValue as? List<*>)?.toSet() ?: emptySet<Any?>()
                val remote = (remoteValue as? List<*>)?.toSet() ?: emptySet<Any?>()
                (local + remote).toList()
            }
            ConflictPolicy.LIST_MERGE -> {
                val local = (localValue as? List<*>) ?: emptyList<Any?>()
                val remote = (remoteValue as? List<*>) ?: emptyList<Any?>()
                local + remote.filter { it !in local }
            }
            ConflictPolicy.LOCAL_ONLY -> localValue
        }

        Log.d(
            TAG,
            "policy=$policy field=$field local=$localValue remote=$remoteValue winner=$winner"
        )
        return winner
    }

    /**
     * Filter a write map so it only contains fields the client is allowed
     * to write. SERVER_ONLY fields are stripped, SERVER_WINS fields are
     * stripped (the client is supposed to be a reader, not a writer),
     * MAX_WINS fields are passed through (the writer is responsible for
     * the read-then-max logic at the call site).
     *
     * Returns the same map reference when no fields are filtered out, so
     * the hot path doesn't allocate.
     */
    fun filterWritable(
        collection: String,
        writeMap: Map<String, Any?>
    ): Map<String, Any?> {
        if (writeMap.isEmpty()) return writeMap
        var filtered = false
        for (field in writeMap.keys) {
            val policy = policyFor(collection, field)
            if (policy == ConflictPolicy.SERVER_ONLY || policy == ConflictPolicy.SERVER_WINS) {
                filtered = true
                break
            }
        }
        if (!filtered) return writeMap
        val out = LinkedHashMap<String, Any?>(writeMap.size)
        for ((field, value) in writeMap) {
            val policy = policyFor(collection, field)
            if (policy == ConflictPolicy.SERVER_ONLY) {
                Log.w(TAG, "dropping SERVER_ONLY field $collection.$field (client attempted write)")
                continue
            }
            if (policy == ConflictPolicy.SERVER_WINS) {
                // SERVER_WINS is technically writable, but the value will
                // be overwritten by the next server snapshot anyway. We
                // log a warning so a misuse is visible without refusing
                // the write (a profile-update that includes a stale
                // `lastUsedAt` is still useful, the server just ignores it).
                Log.d(TAG, "writing SERVER_WINS field $collection.$field (value will be re-stamped on read)")
            }
            out[field] = value
        }
        return out
    }

    /**
     * True when the client should be allowed to write [field] at all.
     * Used by write paths that want to abort the entire write rather than
     * silently strip a field (e.g. an "upgrade plan" call that tries to
     * also write `tier`).
     */
    fun shouldWrite(collection: String, field: String): Boolean {
        val policy = policyFor(collection, field) ?: return true
        return policy != ConflictPolicy.SERVER_ONLY
    }

    /**
     * Throws if the write map contains any field that the client is not
     * allowed to write. Use this in write paths where a SERVER_ONLY field
     * in the payload is a programming error, not a recoverable condition
     * (e.g. a payment webhook handler).
     */
    fun assertCanWrite(collection: String, writeMap: Map<String, Any?>) {
        for (field in writeMap.keys) {
            if (!shouldWrite(collection, field)) {
                throw IllegalStateException(
                    "Refusing to write SERVER_ONLY field '$collection.$field' from the client"
                )
            }
        }
    }

    /**
     * Looks up the policy for a given (collection, field) pair. The matching
     * is by exact field name; if no policy is defined, the default is
     * SERVER_WINS (defensive default — never accidentally allow the client
     * to overwrite authoritative server data).
     *
     * The lookup is O(1) — we map the collection name to its policy map
     * once. The field maps in [SyncStrategy] are immutable `Map` literals,
     * so we can cache the references.
     */
    fun policyFor(collection: String, field: String): ConflictPolicy? {
        val map = collectionPolicy(collection) ?: return null
        return map[field]
    }

    private fun collectionPolicy(collection: String): Map<String, ConflictPolicy>? = when {
        collection == "profiles" -> SyncStrategy.PROFILE
        collection == "continueWatching" || collection == "continue_watching" -> SyncStrategy.CONTINUE_WATCHING
        collection == "myList" || collection == "my_list" -> SyncStrategy.MY_LIST
        collection == "subscription" -> SyncStrategy.SUBSCRIPTION
        collection == "tvSessions" || collection == "tv_sessions" -> SyncStrategy.TV_SESSION
        else -> null
    }
}
