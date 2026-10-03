package com.example.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicIdentityDiscoveryTest {
    @Test fun onlyDirectOfficialHttpsHomepagesYieldNativeIds() {
        assertEquals(listOf("83035795" to "nf"), PublicIdentityDiscovery.homepageIds("https://www.netflix.com/title/83035795"))
        assertEquals(listOf("0REQUESTED123456" to "pv"), PublicIdentityDiscovery.homepageIds("https://www.primevideo.com/detail/0REQUESTED123456"))
        for (bad in listOf("http://www.netflix.com/title/83035795", "https://netflix.com.evil.example/title/83035795", "https://127.0.0.1/title/83035795", "https://user:password@www.netflix.com/title/83035795", "https://www.netflix.com/search?q=83035795"))
            assertTrue(PublicIdentityDiscovery.homepageIds(bad).isEmpty())
    }

    @Test fun nonLatinTitlesKeepTheirLettersAndEmptyTitlesNeverMatch() {
        assertEquals("東京物語", PublicIdentityDiscovery.normalize("東京物語"))
        assertTrue(PublicIdentityDiscovery.sameTitle("東京 物語", "東京物語"))
        assertFalse(PublicIdentityDiscovery.sameTitle("東京物語", "千と千尋"))
        assertFalse(PublicIdentityDiscovery.sameTitle("Москва", "Киев"))
        assertFalse(PublicIdentityDiscovery.sameTitle("---", "!?"))
        assertFalse(PublicIdentityDiscovery.sameTitle("", ""))
    }

    @Test fun aliasesBelongToTheExactTypedTmdbIdentityAndReleaseYear() {
        val data = JSONObject("""{"id":113962,"name":"Lioness","original_name":"Lioness","first_air_date":"2023-07-23","external_ids":{"wikidata_id":"Q116199566"},"alternative_titles":{"results":[{"title":"Special Ops: Lioness"}]}}""")
        assertEquals(listOf("Lioness", "Special Ops: Lioness"), PublicIdentityDiscovery.metadata(data, "113962", "tv", "2023")!!.aliases)
        assertNull(PublicIdentityDiscovery.metadata(data, "113962", "movie", "2023"))
        assertNull(PublicIdentityDiscovery.metadata(data, "113963", "tv", "2023"))
        assertNull(PublicIdentityDiscovery.metadata(data, "113962", "tv", "2026"))
    }

    @Test fun liveJoinRequiresBothMediaTypeAndTmdbIdentityAndOpaqueIds() {
        fun row(type: String, tmdb: String, ott: String, id: String) = """{"type":{"value":"$type"},"tmdb":{"value":"$tmdb"},"ott":{"value":"$ott"},"nativeId":{"value":"$id"}}"""
        val data = JSONObject("""{"results":{"bindings":[${row("tv", "113962", "pv", "0SVGUHKPBBP0BH7FC5VO19ALDR")},${row("movie", "113962", "nf", "80057281")},${row("tv", "1", "nf", "80057281")},${row("tv", "113962", "pv", "https://evil.example")}]}}""")
        assertEquals(listOf("0SVGUHKPBBP0BH7FC5VO19ALDR" to "pv"), PublicIdentityDiscovery.joinedIds(data, "113962", "tv"))
        assertNull(PublicIdentityDiscovery.wikidataQuery("113962\" . ?x ?y ?z", "tv"))
        assertTrue(PublicIdentityDiscovery.wikidataQuery("113962", "tv")!!.contains("wdt:P4983"))
        assertTrue(PublicIdentityDiscovery.wikidataQuery("113962", "movie")!!.contains("wdt:P4947"))
    }

    @Test fun primeSearchUsesResultCardsAndRejectsOtherYearsTypesAndHosts() {
        fun card(title: String, kind: String, year: Int, link: String) = """{"title":"$title","entityType":"$kind","releaseYear":$year,"link":{"url":"$link"}}"""
        val html = """<script>{"init":{"preparations":{"body":{"containers":[{"entities":[${card("Special Ops: Lioness - Season 1", "TVSeason", 2023, "/detail/0SVGUHKPBBP0BH7FC5VO19ALDR?ref_=search")},${card("Lioness - Season 1", "TVSeason", 2021, "/detail/0NQCFU4K7OB1T09EQ02TTB9GY6")},${card("Lioness", "Movie", 2023, "/detail/0MOVIE012345")},${card("Lioness - Season 1", "TVSeason", 2023, "https://evil.example/detail/0UNTRUSTED12345")}]}]}}}}</script><a href="/detail/0RECOMMEND12345">Lioness</a>"""
        assertEquals(listOf("0SVGUHKPBBP0BH7FC5VO19ALDR" to "pv"), PublicIdentityDiscovery.primeSearchIds(html, listOf("Lioness", "Special Ops: Lioness"), "2023", "tv"))
    }

    @Test fun movieEditionLabelsAreNotSilentlyStrippedOrMatchedToGames() {
        assertEquals("Grand Theft Auto Extended", PublicIdentityDiscovery.seriesTitle("Grand Theft Auto Extended", "movie"))
        val data = JSONObject("""{"id":1,"title":"Grand Theft Auto","release_date":"1977-06-16","alternative_titles":{"titles":[]}}""")
        assertNull(PublicIdentityDiscovery.metadata(data, "1", "movie", "2026"))
    }

    @Test fun titleOnlyLaterSeasonsCannotIdentifySameNameRemakes() {
        val html = """<script>{"init":{"preparations":{"body":{"containers":[{"entities":[{"title":"Dark Matter - Season 2","entityType":"TVSeason","releaseYear":2016,"link":{"url":"/detail/0WRONGSHOW123456"}}]}]}}}}</script>"""
        assertTrue(PublicIdentityDiscovery.primeSearchIds(html, listOf("Dark Matter"), "2024", "tv").isEmpty())
        val detail = """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"show":{"title":"Dark Matter - Season 2","titleType":"season","releaseYear":2016}}}}}}}}}</script>"""
        assertFalse(PublicProviderIdentity.matchesPrime(detail, "Dark Matter", "2024", "tv"))
        assertTrue(PublicProviderIdentity.matchesPrime(detail, "Dark Matter", "2024", "tv", typedMapping = true))
    }
}
