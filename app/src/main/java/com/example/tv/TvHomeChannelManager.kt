package com.example.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.example.R
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isSeriesContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the dedicated "NETFLIX PRO" Android TV / Google TV Home Screen Channel
 * and the system-wide "Play Next" / Watch Next row via the official Android TV Provider API.
 */
// Public PreviewProgram/WatchNextProgram builders inherit methods from AndroidX's
// library-restricted base classes. These are the documented app-facing builders.
@android.annotation.SuppressLint("RestrictedApi")
object TvHomeChannelManager {
    private const val TAG = "TvHomeChannelManager"
    private const val CHANNEL_NAME = "NETFLIX PRO"
    private const val PREFS_NAME = "netflix_tv_channel_prefs"
    private const val KEY_CHANNEL_ID = "netflix_pro_channel_id"
    private const val MAX_WATCH_NEXT_PROGRAMS = 5

    private val lastUpdateTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastUpdateStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val watchNextWriteMutex = Mutex()

    private fun isTvProviderAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val providerInfo = context.packageManager.resolveContentProvider("android.media.tv", 0)
            providerInfo != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Finds or registers the dedicated "NETFLIX PRO" channel row on Android TV / Google TV launcher.
     */
    suspend fun getOrCreateNetflixProChannel(context: Context): Long? = withContext(Dispatchers.IO) {
        if (!isTvProviderAvailable(context)) {
            return@withContext null
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_CHANNEL_ID, -1L)

        // Verify if saved channel exists in system TvProvider
        if (savedId != -1L) {
            try {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(savedId),
                    null,
                    null,
                    null,
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        return@withContext savedId
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Saved channel lookup failed: ${e.message}")
            }
        }

        // Query all channels by package
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME),
                "${TvContractCompat.Channels.COLUMN_PACKAGE_NAME} = ?",
                arrayOf(context.packageName),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1)
                    if (name.equals(CHANNEL_NAME, ignoreCase = true)) {
                        prefs.edit().putLong(KEY_CHANNEL_ID, id).apply()
                        return@withContext id
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Existing channels query failed: ${e.message}")
        }

        // Create new dedicated "NETFLIX PRO" System Home Screen Channel
        try {
            val builder = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(CHANNEL_NAME)
                .setDescription("Trending Movies, Shows & Originals on Netflix Pro")
                .setAppLinkIntentUri(Uri.parse("netflixpro://home"))

            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                builder.build().toContentValues()
            )

            if (channelUri != null) {
                val channelId = ContentUris.parseId(channelUri)
                prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply()

                // Set channel brand icon / logo
                try {
                    val logoBitmap = getChannelLogoBitmap(context)
                    if (logoBitmap != null) {
                        ChannelLogoUtils.storeChannelLogo(context, channelId, logoBitmap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set channel logo: ${e.message}")
                }

                // Request channel to be browsable / visible by default on Android TV home screen
                try {
                    TvContractCompat.requestChannelBrowsable(context, channelId)
                } catch (e: Exception) {
                    Log.w(TAG, "Request browsable error: ${e.message}")
                }

                Log.d(TAG, "🎉 Successfully created '$CHANNEL_NAME' Android TV System Channel (ID: $channelId)")
                return@withContext channelId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create '$CHANNEL_NAME' system channel: ${e.message}")
        }
        null
    }

    /**
     * Publishes trending/recommended movies into the "NETFLIX PRO" Android TV Home Screen channel.
     */
    suspend fun publishChannelPrograms(context: Context, movies: List<Movie>) = withContext(Dispatchers.IO) {
        if (!isTvProviderAvailable(context) || movies.isEmpty()) return@withContext

        val channelId = getOrCreateNetflixProChannel(context) ?: return@withContext

        try {
            // Delete old programs in channel
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null
            )

            // Insert top 15-20 movies/shows into the home screen channel
            movies.take(20).forEachIndexed { index, movie ->
                try {
                    val isSeries = movie.isSeriesContent()
                    val programType = if (isSeries) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE

                    val posterUrl = movie.backdropUrl.ifBlank { movie.posterUrl }

                    val program = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setTitle(movie.title)
                        .setDescription(movie.description)
                        .setPosterArtUri(if (posterUrl.isNotBlank()) Uri.parse(posterUrl) else null)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                        .setType(programType)
                        .setIntentUri(Uri.parse("netflixpro://movie/${movie.id}"))
                        .setWeight(20 - index)
                        .setLive(false)
                        .build()

                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed inserting program ${movie.title}: ${e.message}")
                }
            }
            Log.d(TAG, "🍿 Published ${movies.size.coerceAtMost(20)} programs to '$CHANNEL_NAME' TV Home Channel")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing programs to TV channel: ${e.message}")
        }
    }

    /**
     * Updates or inserts a movie into Android TV's system-wide "Play Next" row (WatchNextProgram).
     *
     * @param positionMs Current saved playback position in milliseconds.
     * @param durationMs Total duration in milliseconds.
     * @param isNextEpisode If true, marks as WATCH_NEXT_TYPE_NEXT (next episode queued).
     */
    suspend fun updateWatchNextProgram(
        context: Context,
        movie: Movie,
        positionMs: Long,
        durationMs: Long,
        season: Int = 1,
        episode: Int = 1,
        isNextEpisode: Boolean = false,
        forceUpdate: Boolean = false
    ) = withContext(Dispatchers.IO) {
        watchNextWriteMutex.withLock {
        if (!isTvProviderAvailable(context)) return@withContext

        // Throttle updates: avoid spamming TvProvider every 2 seconds unless forced or state changed
        val now = System.currentTimeMillis()
        val contentKey = "netflixpro:${movie.catalogMediaKind()}:${movie.id}"
        val lastUpdate = lastUpdateTimestamps[contentKey] ?: 0L
        val playbackState = "$season:$episode:$isNextEpisode"
        if (!forceUpdate && lastUpdateStates[contentKey] == playbackState && (now - lastUpdate < 15_000L)) {
            return@withContext
        }

        try {
            val isSeries = movie.isSeriesContent()
            val watchNextType = if (isNextEpisode) {
                TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            } else {
                TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            }

            val titleText = if (isSeries && season > 0 && episode > 0) {
                "${movie.title} • S${season}:E${episode}"
            } else {
                movie.title
            }

            val posterUrl = movie.backdropUrl.ifBlank { movie.posterUrl }
            val deepLink = Uri.Builder()
                .scheme("netflixpro")
                .authority("play")
                .appendPath(movie.id)
                .appendQueryParameter("season", season.toString())
                .appendQueryParameter("episode", episode.toString())
                .appendQueryParameter("pos", positionMs.toString())
                .appendQueryParameter("mediaKind", movie.catalogMediaKind())
                .appendQueryParameter("title", movie.title)
                .build()

            val program = WatchNextProgram.Builder()
                .setType(if (isSeries) TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(watchNextType)
                .setTitle(titleText)
                .setDescription(movie.description)
                .setPosterArtUri(if (posterUrl.isNotBlank()) Uri.parse(posterUrl) else null)
                .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                .setLastPlaybackPositionMillis(positionMs.toInt())
                .setDurationMillis(durationMs.coerceAtLeast(1L).toInt())
                .setLastEngagementTimeUtcMillis(now)
                .setIntentUri(deepLink)
                .setContentId(contentKey)
                .setInternalProviderId(contentKey)
                .build()

            // Find all existing entries for this movie in Watch Next to update or clean duplicates
            val existingIds = mutableListOf<Long>()
            try {
                val cursor = context.contentResolver.query(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    arrayOf(
                        TvContractCompat.WatchNextPrograms._ID,
                        TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                        TvContractCompat.WatchNextPrograms.COLUMN_TITLE
                    ),
                    null,
                    null,
                    null
                )
                cursor?.use { c ->
                    val idCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                    val contentIdCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID)
                    val internalIdCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                    val titleCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)

                    while (c.moveToNext()) {
                        val rowId = if (idCol >= 0) c.getLong(idCol) else -1L
                        val cId = if (contentIdCol >= 0) c.getString(contentIdCol) else null
                        val intId = if (internalIdCol >= 0) c.getString(internalIdCol) else null
                        val existingTitle = if (titleCol >= 0) c.getString(titleCol) else null
                        val sameLegacyTitle = existingTitle == movie.title ||
                            existingTitle?.startsWith("${movie.title} • S") == true

                        if (cId == contentKey || intId == contentKey ||
                            ((cId == movie.id || intId == movie.id) && sameLegacyTitle)) {
                            if (rowId > 0L) existingIds.add(rowId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying existing WatchNext cards: ${e.message}")
            }

            if (existingIds.isNotEmpty()) {
                // Update the primary existing card in-place
                val primaryId = existingIds.first()
                val updateUri = TvContractCompat.buildWatchNextProgramUri(primaryId)
                context.contentResolver.update(updateUri, program.toContentValues(), null, null)

                // Delete any duplicate records for this same movie
                for (i in 1 until existingIds.size) {
                    try {
                        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existingIds[i]), null, null)
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "▶ Updated existing Watch Next Program in-place: $titleText (ID: $primaryId)")
            } else {
                // Insert fresh card
                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues()
                )
                Log.d(TAG, "▶ Inserted new Watch Next Program: $titleText")
            }

            // Prune excess Play Next cards so the user never gets unlimited cards on TV
            pruneWatchNextPrograms(context, MAX_WATCH_NEXT_PROGRAMS)
            lastUpdateTimestamps[contentKey] = now
            lastUpdateStates[contentKey] = playbackState

        } catch (e: Exception) {
            Log.e(TAG, "Error updating WatchNextProgram: ${e.message}")
        }
        }
    }

    /**
     * Enforces strict limit on the TV Play Next row by removing older/excess cards.
     */
    private fun pruneWatchNextPrograms(context: Context, maxItems: Int) {
        try {
            val allCards = mutableListOf<Pair<Long, Long>>() // (id, lastEngagementTime)
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                    TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID
                ),
                null,
                null,
                null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val timeCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
                val contentIdCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID)

                while (c.moveToNext()) {
                    val rowId = if (idCol >= 0) c.getLong(idCol) else -1L
                    val time = if (timeCol >= 0) c.getLong(timeCol) else 0L
                    val contentId = if (contentIdCol >= 0) c.getString(contentIdCol) else null
                    if (rowId > 0L && contentId?.startsWith("netflixpro:") == true) {
                        allCards.add(rowId to time)
                    }
                }
            }

            if (allCards.size > maxItems) {
                // Sort descending by engagement time; cards after maxItems are the oldest to prune
                allCards.sortByDescending { it.second }
                val cardsToDelete = allCards.drop(maxItems)
                for ((excessId, _) in cardsToDelete) {
                    try {
                        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(excessId), null, null)
                        Log.d(TAG, "✂ Pruned excess Watch Next card (ID: $excessId)")
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pruning Watch Next cards: ${e.message}")
        }
    }

    /** Upgrade cards created by older releases so their artwork and playback share an identity. */
    suspend fun migrateLegacyWatchNextLinks(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvProviderAvailable(context)) return@withContext
        try {
            val upgrades = mutableListOf<Pair<Long, ContentValues>>()
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
                    TvContractCompat.WatchNextPrograms.COLUMN_TYPE,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI
                ), null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms._ID))
                    val oldLink = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI))
                        ?: continue
                    val uri = Uri.parse(oldLink)
                    if (uri.scheme != "netflixpro" || uri.host != "play") continue
                    if (uri.getQueryParameter("mediaKind") != null && uri.getQueryParameter("title") != null) continue
                    val movieId = uri.lastPathSegment ?: continue
                    val contentId = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID))
                    val internalId = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID))
                    if (contentId != movieId && internalId != movieId) continue
                    val cardTitle = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_TITLE))
                        ?: continue
                    val baseTitle = cardTitle.replace(Regex(" • S\\d+:E\\d+$"), "").trim()
                    if (baseTitle.isEmpty()) continue
                    val cardType = cursor.getInt(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_TYPE))
                    val kind = if (cardTitle != baseTitle || cardType == TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES) "tv" else "movie"
                    val key = "netflixpro:$kind:$movieId"
                    val upgraded = uri.buildUpon()
                        .appendQueryParameter("mediaKind", kind)
                        .appendQueryParameter("title", baseTitle)
                        .build()
                    val values = ContentValues().apply {
                        put(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI, upgraded.toString())
                        put(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID, key)
                        put(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key)
                    }
                    upgrades.add(rowId to values)
                }
            }
            // Close the provider cursor before writing back to that provider.
            for ((rowId, values) in upgrades) {
                context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(rowId), values, null, null)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not upgrade older Play Next cards", error)
        }
    }

    /** Read the advertised identity when an older launcher card has no title in its link. */
    suspend fun identityForLegacyLink(context: Context, clicked: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!isTvProviderAvailable(context) || clicked.scheme != "netflixpro" || clicked.host != "play") return@withContext null
        val matches = mutableSetOf<Pair<String, String>>()
        try {
            context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
                    TvContractCompat.WatchNextPrograms.COLUMN_TYPE,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val link = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI)) ?: continue
                    val candidate = Uri.parse(link)
                    if (candidate.scheme != clicked.scheme || candidate.host != clicked.host ||
                        candidate.lastPathSegment != clicked.lastPathSegment) continue
                    if (listOf("season", "episode", "pos").any { name ->
                        clicked.getQueryParameter(name)?.let { it != candidate.getQueryParameter(name) } == true
                    }) continue
                    val cardTitle = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)) ?: continue
                    val title = cardTitle.replace(Regex(" • S\\d+:E\\d+$"), "").trim()
                    if (title.isBlank()) continue
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms.COLUMN_TYPE))
                    val kind = candidate.getQueryParameter("mediaKind") ?: if (cardTitle != title ||
                        type == TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES) "tv" else "movie"
                    matches.add(title to kind)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read older Play Next identity", error)
        }
        matches.singleOrNull()
    }

    /** Removes an entry from the Android TV "Play Next" row. */
    suspend fun removeWatchNextProgram(
        context: Context,
        movieId: String,
        mediaKind: String? = null,
        expectedTitle: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!isTvProviderAvailable(context)) return@withContext
        val keys = if (mediaKind == "tv" || mediaKind == "movie") {
            listOf("netflixpro:$mediaKind:$movieId")
        } else {
            listOf("netflixpro:tv:$movieId", "netflixpro:movie:$movieId")
        }
        keys.forEach {
            lastUpdateTimestamps.remove(it)
            lastUpdateStates.remove(it)
        }
        try {
            // Find and delete matching programs by URI
            val idsToDelete = mutableListOf<Long>()
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_TITLE
                ),
                null,
                null,
                null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val contentIdCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID)
                val internalIdCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                val titleCol = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)

                while (c.moveToNext()) {
                    val rowId = if (idCol >= 0) c.getLong(idCol) else -1L
                    val cId = if (contentIdCol >= 0) c.getString(contentIdCol) else null
                    val intId = if (internalIdCol >= 0) c.getString(internalIdCol) else null
                    val cardTitle = if (titleCol >= 0) c.getString(titleCol) else null
                    val sameLegacyTitle = expectedTitle == null || cardTitle == expectedTitle ||
                        cardTitle?.startsWith("$expectedTitle • S") == true

                    if (cId in keys || intId in keys ||
                        ((cId == movieId || intId == movieId) && sameLegacyTitle)) {
                        if (rowId > 0L) idsToDelete.add(rowId)
                    }
                }
            }

            for (id in idsToDelete) {
                context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                Log.d(TAG, "🗑 Removed Watch Next card (ID: $id) for movieId: $movieId")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error removing WatchNextProgram: ${e.message}")
        }
    }

    private fun getChannelLogoBitmap(context: Context): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_netflix_logo) ?: return null
            val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
