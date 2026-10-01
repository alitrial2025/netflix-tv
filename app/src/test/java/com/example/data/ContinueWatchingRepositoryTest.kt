package com.example.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.Movie
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContinueWatchingRepositoryTest {
    private lateinit var database: NetflixDatabase
    private lateinit var repository: ContinueWatchingRepository
    private val show = Movie("42", "Show", "", "", "", "13+", "2026", "Series", "2 Seasons")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetflixDatabase::class.java).build()
        repository = ContinueWatchingRepository(database.continueWatchingDao())
    }

    @After fun tearDown() = database.close()

    @Test fun equalPositionsInDifferentEpisodesAreBothSaved() = runBlocking {
        repository.saveProgress("profile", show, 12_000L, 600_000L, 1, 1, "First")
        repository.saveProgress("profile", show, 12_000L, 600_000L, 1, 2, "Second")
        assertEquals(2, repository.getContinueWatchingById("profile", "42")?.episode)
    }

    @Test fun startingNextEpisodeImmediatelyRestoresContinueWatching() = runBlocking {
        repository.saveProgress("profile", show, 599_000L, 600_000L, 1, 1, "First")
        assertNull(repository.getContinueWatchingById("profile", "42"))
        repository.saveProgress("profile", show, 0L, 600_000L, 1, 2, "Second", force = true)
        val resume = repository.getContinueWatchingById("profile", "42")
        assertNotNull(resume)
        assertEquals(2, resume?.episode)
        assertEquals(0L, resume?.playbackPositionMs)
    }

    @Test fun completionStoresTheCurrentEpisodeMetadata() = runBlocking {
        repository.saveProgress("profile", show, 12_000L, 600_000L, 1, 1, "First")
        repository.saveProgress("profile", show, 599_000L, 600_000L, 2, 1, "New season")
        val completed = database.continueWatchingDao().getOne("profile", "42")
        assertEquals(2, completed?.season)
        assertEquals("New season", completed?.episodeName)
        assertTrue(completed?.completed == true)
    }

    @Test fun forcedExitSaveBypassesDebounce() = runBlocking {
        repository.saveProgress("profile", show, 12_000L, 600_000L)
        repository.saveProgress("profile", show, 13_000L, 600_000L, force = true)
        assertEquals(13_000L, repository.getContinueWatchingById("profile", "42")?.playbackPositionMs)
    }
    @Test fun remoteRewindWinsAndOlderEpisodeCannotOverwriteIt() = runBlocking {
        repository.upsertRemoteProgressIfNewer("profile", show, 300_000L, 600_000L, 1, 2, "Second", 100L)
        repository.upsertRemoteProgressIfNewer("profile", show, 50_000L, 600_000L, 1, 2, "Second", 200L)
        repository.upsertRemoteProgressIfNewer("profile", show, 400_000L, 600_000L, 1, 1, "Old", 150L)
        val resume = repository.getContinueWatchingById("profile", "42")!!
        assertEquals(50_000L, resume.playbackPositionMs)
        assertEquals(200L, resume.lastWatchedTimestamp)
        assertEquals(2, resume.episode)
    }

    @Test fun removalRejectsAnOlderSaveButReplayCanStartAgain() = runBlocking {
        repository.upsertRemoteProgressIfNewer("profile", show, 300_000L, 600_000L, watchedAt = 100L)
        repository.applyRemoteRemoval("profile", "42", 200L)
        repository.upsertRemoteProgressIfNewer("profile", show, 400_000L, 600_000L, watchedAt = 150L)
        assertNull(repository.getContinueWatchingById("profile", "42"))
        repository.upsertRemoteProgressIfNewer("profile", show, 0L, 600_000L, 1, 2, "Second", 300L)
        assertEquals(2, repository.getContinueWatchingById("profile", "42")?.episode)
        assertEquals(0L, repository.getContinueWatchingById("profile", "42")?.playbackPositionMs)
    }

}
