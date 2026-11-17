package com.example.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CaptchaFallbackSeasonLabelTest {
    @Test fun countedLabelsResolveTheRequestedEpisode() = runBlocking {
        for (label in listOf("Season 4 (10 EP)", "S4 (10 episodes)", "4 (10 Episode)",
            " season 4 ( 10 ep ) ", "Season4", "S4", "4")) {
            val seen = mutableListOf<Request>()
            val result = resolver(seen, JSONArray()
                .put(JSONObject().put("s", "Season 2 (4 EP)").put("id", "8200000002"))
                .put(JSONObject().put("s", label).put("id", "8200000004")))
                .resolve("Fixture Show", "2020", "tv", 4, 1, "fixture")
            assertEquals("8300000001", result.contentId)
            val request = seen.single { it.url.encodedPath.endsWith("episodes.php") }
            assertEquals("8200000004", request.url.queryParameter("s"))
            assertEquals("8100000001", request.url.queryParameter("series"))
        }
    }

    @Test fun unrelatedLabelsNeverTriggerEpisodeLookup() = runBlocking {
        for (label in listOf("Specials 4 (10 EP)", "Released 2024 Season 4", "Episode 4",
            "Season 4 Part 2", "Season 4 (10 EP) unrelated", "S40 (10 EP)")) {
            val seen = mutableListOf<Request>()
            try {
                resolver(seen, JSONArray().put(JSONObject().put("s", label).put("id", "8200000004")))
                    .resolve("Fixture Show", "2020", "tv", 4, 1, "fixture")
                fail("Unrelated season label accepted: $label")
            } catch (error: IOException) { assertEquals("CaptchaFallback: season 4 not found", error.message) }
            assertFalse(seen.any { it.url.encodedPath.endsWith("episodes.php") })
        }
    }

    @Test fun ambiguousSeasonsAreRejectedBeforeEpisodeLookup() = runBlocking {
        val seen = mutableListOf<Request>()
        try {
            resolver(seen, JSONArray()
                .put(JSONObject().put("s", "Season 4 (10 EP)").put("id", "8200000004"))
                .put(JSONObject().put("s", "S4 (12 episodes)").put("id", "8200000099")))
                .resolve("Fixture Show", "2020", "tv", 4, 1, "fixture")
            fail("Ambiguous season identities accepted")
        } catch (error: IOException) { assertEquals("CaptchaFallback: season identity is ambiguous", error.message) }
        assertFalse(seen.any { it.url.encodedPath.endsWith("episodes.php") })
    }

    private fun resolver(seen: MutableList<Request>, seasons: JSONArray): CaptchaFallbackResolver {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("captcha_fallback_prefs", Context.MODE_PRIVATE).edit().clear()
            .putString("t_hash_t_cookie", "fixture-cookie")
            .putLong("t_hash_t_saved_at", System.currentTimeMillis()).commit()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertEquals("provider.invalid", request.url.host)
            val body = when (request.url.encodedPath) {
                "/mobile/hs/search.php" -> """[{"id":"8100000001","t":"Fixture Show","type":"t"}]"""
                "/mobile/hs/post.php" -> JSONObject().put("season", seasons).toString()
                "/mobile/hs/episodes.php" -> """{"episodes":[{"id":"8300000001","ep":"S4E1"}],"nextPageShow":0}"""
                "/mobile/hs/playlist.php" -> """{"sources":[{"file":"/mobile/hs/hls/8300000001.m3u8","label":"Auto"}]}"""
                "/mobile/hs/hls/8300000001.m3u8" -> "#EXTM3U\n#EXTINF:10,\nsegment.ts"
                else -> throw AssertionError("Unexpected request ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return CaptchaFallbackResolver(context, client, baseUrl = "https://provider.invalid")
    }
}
