package com.example.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRemoteKeysTest {
    private fun PlayerRemoteKeys.down(code: Int, visible: Boolean, repeat: Int = 0) =
        dispatch(code, KeyEvent.ACTION_DOWN, repeat, visible)

    @Test fun hiddenControllerActsOnTheFirstMediaPress() {
        val keys = PlayerRemoteKeys()
        assertEquals(PlayerRemoteKeys.Action.TOGGLE, keys.down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false))
        assertEquals(PlayerRemoteKeys.Action.CONSUME,
            keys.dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 0, true))
        assertEquals(PlayerRemoteKeys.Action.TOGGLE, keys.down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true))
    }

    @Test fun heldPlaybackKeysDoNotToggleAgainOrSkipExtraEpisodes() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT)) {
            val keys = PlayerRemoteKeys()
            keys.down(code, false)
            assertEquals(PlayerRemoteKeys.Action.CONSUME, keys.down(code, true, 1))
            assertEquals(PlayerRemoteKeys.Action.CONSUME, keys.down(code, true, 2))
        }
    }

    @Test fun wakeConfirmDoesNotClickTheNewlyFocusedButtonOnRelease() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)) {
            val keys = PlayerRemoteKeys()
            assertEquals(PlayerRemoteKeys.Action.TOGGLE, keys.down(code, false))
            assertEquals(PlayerRemoteKeys.Action.CONSUME, keys.down(code, true, 1))
            assertEquals(PlayerRemoteKeys.Action.CONSUME,
                keys.dispatch(code, KeyEvent.ACTION_UP, 0, true))
            // A later press with the controls visible remains owned by the button.
            assertEquals(PlayerRemoteKeys.Action.PASS, keys.down(code, true))
        }
    }

    @Test fun heldSeekKeysContinueSeekingAndDpadNavigationRemainsWithControls() {
        val keys = PlayerRemoteKeys()
        assertEquals(PlayerRemoteKeys.Action.FORWARD, keys.down(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, false))
        assertEquals(PlayerRemoteKeys.Action.FORWARD, keys.down(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true, 1))
        assertEquals(PlayerRemoteKeys.Action.REWIND, keys.down(KeyEvent.KEYCODE_MEDIA_REWIND, true, 2))
        assertEquals(PlayerRemoteKeys.Action.PASS, keys.down(KeyEvent.KEYCODE_DPAD_RIGHT, true))
    }

    @Test fun dedicatedPlayAndPauseHaveTheirOwnActionOnWake() {
        val keys = PlayerRemoteKeys()
        assertEquals(PlayerRemoteKeys.Action.PLAY, keys.down(KeyEvent.KEYCODE_MEDIA_PLAY, false))
        assertEquals(PlayerRemoteKeys.Action.PAUSE, keys.down(KeyEvent.KEYCODE_MEDIA_PAUSE, false))
    }

    @Test fun aLostKeyUpDoesNotDisableTheNextPhysicalConfirmPress() {
        val keys = PlayerRemoteKeys()
        assertEquals(PlayerRemoteKeys.Action.TOGGLE, keys.down(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertEquals(PlayerRemoteKeys.Action.PASS, keys.down(KeyEvent.KEYCODE_DPAD_CENTER, true))
    }
}
