package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.Movie
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "continue_watching", primaryKeys = ["profileId", "movieId"])
data class ContinueWatchingEntity(
    val profileId: String,
    val movieId: String,
    val title: String,
    val description: String,
    val backdropUrl: String,
    val posterUrl: String,
    val rating: String,
    val year: String,
    val type: String,
    val duration: String,
    val playbackPositionMs: Long,
    val durationMs: Long,
    val lastWatchedTimestamp: Long,
    val season: Int = 1,
    val episode: Int = 1,
    val episodeName: String = "",
    val logoUrl: String? = null,
    /**
     * `true` once the user has reached >= 95% of the duration. When set, the
     * row is hidden from the "Continue Watching" list and instead surfaces in
     * "Recently Watched". This avoids the prior behaviour where a near-end
     * position would oscillate in/out of the list as the user paused near the
     * end.
     */
    val completed: Boolean = false
) {
    fun toMovie(): Movie {
        return Movie(
            id = movieId,
            title = title,
            description = description,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            rating = rating,
            year = year,
            type = type,
            duration = duration,
            logoUrl = logoUrl
        )
    }

    companion object {
        fun fromMovie(
            profileId: String,
            movie: Movie,
            playbackPositionMs: Long,
            durationMs: Long,
            season: Int = 1,
            episode: Int = 1,
            episodeName: String = ""
        ): ContinueWatchingEntity {
            val percentage = if (durationMs > 0L) {
                (playbackPositionMs.toFloat() / durationMs.toFloat()) * 100f
            } else 0f
            val isNearEnd = durationMs > 0L && (durationMs - playbackPositionMs) <= 30_000L
            val completed = isNearEnd || percentage >= 95f
            return ContinueWatchingEntity(
                profileId = profileId,
                movieId = movie.id,
                title = movie.title,
                description = movie.description,
                backdropUrl = movie.backdropUrl,
                posterUrl = movie.posterUrl,
                rating = movie.rating,
                year = movie.year,
                type = movie.type,
                duration = movie.duration,
                playbackPositionMs = playbackPositionMs,
                durationMs = durationMs,
                lastWatchedTimestamp = System.currentTimeMillis(),
                season = season,
                episode = episode,
                episodeName = episodeName,
                logoUrl = movie.logoUrl,
                completed = completed
            )
        }
    }
}

@Dao
interface ContinueWatchingDao {
    @Query("SELECT * FROM continue_watching WHERE profileId = :profileId AND completed = 0 ORDER BY lastWatchedTimestamp DESC")
    fun getContinueWatchingList(profileId: String): Flow<List<ContinueWatchingEntity>>

    @Query("SELECT * FROM continue_watching WHERE profileId = :profileId AND movieId = :movieId AND completed = 0")
    suspend fun getContinueWatchingById(profileId: String, movieId: String): ContinueWatchingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(continueWatching: ContinueWatchingEntity)

    @Query("DELETE FROM continue_watching WHERE profileId = :profileId AND movieId = :movieId")
    suspend fun deleteContinueWatching(profileId: String, movieId: String)

    @Query("DELETE FROM continue_watching WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)

    @Query("DELETE FROM continue_watching")
    suspend fun clearAll()

    /**
     * Purge rows that have been idle for [olderThanMs] milliseconds. Used to
     * enforce the 30-day inactivity policy (per common industry practice)
     * without keeping stale rows in the local cache forever.
     */
    @Query("DELETE FROM continue_watching WHERE lastWatchedTimestamp < :olderThanMs")
    suspend fun purgeStale(olderThanMs: Long)

    /**
     * Mark a row as completed (used when a near-100% playback position is
     * detected). Returns the number of rows updated.
     */
    @Query("UPDATE continue_watching SET completed = 1 WHERE profileId = :profileId AND movieId = :movieId")
    suspend fun markCompleted(profileId: String, movieId: String): Int

    /**
     * Get a single row — suspend variant used by the player resume path
     * without re-emitting the full Flow.
     */
    @Query("SELECT * FROM continue_watching WHERE profileId = :profileId AND movieId = :movieId LIMIT 1")
    suspend fun getOne(profileId: String, movieId: String): ContinueWatchingEntity?
}

@Database(
    entities = [ContinueWatchingEntity::class, PendingWrite::class],
    version = 5,
    exportSchema = false
)
abstract class NetflixDatabase : RoomDatabase() {
    abstract fun continueWatchingDao(): ContinueWatchingDao
    abstract fun pendingWriteDao(): PendingWriteDao

    companion object {
        @Volatile
        private var INSTANCE: NetflixDatabase? = null

        /**
         * Migration 4 -> 5: introduce the `pending_writes` outbox table
         * for the offline-first Firestore sync (see
         * [com.example.sync.PendingWriteRetryWorker]).
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_writes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        collection TEXT NOT NULL,
                        documentId TEXT,
                        operation TEXT NOT NULL,
                        payload TEXT,
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastAttemptAt INTEGER,
                        lastError TEXT,
                        nextAttemptAt INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending'
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_writes_status ON pending_writes (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_writes_nextAttemptAt ON pending_writes (nextAttemptAt)")
            }
        }

        fun getDatabase(context: Context): NetflixDatabase {
            return INSTANCE ?: synchronized(this) {
                // perf: use the synchronous in-memory builder only when the DB is already
                // created. On first access the build call will create the schema in the
                // background; calling sites already gate this on the IO dispatcher.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetflixDatabase::class.java,
                    "netflix_database"
                )
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                // perf: avoid auto-running onCreate on the main thread the first time the
                // DB is opened from a non-IO context. Room runs the callback on the
                // caller's thread by default — setQueryExecutor is the safer knob.
                .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
                    Thread(r, "netflix-db-io").apply { isDaemon = true }
                })
                .setTransactionExecutor(java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
                    Thread(r, "netflix-db-tx").apply { isDaemon = true }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ContinueWatchingRepository(private val dao: ContinueWatchingDao) {
    fun getContinueWatchingList(profileId: String): Flow<List<ContinueWatchingEntity>> {
        return dao.getContinueWatchingList(profileId)
    }

    suspend fun getContinueWatchingById(profileId: String, movieId: String): ContinueWatchingEntity? {
        return dao.getContinueWatchingById(profileId, movieId)
    }

    /**
     * Save playback progress with three guards:
     *   1. **Debounce**: callers can supply [force] = false to skip writes when
     *      the new position is within 2s of the previously persisted position.
     *      This was a real bug — ExoPlayer's onPositionDiscontinuity + the 10s
     *      heartbeat would each fire a write, and a flaky network would land
     *      a stale write 30s behind the latest position. The check uses the
     *      row already in the DB, not an in-memory cache, so it's correct
     *      after process restart.
     *   2. **Completed-at-end**: when the user crosses 95% / the last 30s,
     *      we don't write a "still in progress" row — we mark the existing
     *      row as completed and let the UI hide it. The row stays so
     *      "Recently Watched" can surface it. (The previous implementation
     *      *deleted* the row, losing the title from history.)
     *   3. **Crash safety**: every code path is wrapped so a DB error
     *      never propagates to the player. Callers can fire-and-forget.
     */
    suspend fun saveProgress(
        profileId: String,
        movie: Movie,
        playbackPositionMs: Long,
        durationMs: Long,
        season: Int = 1,
        episode: Int = 1,
        episodeName: String = "",
        force: Boolean = false
    ) {
        if (durationMs > 0 && profileId.isNotEmpty()) {
            try {
                val percentage = (playbackPositionMs.toFloat() / durationMs.toFloat()) * 100
                val isNearEnd = (durationMs - playbackPositionMs) <= 30000L // 30 seconds remaining
                val isCompleted = isNearEnd || percentage >= 95f
                if (isCompleted) {
                    // Mark the row as completed (or insert it as completed if
                    // we never persisted before). This keeps the title in
                    // "Recently Watched" while removing it from
                    // "Continue Watching".
                    val existing = dao.getOne(profileId, movie.id)
                    if (existing != null) {
                        dao.markCompleted(profileId, movie.id)
                    } else {
                        dao.insertOrUpdate(
                            ContinueWatchingEntity.fromMovie(
                                profileId = profileId,
                                movie = movie,
                                playbackPositionMs = playbackPositionMs,
                                durationMs = durationMs,
                                season = season,
                                episode = episode,
                                episodeName = episodeName
                            ).copy(completed = true)
                        )
                    }
                    return
                }

                if (playbackPositionMs > 2000L) {
                    // Debounce: skip writes that are within 2s of the last
                    // persisted position. The previous implementation wrote
                    // on every timer tick, which thrashed the local DB and
                    // could race with a stale Firestore sync.
                    if (!force) {
                        val existing = dao.getOne(profileId, movie.id)
                        if (existing != null &&
                            kotlin.math.abs(existing.playbackPositionMs - playbackPositionMs) < 2000L) {
                            return
                        }
                    }
                    val entity = ContinueWatchingEntity.fromMovie(
                        profileId = profileId,
                        movie = movie,
                        playbackPositionMs = playbackPositionMs,
                        durationMs = durationMs,
                        season = season,
                        episode = episode,
                        episodeName = episodeName
                    )
                    dao.insertOrUpdate(entity)
                }
            } catch (e: Exception) {
                // DB failure must never crash the player.
                android.util.Log.e("ContinueWatchingRepository", "saveProgress failed for ${movie.id}", e)
            }
        }
    }

    suspend fun deleteProgress(profileId: String, movieId: String) {
        if (profileId.isNotEmpty()) {
            try {
                dao.deleteContinueWatching(profileId, movieId)
            } catch (e: Exception) {
                android.util.Log.e("ContinueWatchingRepository", "deleteProgress failed for $movieId", e)
            }
        }
    }

    suspend fun deleteForProfile(profileId: String) {
        if (profileId.isNotEmpty()) {
            try {
                dao.deleteForProfile(profileId)
            } catch (e: Exception) {
                android.util.Log.e("ContinueWatchingRepository", "deleteForProfile failed for $profileId", e)
            }
        }
    }

    /**
     * Purge rows idle for more than 30 days. Called from the ViewModel on
     * app start so the local cache doesn't grow unbounded.
     */
    suspend fun purgeStaleEntries(maxAgeMs: Long) {
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            dao.purgeStale(cutoff)
        } catch (e: Exception) {
            android.util.Log.e("ContinueWatchingRepository", "purgeStaleEntries failed", e)
        }
    }
}
