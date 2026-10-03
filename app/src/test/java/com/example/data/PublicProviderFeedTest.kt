package com.example.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicProviderFeedTest {
    private fun feed(now: Long): JSONObject {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val native = JSONObject(context.assets.open("public-provider-catalog.json").bufferedReader().use { it.readText() }).getJSONArray("rows")
        val partner = JSONObject(context.assets.open("public-hotstar-catalog.json").bufferedReader().use { it.readText() }).getJSONArray("rows")
        native.put(JSONArray(listOf("tv", "999999001", "hs", "1971999001", "Q999999001")))
        return JSONObject().put("schemaVersion",2).put("generatedAt",now).put("nativeRows",native).put("partnerRows",partner)
    }
    @Test fun expiredFuturePartialAndUnsafeFeedCandidatesAreRejected() {
        val now = 1_800_000_000_000L
        val good = feed(now)
        assertEquals(listOf("1971999001" to "hs"), PublicProviderCatalog.parseFeed(good,now).native["tv:999999001"])
        val invalid = listOf(
            JSONObject(good.toString()).put("generatedAt",now-15*86_400_000L),
            JSONObject(good.toString()).put("generatedAt",now+600_000L),
            JSONObject(good.toString()).put("nativeRows",JSONArray()),
            JSONObject(good.toString()).apply { getJSONArray("nativeRows").put(JSONArray(listOf("tv","99999","hs","https://evil.example/"))) },
            JSONObject(good.toString()).apply { getJSONArray("partnerRows").put(JSONArray(listOf("tv","fixture","1971999001","https://evil.example/title"))) }
        )
        invalid.forEach { try { PublicProviderCatalog.parseFeed(it,now); fail("Unsafe feed accepted") } catch (_: IOException) { } }
    }
    @Test fun refreshedIdentitySurvivesRestartAndBrokenRefreshKeepsTheLastGoodSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir,"public_provider_feed_v2.json")
        file.delete()
        val good = feed(System.currentTimeMillis())
        var body = good.toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(PublicProviderCatalog.FEED_URL, request.url.toString())
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val catalog = PublicProviderCatalog(context)
            assertTrue(catalog.candidates("tv","999999001").isEmpty())
            catalog.refresh(client,true)
            assertEquals(listOf("1971999001" to "hs"),catalog.candidates("tv","999999001"))
            assertEquals(listOf("1971999001" to "hs"),PublicProviderCatalog(context).candidates("tv","999999001"))
            val saved = file.readText()
            body = "{\"schemaVersion\":2,\"nativeRows\":[]}"
            catalog.refresh(client,true)
            assertEquals(saved,file.readText())
            assertEquals(listOf("1971999001" to "hs"),catalog.candidates("tv","999999001"))
        } finally { file.delete() }
    }
}
