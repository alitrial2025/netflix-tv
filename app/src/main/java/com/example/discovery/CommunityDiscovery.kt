package com.example.discovery

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared daily unique-account totals, with private per-account deduplication documents. */
class CommunityDiscovery {
    private val state = MutableStateFlow<Map<String, Long>>(emptyMap())
    val counts: StateFlow<Map<String, Long>> = state
    private val recorded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        val listeners = mutableListOf<ListenerRegistration>()
        var identity = ""
        try {
            while (isActive) {
                val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }
                val today = ReleasePolicy.day()
                val next = "${user?.uid.orEmpty()}:$today"
                if (next != identity) {
                    identity = next; listeners.forEach { it.remove() }; listeners.clear(); state.value = emptyMap()
                    if (user != null) {
                        val buckets = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()
                        for (offset in 0..2) {
                            val day = ReleasePolicy.day(System.currentTimeMillis() - offset * 86_400_000L)
                            listeners += FirebaseFirestore.getInstance().collection("community_trends").document(day)
                                .collection("titles").orderBy("viewers",Query.Direction.DESCENDING).limit(30)
                                .addSnapshotListener { snapshot, error ->
                                    if (error == null && identity == next) {
                                        buckets[day] = snapshot?.documents.orEmpty().mapNotNull { doc ->
                                            val key = doc.getString("key") ?: return@mapNotNull null
                                            if (!key.matches(Regex("(movie|tv):[0-9]+"))) return@mapNotNull null
                                            key to (doc.getLong("viewers") ?: 0L).coerceAtLeast(0L)
                                        }.toMap()
                                        state.value = buckets.values.flatMap { it.entries }.groupBy { it.key }
                                            .mapValues { (_, values) -> values.sumOf { it.value.coerceAtMost(1_000_000L) } }
                                    }
                                }
                        }
                    }
                }
                delay(60_000)
            }
        } finally { identity = ""; listeners.forEach { it.remove() }; state.value = emptyMap() }
    }
    suspend fun record(key: String) {
        if (!key.matches(Regex("(movie|tv):[0-9]+"))) return
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: return
        val day = ReleasePolicy.day()
        val memo = "${user.uid}:$day:$key"
        if (!recorded.add(memo)) return
        try {
            val db = FirebaseFirestore.getInstance()
            val titleId = key.replace(':','_')
            val vote = db.collection("users").document(user.uid).collection("discovery_votes").document("${day}_$titleId")
            val title = db.collection("community_trends").document(day).collection("titles").document(titleId)
            val quota = db.collection("users").document(user.uid).collection("discovery_days").document(day)
            db.runTransaction { transaction ->
                if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) return@runTransaction null
                val previous = transaction.get(vote)
                val total = transaction.get(title)
                val budget = transaction.get(quota)
                val contributions = budget.getLong("count") ?: 0L
                if (!previous.exists() && contributions < 20L) {
                    transaction.set(vote,mapOf("day" to day,"key" to key,"createdAt" to FieldValue.serverTimestamp()))
                    transaction.set(title,mapOf("key" to key,"day" to day,"viewers" to ((total.getLong("viewers") ?: 0L)+1L),"updatedAt" to FieldValue.serverTimestamp()))
                    transaction.set(quota,mapOf("count" to contributions+1L,"updatedAt" to FieldValue.serverTimestamp()))
                }
                null
            }.await()
        } catch (cancelled: CancellationException) { recorded.remove(memo); throw cancelled }
        catch (_: Exception) { /* No permission/network must never interrupt playback; no retry flood per tick. */ }
    }
}

/** Qualifies elapsed advancing playback, not seeking to the two-minute position. */
class QualifiedPlayback {
    private var key = ""
    private var previousAt = 0L
    private var previousPosition = 0
    private var watchedMs = 0L
    private var claimed = false
    fun sample(mediaKey: String, positionSeconds: Int, playing: Boolean, elapsedMs: Long): Boolean {
        if (mediaKey != key) { key=mediaKey; previousAt=elapsedMs; previousPosition=positionSeconds; watchedMs=0; claimed=false; return false }
        val delta = elapsedMs-previousAt
        val advance = positionSeconds-previousPosition
        if (playing && advance == 0 && delta in 0..20_000) return false
        if (playing && delta in 1..20_000 && advance in 1..(delta / 1000 + 3).toInt())
            watchedMs += minOf(delta, advance * 1000L)
        previousAt=elapsedMs; previousPosition=positionSeconds
        if (!claimed && watchedMs >= 120_000) { claimed=true; return true }
        return false
    }
}
