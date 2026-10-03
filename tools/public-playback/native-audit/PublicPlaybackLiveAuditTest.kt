package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in cloud audit of production Kotlin, including an issued media segment. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicPlaybackLiveAuditTest {
    private data class Selection(val title: String, val year: String, val type: String,
        val tmdb: String, val season: Int = 0, val episode: Int = 0)

    @Test fun productionResolverReachesActualSegments() = runBlocking(Dispatchers.IO) {
        val output = File(requireNotNull(System.getenv("NPRO_PLAYBACK_AUDIT_OUTPUT")))
        val rows = JSONArray()
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                check(chain.request().header("Cookie") == null)
                check(chain.request().header("Authorization") == null)
                requests.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val catalog = PublicProviderCatalog(app)
        catalog.refresh(client, force = true)
        val resolver = PublicPlaybackResolver(client, catalog = catalog, backgroundCatalogRefresh = false)
        val selections = listOf(
            Selection("Stranger Things", "2016", "tv", "66732", 1, 1),
            Selection("Stranger Things", "2016", "tv", "66732", 4, 1),
            Selection("Lioness", "2023", "tv", "113962", 1, 1),
            Selection("Grand Theft Auto VI: An Extended Look", "2026", "movie", "1744462")
        )
        for (selection in selections) {
            val row = JSONObject().put("title", selection.title).put("year", selection.year)
                .put("type", selection.type).put("tmdbId", selection.tmdb)
                .put("season", selection.season).put("episode", selection.episode)
            val started = System.nanoTime()
            val before = requests.get()
            try {
                val result = resolver.resolve(selection.title, selection.year, selection.type,
                    selection.season, selection.episode, selection.tmdb)
                row.put("resolveMs", (System.nanoTime() - started) / 1_000_000)
                    .put("ott", result.ott).put("contentId", result.contentId)
                val segment = issuedSegment(client, result.url.toHttpUrl(), result.headers)
                row.put("segment", segment).put("success", true)
            } catch (error: Exception) {
                row.put("success", false).put("errorType", error.javaClass.simpleName)
                    .put("error", error.message.orEmpty()
                        .replace(Regex("https?://\\S+"), "[URL omitted]").take(180))
            }
            row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                .put("httpRequests", requests.get() - before)
            rows.put(row)
            output.parentFile.mkdirs()
            output.writeText(JSONObject().put("schemaVersion", 1)
                .put("source", "production Kotlin PublicPlaybackResolver")
                .put("cookies", false).put("warming", false)
                .put("generatedAt", System.currentTimeMillis()).put("results", rows).toString(2))
            println("AUDIT ${selection.title} S${selection.season}E${selection.episode}: ${row.optBoolean("success")} (${row.optLong("elapsedMs")}ms)")
            if (PlaybackServiceGate.remainingMs() > 0) break
        }
        assertTrue("A production playback route failed; see sanitized playback-audit.json",
            rows.length() == selections.size && (0 until rows.length()).all { rows.getJSONObject(it).optBoolean("success") })
    }

    private fun request(url: HttpUrl, headers: Map<String, String>): Request {
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("Invalid issued media URL")
        return Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }

    private suspend fun issuedSegment(client: OkHttpClient, master: HttpUrl, headers: Map<String, String>): JSONObject {
        var playlist = master
        repeat(4) {
            val response = client.fetchText(request(playlist, headers), 1024L * 1024)
            if (!response.isSuccessful || !response.body.trimStart().startsWith("#EXTM3U")) throw IOException("Issued media playlist failed (HTTP ${response.code})")
            val lines = response.body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val variant = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            if (variant >= 0) {
                playlist = playlist.resolve(lines.getOrNull(variant + 1).orEmpty()) ?: throw IOException("Invalid issued child playlist")
            } else {
                val duration = lines.indexOfFirst { it.startsWith("#EXTINF:") }
                if (duration < 0) throw IOException("Issued playlist has no media segments")
                val relative = lines.drop(duration + 1).firstOrNull { !it.startsWith("#") }
                    ?: throw IOException("Issued playlist has no segment URL")
                val segment = playlist.resolve(relative) ?: throw IOException("Invalid issued segment")
                val byteRange = lines.takeWhile { it != relative }.lastOrNull { it.startsWith("#EXT-X-BYTERANGE:") }
                    ?.substringAfter(":")?.substringAfter("@", "0")?.toLongOrNull() ?: 0L
                val probe = request(segment, headers).newBuilder().header("Range", "bytes=$byteRange-${byteRange + 16383}").build()
                client.newCall(probe).execute().use { media ->
                    if (!media.isSuccessful) throw IOException("Issued segment failed (HTTP ${media.code})")
                    val source = media.body?.source() ?: throw IOException("Issued segment body missing")
                    source.request(16384)
                    val bytes = source.readByteArray(minOf(source.buffer.size, 16384))
                    if (bytes.size < 188) throw IOException("Issued segment is empty or truncated")
                    val prefix = bytes.take(128).toByteArray().toString(Charsets.US_ASCII).trimStart().lowercase()
                    if (prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("{\"error")) throw IOException("Issued segment returned an error page")
                    val container = when {
                        (0 until minOf(188, bytes.size)).any { offset -> offset + 376 < bytes.size &&
                            listOf(offset, offset + 188, offset + 376).all { (bytes[it].toInt() and 0xff) == 0x47 } } -> "mpeg_ts"
                        bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) in
                            listOf("ftyp", "styp", "moof", "sidx") -> "iso_bmff"
                        else -> throw IOException("Issued segment has no recognized video container")
                    }
                    return JSONObject().put("httpStatus", media.code).put("host", segment.host)
                        .put("container", container).put("sampleBytes", bytes.size).put("sampleSha256", MessageDigest.getInstance("SHA-256")
                            .digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
        }
        throw IOException("Issued manifest nesting exceeded audit limit")
    }
}
