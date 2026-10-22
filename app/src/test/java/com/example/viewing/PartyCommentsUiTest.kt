package com.example.viewing

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [34], application = Application::class)
class PartyCommentsUiTest {
    @get:Rule val rule = createComposeRule()
    private val now = 1_800_000_000_000L
    private fun party() = WatchParty("AABBCCDDEE", 1, ViewingTarget("movie", "123", "Film"), 0, 100_000, true, false,
        now, now, now + 60_000, false, false, emptyList(), messages = listOf(PartyComment("one", "a", "Guest", "Hello", now)))

    @Test fun landscapeChatPreservesFailedDraftAndDisablesSendingWhenHostPausesComments() {
        var room by mutableStateOf(party())
        var error by mutableStateOf<String?>(null)
        var sent: String? = null
        rule.setContent { MaterialTheme { PartyChatPanel(room, false, error, true, { sent = it; error = "Message not sent. Try again." }, {}, {}, {}, {}) } }
        rule.onNodeWithTag("party_message_input").performTextInput("My message")
        rule.onNodeWithTag("party_message_send").assertIsDisplayed().performClick()
        assertEquals("My message", sent)
        rule.onNodeWithTag("party_message_input").assertTextContains("My message")
        rule.onNodeWithTag("party_comments_moderate").assertDoesNotExist()
        rule.runOnIdle { room = room.copy(commentsEnabled = false); error = null }
        rule.onNodeWithTag("party_message_send").assertIsNotEnabled()
        rule.onNodeWithTag("party_reaction_👏").assertIsNotEnabled()
    }
    @Test @Config(qualifiers = "w960dp-h540dp-television-mdpi")
    fun televisionPanelClosesWithOneRemotePressAndExposesHostModeration() {
        var closed = 0; var deleted: String? = null; var enabled: Boolean? = null
        rule.setContent { MaterialTheme { PartyChatPanel(party().copy(isHost = true), false, null, true, {}, {}, { enabled = it }, { deleted = it }, { closed++ }) } }
        rule.onNodeWithTag("party_chat_close").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, closed)
        rule.onNodeWithTag("party_comments_moderate").performClick(); assertEquals(false, enabled)
        rule.onNodeWithTag("party_delete_one").performClick(); assertEquals("one", deleted)
    }
    private fun advance(ms: Long) {
        rule.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }
    @Test fun peekIsBriefCanBeHiddenAndDoesNotShowOldHistoryOnEntry() {
        rule.mainClock.autoAdvance = false
        var visible by mutableStateOf(true)
        var room by mutableStateOf(party())
        rule.setContent { MaterialTheme { PartyCommentPeek(room, visible, now) } }
        advance(50)
        rule.onNodeWithTag("party_comment_peek").assertIsDisplayed()
        rule.runOnIdle { visible = false }; advance(50)
        rule.onNodeWithTag("party_comment_peek").assertDoesNotExist()
        rule.runOnIdle { visible = true; room = room.copy(messages = listOf(room.messages.single().copy(id = "old", createdAtMs = now - 11_000))) }
        advance(50)
        rule.onNodeWithTag("party_comment_peek").assertDoesNotExist()
        rule.runOnIdle { room = party().copy(messages = listOf(party().messages.single().copy(id = "fresh"))) }
        advance(50)
        rule.onNodeWithTag("party_comment_peek").assertIsDisplayed()
        advance(6_100)
        rule.onNodeWithTag("party_comment_peek").assertDoesNotExist()
    }
}
