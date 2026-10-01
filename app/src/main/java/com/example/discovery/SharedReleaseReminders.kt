package com.example.discovery

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Same typed, profile-scoped reminder path on both devices; disabled entries are tombstones. */
class SharedReleaseReminders(context: Context) {
    private val prefs = context.getSharedPreferences("shared_release_reminders_v1",Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = state
    private var listener: ListenerRegistration? = null
    private var generation = 0L
    private var owner = ""
    private var profile = ""
    fun select(profileId: String, legacy: Set<String> = emptySet()) {
        val uid = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid.orEmpty()
        if (uid==owner && profileId==profile) return
        generation++; val ticket=generation
        listener?.remove(); listener=null;owner=uid;profile=profileId
        if(uid.isEmpty() || profileId.isEmpty()) {state.value=emptySet();return}
        val cacheKey="$uid:$profileId"
        val initial=prefs.getStringSet(cacheKey,null)?.toSet() ?: legacy
        state.value=initial
        val migrationStarted=mutableSetOf<String>()
        val collection=FirebaseFirestore.getInstance().collection("users").document(uid).collection("profiles").document(profileId).collection("release_reminders")
        listener=collection.addSnapshotListener { snapshot,error ->
            if(ticket!=generation || FirebaseAuth.getInstance().currentUser?.uid!=uid || error!=null || snapshot==null) return@addSnapshotListener
            val rows=snapshot.documents.mapNotNull { doc ->
                val key=doc.getString("key") ?: return@mapNotNull null
                if(!key.matches(Regex("(movie|tv):[0-9]+"))) return@mapNotNull null
                key to (doc.getBoolean("enabled")==true)
            }.toMap()
            val unmatched = initial.filter { it !in rows }.toSet()
            val migrating=if(!snapshot.metadata.isFromCache) unmatched else emptySet()
            val keys=rows.filterValues { it }.keys+unmatched
            state.value=keys;prefs.edit().putStringSet(cacheKey,keys).apply()
            migrating.filter { migrationStarted.add(it) }.forEach { key ->
                val target=collection.document(key.replace(':','_'))
                FirebaseFirestore.getInstance().runTransaction { transaction ->
                    if(ticket!=generation || FirebaseAuth.getInstance().currentUser?.uid!=uid) return@runTransaction null
                    // A concurrent disable must win over importing a legacy enabled reminder.
                    if(!transaction.get(target).exists()) transaction.set(target,
                        mapOf("key" to key,"enabled" to true,"updatedAt" to FieldValue.serverTimestamp()))
                    null
                }
            }
        }
    }
    fun toggle(key: String) {
        if(owner.isEmpty() || profile.isEmpty() || FirebaseAuth.getInstance().currentUser?.uid!=owner || !key.matches(Regex("(movie|tv):[0-9]+"))) return
        val enabled=key !in state.value
        state.value=if(enabled)state.value+key else state.value-key
        prefs.edit().putStringSet("$owner:$profile",state.value).apply()
        FirebaseFirestore.getInstance().collection("users").document(owner).collection("profiles").document(profile)
            .collection("release_reminders").document(key.replace(':','_'))
            .set(mapOf("key" to key,"enabled" to enabled,"updatedAt" to FieldValue.serverTimestamp()))
    }
    fun close() { generation++;listener?.remove();listener=null;owner="";profile="";state.value=emptySet() }
}
