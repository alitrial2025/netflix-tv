package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Outbox-pattern row representing a Firestore write the user (or the app on
 * the user's behalf) has committed intent for, but which has not yet been
 * durably committed to the server.
 *
 * Lifecycle:
 *
 *   insert -> "pending" --(worker picks it up)--> "in_flight" --> "done" (terminal)
 *                                              \-> "failed" (after 5 attempts; terminal
 *                                                  unless the user manually retries)
 *
 * Rows are never deleted on success — they're marked `done` and pruned by
 * [PendingWriteDao.pruneDone] on a 7-day rolling window. This keeps the
 * audit trail simple and avoids a "delete just succeeded" race with a
 * restart mid-commit.
 */
@Entity(
    tableName = "pending_writes",
    indices = [
        Index(value = ["status"]),
        Index(value = ["nextAttemptAt"])
    ]
)
data class PendingWrite(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /**
     * Firestore collection name, e.g. `"users"`, `"profiles"`,
     * `"continue_watching"`, `"continueWatching"`. Stored as a plain string
     * (not an enum) so the outbox stays decoupled from the schema.
     */
    val collection: String,
    /**
     * Firestore document id, or `null` for collection-level writes. For
     * "batch" rows, this is unused; the payload carries the full batch.
     */
    val documentId: String?,
    /**
     * One of `"create"`, `"update"`, `"delete"`, `"batch"`. Stored as a
     * string so we don't have to bump the schema every time we add a new
     * write kind. The worker interprets the string.
     */
    val operation: String,
    /**
     * JSON-serialized payload. `null` for `delete` rows; for `batch` rows
     * it contains a JSON array of individual write descriptors. See
     * `SyncScheduler.encodePayload` for the serialization helpers.
     */
    val payload: String?,
    /** Epoch millis at enqueue time. */
    val createdAt: Long,
    /** How many times we've tried (and failed) to commit this row. */
    val retryCount: Int = 0,
    /** Epoch millis of the last attempt; `null` until the first try. */
    val lastAttemptAt: Long? = null,
    /** Error message from the last failed attempt; `null` until first failure. */
    val lastError: String? = null,
    /**
     * Epoch millis at which the row is eligible to be picked up by the
     * worker again. Initialized to `createdAt` so a row is eligible
     * immediately; bumped out into the future on each failed attempt.
     */
    val nextAttemptAt: Long,
    /**
     * `"pending"`, `"in_flight"`, `"failed"`, or `"done"`. See the
     * companion object for the canonical constants.
     */
    val status: String = STATUS_PENDING
) {
    companion object {
        const val OP_CREATE = "create"
        const val OP_UPDATE = "update"
        const val OP_DELETE = "delete"
        const val OP_BATCH = "batch"

        const val STATUS_PENDING = "pending"
        const val STATUS_IN_FLIGHT = "in_flight"
        const val STATUS_FAILED = "failed"
        const val STATUS_DONE = "done"

        /**
         * Backoff schedule (ms) indexed by retry count. After the 5th
         * failure the row transitions to `failed` and stops retrying until
         * a user-triggered retry resets the counter.
         */
        val BACKOFF_MS: LongArray = longArrayOf(
            1_000L,        // attempt 1 -> 1s
            5_000L,        // attempt 2 -> 5s
            30_000L,       // attempt 3 -> 30s
            5L * 60_000L,  // attempt 4 -> 5min
            30L * 60_000L  // attempt 5 -> 30min
        )

        /** Maximum retry count before the row is parked as `failed`. */
        const val MAX_RETRIES: Int = 5

        /** Rows that have been `in_flight` longer than this are assumed stuck. */
        const val STUCK_IN_FLIGHT_THRESHOLD_MS: Long = 5L * 60_000L

        /** Done rows older than this are pruned on every worker run. */
        const val PRUNE_DONE_AFTER_MS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
