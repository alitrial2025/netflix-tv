package com.example.ui.screens.player

import android.view.KeyEvent

/** Dispatch playback keys before a hidden controller consumes the wake-up event. */
internal class PlayerRemoteKeys {
    enum class Action { PASS, CONSUME, TOGGLE, PLAY, PAUSE, FORWARD, REWIND, NEXT, REVEAL }

    private var wakeConfirmKey: Int? = null

    fun dispatch(keyCode: Int, eventAction: Int, repeatCount: Int, controlsVisible: Boolean): Action {
        // Focus may move to a button after the wake-up down event. Its matching
        // up event must not then click that button and undo the first action.
        if (wakeConfirmKey == keyCode) {
            if (eventAction == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                // Window/lifecycle changes can drop a key-up. A new physical
                // press must still work rather than remain suppressed forever.
                wakeConfirmKey = null
            } else {
                if (eventAction == KeyEvent.ACTION_UP) wakeConfirmKey = null
                return Action.CONSUME
            }
        }

        val mediaAction = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Action.TOGGLE
            KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PAUSE
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FORWARD
            KeyEvent.KEYCODE_MEDIA_REWIND -> Action.REWIND
            KeyEvent.KEYCODE_MEDIA_NEXT -> Action.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Action.REVEAL
            else -> null
        }
        if (mediaAction != null) {
            if (eventAction != KeyEvent.ACTION_DOWN) return Action.CONSUME
            // Seek keys can repeat while held; toggles and episode changes act
            // once per physical press regardless of Android's repeat count.
            if (repeatCount > 0 && mediaAction != Action.FORWARD && mediaAction != Action.REWIND) {
                return Action.CONSUME
            }
            return mediaAction
        }

        val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
        if (!controlsVisible && isConfirm && eventAction == KeyEvent.ACTION_DOWN) {
            if (repeatCount > 0) return Action.CONSUME
            wakeConfirmKey = keyCode
            return Action.TOGGLE
        }
        return Action.PASS
    }
}
