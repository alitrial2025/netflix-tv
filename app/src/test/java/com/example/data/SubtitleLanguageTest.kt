package com.example.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubtitleLanguageTest {
    @Test fun missingLanguageIsNeverManufacturedAsEnglish() {
        val unknown = PlaybackMetadataTracks.parse(JSONObject("""{"tracks":[{"file":"/1234.vtt","kind":"subtitles"}]}"""), "https://net52.cc".toHttpUrl()).single()
        assertEquals("und", unknown.languageCode)
        assertEquals("Unknown language", unknown.language)
        assertFalse(SubtitleLanguage.matches(unknown.language, unknown.languageCode, "English", unknown.url))
    }
    @Test fun isoThreeNamesAndRegionalTagsResolveWithoutChangingLanguages() {
        assertEquals("en", SubtitleLanguage.code("eng"))
        assertEquals("fr", SubtitleLanguage.code("fra"))
        assertEquals("en", SubtitleLanguage.code("English [CC]"))
        assertEquals("es", SubtitleLanguage.code("es-419"))
        assertEquals(SubtitleLanguage.Identity("French", "fra"), SubtitleLanguage.identity("", "fra"))
        assertEquals("en-gb", SubtitleLanguage.identity("English", "en-GB").code)
        assertFalse(SubtitleLanguage.matches("Hindi", "hin", "English"))
    }
    @Test fun conflictingProviderFieldsAndFilenameCannotMasqueradeAsEnglish() {
        val conflict = SubtitleLanguage.normalize(Caption("https://cdn.example/123-hin.srt", "English", "srt", "en"))
        assertEquals("und", conflict.languageCode)
        assertFalse(SubtitleLanguage.matches(conflict.language, conflict.languageCode, "English", conflict.url))
        assertEquals(conflict, SubtitleLanguage.normalize(conflict))
        assertFalse(SubtitleLanguage.matches("English", "hin", "English"))
        assertTrue(SubtitleLanguage.matches("English [CC]", "eng", "English"))
    }
    private fun group(id: String, label: String?, code: String?, selected: Boolean, flags: Int = 0): Tracks.Group {
        val format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.TEXT_VTT).setLabel(label).setLanguage(code).setSelectionFlags(flags).build()
        return Tracks.Group(TrackGroup(id, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
    }
    @Test fun explicitEnglishChoiceNeverSelectsForeignDefaultOrUnknownTrack() {
        val french = group("fr", "French", "fr", true, C.SELECTION_FLAG_DEFAULT)
        val unknown = group("unknown", null, null, false)
        assertNull(SubtitleTrackSelection.choose(Tracks(ImmutableList.of(french, unknown)), "English"))
        val english = group("en", "English", "eng", false)
        assertEquals(english.mediaTrackGroup, SubtitleTrackSelection.choose(Tracks(ImmutableList.of(french, unknown, english)), "English")?.group)
        assertNull(SubtitleTrackSelection.choose(Tracks(ImmutableList.of(english)), "Off"))
    }
    @Test fun explicitCcChoiceAndAlreadySelectedRenditionRemainStable() {
        val plain = group("plain", "English", "en", false)
        val cc = group("cc", "English [CC]", "en", true)
        assertEquals(cc.mediaTrackGroup, SubtitleTrackSelection.choose(Tracks(ImmutableList.of(plain, cc)), "English [CC]")?.group)
        val wrong = group("bad", "English", "hi", true)
        assertNull(SubtitleTrackSelection.choose(Tracks(ImmutableList.of(wrong)), "English"))
    }
}
