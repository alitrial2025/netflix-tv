package com.example.collections

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A screen observes state and issues explicit edits; storage/auth remains outside the native UI. */
interface CollectionsController : AutoCloseable {
    val state: StateFlow<CollectionsState>
    fun start()
    fun dismissError()
    fun create(rawName: String): Boolean
    fun rename(id: String, rawName: String): Boolean
    fun delete(id: String): Boolean
    fun add(id: String, raw: CollectionTitle): Boolean
    fun remove(id: String, title: CollectionTitle): Boolean
}

/** Firestore's persistent cache/queued writes provide offline edits without crossing account/profile paths. */
class CollectionsRepository(private val ownerUid: String?, private val profileId: String) : CollectionsController {
    private val scope = CollectionPolicy.profileScope(ownerUid, profileId)
    private val _state = MutableStateFlow(CollectionsState())
    override val state: StateFlow<CollectionsState> = _state.asStateFlow()
    private var listener: ListenerRegistration? = null
    private var closed = false
    private fun currentOwner(): Boolean = scope != null && !closed &&
        FirebaseAuth.getInstance().currentUser?.let { !it.isAnonymous && it.uid == scope.first } == true
    private fun reference() = FirebaseFirestore.getInstance().collection("users").document(requireNotNull(scope).first)
        .collection("profiles").document(scope.second).collection("collections")

    override fun start() {
        if (listener != null || closed) return
        if (!currentOwner()) { _state.value = CollectionsState(loading = false, error = "Sign in to save collections for this profile."); return }
        listener = reference().whereEqualTo("deleted", false).limit(CollectionPolicy.MAX_COLLECTIONS.toLong())
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!currentOwner()) { _state.value = CollectionsState(loading = false); return@addSnapshotListener }
                if (error != null) {
                    _state.value = _state.value.copy(loading = false, error = "Collections could not sync. Reopen this screen to retry.")
                } else if (snapshot != null) {
                    _state.value = CollectionsState(items = snapshot.documents.mapNotNull {
                        CollectionPolicy.parse(it.id, it.data, it.metadata.hasPendingWrites())
                    }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }, loading = false, offline = snapshot.metadata.isFromCache)
                }
            }
    }
    override fun dismissError() { _state.value = _state.value.copy(error = null) }
    private fun fail(message: String): Boolean { _state.value = _state.value.copy(error = message); return false }
    private fun safe(): Boolean = currentOwner() || fail("Sign in to edit collections for this profile.")
    private fun write(block: () -> com.google.android.gms.tasks.Task<Void>): Boolean {
        if (!safe()) return false
        return try {
            block().addOnFailureListener {
                if (currentOwner()) fail("This change could not sync. Your saved titles are unchanged. Try again.")
            }
            true // Local snapshots reflect the queued write immediately, even while offline.
        } catch (_: Exception) { fail("This change could not be saved. Try again.") }
    }
    override fun create(rawName: String): Boolean {
        val name = CollectionPolicy.name(rawName) ?: return fail("Use a collection name with 1–40 characters.")
        if (CollectionPolicy.duplicate(name, _state.value.items)) return fail("A collection with this name already exists.")
        if (_state.value.items.size >= CollectionPolicy.MAX_COLLECTIONS) return fail("You can create up to 40 collections.")
        return write { reference().document(UUID.randomUUID().toString()).set(mapOf("name" to name, "deleted" to false,
            "items" to emptyMap<String, Any>(), "updatedAt" to FieldValue.serverTimestamp())) }
    }
    override fun rename(id: String, rawName: String): Boolean {
        val name = CollectionPolicy.name(rawName) ?: return fail("Use a collection name with 1–40 characters.")
        if (_state.value.items.none { it.id == id }) return fail("This collection is no longer available.")
        if (CollectionPolicy.duplicate(name, _state.value.items, id)) return fail("A collection with this name already exists.")
        return write { reference().document(id).update("name", name, "updatedAt", FieldValue.serverTimestamp()) }
    }
    override fun delete(id: String): Boolean {
        if (_state.value.items.none { it.id == id }) return fail("This collection is no longer available.")
        return write { reference().document(id).update("deleted", true, "updatedAt", FieldValue.serverTimestamp()) }
    }
    override fun add(id: String, raw: CollectionTitle): Boolean {
        val title = CollectionTitle.sanitize(raw) ?: return fail("This title cannot be added to a collection.")
        val collection = _state.value.items.firstOrNull { it.id == id } ?: return fail("This collection is no longer available.")
        if (collection.items.any { it.key == title.key }) return true
        if (collection.items.size >= CollectionPolicy.MAX_TITLES) return fail("A collection can hold up to 200 titles.")
        // One typed item per field: independent device additions/removals never replace the whole list.
        return write { reference().document(id).update("items.${title.fieldKey}", title.toMap(), "updatedAt", FieldValue.serverTimestamp()) }
    }
    override fun remove(id: String, title: CollectionTitle): Boolean {
        if (_state.value.items.none { it.id == id }) return fail("This collection is no longer available.")
        return write { reference().document(id).update("items.${title.fieldKey}", FieldValue.delete(), "updatedAt", FieldValue.serverTimestamp()) }
    }
    override fun close() { closed = true; listener?.remove(); listener = null }
}
