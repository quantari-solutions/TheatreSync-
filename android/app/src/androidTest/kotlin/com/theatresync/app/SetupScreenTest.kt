package com.theatresync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real app on an emulator. The screens scroll, so each item is
 * scrolled into view before checking it.
 */
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun openHeadphones() {
        rule.onNodeWithText("Check headphones", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Headphones").assertIsDisplayed()
    }

    private fun assertHome() {
        rule.onNodeWithText("Choose movie", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun showsThreeStepsAndStartIsDisabledUntilReady() {
        rule.onNodeWithText("Choose a movie").performScrollTo().assertIsDisplayed()
        assertHome()
        rule.onNodeWithText("Choose your TV").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("start").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun backArrowReturnsFromHeadphones() {
        openHeadphones()
        rule.onNodeWithTag("back").performClick()
        assertHome()
    }

    @Test
    fun systemBackReturnsFromHeadphones() {
        openHeadphones()
        // System back (button or gesture) reaches the app through its OnBackPressedDispatcher,
        // which is where BackHandler listens. Dispatch it there directly: a key event on a
        // headless emulator can be swallowed by whatever system window holds focus.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertHome()
    }

    @Test
    fun technicalDetailsOnlyBehindInfoButton() {
        openHeadphones()
        rule.onNodeWithText("Technical details", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("info").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("protocol v1", substring = true).assertIsDisplayed()
    }
}
