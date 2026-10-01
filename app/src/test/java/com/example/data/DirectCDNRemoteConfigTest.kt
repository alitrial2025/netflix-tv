package com.example.data

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectCDNRemoteConfigTest {
    @Test fun aRemoteHintNeverRevokesIssuedRoutesAndUsesASeparateEndpoint() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("last_token_mode", "su").putString("freecdn_routing_v1", "fixture-route")
            .putString("freecdn_nonce", "fixture-nonce").commit()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/updates/streaming.json", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"tokenHint":{"masterMode":"ek","hash1":"cccccccccccccccccccccccccccccccc"},"quryParam":"futureParam"}""".toResponseBody()).build()
        }.build()
        DirectCDNResolver(RuntimeEnvironment.getApplication(), client).checkRemoteConfig()
        assertEquals("su", prefs.getString("last_token_mode", null))
        assertEquals("fixture-route", prefs.getString("freecdn_routing_v1", null))
        assertEquals("fixture-nonce", prefs.getString("freecdn_nonce", null))
        assertEquals("ek", prefs.getString("remote_master_mode", null))
        assertEquals("cccccccccccccccccccccccccccccccc", prefs.getString("remote_master_hash1", null))
    }

    @Test fun concurrentFailedChecksAreSingleFlightAndBackOff() = runBlocking {
        RuntimeEnvironment.getApplication().getSharedPreferences("directcdn_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable").body("".toResponseBody()).build()
        }.build()
        val resolver = DirectCDNResolver(RuntimeEnvironment.getApplication(), client)
        coroutineScope { repeat(3) { launch { resolver.checkRemoteConfig() } } }
        assertEquals(1, calls.get())
    }
}
