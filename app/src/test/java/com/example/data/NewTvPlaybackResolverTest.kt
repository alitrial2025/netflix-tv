package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NewTvPlaybackResolverTest {
    // Robolectric resets elapsedRealtime between tests; process-level queues and
    // cooldowns must not retain a deadline from a previous clock instance.
    @Before @After fun isolateProviderGate() {
        VerifiedManifestHandoff.clear()
        val cooldown = org.robolectric.util.ReflectionHelpers.getStaticField<PlaybackRequestCooldown>(
            PlaybackServiceGate::class.java, "cooldown")
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "blockedUntilMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "lastLimitMs", 0L)
        org.robolectric.util.ReflectionHelpers.setField(cooldown, "strikes", 0)
        org.robolectric.util.ReflectionHelpers.setStaticField(PlaybackServiceGate::class.java, "lastRequestAt", 0L)
    }

    private data class Fixture(val body: String, val code: Int = 200, val headers: Map<String, String> = emptyMap())
    private fun client(seen: MutableList<Request>, respond: (Request) -> Fixture): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(seen) { seen += request }
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            val response = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(response.code).message("fixture")
                .apply { response.headers.forEach { (name, value) -> header(name, value) } }
                .header("Set-Cookie", "t_hash_t=unused; Secure; Path=/")
                .body(response.body.toResponseBody()).build()
        }.build()
    private fun discovery(base: String = "https://tv.imgcdn.kim") = Fixture("""{"token_hash":"${Base64.getEncoder().encodeToString(base.toByteArray())}"}""")
    private val media = Fixture("#EXTM3U\n#EXTINF:10,\nsegment.jpg\n#EXT-X-ENDLIST")
    private fun newTv(http: OkHttpClient, nowMs: () -> Long = System::currentTimeMillis, gate: CookieFreePlaybackGate = CookieFreePlaybackGate()) =
        NewTvPlaybackResolver(http, nowMs = nowMs, gate = gate)

    @Test fun advertisedSubtitlesAndThumbnailsSurviveThePrimaryRoute() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8",
                "tracks":[{"kind":"subtitles","file":"https://cdn.example/en.vtt","label":"English","srclang":"en"}],
                "captions":[{"kind":"thumbnails","src":"previews/index.vtt?signature=issued"}],
                "thumbnails":{"url":"previews/index.vtt?signature=issued"},
                "thumb":"https://cdn.example/poster.jpg"}""")
            "/media.m3u8" -> media
            else -> throw AssertionError("Optional tracks must not trigger guessed or premature requests")
        } })
        val result = resolver.resolve("1271684191", "hs")
        assertEquals(2, result.captions.size)
        assertEquals("vtt", result.captions.first().type)
        assertEquals("https://tv.imgcdn.kim/newtv/previews/index.vtt?signature=issued", result.captions.last().url)
        assertEquals("thumbnails", result.captions.last().type)
        assertEquals(3, seen.size)
    }

    @Test fun optionalTracksRejectUnsafeUrlsAndKeepExplicitTimedTracks() {
        val metadata = org.json.JSONObject("""{
            "tracks":[{"kind":"thumbnails","src":"/timed-preview.php?id=1271684191"},
                {"kind":"thumbnails","src":"http://cdn.example/previews.vtt"},
                {"kind":"thumbnails","src":"https://user:password@cdn.example/previews.vtt"},
                {"kind":"thumbnails","src":"https://localhost/previews.vtt"}],
            "storyboard":"https://cdn.example/story.vtt?signature=issued",
            "thumbnail":"https://cdn.example/poster.jpg"
        }""")
        val tracks = PlaybackMetadataTracks.parse(metadata, "https://tv.imgcdn.kim/newtv/player.php".toHttpUrl())
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.type == "thumbnails" })
        assertEquals("https://tv.imgcdn.kim/timed-preview.php?id=1271684191", tracks.first().url)
        assertEquals("https://cdn.example/story.vtt?signature=issued", tracks.last().url)
    }

    @Test fun apiRouteRetainsMasterValidatesAllUrisAndProbesVideoAudioAndSubtitles() = runBlocking {
        val seen = mutableListOf<Request>()
        val master = "https://tv.imgcdn.kim/newtv/hls/hs/1271684191.m3u8"
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> {
                assertEquals("1271684191", request.url.queryParameter("id")); assertEquals("hs", request.header("Ott"))
                Fixture("""{"ott":"hs","video_link":"$master","referer":"https://net52.cc/"}""")
            }
            "/newtv/hls/hs/1271684191.m3u8" -> Fixture("""#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="en",URI="https://cdn.example/audio/en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="en",URI="https://cdn.example/subs/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO="audio",SUBTITLES="subs"
https://cdn.example/video/480.m3u8?in=provider-issued
#EXT-X-STREAM-INF:BANDWIDTH=1500000,AUDIO="audio",SUBTITLES="subs"
https://cdn.example/video/720.m3u8?in=provider-issued
""")
            "/audio/en.m3u8", "/subs/en.m3u8", "/video/480.m3u8", "/video/720.m3u8" -> {
                assertEquals("https://net52.cc/", request.header("Referer")); media
            }
            else -> throw AssertionError("Unexpected endpoint ${request.url}")
        } })
        val result = resolver.resolve("1271684191", "hs")
        assertEquals(master, result.url); assertEquals(PlaybackRoute.NEWTV, result.route)
        assertEquals(6, seen.size)
        assertFalse(seen.any { it.url.encodedPath == "/video/720.m3u8" })
        assertFalse(result.headers.containsKey("Cookie"))
    }

    @Test fun trustedDiscoveryIsCachedFor24HoursAcrossDifferentVideos() = runBlocking {
        var now = 10_000L
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            "/media.m3u8" -> media
            else -> throw AssertionError("Unexpected endpoint")
        } }, nowMs = { now })
        resolver.resolve("1271684191", "hs"); resolver.resolve("1271684192", "nf")
        assertEquals(1, seen.count { it.url.encodedPath == "/checknewtv.php" })
        now += 86_400_001L
        resolver.resolve("0EPISODE123456789", "pv")
        assertEquals(2, seen.count { it.url.encodedPath == "/checknewtv.php" })
    }

    @Test fun retryAfterStopsThisProviderWithoutAnyFollowupRequests() = runBlocking {
        var clock = 1_000L
        val gate = CookieFreePlaybackGate { clock }
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { Fixture("Too many requests", 429, mapOf("Retry-After" to "120")) }, gate = gate)
        repeat(2) {
            try { resolver.resolve("1271684191", "hs"); fail("Rate limit ignored") }
            catch (_: PlaybackRateLimitedException) { }
        }
        assertEquals(1, seen.size); assertEquals(120_000L, gate.remainingMs())
        clock += 119_000L
        try { resolver.resolve("1271684191", "hs"); fail("Retry-After bypassed") } catch (_: PlaybackRateLimitedException) { }
        assertEquals(1, seen.size)
    }

    @Test fun rejectsEmptyHostsCredentialsCleartextAndUnsafeSecondaryVariants() = runBlocking {
        for (raw in listOf("https:///files/123/video.m3u8", "http://cdn.example/video.m3u8", "https://user:password@cdn.example/video.m3u8", "")) {
            val seen = mutableListOf<Request>()
            val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
                "/checknewtv.php" -> discovery()
                "/newtv/player.php" -> Fixture("""{"video_link":"$raw"}""")
                else -> throw AssertionError("Unsafe URL fetched")
            } })
            try { resolver.resolve("1271684191", "nf"); fail("Unsafe source accepted: $raw") } catch (_: IOException) { }
            assertEquals(2, seen.size)
        }
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/master.m3u8"}""")
            "/master.m3u8" -> Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nhttps:///files/video.m3u8")
            else -> throw AssertionError("Malformed secondary variant should reject before rendition requests")
        } })
        try { resolver.resolve("1271684191", "pv"); fail("Malformed secondary variant accepted") } catch (_: IOException) { }
        assertEquals(3, seen.size)
    }

    @Test fun redirectsDoNotLeakHeadersOrFollowUnsafeLocations() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("", 302, mapOf("Location" to "https://user:password@evil.example/player.php"))
            else -> throw AssertionError("Redirect was followed")
        } })
        try { resolver.resolve("1271684191", "hs"); fail("Redirect accepted") } catch (_: IOException) { }
        assertEquals(2, seen.size)
    }

    @Test fun untrustedDiscoveryNeverControlsApiDestination() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery("https://evil.example")
            "/newtv/player.php" -> { assertEquals("tv.imgcdn.kim", request.url.host); Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""") }
            "/media.m3u8" -> media
            else -> throw AssertionError("Untrusted host fetched")
        } })
        resolver.resolve("1271684191", "hs")
        assertFalse(seen.any { it.url.host == "evil.example" })
    }

    @Test fun cancellationStopsDiscoveryWithoutContinuingToAnotherEndpoint() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { Thread.sleep(300L); discovery() })
        try { withTimeout(50L) { resolver.resolve("1271684191", "hs") }; fail("Cancellation ignored") }
        catch (_: CancellationException) { }
        assertTrue(seen.size <= 1)
    }

    @Test fun exactSeasonAndEpisodeAreResolvedBeforePrimaryAndConcurrentPlaysShareTheResult() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"wrong-default","ep":"E1"}],"season":[{"id":"1271685002","s":"S2"}]}""")
            "/mobile/hs/episodes.php" -> {
                assertEquals("1271685002", request.url.queryParameter("s")); Fixture("""{"episodes":[{"id":"1271685003","ep":"E1"}]}""")
            }
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> {
                assertEquals("1271685003", request.url.queryParameter("id")); Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            }
            "/media.m3u8" -> media
            else -> throw AssertionError("Primary must not need a playlist or session")
        } }
        val resolver = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
        val first = async { resolver.resolve("Lanterns", "2026", "tv", 2, 1, "95350") }
        val second = async { resolver.resolve("Lanterns", "2026", "tv", 2, 1, "95350") }
        assertEquals(first.await(), second.await())
        assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
        assertEquals("1271685003", first.await().contentId)
    }

    @Test fun publicDirectHlsIsSecondAndIssuedPlaylistRemainsThird() = runBlocking {
        for (directWorks in listOf(true, false)) {
            val seen = mutableListOf<Request>()
            var directCalls = 0
            val http = client(seen) { request -> when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
                "/checknewtv.php" -> discovery()
                "/newtv/player.php" -> Fixture("""{"video_link":""}""")
                "/mobile/hs/hls/1271684191.m3u8" -> {
                    directCalls++; assertNull(request.url.queryParameter("in"))
                    if (directWorks) media else Fixture("Not found", 404)
                }
                "/mobile/hs/playlist.php" -> { assertFalse(directWorks); Fixture("""{"sources":[{"file":"https://cdn.example/issued.m3u8?in=issued"}]}""") }
                "/issued.m3u8" -> media
                else -> throw AssertionError("Unexpected endpoint ${request.url}")
            } }
            val result = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http)).resolve("Lanterns", "2026", "tv", 1, 1, "95350")
            assertEquals(if (directWorks) PlaybackRoute.DIRECT else PlaybackRoute.ISSUED, result.route)
            assertEquals(1, directCalls)
            val paths = seen.map { it.url.encodedPath }
            assertTrue(paths.indexOf("/newtv/player.php") < paths.indexOf("/mobile/hs/hls/1271684191.m3u8"))
            if (!directWorks) assertTrue(paths.indexOf("/mobile/hs/hls/1271684191.m3u8") < paths.indexOf("/mobile/hs/playlist.php"))
            assertFalse(paths.any { it.contains("verify") })
        }
    }
    @Test fun rejectsErrorStatusAndWrongAdvertisedIdentityBeforeRequestingMedia() = runBlocking {
        for (metadata in listOf(
            "\"status\":false", "\"status\":\"error\"", "\"ott\":\"nf\"",
            "\"id\":\"1271684999\"", "\"ep\":\"E1 • S2\""
        )) {
            val seen = mutableListOf<Request>()
            val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
                "/checknewtv.php" -> discovery()
                "/newtv/player.php" -> Fixture("{\"video_link\":\"https://cdn.example/media.m3u8\",$metadata}")
                else -> throw AssertionError("Rejected metadata should never reach a manifest")
            } })
            try { resolver.resolve("1271684191", "hs", 1, 1); fail("Bad identity accepted: $metadata") }
            catch (_: IOException) { }
            assertEquals(2, seen.size)
        }
    }

    @Test fun legitimateNineMinuteEpisodeDoesNotTriggerProviderCooldown() = runBlocking {
        val seen = mutableListOf<Request>()
        val gate = CookieFreePlaybackGate()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            "/media.m3u8" -> Fixture("#EXTM3U\n#EXTINF:540,\nsegment.jpg\n#EXT-X-ENDLIST")
            else -> throw AssertionError("Unexpected endpoint")
        } }, gate = gate)
        assertEquals(PlaybackRoute.NEWTV, resolver.resolve("1271684191", "hs").route)
        assertEquals(0L, gate.remainingMs())
    }

    @Test fun routeTimeoutBecomesFallbackFailureWhileExternalCancellationStillPropagates() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { Thread.sleep(200L); discovery() }
        val resolver = NewTvPlaybackResolver(http, gate = CookieFreePlaybackGate(), routeTimeoutMs = 40L)
        try { resolver.resolve("1271684191", "hs"); fail("Route budget ignored") }
        catch (error: IOException) { assertEquals("NewTV playback timed out", error.message) }
        assertTrue(seen.size <= 1)
    }

    @Test fun primary429CanUseAnotherProviderButDoesNotRetryPrimary() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("Too many requests", 429, mapOf("Retry-After" to "120"))
            "/mobile/hs/hls/1271684191.m3u8" -> media
            else -> throw AssertionError("Unexpected endpoint ${request.url}")
        } }
        val primary = newTv(http)
        repeat(2) {
            val result = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = primary)
                .resolve("Lanterns", "2026", "tv", 1, 1, "95350")
            assertEquals(PlaybackRoute.DIRECT, result.route)
        }
        assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
        assertEquals(2, seen.count { it.url.encodedPath == "/mobile/hs/hls/1271684191.m3u8" })
    }

    @Test fun trustedEndpointCacheSurvivesResolverRecreation() = runBlocking {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        application.getSharedPreferences("newtv_endpoint_cache", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val cache = NewTvEndpointCache(application)
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            "/media.m3u8" -> media
            else -> throw AssertionError("Unexpected endpoint")
        } }
        repeat(2) {
            NewTvPlaybackResolver(http, gate = CookieFreePlaybackGate(), endpointCache = cache)
                .resolve("1271684191", "hs")
        }
        assertEquals(1, seen.count { it.url.encodedPath == "/checknewtv.php" })
    }

    @Test fun explicitRetryEvictsTheResolvedUrlButReusesTheExactEpisodeIdentity() = runBlocking {
        val seen = mutableListOf<Request>()
        var issued = 0
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> { issued++; Fixture("""{"video_link":"https://cdn.example/video-$issued.m3u8"}""") }
            "/video-1.m3u8", "/video-2.m3u8" -> media
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val resolver = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
        val first = resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertEquals(first, resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350"))
        assertEquals(1, issued)
        resolver.evict("95350", "tv", 1, 1)
        val retried = resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertNotEquals(first.url, retried.url)
        assertEquals(2, issued)
        assertEquals(1, seen.count { it.url.encodedPath == "/mobile/hs/post.php" })
    }

    @Test fun legacyCooldownRevisionKeepsPrimaryCacheAndItsVerifiedManifestHandoff() = runBlocking {
        VerifiedManifestHandoff.clear()
        if (PlaybackServiceGate.remainingMs() > 0L) org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofMillis(PlaybackServiceGate.remainingMs() + 1L))
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            "/media.m3u8" -> media
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val resolver = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
        val first = resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        try {
            PlaybackServiceGate.recordLimit(120_000L)
            assertEquals(first, resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350"))
            assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
            assertNull(VerifiedManifestHandoff.take(first.url, first.headers))
            assertNotNull(VerifiedManifestHandoff.take(first.url, first.headers, sourceId = "[NewTV]"))
        } finally {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(PlaybackServiceGate.remainingMs() + 1L))
            VerifiedManifestHandoff.clear()
        }
    }
    @Test fun unsignedWaitingClipFallsBackToExistingIssuedPlaylistWithoutServiceCooldown() = runBlocking {
        val seen = mutableListOf<Request>()
        val revision = PlaybackServiceGate.sourceRevision
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("Page Not Found", 403)
            "/mobile/hs/hls/1271684191.m3u8" -> {
                assertNull(request.url.queryParameter("in"))
                Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nhttps://cdn.example/files/220884/video.m3u8")
            }
            "/mobile/hs/playlist.php" -> Fixture("""{"sources":[{"file":"https://cdn.example/issued.m3u8?in=provider-issued"}]}""")
            "/issued.m3u8" -> media
            else -> throw AssertionError("Rejected unsigned probe must not fetch the waiting clip: ${request.url}")
        } }
        val result = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
            .resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertEquals("https://cdn.example/issued.m3u8?in=provider-issued", result.url)
        assertEquals(0L, PlaybackServiceGate.remainingMs())
        assertEquals(revision, PlaybackServiceGate.sourceRevision)
        val paths = seen.map { it.url.encodedPath }
        assertTrue(paths.indexOf("/mobile/hs/hls/1271684191.m3u8") < paths.indexOf("/mobile/hs/playlist.php"))
        assertEquals(1, paths.count { it == "/mobile/hs/playlist.php" })
    }

    @Test fun unsignedProbeHonorsReal429ExplicitLimitAndServerRetryAfterBeforeIssuedFallback() = runBlocking {
        for (rejection in listOf(
            Fixture("busy", 429, mapOf("Retry-After" to "120")),
            Fixture("Too many requests", 200),
            Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8", 200, mapOf("Retry-After" to "120"))
        )) {
            isolateProviderGate()
            val seen = mutableListOf<Request>()
            val http = client(seen) { request -> when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
                "/checknewtv.php" -> discovery()
                "/newtv/player.php" -> Fixture("""{"video_link":""}""")
                "/mobile/hs/hls/1271684191.m3u8" -> rejection
                else -> throw AssertionError("Same-provider fallback must stop at a real rate limit")
            } }
            try {
                PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
                    .resolve("Lanterns", "2026", "tv", 1, 1, "95350")
                fail("Real provider rate limit ignored")
            } catch (_: PlaybackRateLimitedException) { }
            assertFalse(seen.any { it.url.encodedPath.endsWith("/playlist.php") })
            assertTrue(PlaybackServiceGate.remainingMs() > 0L)
            if (rejection.headers.containsKey("Retry-After")) assertEquals(120_000L, PlaybackServiceGate.remainingMs())
        }
    }

    @Test fun speculativeRouteBudgetsPreserveTheExistingIssuedRouteWindow() {
        var nowNanos = 0L
        val budget = PlaybackProbeBudget(28_000_000_000L) { nowNanos }
        assertEquals(4_000L, budget.primaryMs())
        assertEquals(2_000L, budget.directMs())
        nowNanos += 9_500_000_000L // Public identity discovery consumed most of the spare window.
        assertEquals(500L, budget.primaryMs())
        assertEquals(500L, budget.directMs())
        nowNanos += 500_000_000L
        assertEquals(0L, budget.primaryMs())
        assertEquals(0L, budget.directMs())
    }

    @Test fun slowPrimaryIsCancelledInTimeForTheExistingIssuedRouteToPlay() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> {
                Thread.sleep(4_300L)
                Fixture("""{"video_link":"https://cdn.example/slow-primary.m3u8"}""")
            }
            "/mobile/hs/hls/1271684191.m3u8" -> Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8")
            "/mobile/hs/playlist.php" -> Fixture("""{"sources":[{"file":"https://cdn.example/issued.m3u8?in=provider-issued"}]}""")
            "/issued.m3u8" -> media
            else -> throw AssertionError("Cancelled primary must not continue fetching manifests: ${request.url}")
        } }
        val result = withTimeout(12_000L) {
            PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
                .resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        }
        assertEquals(PlaybackRoute.ISSUED, result.route)
        assertFalse(seen.any { it.url.encodedPath == "/slow-primary.m3u8" })
        assertEquals(0L, PlaybackServiceGate.remainingMs())
    }

    @Test fun cancellingTheWholeResolutionCancelsPrimaryWithoutStartingFallback() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(seen) { request -> when (request.url.encodedPath) {
            "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"}]}""")
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> { Thread.sleep(500L); Fixture("""{"video_link":"https://cdn.example/cancelled.m3u8"}""") }
            else -> throw AssertionError("External cancellation must not trigger a fallback")
        } }
        val pending = async(kotlinx.coroutines.Dispatchers.Default) {
            PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = newTv(http))
                .resolve("Lanterns", "2026", "tv", 1, 1, "95350")
        }
        withTimeout(3_000L) {
            while (synchronized(seen) { seen.none { it.url.encodedPath == "/newtv/player.php" } }) kotlinx.coroutines.delay(10L)
        }
        pending.cancel()
        try { pending.await(); fail("Resolution cancellation was swallowed") } catch (_: CancellationException) { }
        assertFalse(synchronized(seen) { seen.any { it.url.encodedPath.contains("/hls/") || it.url.encodedPath.endsWith("/playlist.php") } })
    }

    @Test fun unavailablePrimaryIsSkippedAcrossTitlesUntilBoundedRecoveryWindowExpires() = runBlocking {
        for (rejectionCode in listOf(403, 404)) {
            isolateProviderGate()
            val seen = mutableListOf<Request>()
            var primaryAvailable = false
            val primaryGate = CookieFreePlaybackGate()
            val http = client(seen) { request -> when (request.url.encodedPath) {
                "/mobile/hs/post.php" -> Fixture("""{"status":"y","title":"Lanterns","type":"t","year":"2026","episodes":[{"id":"1271684191","s":"S1","ep":"E1"},{"id":"1271684192","s":"S1","ep":"E2"},{"id":"1271684193","s":"S1","ep":"E3"}]}""")
                "/checknewtv.php" -> discovery()
                "/newtv/player.php" -> if (primaryAvailable) Fixture("""{"video_link":"https://cdn.example/recovered.m3u8"}""") else Fixture("Page Not Found", rejectionCode)
                "/mobile/hs/hls/1271684191.m3u8", "/mobile/hs/hls/1271684192.m3u8" ->
                    Fixture("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8")
                "/mobile/hs/playlist.php" -> Fixture("""{"sources":[{"file":"https://cdn.example/issued.m3u8?in=provider-issued"}]}""")
                "/issued.m3u8", "/recovered.m3u8" -> media
                else -> throw AssertionError("Unexpected endpoint ${request.url}")
            } }
            val primary = newTv(http, gate = primaryGate)
            val resolver = PublicPlaybackResolver(http, backgroundCatalogRefresh = false, newTv = primary)
            assertEquals(PlaybackRoute.ISSUED, resolver.resolve("Lanterns", "2026", "tv", 1, 1, "95350").route)
            assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
            assertEquals(0L, primaryGate.remainingMs()) // Availability is not a subscription/provider rate limit.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(59_999L))
            primaryAvailable = true
            assertEquals(PlaybackRoute.ISSUED, resolver.resolve("Lanterns", "2026", "tv", 1, 2, "95350").route)
            assertEquals(1, seen.count { it.url.encodedPath == "/newtv/player.php" })
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1L))
            assertEquals(PlaybackRoute.NEWTV, resolver.resolve("Lanterns", "2026", "tv", 1, 3, "95350").route)
            assertEquals(2, seen.count { it.url.encodedPath == "/newtv/player.php" })
            assertEquals(1, seen.count { it.url.encodedPath == "/checknewtv.php" })
            assertEquals(2, seen.count { it.url.encodedPath == "/mobile/hs/playlist.php" })
            assertEquals(0L, PlaybackServiceGate.remainingMs())
        }
    }

    @Test fun rejectedCdnManifestDoesNotDisableThePrimaryApiForOtherTitles() = runBlocking {
        val seen = mutableListOf<Request>()
        var cdnAvailable = false
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/player.php" -> Fixture("""{"video_link":"https://cdn.example/media.m3u8"}""")
            "/media.m3u8" -> if (cdnAvailable) media else Fixture("Forbidden", 403)
            else -> throw AssertionError("Unexpected endpoint")
        } })
        try { resolver.resolve("1271684191", "hs"); fail("Unavailable CDN accepted") }
        catch (error: IOException) { assertFalse(error is PlaybackRateLimitedException) }
        cdnAvailable = true
        assertEquals(PlaybackRoute.NEWTV, resolver.resolve("1271684192", "hs").route)
        assertEquals(2, seen.count { it.url.encodedPath == "/newtv/player.php" })
    }

    @Test fun labelledEpisodeCatalogCanReachPageSixWithoutAPlayerOrSessionRequest() = runBlocking {
        val seen = mutableListOf<Request>()
        val resolver = newTv(client(seen) { request -> when (request.url.encodedPath) {
            "/checknewtv.php" -> discovery()
            "/newtv/post.php" -> Fixture("""{"status":"y","title":"Long Season","type":"t","season":[{"s":"S1","id":"80001001"}]}""")
            "/newtv/episodes.php" -> {
                assertEquals("80001001", request.url.queryParameter("id"))
                val page = request.url.queryParameter("page")!!.toInt()
                Fixture("""{"episodes":[{"id":"${80002000 + page}","s":"S1","ep":"E${(page - 1) * 10 + 1}"}],"nextPageShow":${if (page < 6) 1 else 0}}""")
            }
            else -> throw AssertionError("Unexpected path ${request.url.encodedPath}")
        } })
        assertEquals("80002006", resolver.resolveSeriesEpisodeId("80001000", "nf", "Long Season", 1, 51))
        assertEquals(6, seen.count { it.url.encodedPath == "/newtv/episodes.php" })
        assertFalse(seen.any { it.url.encodedPath.contains("player.php") || it.header("Cookie") != null })
    }

}
