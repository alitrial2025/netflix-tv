package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the [PendingWrite] outbox.
 *
 * All writes are suspend functions and run on the `netflix-db-tx` executor
 * that the prior agent wired up on [NetflixDatabase]. Reads (especially
 * `observeStatusCounts`) are Flows that Room re-emits on table changes.
 */
@Dao
interface PendingWriteDao {

    /**
     * Insert a new pending write. Returns the auto-generated row id so the
     * caller (the worker) can reference it later.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(write: PendingWrite): Long

    @Query("UPDATE pending_writes SET status = 'in_flight', lastAttemptAt = :now WHERE id = :id")
    suspend fun markInFlight(id: Long, now: Long)

    @Query("UPDATE pending_writes SET status = 'done', lastAttemptAt = :now, lastError = NULL WHERE id = :id")
    suspend fun markDone(id: Long, now: Long)

    /**
     * Bump retry metadata and re-schedule. Status is `pending` (we'll try
     * again) unless [terminal] is true, in which case we park the row as
     * `failed` and stop scheduling it.
     */
    @Query("""UPDATE pending_writes
              SET status = :newStatus,
                  lastAttemptAt = :now,
                  lastError = :error,
                  nextAttemptAt = :nextAttemptAt,
                  retryCount = retryCount + 1
              WHERE id = :id""")
    suspend fun markRetry(
        id: Long,
        now: Long,
        error: String,
        nextAttemptAt: Long,
        newStatus: String
    )

    /**
     * Read up to [limit] writes that are ready to fire. The worker
     * re-queries on every retry so it doesn't hold the same batch in
     * memory across attempts.
     */
    @Query("""SELECT * FROM pending_writes
              WHERE status = 'pending' AND nextAttemptAt <= :now
              ORDER BY nextAttemptAt ASC
              LIMIT :limit""")
    suspend fun listReadyToFire(now: Long, limit: Int = 50): List<PendingWrite>

    /** Read a single write by id (used to look up a row the worker is about to retry). */
    @Query("SELECT * FROM pending_writes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PendingWrite?

    /**
     * Read rows by status. Used by the debug UI to show "5 pending, 1 failed"
     * and by tests to assert state transitions.
     */
    @Query("SELECT * FROM pending_writes WHERE status = :status ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByStatus(status: String, limit: Int = 100): List<PendingWrite>

    /**
     * Delete done rows older than [olderThanMillis]. The worker calls this
     * once per run to keep the table bounded. See [PendingWrite.PRUNE_DONE_AFTER_MS]
     * for the 7-day window.
     */
    @Query("DELETE FROM pending_writes WHERE status = 'done' AND lastAttemptAt < :olderThanMillis")
    suspend fun pruneDone(olderThanMillis: Long)

    /**
     * Reset rows that are stuck `in_flight` (e.g. the app was force-killed
     * mid-write) back to `pending` so the next worker run will retry them.
     */
    @Query("""UPDATE pending_writes
              SET status = 'pending'
              WHERE status = 'in_flight' AND lastAttemptAt < :thresholdMillis""")
    suspend fun resetStuckInFlight(thresholdMillis: Long): Int

    /**
     * Mark a user's pending writes as `failed` on sign-out. The spec calls
     * for keeping them as `failed` (not deleting) so the user can review
     * and manually retry from the UI.
     */
    @Query("""UPDATE pending_writes
              SET status = 'failed',
                  lastError = COALESCE(lastError, 'cancelled on sign-out')
              WHERE status IN ('pending', 'in_flight') AND documentId LIKE :userPattern""")
    suspend fun cancelForUser(userPattern: String): Int

    /**
     * Live count per status. The UI's "sync status" badge subscribes to
     * this; a row is emitted every time any of the counts change. Room
     * generates the SQL for the `GROUP BY`.
     */
    @Query("SELECT status AS status, COUNT(*) AS count FROM pending_writes GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    /**
     * Tiny DTO for [observeStatusCounts]. Kept as a data class on the DAO
     * itself so it sits close to the SQL that produces it.
     */
    data class StatusCount(val status: String, val count: Int)
}
