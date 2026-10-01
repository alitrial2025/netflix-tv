package com.example.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.NetflixDatabase
import com.example.data.PendingWrite
import com.example.data.PendingWriteDao
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * WorkManager worker that drains the `pending_writes` outbox.
 *
 * Flow on every doWork() invocation:
 *
 *   1. Reset stuck `in_flight` rows (older than 5 min) back to `pending`.
 *   2. Prune `done` rows older than 7 days.
 *   3. Read up to 50 ready-to-fire rows.
 *   4. For each row: mark `in_flight`, attempt the Firestore write, on
 *      success mark `done`, on failure bump retry count and reschedule
 *      with exponential backoff. After the 5th failure the row is
 *      parked as `failed` (terminal until the user manually retries).
 *   5. If any rows are still `pending` / `failed`, return Result.retry()
 *      so WorkManager re-runs us under the backoff policy.
 *
 * Constraints:
 *  - `setRequiredNetworkType(CONNECTED)` on the request so we don't even
 *    spin up when offline.
 *  - `setBackoffCriteria(EXPONENTIAL, 10s)` as a safety net; the per-row
 *    `nextAttemptAt` is the actual gate.
 */
class PendingWriteRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val dao: PendingWriteDao by lazy {
        NetflixDatabase.getDatabase(applicationContext).pendingWriteDao()
    }

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()

        // 1. Recover from force-kills: anything stuck in_flight for >5 min
        //    gets reset to pending. Without this, a row that was mid-write
        //    when the OS killed the process would be stuck forever.
        try {
            val stuckThreshold = now - PendingWrite.STUCK_IN_FLIGHT_THRESHOLD_MS
            val reset = dao.resetStuckInFlight(stuckThreshold)
            if (reset > 0) {
                android.util.Log.d(
                    "PendingWrites",
                    "reset $reset stuck in_flight row(s) back to pending"
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "resetStuckInFlight failed: ${e.message}")
        }

        // 2. Prune done rows. Bounded table = bounded logcat noise and
        //    bounded backup size.
        try {
            val pruneBefore = now - PendingWrite.PRUNE_DONE_AFTER_MS
            dao.pruneDone(pruneBefore)
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "pruneDone failed: ${e.message}")
        }

        // 3. Pull a batch.
        val ready: List<PendingWrite> = try {
            dao.listReadyToFire(now, limit = 50)
        } catch (e: Exception) {
            android.util.Log.w("PendingWrites", "listReadyToFire failed: ${e.message}")
            return Result.retry()
        }

        if (ready.isEmpty()) {
            return Result.success()
        }

        // 4. Process the batch.
        var hasMorePending = false
        for (row in ready) {
            val processed = processOne(row, now)
            if (processed) {
                // Row finished (done or terminal failed). Continue to next.
            } else {
                // The row was rescheduled for a future attempt but is still
                // in the active set. We don't need to know specifically;
                // we just need to know that there's still work to do.
                hasMorePending = true
            }
        }

        // 5. If anything is still eligible to retry, ask WorkManager to
        //    re-run us. The backoff policy on the request will throttle
        //    the retry; the per-row `nextAttemptAt` is the actual gate.
        return if (hasMorePending) Result.retry() else Result.success()
    }

    /**
     * Returns `true` if the row is fully resolved (done or terminal
     * `failed`); `false` if it was rescheduled for a future attempt.
     */
    private suspend fun processOne(row: PendingWrite, now: Long): Boolean {
        return try {
            dao.markInFlight(row.id, now)
            attemptCommit(row)
            dao.markDone(row.id, now)
            android.util.Log.d(
                "PendingWrites",
                "done id=${row.id} collection=${row.collection} doc=${row.documentId} op=${row.operation} attempt=${row.retryCount + 1}"
            )
            true
        } catch (e: Exception) {
            handleFailure(row, e, now)
        }
    }

    /**
     * Perform the actual Firestore write. Mirrors the structure of the
     * original ViewModel code paths so the worker is a true drop-in
     * replacement for the direct calls.
     */
    private suspend fun attemptCommit(row: PendingWrite) {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            // Firebase isn't initialized — treat as a transient failure
            // so the row stays pending and retries after backoff.
            throw IllegalStateException("FirebaseApp not initialized")
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Cannot replay writes while signed out")
        if (user.isAnonymous) throw IllegalStateException("Anonymous TV session cannot replay account writes")
        fun requireOwner(collectionPath: String) {
            val parts = collectionPath.split("/")
            if (parts.size < 2 || parts[0] != "users" || parts[1] != user.uid) {
                throw IllegalStateException("Queued write owner does not match signed-in account")
            }
        }
        requireOwner(row.collection + if (row.collection == "users") "/" + (row.documentId ?: "") else "")
        val db = FirebaseFirestore.getInstance()
        when (row.operation) {
            PendingWrite.OP_CREATE, PendingWrite.OP_UPDATE -> {
                val payload = row.payload
                    ?: throw IllegalStateException("update/create row missing payload")
                val data = jsonToMap(JSONObject(payload))
                val docId = row.documentId
                    ?: throw IllegalStateException("update/create row missing documentId")
                val docRef = resolveDocRef(db, row.collection, docId)
                docRef.set(data, SetOptions.merge()).await()
            }
            PendingWrite.OP_DELETE -> {
                val docId = row.documentId
                    ?: throw IllegalStateException("delete row missing documentId")
                val docRef = resolveDocRef(db, row.collection, docId)
                docRef.delete().await()
            }
            PendingWrite.OP_BATCH -> {
                val payload = row.payload
                    ?: throw IllegalStateException("batch row missing payload")
                val arr = org.json.JSONArray(payload)
                val batch = db.batch()
                val progressRecords = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any?>>>()
                for (i in 0 until arr.length()) {
                    val op = arr.getJSONObject(i)
                    val opName = op.getString("op")
                    val coll = op.getString("collection")
                    requireOwner(coll)
                    val docId = op.getString("documentId")
                    val docRef = resolveDocRef(db, coll, docId)
                    when (opName) {
                        PendingWrite.OP_UPDATE, PendingWrite.OP_CREATE -> {
                            val data = jsonToMap(op.getJSONObject("data"))
                            batch.set(docRef, data, SetOptions.merge())
                            if (coll.endsWith("/continue_watching") || coll.endsWith("/continueWatching")) progressRecords.add(docRef to data)
                        }
                        PendingWrite.OP_DELETE -> batch.delete(docRef)
                        else -> throw IllegalStateException("unknown batch op: $opName")
                    }
                }
                if (progressRecords.size == arr.length() && progressRecords.isNotEmpty() && progressRecords.first().second["lastWatchedTimestamp"] is Number)
                    com.example.data.ContinueWatchingCloudCommit.write(db, progressRecords)
                else batch.commit().await()
            }
            else -> throw IllegalStateException("unknown operation: ${row.operation}")
        }
    }

    /**
     * Resolve a Firestore DocumentReference from a `/`-delimited path like
     * `users/{uid}/continue_watching/{docId}`. Segments at even indices
     * are collection names; at odd indices are document ids. The collection
     * field on the row is the *first* segment; `documentId` is the *last*
     * document id. Middle segments are passed through.
     */
    private fun resolveDocRef(
        db: FirebaseFirestore,
        collectionPath: String,
        documentId: String
    ): com.google.firebase.firestore.DocumentReference {
        val parts = collectionPath.split("/").filter { it.isNotBlank() }
        require(parts.isNotEmpty()) { "empty collection path" }
        require(parts.size % 2 == 1) {
            "collection path must end in a collection segment: $collectionPath"
        }
        // parts is [coll0, doc0, coll1, doc1, ...]
        var collRef: com.google.firebase.firestore.CollectionReference =
            db.collection(parts[0])
        var i = 1
        while (i < parts.size - 1) {
            collRef = collRef.document(parts[i]).collection(parts[i + 1])
            i += 2
        }
        return collRef.document(documentId)
    }

    private suspend fun handleFailure(row: PendingWrite, error: Throwable, now: Long): Boolean {
        val newRetryCount = row.retryCount + 1
        val errorMsg = (error.message ?: error.javaClass.simpleName).take(500)
        val isTerminal = newRetryCount >= PendingWrite.MAX_RETRIES
        if (isTerminal) {
            dao.markRetry(
                id = row.id,
                now = now,
                error = errorMsg,
                nextAttemptAt = now,
                newStatus = PendingWrite.STATUS_FAILED
            )
            android.util.Log.w(
                "PendingWrites",
                "FAILED id=${row.id} collection=${row.collection} doc=${row.documentId} " +
                    "op=${row.operation} attempts=$newRetryCount error=$errorMsg"
            )
            return true
        } else {
            val backoffIdx = (newRetryCount - 1).coerceIn(0, PendingWrite.BACKOFF_MS.size - 1)
            val nextAt = now + PendingWrite.BACKOFF_MS[backoffIdx]
            dao.markRetry(
                id = row.id,
                now = now,
                error = errorMsg,
                nextAttemptAt = nextAt,
                newStatus = PendingWrite.STATUS_PENDING
            )
            android.util.Log.w(
                "PendingWrites",
                "retrying id=${row.id} collection=${row.collection} doc=${row.documentId} " +
                    "op=${row.operation} attempt=$newRetryCount nextInMs=${PendingWrite.BACKOFF_MS[backoffIdx]} error=$errorMsg"
            )
            return false
        }
    }

    /**
     * Convert a JSONObject to a flat `Map<String, Any?>` that Firestore
     * can accept. Server-timestamp sentinels are preserved as
     * [FieldValue] instances. Nested JSONObjects become nested Maps.
     */
    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val it = json.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = jsonValueToFirestore(json, k)
        }
        return out
    }

    private fun jsonValueToFirestore(json: JSONObject, key: String): Any? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val v = json.get(key)) {
            is JSONObject -> {
                if (v.length() == 1 && v.has("__serverTimestamp") && v.optBoolean("__serverTimestamp", false)) {
                    FieldValue.serverTimestamp()
                } else {
                    jsonToMap(v)
                }
            }
            is org.json.JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until v.length()) {
                    val item = v.get(i)
                    when (item) {
                        is JSONObject -> list.add(jsonToMap(item))
                        is org.json.JSONArray -> list.add(item.toString())
                        JSONObject.NULL -> list.add(null)
                        else -> list.add(item)
                    }
                }
                list
            }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
