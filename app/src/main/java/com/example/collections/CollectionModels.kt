package com.example.collections

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Display metadata only. Opening a title must resolve its typed identity through normal admission. */
data class CollectionTitle(val id: String, val kind: String, val title: String, val posterUrl: String = "") {
    val key: String get() = "$kind:$id"
    val fieldKey: String get() = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun toMap(): Map<String, Any> = mapOf("id" to id, "kind" to kind, "title" to title, "posterUrl" to posterUrl)
    companion object {
        fun sanitize(value: CollectionTitle): CollectionTitle? {
            if (value.kind !in setOf("movie", "tv")) return null
            val prefixed = Regex("^(?:tmdb:)?(movie|tv):([1-9][0-9]{0,9})$").matchEntire(value.id)
            if (prefixed != null && prefixed.groupValues[1] != value.kind) return null
            val id = prefixed?.groupValues?.get(2) ?: value.id
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return null
            val title = value.title.trim().takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
            val poster = runCatching { URI(value.posterUrl) }.getOrNull()?.takeIf {
                it.scheme == "https" && it.host == "image.tmdb.org" && it.userInfo == null && it.port == -1 &&
                    it.path?.startsWith("/t/p/") == true && value.posterUrl.length <= 1000
            }?.toString().orEmpty()
            return value.copy(id = id, title = title, posterUrl = poster)
        }
        fun fromMap(value: Any?): CollectionTitle? {
            val map = value as? Map<*, *> ?: return null
            return sanitize(CollectionTitle(map["id"] as? String ?: return null, map["kind"] as? String ?: return null,
                map["title"] as? String ?: return null, map["posterUrl"] as? String ?: ""))
        }
    }
}
data class TitleCollection(val id: String, val name: String, val items: List<CollectionTitle>, val pendingSync: Boolean = false)
data class CollectionsState(val items: List<TitleCollection> = emptyList(), val loading: Boolean = true,
    val offline: Boolean = false, val error: String? = null)

object CollectionPolicy {
    const val MAX_COLLECTIONS = 40
    const val MAX_TITLES = 200
    fun name(value: String): String? = value.trim().replace(Regex("\\s+"), " ")
        .takeIf { it.length in 1..40 && it.none(Char::isISOControl) }
    fun duplicate(value: String, collections: List<TitleCollection>, exceptId: String? = null): Boolean =
        collections.any { it.id != exceptId && it.name.lowercase(Locale.ROOT) == value.lowercase(Locale.ROOT) }
    fun profileScope(uid: String?, profileId: String?): Pair<String, String>? =
        if (uid.isNullOrBlank() || uid.length > 128 || '/' in uid ||
            profileId.isNullOrBlank() || profileId.length > 100 || '/' in profileId || profileId in setOf("guest", "Guest")) null
        else uid to profileId
    /** Deleted documents stay tombstoned so a delayed item edit cannot resurrect a removed collection. */
    fun parse(id: String, document: Map<String, Any>?, pending: Boolean = false): TitleCollection? {
        document ?: return null
        if (document["deleted"] != false || !id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return null
        val name = name(document["name"] as? String ?: return null) ?: return null
        val items = (document["items"] as? Map<*, *>)?.entries.orEmpty().take(MAX_TITLES).mapNotNull { entry ->
            CollectionTitle.fromMap(entry.value)?.takeIf { it.fieldKey == entry.key }
        }.distinctBy { it.key }.sortedBy { it.title.lowercase(Locale.ROOT) }
        return TitleCollection(id, name, items, pending)
    }
}
