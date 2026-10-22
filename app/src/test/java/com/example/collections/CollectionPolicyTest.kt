package com.example.collections

import org.junit.Assert.*
import org.junit.Test

class CollectionPolicyTest {
    private val movie = CollectionTitle("123", "movie", "A film", "https://image.tmdb.org/t/p/w500/poster.jpg")
    private val series = CollectionTitle("123", "tv", "A series")
    private fun document(titles: List<CollectionTitle> = listOf(movie)) = mapOf<String, Any>(
        "name" to "Weekend", "deleted" to false, "items" to titles.associate { it.fieldKey to it.toMap() })

    @Test fun typedIdentitiesNeverCollideAndPrefixedTmdbIdsNormalise() {
        assertNotEquals(movie.fieldKey, series.fieldKey)
        assertEquals(64, movie.fieldKey.length)
        assertEquals(movie, CollectionTitle.sanitize(movie.copy(id = "tmdb:movie:123")))
        assertNull(CollectionTitle.sanitize(movie.copy(id = "tmdb:tv:123")))
        assertNull(CollectionTitle.sanitize(movie.copy(kind = "series")))
        assertNull(CollectionTitle.sanitize(movie.copy(id = "../other")))
    }
    @Test fun untrustedArtworkIsRemovedAndTitlesAreBounded() {
        for (url in listOf("file:///data/secret", "http://image.tmdb.org/t/p/x.jpg", "https://image.tmdb.org.attacker.org/t/p/x.jpg",
            "https://user:pass@image.tmdb.org/t/p/x.jpg", "https://image.tmdb.org:444/t/p/x.jpg", "https://127.0.0.1/a")) {
            assertEquals("", CollectionTitle.sanitize(movie.copy(posterUrl = url))!!.posterUrl)
        }
        assertEquals(movie.posterUrl, CollectionTitle.sanitize(movie)!!.posterUrl)
        assertNull(CollectionTitle.sanitize(movie.copy(title = " ")))
        assertNull(CollectionTitle.sanitize(movie.copy(title = "x".repeat(201))))
    }
    @Test fun malformedOrMismatchedMapFieldsCannotOverrideAnotherTitle() {
        val data = document(listOf(movie, series)).toMutableMap()
        data["items"] = mapOf(movie.fieldKey to series.toMap(), "not-a-title-key" to movie.toMap(), series.fieldKey to series.toMap())
        assertEquals(listOf(series), CollectionPolicy.parse("collection", data)!!.items)
        assertNull(CollectionTitle.fromMap(mapOf("id" to 123, "kind" to "movie", "title" to "A film")))
    }
    @Test fun collectionDeletesRemainDeletedAfterDelayedItemOrNameWrites() {
        val deleted = document().toMutableMap().apply { this["deleted"] = true }
        deleted["items"] = mapOf(series.fieldKey to series.toMap())
        deleted["name"] = "Delayed rename"
        assertNull(CollectionPolicy.parse("collection", deleted))
        assertNull(CollectionPolicy.parse("collection", document().minus("deleted")))
    }
    @Test fun namesRejectBlankOversizeAndDuplicatesWithoutLocaleDependence() {
        assertEquals("Movie night", CollectionPolicy.name("  Movie    night  "))
        for (value in listOf("", "    ", "x".repeat(41), "bad\u0000name")) assertNull(CollectionPolicy.name(value))
        val collections = listOf(TitleCollection("one", "MOVIE NIGHT", emptyList()))
        assertTrue(CollectionPolicy.duplicate("movie night", collections))
        assertFalse(CollectionPolicy.duplicate("movie night", collections, "one"))
    }
    @Test fun accountAndProfilePathsCannotEscapeTheirOwner() {
        assertEquals("owner" to "profile", CollectionPolicy.profileScope("owner", "profile"))
        assertNotEquals(CollectionPolicy.profileScope("owner", "one"), CollectionPolicy.profileScope("owner", "two"))
        assertNotEquals(CollectionPolicy.profileScope("first", "profile"), CollectionPolicy.profileScope("second", "profile"))
        for ((owner, profile) in listOf(null to "profile", "" to "profile", "owner" to null, "owner" to "guest", "a/b" to "profile", "owner" to "../other"))
            assertNull(CollectionPolicy.profileScope(owner, profile))
    }
    @Test fun boundedParsingKeepsDistinctTypedTitlesAndPendingSyncState() {
        val parsed = CollectionPolicy.parse("collection", document(listOf(movie, series)), pending = true)!!
        assertTrue(parsed.pendingSync)
        assertEquals(setOf("movie:123", "tv:123"), parsed.items.map { it.key }.toSet())
        assertNull(CollectionPolicy.parse("../collection", document()))
        val many = (1..240).map { movie.copy(id = it.toString()) }
        assertEquals(200, CollectionPolicy.parse("collection", document(many))!!.items.size)
    }
}
