package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SplashEntryGateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun entryFailureOffersRetryAndSignOutWithoutOpeningTheApp() {
        var retries = 0
        var signOuts = 0
        var completed = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                SplashScreen(isWarmupFinished = false, startupError = "Reconnect to prepare your account.",
                    onRetryStartup = { retries++ }, onSignOut = { signOuts++ },
                    onSplashComplete = { completed++ })
            }
        }
        composeRule.onNodeWithText("Reconnect to prepare your account.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, signOuts)
            assertEquals(0, completed)
        }
    }

    @Test fun animationCannotOpenTheAppWhileEntryPreparationIsPending() {
        var completed = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme { SplashScreen(isWarmupFinished = false, onSplashComplete = { completed++ }) }
        }
        composeRule.mainClock.advanceTimeBy(12_000)
        composeRule.runOnIdle { assertEquals(0, completed) }
    }
}
