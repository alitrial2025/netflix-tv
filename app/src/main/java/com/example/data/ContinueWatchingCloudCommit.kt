package com.example.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** Guard the canonical record and all compatibility mirrors in one atomic transaction. */
object ContinueWatchingCloudCommit {
    suspend fun write(db: FirebaseFirestore, records: List<Pair<DocumentReference, Map<String, Any?>>>) {
        require(records.isNotEmpty())
        val canonical = records.first()
        val stamp = (canonical.second["lastWatchedTimestamp"] as? Number)?.toLong() ?: error("Missing watch event timestamp")
        val ownerUid = canonical.first.path.split("/").getOrNull(1) ?: error("Missing watch account")
        db.runTransaction { transaction ->
            check(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid == ownerUid) { "Watch account changed" }
            val remote = transaction.get(canonical.first)
            if (ContinueWatchingEventPolicy.isNewer(stamp, remote.getLong("lastWatchedTimestamp"))) {
                records.forEach { (ref, data) -> transaction.set(ref, data, SetOptions.merge()) }
            }
        }.await()
    }
}
