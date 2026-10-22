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
    private fun pilotDetails(season: String = "S1", episode: String = "E1", date: String = "10 Apr 2026") =
        JSONObject("""{"status":"y","title":"Perfect Crown","type":"t","year":"2026","episodes":[{"id":"1271531262","s":"$season","ep":"$episode","date":"$date"}]}""")

    @Test fun datedPilotVerifiesUntypedHotstarSeriesWithoutTrustingTheGenericYear() {
        assertTrue(PublicProviderIdentity.matches(pilotDetails(), "Perfect Crown", "2026", "tv"))
        assertTrue(PublicProviderIdentity.matches(pilotDetails(date = "2026-04-10").put("year", "2028"), "Perfect Crown", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matches(pilotDetails(), "Perfect Crown", "2025", "tv"))
        assertFalse(PublicProviderIdentity.matches(pilotDetails(), "Other Show", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matches(pilotDetails(), "Perfect Crown", "2026", "movie"))
        assertFalse(PublicProviderIdentity.matches(pilotDetails().put("status", "n"), "Perfect Crown", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matches(pilotDetails().put("first_air_date", "2025-04-10"), "Perfect Crown", "2026", "tv"))
    }

    @Test fun laterEpisodesAmbiguousPilotsAndInvalidDatesCannotEstablishPremiereYear() {
        for ((season, episode, date) in listOf(
            Triple("S2", "E1", "10 Apr 2026"), Triple("S1", "E2", "10 Apr 2026"),
            Triple("S1", "S2E1", "10 Apr 2026"), Triple("", "E1", "10 Apr 2026"),
            Triple("S1", "E1", "31 Apr 2026"), Triple("S1", "E1", "2026-02-30"),
            Triple("S1", "E1", "2026"), Triple("S1", "E1", "")
        )) assertFalse("$season/$episode/$date", PublicProviderIdentity.matches(pilotDetails(season, episode, date), "Perfect Crown", "2026", "tv"))
        val ambiguous = pilotDetails()
        ambiguous.getJSONArray("episodes").put(JSONObject("""{"id":"1271531266","s":"S1","ep":"E1","date":"10 Apr 2026"}"""))
        assertFalse(PublicProviderIdentity.matches(ambiguous, "Perfect Crown", "2026", "tv"))
        val missingId = pilotDetails()
        missingId.getJSONArray("episodes").getJSONObject(0).remove("id")
        assertFalse(PublicProviderIdentity.matches(missingId, "Perfect Crown", "2026", "tv"))
    }

    @Test fun partnerSeriesPageCanSupplyACandidateWithoutVerifyingItsPremiereYear() {
        val page = """<p id="banner-content-release-year">2028</p><script>{"@type":"VideoObject","name":"Perfect Crown","uploadDate":"1970-01-01"}</script>"""
        assertTrue(PublicProviderIdentity.matchesAirtelCandidate(page, "Perfect Crown", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matchesAirtel(page, "Perfect Crown", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matchesAirtelCandidate(page, "Other Show", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matchesAirtelCandidate(page, "Perfect Crown", "2026", "movie"))
    }

    @Test fun hotstarShortWatchLinksRetainNativeIdsAndRejectUnrelatedUrls() {
        val page = """<a href="https://hotstar.com/1260057812/watch">Watch</a>
            <a href="https://www.hotstar.com/in/movies/the-new-mutants/1260057812/watch">Watch</a>
            <a href="https://www.hotstar.com/in/movies/other-film/1260149193">Watch</a>"""
        assertEquals(listOf("1260057812", "1260149193"), PublicProviderIdentity.linkedHotstarIds(page))
        assertEquals(listOf("1260057812", "1260149193"), PublicProviderIdentity.linkedHotstarIds(page.replace("/", "\\/")))
        for (invalid in listOf(
            "http://hotstar.com/1260057812/watch", "https://hotstar.com.evil.test/1260057812/watch",
            "https://user@hotstar.com/1260057812/watch", "https://hotstar.com:444/1260057812/watch",
            "https://evil.test/?next=https://hotstar.com/1260057812/watch",
            "https://hotstar.com/1260057812/other", "https://hotstar.com/help/1260057812/watch"
        )) assertTrue(invalid, PublicProviderIdentity.linkedHotstarIds("""<a href="$invalid">Watch</a>""").isEmpty())
    }

    @Test fun netflixMoviesCanPublishDateCreatedInsteadOfDatePublished() {
        val html = """<script type="application/ld+json">{"@type":"Movie","name":"Grand Theft Auto VI: An Extended Look","dateCreated":"2026-8-27","trailer":{"@type":"VideoObject","uploadDate":"2025-01-01"}}</script>"""
        assertTrue(PublicProviderIdentity.matchesNetflix(html, "Grand Theft Auto VI: An Extended Look", "2026", "movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html, "Grand Theft Auto VI: An Extended Look", "2025", "movie"))
        assertFalse(PublicProviderIdentity.matchesNetflix(html, "Grand Theft Auto VI: An Extended Look", "2026", "tv", typedMapping = true))
        assertFalse(PublicProviderIdentity.matchesNetflix(html.replace("dateCreated", "missingDate"), "Grand Theft Auto VI: An Extended Look", "2025", "movie"))
    }

    @Test fun laterSeasonDatesCannotVerifyTitleOnlySameNameSeries() {
        val page = """<script type="application/ld+json">{"@type":"TVSeries","name":"Same Name","dateCreated":"2026-01-01","startDate":"2026-01-01"}</script>"""
        assertFalse(PublicProviderIdentity.matchesNetflix(page, "Same Name", "2026", "tv"))
        assertTrue(PublicProviderIdentity.matchesNetflix(page, "Same Name", "2016", "tv", typedMapping = true))
        assertFalse(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Same Name","type":"t","year":"2026"}"""), "Same Name", "2026", "tv"))
        assertFalse(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Same Name","type":"t"}"""), "Same Name", "", "tv"))
    }

    @Test fun accentedPublishedPathsMatchCanonicalTitleAndRetainUrlEncoding() {
        val path = "/movies/tár/HOTSTAR_DTH_MOVIE_1971309279"
        assertEquals(listOf("1971309279" to "/movies/t%C3%A1r/HOTSTAR_DTH_MOVIE_1971309279"),
            PublicProviderIdentity.partnerLinks("""<a href="$path">Tár</a>""", "Tár", "movie"))
    }
    @Test fun seriesLandingYearAndPublishedBrowseLinksAllowMigratedAndNewTitles() {
        val path = "/tv-shows/house-of-the-dragon/HOTSTAR_DTH_TVSHOW_1971002877"
        val page = """<p id="banner-content-release-year">2026</p><script>{"@type":"VideoObject","name":"House Of The Dragon"}</script><a href="$path">Watch</a>"""
        assertTrue(PublicProviderIdentity.matchesAirtel(page, "House of the Dragon", "2022", "tv", typedMapping = true))
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
        assertTrue(PublicProviderIdentity.matches(JSONObject("""{"status":"y","title":"Game Of Thrones","type":"t","year":"2019"}"""), "Game of Thrones", "2011", "tv", typedMapping = true))
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
