package com.example.ui.screens.player

import androidx.media3.common.Format
import com.example.data.Caption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlayerSubtitleChoicesTest {
    @Test fun onlyConfirmedTracksAppearInThePlayer() {
        val captions = listOf(
            Caption("https://example.test/en.vtt", "English", "vtt", "en"),
            Caption("https://example.test/en-cc.vtt", "English [CC]", "vtt", "en-cc"),
            Caption("https://example.test/es.srt", "Spanish", "srt", "es", isVerified = false),
            Caption("https://example.test/sprites.vtt", "Thumbnails", "thumbnails")
        )
        assertEquals(listOf("Off", "English", "English [CC]"), availableSubtitleOptions(captions))
        assertEquals(listOf("Off"), availableSubtitleOptions(emptyList()))
    }

    @Test fun unknownLanguageNeverMatchesEnglish() {
        assertFalse(captionMatchesLanguage(Caption("https://example.test/a.vtt", "Unknown", "vtt"), "English"))
        assertFalse(captionMatchesLanguage(Caption("https://example.test/a.vtt", "French", "vtt", "fr"), "English"))
        assertFalse(captionMatchesLanguage(Caption("https://example.test/a.vtt", "Bengali", "vtt", "bn"), "English"))
        assertFalse(trackMatchesLanguage(Format.Builder().setLabel("French").setLanguage("fr").build(), "English"))
        assertEquals("und", getIso2LanguageCode("Unknown"))
        assertTrue(captionMatchesLanguage(Caption("https://example.test/a.vtt", "English [CC]", "vtt", "en-cc"), "English"))
        assertTrue(trackMatchesLanguage(Format.Builder().setLabel("Spanish (España)").setLanguage("es-ES").build(), "Spanish"))
    }
}
