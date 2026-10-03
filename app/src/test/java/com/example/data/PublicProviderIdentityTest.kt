package com.example.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicProviderIdentityTest {
    @Test fun accentedPublishedPathsMatchCanonicalTitleAndRetainUrlEncoding() {
        val path = "/movies/tár/HOTSTAR_DTH_MOVIE_1971309279"
        assertEquals(listOf("1971309279" to "/movies/t%C3%A1r/HOTSTAR_DTH_MOVIE_1971309279"),
            PublicProviderIdentity.partnerLinks("""<a href="$path">Tár</a>""", "Tár", "movie"))
    }
    @Test fun seriesLandingYearAndPublishedBrowseLinksAllowMigratedAndNewTitles() {
        val path = "/tv-shows/house-of-the-dragon/HOTSTAR_DTH_TVSHOW_1971002877"
        val page = """<p id="banner-content-release-year">2026</p><script>{"@type":"VideoObject","name":"House Of The Dragon"}</script><a href="$path">Watch</a>"""
        assertTrue(PublicProviderIdentity.matchesAirtel(page, "House of the Dragon", "2022", "tv"))
        assertFalse(PublicProviderIdentity.matchesAirtel(page, "Other show", "2022", "tv"))
        assertFalse(PublicProviderIdentity.matchesAirtel(page, "House of the Dragon", "2022", "movie"))
        assertEquals(listOf("1971002877" to path), PublicProviderIdentity.partnerLinks(page, "House of the Dragon", "tv"))
        assertTrue(PublicProviderIdentity.partnerLinks(page.replace("href=\"/", "href=\"https://evil.example/"), "House of the Dragon", "tv").isEmpty())
    }
    @Test fun partnerIdsAreNotAssumedToBeNativeMovieIdsAndOfficialMetadataMustMatch() {
        val page = """<p id="banner-content-release-year">2023</p><script>{"@type":"VideoObject","name":"Tumse Na Ho Payega"}</script><a href="https://www.hotstar.com/in/movies/tumse-na-ho-payega/1260149193">Watch</a>"""
        assertTrue(PublicProviderIdentity.matchesAirtel(page,"Tumse Na Ho Payega","2023"))
        assertFalse(PublicProviderIdentity.matchesAirtel(page,"Tumse Na Ho Payega","2024"))
        assertFalse(PublicProviderIdentity.matchesAirtel(page,"Other movie","2023"))
        assertEquals(listOf("1260149193"),PublicProviderIdentity.linkedHotstarIds(page))
        assertTrue(PublicProviderIdentity.linkedHotstarIds(page.replace("hotstar.com","hotstar.com.evil.test")).isEmpty())
    }
    @Test fun officialNetflixMetadataRejectsWrongRemakesAndMediaTypes() {
        val html = """<script type="application/ld+json">{"@type":"Movie","name":"Road House","datePublished":"1989-05-19"}</script>"""
        assertTrue(PublicProviderIdentity.matchesNetflix(html,"Road House","1989","movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html,"Road House","2024","movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html,"Road House","1989","tv"))
    }
    @Test fun seriesLastSeasonYearDoesNotRejectAnAuthoritativeShowId() {
        assertTrue(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Game Of Thrones","type":"t","year":"2019"}"""), "Game of Thrones", "2011", "tv"))
        assertFalse(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Game Of Thrones","type":"m","year":"2011"}"""), "Game of Thrones", "2011", "tv"))
        assertFalse(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Other title","type":"t","year":"2011"}"""), "Game of Thrones", "2011", "tv"))
    }
    @Test fun movieRemakesAndUnverifiedSeedsAreRejected() {
        assertFalse(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Road House","type":"m","year":"1989"}"""), "Road House", "2024", "movie"))
        assertNull(PublicProviderIdentity.seed("95350", "movie", "2026"))
        assertNull(PublicProviderIdentity.seed("95350", "tv", "2025"))
    }
    @Test fun onlyValidActiveNativeIdsFromTheRequestedEntityAreUsed() {
        val json="""{"entities":{"Q1":{"claims":{"P11049":[{"rank":"normal","mainsnak":{"datavalue":{"value":"1971002880"}}},{"rank":"deprecated","mainsnak":{"datavalue":{"value":"1271680756"}}},{"mainsnak":{"datavalue":{"value":"https://evil.example"}}}]}}}}"""
        assertEquals(listOf("1971002880"), PublicProviderIdentity.hotstarIds(json,"Q1"))
        assertTrue(PublicProviderIdentity.hotstarIds(json,"Q2").isEmpty())
    }
}
