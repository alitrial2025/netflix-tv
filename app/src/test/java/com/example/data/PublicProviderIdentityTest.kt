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
