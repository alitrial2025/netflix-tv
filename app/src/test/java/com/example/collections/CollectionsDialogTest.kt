package com.example.collections

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-mdpi", sdk = [34], application = Application::class)
class CollectionsDialogTest {
    @get:Rule val rule = createComposeRule()
    private val film = CollectionTitle("123", "movie", "An actual film")
    private val series = CollectionTitle("123", "tv", "An actual series")

    @Test fun createAddRenameAndRemoveWorkWithoutChangingMyListOrBypassingTheOpenCallback() {
        val controller = FakeCollectionsController()
        val opened = mutableListOf<CollectionTitle>()
        val available = mutableListOf(film, series)
        rule.setContent { MaterialTheme {
            CollectionsDialog("owner", "profile", available, { opened += it }, {}, controller = controller)
        } }
        rule.onNodeWithTag("collection_create").performClick()
        rule.onNodeWithText("Save", substring = false).assertIsNotEnabled()
        rule.onNodeWithTag("collection_name_input").performTextInput("Weekend")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithTag("collection_created-1").performClick()
        rule.onNodeWithText("Add titles", substring = false).performClick()
        rule.onNodeWithTag("collection_add_movie:123").performClick()
        rule.onNodeWithTag("collection_add_movie:123").assertDoesNotExist()
        rule.onNodeWithTag("collection_add_tv:123").assertIsDisplayed()
        rule.onNodeWithTag("collection_back").performClick()
        rule.onNodeWithText("Rename", substring = false).performClick()
        rule.onNodeWithTag("collection_name_input").performTextReplacement("Film night")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithText("Film night", substring = false).assertIsDisplayed()
        rule.onNodeWithTag("collection_open_movie:123").performClick()
        assertEquals(listOf(film), opened)
        rule.onNodeWithTag("collection_remove_movie:123").performClick()
        rule.onNodeWithText("No titles yet.", substring = true).assertIsDisplayed()
        assertTrue(controller.state.value.items.single().items.isEmpty())
        // The source list is only a picker input; collection removal does not mutate it.
        assertEquals(listOf(film, series), available)
    }

    @Test fun deletionRequiresConfirmationAndCancelRetainsTheCollection() {
        val controller = FakeCollectionsController(listOf(TitleCollection("saved", "Saved titles", listOf(film))))
        rule.setContent { MaterialTheme {
            CollectionsDialog("owner", "profile", listOf(film), {}, {}, controller = controller)
        } }
        rule.onNodeWithTag("collection_saved").performClick()
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.onNodeWithTag("collection_confirm_delete").assertIsDisplayed()
        assertEquals(0, controller.deletes)
        rule.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(1, controller.state.value.items.size)
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.onNodeWithTag("collection_confirm_delete").performClick()
        assertEquals(1, controller.deletes)
        rule.onNodeWithTag("collection_saved").assertDoesNotExist()
        rule.onNodeWithText("Create a collection for movie nights", substring = true).assertIsDisplayed()
    }

    @Test fun aRejectedEditShowsItsErrorAndKeepsTheDraftForRetry() {
        val controller = FakeCollectionsController().apply { rejectNext = true }
        rule.setContent { MaterialTheme {
            CollectionsDialog("owner", "profile", emptyList(), {}, {}, controller = controller)
        } }
        rule.onNodeWithTag("collection_create").performClick()
        rule.onNodeWithTag("collection_name_input").performTextInput("Weekend")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithTag("collections_error").assertTextEquals("Couldn't save this change. Retry.")
        rule.onNodeWithTag("collection_name_input").assertTextContains("Weekend")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithTag("collection_created-1").assertIsDisplayed()
    }

    @Test fun changingAccountDisposesTheOldListenerAndClearsItsSelectedCollection() {
        val first = FakeCollectionsController(listOf(TitleCollection("old", "Private old collection", listOf(film))))
        val second = FakeCollectionsController(listOf(TitleCollection("new", "Different account", listOf(series))))
        var owner by mutableStateOf("first")
        rule.setContent { MaterialTheme {
            CollectionsDialog(owner, "same-profile-id", listOf(film, series), {}, {},
                controller = if (owner == "first") first else second)
        } }
        rule.onNodeWithTag("collection_old").performClick()
        rule.onNodeWithTag("collection_open_movie:123").assertIsDisplayed()
        rule.runOnIdle { owner = "second" }
        rule.onNodeWithTag("collection_open_movie:123").assertDoesNotExist()
        rule.onNodeWithText("Private old collection", substring = false).assertDoesNotExist()
        rule.onNodeWithTag("collection_new").assertIsDisplayed()
        assertEquals(1, first.closes)
        assertEquals(1, second.starts)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvDialogHasVisibleRemoteFocusAndOneOkPressOpensTheSelectedCollection() {
        val controller = FakeCollectionsController(listOf(TitleCollection("saved", "Remote collection", listOf(series))))
        rule.setContent { MaterialTheme {
            CollectionsDialog("owner", "profile", listOf(series), {}, {}, isTv = true, controller = controller)
        } }
        rule.onNodeWithTag("collections_close").assertIsFocused()
        rule.onNodeWithTag("collection_saved").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("collection_saved").assertIsFocused()
        rule.onNodeWithTag("collection_saved").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("collection_open_tv:123").assertIsDisplayed()
        rule.onNodeWithTag("collections_close").assertIsFocused()
        val dialog = rule.onNodeWithTag("collections_dialog").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("collections_close", "collection_open_tv:123", "collection_back")) {
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fits the TV dialog", bounds.left >= dialog.left && bounds.right <= dialog.right &&
                bounds.top >= dialog.top && bounds.bottom <= dialog.bottom)
        }
    }
}

private class FakeCollectionsController(initial: List<TitleCollection> = emptyList()) : CollectionsController {
    private val mutable = MutableStateFlow(CollectionsState(items = initial, loading = false))
    override val state: StateFlow<CollectionsState> = mutable
    var starts = 0
    var closes = 0
    var deletes = 0
    var rejectNext = false
    private var ids = 0
    override fun start() { starts++ }
    override fun close() { closes++ }
    override fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    override fun create(rawName: String): Boolean {
        if (rejectNext) { rejectNext = false; mutable.value = mutable.value.copy(error = "Couldn't save this change. Retry."); return false }
        mutable.value = mutable.value.copy(items = mutable.value.items + TitleCollection("created-${++ids}", rawName, emptyList()), error = null)
        return true
    }
    override fun rename(id: String, rawName: String): Boolean {
        mutable.value = mutable.value.copy(items = mutable.value.items.map { if (it.id == id) it.copy(name = rawName) else it })
        return true
    }
    override fun delete(id: String): Boolean { deletes++; mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.id == id }); return true }
    override fun add(id: String, raw: CollectionTitle): Boolean {
        mutable.value = mutable.value.copy(items = mutable.value.items.map { if (it.id == id) it.copy(items = it.items + raw) else it }); return true
    }
    override fun remove(id: String, title: CollectionTitle): Boolean {
        mutable.value = mutable.value.copy(items = mutable.value.items.map { if (it.id == id) it.copy(items = it.items.filterNot { item -> item.key == title.key }) else it }); return true
    }
}
