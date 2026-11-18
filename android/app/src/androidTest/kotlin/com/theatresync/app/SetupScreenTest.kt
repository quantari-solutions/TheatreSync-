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
 * Launches the real app on an emulator and checks the three-step setup screen.
 * The screen scrolls, so each item is scrolled into view before checking it
 * (small emulator screens don't show everything at once).
 */
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsThreeStepsAndStartIsDisabledUntilReady() {
        rule.onNodeWithText("Choose a movie").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Choose movie", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Choose your TV").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("start").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun headphoneCheckOpensAndCloses() {
        rule.onNodeWithText("Check headphones", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Allow Bluetooth").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Choose movie", substring = true).performScrollTo().assertIsDisplayed()
    }
}
