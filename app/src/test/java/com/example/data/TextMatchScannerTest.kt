package com.example.data

import org.junit.Assert.*
import org.junit.Test

class TextMatchScannerTest {
    @Test fun denseProviderPagePreservesAllLinksAndDetachedGroups() {
        val page = buildString {
            repeat(1500) { append("<a href=\"https://assets.example/$it\">link</a>") }
            append("<a href=\"https://www.hotstar.com/1234567890/watch\">watch</a>")
        }
        assertEquals(listOf("1234567890"), PublicProviderIdentity.linkedHotstarIds(page))
        val matches = Regex("(a)(b)?").scanMatches("ab a").toList()
        assertEquals(listOf("ab", "a", "b"), matches[0].groupValues)
        assertEquals(listOf("a", "a", ""), matches[1].groupValues)
    }

    @Test fun scanningKeepsHostValidationAndDoesNotAcceptLookalikeLinks() {
        val page = """https://hotstar.com.evil.example/1234567890/watch
            https://hotstar.com@evil.example/1234567890/watch
            https://www.hotstar.com/1234567890/watch
            https:\/\/www.hotstar.com\/1234567890\/watch"""
        assertEquals(listOf("1234567890"), PublicProviderIdentity.linkedHotstarIds(page))
    }
}
