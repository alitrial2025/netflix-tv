package com.example.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicSeasonMetadataTest {
    @Test fun brandedNetflixSeasonsResolveWithoutTitleSpecificIds() {
        val html = """<script type="application/ld+json">{"@type":"TVSeries","name":"Fixture Show","numberOfSeasons":3}</script><select name="seasonSelect"><option value="80001001">Fixture Show</option><option value="80001002">Fixture Show 2</option><option value="80001003">Fixture Show 3</option></select>"""
        assertEquals(mapOf(1 to "80001001", 2 to "80001002", 3 to "80001003"), PublicPlaybackResolver.parseNetflixSeasons(html, "Fixture Show"))
    }

    @Test fun arbitraryNamedNetflixSeasonsCannotBeNumberedFromSelectorOrder() {
        val html = """<script type="application/ld+json">{"@type":"TVSeries","name":"Anthology","numberOfSeasons":2}</script><select name="seasonSelect"><option value="80001001">The Beginning</option><option value="80001002">The Return</option></select>"""
        for (bad in listOf(html, html.replace("The Beginning", "Temporary").replace("The Return", "The Beginning").replace("Temporary", "The Return"), html.replace("numberOfSeasons\":2", "numberOfSeasons\":3"), html.replace("80001002", "80001001"), html.replace("seasonSelect", "recommendations"))) {
            try { PublicPlaybackResolver.parseNetflixSeasons(bad, "Anthology"); fail("Unverified season mapping accepted") } catch (_: IOException) { }
        }
    }

    @Test fun primeSeasonsCannotComeFromAnotherSeriesInTheSamePageState() {
        val html = """<script>{"init":{"preparations":{"body":{"atf":{"state":{"detail":{"headerDetail":{"requested":{"title":"Fixture Show - Season 1","titleType":"season"}}},"seasons":{"requested":[{"sequenceNumber":1,"seasonLink":"/detail/0REQUESTED123456"}],"recommended":[{"sequenceNumber":2,"seasonLink":"/detail/0WRONGSHOW123456"}]}}}}}}}</script>"""
        assertEquals(mapOf(1 to "0REQUESTED123456"), PublicPlaybackResolver.parsePrimeSeasons(html, "Fixture Show"))
    }
}
