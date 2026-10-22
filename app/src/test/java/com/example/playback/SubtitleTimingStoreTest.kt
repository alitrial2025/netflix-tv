package com.example.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SubtitleTimingStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("subtitle_timing", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun timingPersistsForTheSameAccountProfileAndTypedTitle() {
        SubtitleTimingStore(context).put("account-a", "profile-a", "tv:42", -1250)
        assertEquals(-1250, SubtitleTimingStore(context).get("account-a", "profile-a", "tv:42"))
    }
    @Test fun accountsProfilesAndMovieTvNamespacesCannotLeakOffsets() {
        val store = SubtitleTimingStore(context)
        store.put("account-a", "profile-a", "tv:42", 1500)
        assertEquals(0, store.get("account-b", "profile-a", "tv:42"))
        assertEquals(0, store.get("account-a", "profile-b", "tv:42"))
        assertEquals(0, store.get("account-a", "profile-a", "movie:42"))
        assertEquals(0, store.get("account-a", "profile-a", "tv:43"))
    }
    @Test fun resetAndBoundsSurviveRecreation() {
        val store = SubtitleTimingStore(context)
        store.put("account", "profile", "movie:42", Int.MAX_VALUE)
        assertEquals(10_000, SubtitleTimingStore(context).get("account", "profile", "movie:42"))
        store.put("account", "profile", "movie:42", Int.MIN_VALUE)
        assertEquals(-10_000, SubtitleTimingStore(context).get("account", "profile", "movie:42"))
        store.put("account", "profile", "movie:42", 0)
        assertEquals(0, SubtitleTimingStore(context).get("account", "profile", "movie:42"))
    }
    @Test fun storedKeysContainNoRawAccountProfileOrTitleIdentifiers() {
        SubtitleTimingStore(context).put("private-account", "private-profile", "tv:42", 750)
        val key = context.getSharedPreferences("subtitle_timing", Context.MODE_PRIVATE).all.keys.single()
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertFalse(key.contains("private"))
    }
}
