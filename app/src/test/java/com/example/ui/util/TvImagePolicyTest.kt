package com.example.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvImagePolicyTest {
    @Test
    fun cacheLeavesHeadroomOnSmallAndLargeTvHeaps() {
        val mib = 1024L * 1024L
        assertTrue(TvImagePolicy.memoryCacheBytes(64 * mib, true) < 8 * mib)
        assertTrue(TvImagePolicy.memoryCacheBytes(64 * mib, false) < 12 * mib)
        assertEquals(16 * mib, TvImagePolicy.memoryCacheBytes(512 * mib, true).toLong())
        assertEquals(32 * mib, TvImagePolicy.memoryCacheBytes(512 * mib, false).toLong())
    }

    @Test
    fun artworkKeepsDisplaySizeUntilFullHdLimit() {
        assertEquals(1280 to 720, TvImagePolicy.backdropSize(1280, 720))
        assertEquals(1920 to 1080, TvImagePolicy.backdropSize(1920, 1080))
        assertEquals(1920 to 1080, TvImagePolicy.backdropSize(3840, 2160))
        assertEquals(1080 to 1920, TvImagePolicy.backdropSize(2160, 3840))
        assertEquals(1280 to 720, TvImagePolicy.backdropSize(3840, 2160, true))
    }

    @Test
    fun unmeasuredViewsNeverRequestOriginalSize() {
        assertEquals(1 to 1, TvImagePolicy.backdropSize(0, 0))
        assertEquals(1 to 1, TvImagePolicy.backdropSize(-1, -1))
        assertEquals(0, TvImagePolicy.memoryCacheBytes(0, true))
    }
    @Test
    fun smallArtworkDoesNotDownloadOriginalTmdbFiles() {
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg",
            TvImagePolicy.artworkUrl("https://image.tmdb.org/t/p/original/poster.jpg", 240, TvArtworkKind.POSTER))
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            TvImagePolicy.artworkUrl("https://image.tmdb.org/t/p/original/backdrop.jpg", 1920, TvArtworkKind.BACKDROP))
        assertEquals("https://image.tmdb.org/t/p/w300/logo.png?version=2#brand",
            TvImagePolicy.artworkUrl("https://image.tmdb.org/t/p/w500/logo.png?version=2#brand", 210, TvArtworkKind.LOGO))
    }

    @Test
    fun customSignedAndVectorSourcesAreUntouched() {
        for (url in listOf(
            "https://cdn.example.com/original/poster.jpg?signature=123",
            "https://image.tmdb.org.evil.example/t/p/original/poster.jpg",
            "https://image.tmdb.org/t/p/original/logo.svg",
            "https://image.tmdb.org/t/p/custom/poster.jpg"
        )) assertEquals(url, TvImagePolicy.artworkUrl(url, 100, TvArtworkKind.LOGO))
    }
}
