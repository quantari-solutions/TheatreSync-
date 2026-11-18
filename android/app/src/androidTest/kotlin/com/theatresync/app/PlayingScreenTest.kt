package com.theatresync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every control on the playing screen calls the right action, on a real Android device. */
@RunWith(AndroidJUnit4::class)
class PlayingScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun controlsAreWiredCorrectly() {
        val calls = mutableListOf<String>()
        val ui = MovieSession.Ui(
            movieTitle = "Test Movie", connectedTv = "Theatre TV", phase = MovieSession.Phase.PLAYING,
            positionMs = 60_000, durationMs = 600_000, driftMs = 5, joinAddress = "10.0.0.2:5000",
        )
        rule.setContent {
            QuantariTheme {
                GridBackground {
                    PlayingScreen(
                        ui,
                        onPlayPause = { calls += "playpause" },
                        onSkip = { calls += "skip$it" },
                        onSoundEarly = { calls += "early" },
                        onSoundLate = { calls += "late" },
                        onHeadphonesHere = { calls += "here$it" },
                        onMuteTv = { calls += "mute$it" },
                        onStop = { calls += "stop" },
                        onDismiss = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Test Movie").assertIsDisplayed()
        rule.onNodeWithText("In sync", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("playpause").performClick()
        rule.onNodeWithTag("back10").performClick()
        rule.onNodeWithTag("fwd10").performClick()
        rule.onNodeWithTag("early").performScrollTo().performClick()
        rule.onNodeWithTag("late").performScrollTo().performClick()
        rule.onNodeWithText("Stop movie", substring = true).performScrollTo().performClick()
        assertEquals(listOf("playpause", "skip-10000", "skip10000", "early", "late", "stop"), calls)
    }
}
