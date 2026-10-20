package com.example.ui.util

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreviewPlayerCleanupTest {
    private class FakePlayer(state: Int = Player.STATE_READY, items: Int = 1) {
        var state = state
        var items = items
        val commands = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> this.state
                "getMediaItemCount" -> this.items
                "stop" -> { commands += "stop"; this.state = Player.STATE_IDLE; Unit }
                "clearMediaItems" -> { commands += "clear"; this.items = 0; Unit }
                else -> error("Unexpected player access: ${method.name}")
            }
        } as Player
    }

    @Test fun repeatedTabStopsIssueOnlyOneStopAndOnePlaylistClear() {
        val fake = FakePlayer()
        repeat(20) { fake.player.clearHomePreviewIfOwned(true) }
        assertEquals(listOf("stop", "clear"), fake.commands)
    }

    @Test fun idlePlayerWithQueuedMediaStillClearsItsSource() {
        val fake = FakePlayer(Player.STATE_IDLE, 1)
        fake.player.clearHomePreviewIfOwned(true)
        assertEquals(listOf("clear"), fake.commands)
    }

    @Test fun bufferingPreviewStopsEvenWhenItsPlaylistIsAlreadyEmpty() {
        val fake = FakePlayer(Player.STATE_BUFFERING, 0)
        fake.player.clearHomePreviewIfOwned(true)
        assertEquals(listOf("stop"), fake.commands)
    }

    @Test fun departingHomeCannotReadOrChangeTheForegroundPlayersState() {
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            error("Departing preview accessed ${method.name} after ownership transferred")
        } as Player
        player.clearHomePreviewIfOwned(false)
    }
}
