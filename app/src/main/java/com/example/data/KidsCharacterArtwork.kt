package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Dedicated alpha artwork, keyed by catalogue identity rather than a guessed character. */
object KidsCharacterArtwork {
    @Volatile private var cachedCatalog: KidsCharacterCatalog? = null

    suspend fun load(context: Context): KidsCharacterCatalog = withContext(Dispatchers.IO) {
        cachedCatalog ?: synchronized(this@KidsCharacterArtwork) {
            cachedCatalog ?: readCatalog(context).also { cachedCatalog = it }
        }
    }

    private fun readCatalog(context: Context): KidsCharacterCatalog {
        val files = runCatching { context.assets.list("kids_characters").orEmpty().toSet() }
            .getOrDefault(emptySet())
        val entries = runCatching {
            context.assets.open("kids_characters/catalog.json").bufferedReader().use { reader ->
                val items = JSONObject(reader.readText()).optJSONArray("artworks")
                buildList {
                    if (items != null) for (index in 0 until items.length().coerceAtMost(512)) {
                        val entry = items.optJSONObject(index) ?: continue
                        add(KidsCharacterEntry(
                            mediaKind = entry.optString("media_kind"),
                            tmdbId = entry.optString("tmdb_id").takeIf { it.isNotBlank() },
                            imdbId = entry.optString("imdb_id").takeIf { it.isNotBlank() },
                            file = entry.optString("file").takeIf { it.isNotBlank() },
                            url = entry.optString("url").takeIf { it.isNotBlank() },
                            title = entry.optString("title").takeIf { it.isNotBlank() }
                        ))
                    }
                }
            }
        }.getOrDefault(emptyList())
        return KidsCharacterCatalog(files, entries)
    }
}
