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

    @Test
    fun youTubeTileOpensPageAndBackReturns() {
        rule.onNodeWithTag("tile_youtube").performScrollTo().performClick()
        rule.onNodeWithText("Cast a YouTube video").assertIsDisplayed()
        rule.onNodeWithTag("back").performClick()
        assertHome()
    }

    @Test
    fun mirrorTileOpensPage() {
        rule.onNodeWithTag("tile_mirror").performScrollTo().performClick()
        rule.onNodeWithTag("mirror_start").performScrollTo().assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertHome()
    }

    /**
     * The step that crashed on the owner's phone: choosing a real file returned by Android's
     * storage system (a content:// URI from MediaStore, like the Files app returns).
     */
    @Test
    fun choosingARealContentUriShowsTheMovie() {
        val ctx = rule.activity
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "TheatreSync test clip.wav")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/wav")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(TestMedia.wav(ctx.cacheDir, 1).readBytes()) }
            rule.runOnUiThread { (ctx.application as TheatreSyncApplication).session.chooseMovie(uri) }
            rule.waitForIdle()
            rule.onNodeWithText("TheatreSync test clip", substring = true).performScrollTo().assertIsDisplayed()
        } finally {
            ctx.contentResolver.delete(uri, null, null)
        }
    }
}
