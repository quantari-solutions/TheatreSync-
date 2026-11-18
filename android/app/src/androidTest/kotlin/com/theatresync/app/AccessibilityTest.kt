package com.theatresync.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theatresync.core.CastActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Google's Accessibility Test Framework (the checks behind Accessibility Scanner)
 * on every main screen: missing labels, touch targets under 48 dp, low contrast, etc.
 * Any finding fails the test.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun check(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.enableAccessibilityChecks()
        rule.setContent { QuantariTheme { GridBackground { content() } } }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun setupScreen() = check {
        SetupScreen(MovieSession.Ui(movieTitle = "Interstellar", tvs = listOf(MovieSession.Tv("1", "Theatre Room TV"))), {}, {}, {}, {}, {}, {})
    }

    @Test fun setupReady() = check {
        SetupScreen(MovieSession.Ui(movieTitle = "Interstellar", connectedTv = "Theatre Room TV"), {}, {}, {}, {}, {}, {})
    }

    @Test fun playingScreen() = check {
        PlayingScreen(
            MovieSession.Ui(movieTitle = "Interstellar", connectedTv = "TV", phase = MovieSession.Phase.PLAYING, positionMs = 1000, durationMs = 9000, driftMs = 4, joinAddress = "10.0.0.2:5000"),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun headphonesScreen() = check {
        HeadphonesScreen(listOf(DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH)), "details", true, {}, {}, {})
    }

    @Test fun nowCastingScreen() = check {
        NowCastingScreen(
            TvWatchers.NowCasting("1", "TV", CastActivity("233637DE", "YouTube", CastActivity.Kind.YOUTUBE, title = "Trailer", playerState = "PLAYING", currentTimeSec = 1.0, durationSec = 10.0, mediaSessionId = 1, transportId = "t")),
            {}, {}, {},
        )
    }

    @Test fun youTubeScreen() = check {
        YouTubeScreen(MovieSession.Ui(tvs = listOf(MovieSession.Tv("1", "TV"))), "https://youtu.be/dQw4w9WgXcQ", { _, _ -> }, {}, {})
    }

    @Test fun mirrorScreen() = check { MirrorScreen({}, {}) }

    @Test fun settingsScreen() = check { SettingsScreen(1, {}, {}, {}) }

    @Test fun crashTimeline() = check {
        CrashReportsScreen(
            listOf(CrashReporter.Entry("1", 1_763_456_941_000, "0.4.2", CrashReporter.Kind.CRASH, "Test", "report", CrashReporter.Status.SAVED, null)),
            {}, {}, {},
        )
    }

    @Test fun connectGitHub() = check { ConnectGitHubScreen(true, false, null, GitHubState.IDLE, {}, {}, {}, {}) }
    @Test fun gitHubSetup() = check { ConnectGitHubScreen(false, false, null, GitHubState.IDLE, {}, {}, {}, {}) }
    @Test fun gitHubConnected() = check { ConnectGitHubScreen(true, true, null, GitHubState.DONE, {}, {}, {}, {}, lastUpload = "Uploaded #21") }

    @Test fun headphonesChooseOutput() = check {
        HeadphonesScreen(
            listOf(DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH, 1), DiagnosticsReport.Output("Phone speaker", DiagnosticsReport.Kind.SPEAKER, 2)),
            "details", true, {}, {}, {}, preferredOutputId = 1,
        )
    }
}
