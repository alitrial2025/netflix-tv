package com.example.data

import com.example.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsCharacterCatalogTest {
    private fun movie(id: String = "82728", kind: String = "tv", imdb: String? = null) = Movie(
        id = id, title = "A title", description = "", backdropUrl = "", posterUrl = "",
        type = if (kind == "tv") "Series" else "Movie",
        duration = if (kind == "tv") "Series" else "Feature", imdbId = imdb
    )

    @Test fun localTmdbCutoutsTakePriorityAndNeverNeedARequest() {
        val catalog = KidsCharacterCatalog(setOf("tv_82728.png", "tt7678620.webp"))
        assertEquals("file:///android_asset/kids_characters/tv_82728.png",
            catalog.urlFor(movie(imdb = "tt7678620")))
        assertFalse(catalog.needsImdbLookup(movie()))
        assertNull(catalog.urlFor(movie(kind = "movie")))
    }

    @Test fun imdbOnlyCutoutsResolveAfterTheTmdbIdentityLookup() {
        val catalog = KidsCharacterCatalog(setOf("tv_tt7678620.webp"))
        assertTrue(catalog.needsImdbLookup(movie()))
        assertEquals("file:///android_asset/kids_characters/tv_tt7678620.webp",
            catalog.urlFor(movie(), "tt7678620"))
        assertFalse(catalog.needsImdbLookup(movie(kind = "movie")))
        assertNull(catalog.urlFor(movie(kind = "movie"), "tt7678620"))
    }

    @Test fun missingArtworkDoesNotStartCatalogWideLookups() {
        val catalog = KidsCharacterCatalog(setOf("README.md", "catalog.json", "tv_82728.png"))
        assertFalse(catalog.needsImdbLookup(movie("999")))
        assertNull(catalog.urlFor(movie("999")))
        val imdbCatalog = KidsCharacterCatalog(setOf("tv_tt7678620.png"))
        assertFalse(imdbCatalog.needsImdbLookup(movie(imdb = "tt7539608")))
    }

    @Test fun registeredImdbIdentityKeepsMoviesAndTvSeparate() {
        val catalog = KidsCharacterCatalog(setOf("bluey.png"), listOf(
            KidsCharacterEntry("tv", imdbId = "tt7678620", file = "bluey.png")
        ))
        assertTrue(catalog.needsImdbLookup(movie()))
        assertEquals("file:///android_asset/kids_characters/bluey.png",
            catalog.urlFor(movie(), "tt7678620"))
        assertNull(catalog.urlFor(movie(kind = "movie"), "tt7678620"))
    }

    @Test fun unsafeSourcesAndInvalidIdentitiesAreIgnored() {
        val catalog = KidsCharacterCatalog(emptySet(), listOf(
            KidsCharacterEntry("tv", tmdbId = "82728", url = "http://example.com/cutout.png"),
            KidsCharacterEntry("tv", tmdbId = "-1", url = "https://example.com/cutout.png"),
            KidsCharacterEntry("tv", imdbId = "7678620", url = "https://example.com/cutout.png")
        ))
        assertNull(catalog.urlFor(movie()))
        assertNull(catalog.urlFor(movie("-1")))
        assertFalse(catalog.needsImdbLookup(movie()))
    }
}
