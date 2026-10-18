package com.example.data

import com.google.firebase.firestore.FieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TrustedProfileSyncTest {
    @Test fun queuedAndDirectProfilePayloadsRetainIdentityAndSettingsWithoutSerializingServerSentinels() {
        val body = TrustedProfileSync.payload(listOf(mapOf("id" to "viewer_1", "name" to "Viewer",
            "isKids" to true, "favoriteGenres" to listOf("Animation"), "updatedAt" to FieldValue.serverTimestamp())), "viewer_1")
        assertEquals("viewer_1", body.getString("activeProfileId"))
        val profile = body.getJSONArray("profiles").getJSONObject(0)
        assertEquals("viewer_1", profile.getString("id"))
        assertTrue(profile.getBoolean("isKids"))
        assertEquals("Animation", profile.getJSONArray("favoriteGenres").getString(0))
        assertFalse(profile.has("updatedAt"))
    }
}
