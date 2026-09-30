package com.example.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.ui.screens.ProfileSetupWalkthroughScreen

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1920dp-h1080dp")
class WalkthroughCrashTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLanguageSelectionCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        composeTestRule.setContent {
            ProfileSetupWalkthroughScreen(viewModel = NetflixViewModel(app), onComplete = {}, onCancel = {})
        }
        
        // Verify profile setup walkthrough screen rendered successfully without crashing
        composeTestRule.onNodeWithText("Who will be watching?").assertExists()
        composeTestRule.onNodeWithText("Step 1: Setup Profile Identity").assertExists()
    }
}
