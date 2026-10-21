package com.example.tv

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvPlaybackRequestTest {
    @Test fun launcherRequestPreservesTitleIdentityEpisodeAndResumePosition() {
        val request = TvPlaybackRequest.parse("netflixpro://play/123?season=6&episode=22&pos=66000&mediaKind=tv&title=Smallville")!!
        assertEquals("player/123?season=6&episode=22&pos=66000&mediaKind=tv&title=Smallville", request.route)
    }
    @Test fun legacyLinksKeepDefaultsAndCanResolveTheirIdentityAfterAdmission() {
        val request = TvPlaybackRequest.parse("netflixpro://play/123")!!
        assertEquals("netflixpro://play/123", request.uri.toString())
        assertEquals("player/123?season=1&episode=1&pos=0&mediaKind=&title=", request.route)
    }
    @Test fun titleCannotInjectAnotherNavigationArgumentOrBypassTheGate() {
        val request = TvPlaybackRequest.parse("netflixpro://play/123?title=A%26trailer%3Dtrue%3Ftest&mediaKind=movie&trailer=true")!!
        assertTrue(request.route.contains("title=A%26trailer%3Dtrue%3Ftest"))
        assertFalse(request.route.contains("&trailer=true"))
    }
    @Test fun coldAndRevokedSessionsCannotOpenThePlayerBeforeEntryAdmission() {
        val request = TvPlaybackRequest.parse("netflixpro://play/123")!!
        assertFalse(request.canOpen(false, false, "splash"))
        assertFalse(request.canOpen(false, true, "home"))
        assertFalse(request.canOpen(false, true, "player/123"))
        assertFalse(request.canOpen(true, false, "home"))
        for (route in listOf("", "splash", "profiles", "tvAuth", "profileSetup", "editProfile/1"))
            assertFalse(route, request.canOpen(true, true, route))
        assertTrue(request.canOpen(true, true, "home"))
        assertTrue(request.canOpen(true, true, "player/456"))
    }
    @Test fun malformedExternalTargetsAreRejected() {
        for (uri in listOf("https://play/123", "netflixpro://play/", "netflixpro://play/1/2",
            "netflixpro://play/123?season=-1", "netflixpro://play/123?episode=abc",
            "netflixpro://play/123?pos=999999999999999999999", "netflixpro://play/123?mediaKind=other"))
            assertNull(uri, TvPlaybackRequest.parse(uri))
    }
}
