package com.example.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.PendingWrite
import com.example.data.PendingWriteDao
import com.example.data.PendingWriteDao.StatusCount
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Single point of entry for "the user wants this Firestore write to happen".
 *
 * The outbox pattern: callers (the [com.example.ui.NetflixViewModel] and the
 * delete / update helpers) [enqueueWrite] FIRST, then attempt the direct
 * Firestore call. If the direct call succeeds, the worker will see the
 * row already marked `done` (or, in our case, the caller marks it `done`
 * inline — see `NetflixViewModel.syncX` wrappers). If the direct call
 * fails, the row stays `pending` and [PendingWriteRetryWorker] picks it up
 * on the next run.
 *
 * The scheduler owns the [WorkManager] glue. ViewModel code only knows
 * about [enqueueWrite] / [observeStatusCounts]; it doesn't need to know
 * about workers, backoff, or constraints.
 */
object SyncScheduler {

    private const val WORK_NAME = "pending_write_retry"
    private const val PERIODIC_WORK_NAME = "pending_write_retry_periodic"

    /**
     * Persist a Firestore write intent as a [PendingWrite] row and ensure
     * the worker is enqueued. Returns the new row id; callers can use it
     * to mark the row `done` after a successful direct write.
     */
    suspend fun enqueueWrite(
        context: Context,
        dao: PendingWriteDao,
        collection: String,
        documentId: String?,
        operation: String,
        payload: String?
    ): Long {
        val now = System.currentTimeMillis()
        val row = PendingWrite(
            collection = collection,
            documentId = documentId,
            operation = operation,
            payload = payload,
            createdAt = now,
            retryCount = 0,
            lastAttemptAt = null,
            lastError = null,
            nextAttemptAt = now,
            status = PendingWrite.STATUS_PENDING
        )
        val id = dao.insert(row)
        android.util.Log.d(
            "PendingWrites",
            "enqueued id=$id collection=$collection doc=$documentId op=$operation"
        )
        scheduleRetry(context, initialDelay = 1_000L)
        return id
    }

    /**
     * Enqueue the one-shot retry worker. Called from [enqueueWrite] and
     * from [schedulePeriodic] at app start. Uses [ExistingWorkPolicy.KEEP]
     * so a back-to-back enqueue doesn't restart a worker that's already
     * running — the worker's `doWork` re-queries the DAO and picks up the
     * new row on its next pass.
     */
    fun scheduleRetry(
        context: Context,
        initialDelay: Long = 1_000L
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<PendingWriteRetryWorker>()
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10L,
                TimeUnit.SECONDS
            )
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            // WorkManager may not be initialized yet (e.g. unit test). The
            // row is durable in Room, so a later sync will pick it up.
            android.util.Log.w("PendingWrites", "WorkManager enqueue failed: ${e.message}")
        }
    }

    /**
     * Periodic safety net: every 15 minutes (flex 5), if there's network,
     * drain anything the one-shot worker missed (e.g. the app was
     * force-killed between enqueue and the one-shot firing).
     */
    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PendingWriteRetryWorker>(
            15L, TimeUnit.MINUTES,
            5L, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10L,
                TimeUnit.SECONDS
            )
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "WorkManager periodic enqueue failed: ${e.message}")
        }
    }

    /**
     * Cancel every queued retry worker. Called on sign-out so we don't
     * fire a `pending` write from the previous user into a new user's
     * session. (We also call [PendingWriteDao.cancelForUser] from the
     * ViewModel to mark the rows `failed`.)
     */
    fun cancelAll(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "WorkManager cancel failed: ${e.message}")
        }
    }

    /** Pass-through to the DAO for the "sync status" UI badge. */
    fun observeStatusCounts(dao: PendingWriteDao): Flow<List<StatusCount>> =
        dao.observeStatusCounts()

    // ── Payload helpers ────────────────────────────────────────────────────
    // Kept on the scheduler (not the entity) so the entity stays a dumb
    // DTO. Use `org.json` directly because the project already uses it
    // (see NetflixViewModel.persistMyListAddedAt) and adding kotlinx-
    // serialization just for the outbox is overkill.

    fun encodePayload(map: Map<String, Any?>): String =
        JSONObject(map).toString()

    fun encodeBatchPayload(writes: List<Map<String, Any?>>): String {
        val arr = JSONArray()
        for (w in writes) arr.put(JSONObject(w))
        return arr.toString()
    }

    /**
     * Convenience: encode a single "update" write descriptor that the
     * worker can read off a batch row's payload.
     */
    fun batchUpdate(
        collection: String,
        documentId: String,
        data: Map<String, Any?>
    ): Map<String, Any?> = mapOf(
        "op" to PendingWrite.OP_UPDATE,
        "collection" to collection,
        "documentId" to documentId,
        "data" to data
    )

    fun batchDelete(
        collection: String,
        documentId: String
    ): Map<String, Any?> = mapOf(
        "op" to PendingWrite.OP_DELETE,
        "collection" to collection,
        "documentId" to documentId
    )
}
