package com.theatresync.app

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.theatresync.app.MovieSession.Phase
import com.theatresync.app.MovieSession.Tv
import com.theatresync.app.MovieSession.Ui
import com.theatresync.core.CastActivity
import org.junit.Rule
import org.junit.Test

/**
 * Renders every screen state to PNGs (no phone or emulator needed).
 * Record:  ./gradlew :app:recordPaparazziDebug   → app/src/test/snapshots/images/
 * Verify:  ./gradlew :app:verifyPaparazziDebug   (fails if the UI changed unexpectedly)
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.NoActionBar", maxPercentDifference = 0.01)

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        QuantariTheme { GridBackground { content() } }
    }

    private fun setup(ui: Ui) = shot { SetupScreen(ui, {}, {}, {}, {}, {}, {}) }
    private fun playing(ui: Ui) = shot { PlayingScreen(ui, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun setup1_empty() = setup(Ui())
    @Test fun setup2_movieChosen_tvsFound() = setup(Ui(movieTitle = "Interstellar", tvs = listOf(Tv("1", "Theatre Room TV"), Tv("2", "Bedroom TV"))))
    @Test fun setup3_ready() = setup(Ui(movieTitle = "Interstellar", connectedTv = "Theatre Room TV"))
    @Test fun setup4_error() = setup(Ui(movieTitle = "Interstellar", message = "Connect this phone to the same Wi-Fi as the Chromecast."))

    @Test fun playing1_inSync() = playing(
        Ui(
            movieTitle = "Interstellar", connectedTv = "Theatre Room TV", phase = Phase.PLAYING,
            positionMs = 2_712_000, durationMs = 10_140_000, soundDelayMs = 60, headphoneName = "WH-1000XM4",
            friends = 1, joinAddress = "192.168.1.23:41234", driftMs = 6,
        ),
    )

    @Test fun playing2_correcting_paused() = playing(
        Ui(
            movieTitle = "Interstellar", connectedTv = "Theatre Room TV", phase = Phase.PAUSED,
            positionMs = 2_712_000, durationMs = 10_140_000, soundDelayMs = -20, friends = 0,
            joinAddress = "192.168.1.23:41234", driftMs = null,
        ),
    )

    private val youtube = TvWatchers.NowCasting(
        "1", "Theatre Room TV",
        CastActivity(
            appId = "233637DE", appName = "YouTube", kind = CastActivity.Kind.YOUTUBE,
            title = "Interstellar – Official Trailer", subtitle = "Warner Bros. Pictures",
            imageUrl = null, playerState = "PLAYING", currentTimeSec = 84.0, durationSec = 170.0,
            mediaSessionId = 1, transportId = "web-1", hasMedia = true, sessionId = "s1",
        ),
    )
    private val mirroring = TvWatchers.NowCasting(
        "1", "Theatre Room TV",
        CastActivity(appId = "0F5096E8", appName = "Chrome Mirroring", kind = CastActivity.Kind.MIRRORING, statusText = "Casting tab: Netflix"),
    )
    private val details = "TheatreSync 0.2.0 · protocol v1\nDevice: motorola moto g\nAndroid 14 (API 34)\n\nAudio outputs (2)\n  • WH-1000XM4\n    type=8 route=BLUETOOTH_A2DP id=12"

    @Test fun setup5_nowCastingYouTube() = setup(Ui(nowCasting = listOf(youtube), tvs = listOf(Tv("1", "Theatre Room TV"))))
    @Test fun casting1_youtube() = shot { NowCastingScreen(youtube, {}, {}, {}) }
    @Test fun casting2_mirroring() = shot { NowCastingScreen(mirroring, {}, {}, {}) }
    @Test fun headphones1_connected() = shot {
        HeadphonesScreen(
            listOf(DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH), DiagnosticsReport.Output("Phone speaker", DiagnosticsReport.Kind.SPEAKER)),
            details, namesAllowed = true, onAllow = {}, onOpenBluetooth = {}, onBack = {},
        )
    }
    @Test fun headphones2_none() = shot {
        HeadphonesScreen(listOf(DiagnosticsReport.Output("Phone speaker", DiagnosticsReport.Kind.SPEAKER)), details, namesAllowed = false, onAllow = {}, onOpenBluetooth = {}, onBack = {})
    }
    @Test fun headphones3_technicalDetails() = shot { TechnicalDetails(details) }
    @Test fun youtube1_paste() = shot { YouTubeScreen(Ui(tvs = listOf(Tv("1", "Media Room TV"))), "https://youtu.be/dQw4w9WgXcQ", { _, _ -> }, {}, {}) }
    @Test fun youtube2_failed() = shot {
        YouTubeScreen(Ui(tvs = listOf(Tv("1", "Media Room TV"), Tv("2", "Alfred")), youTube = MovieSession.YouTubeState.FAILED), "https://youtu.be/dQw4w9WgXcQ", { _, _ -> }, {}, {})
    }
    @Test fun mirror() = shot { MirrorScreen({}, {}) }
    private val crash = CrashReporter.Entry("1", 1_763_456_941_000, "0.4.0", CrashReporter.Kind.CRASH,
        "NetworkOnMainThreadException", "TheatreSync 0.4.0 (5) crash report\n…", CrashReporter.Status.SAVED, null)
    private val handled = CrashReporter.Entry("2", 1_763_457_000_000, "0.4.2", CrashReporter.Kind.HANDLED,
        "IOException: YouTube answered 403", "…", CrashReporter.Status.SENT_GITHUB, 1_763_457_100_000)
    @Test fun setup6_crashCard() = shot { SetupScreen(Ui(), {}, {}, {}, {}, {}, {}, pendingCrash = crash) }
    @Test fun settings() = shot { SettingsScreen(2, {}, {}, {}, autoReports = true) }
    @Test fun headphones4_chooseOutput() = shot {
        HeadphonesScreen(
            listOf(
                DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH, 11),
                DiagnosticsReport.Output("Wired headphones", DiagnosticsReport.Kind.WIRED, 12),
                DiagnosticsReport.Output("Phone speaker", DiagnosticsReport.Kind.SPEAKER, 13),
            ),
            details, namesAllowed = true, onAllow = {}, onOpenBluetooth = {}, onBack = {}, preferredOutputId = 11,
        )
    }
    @Test fun headphones5_soundTestHeadphones() = shot {
        HeadphonesScreen(
            listOf(DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH, 11)),
            details, namesAllowed = true, onAllow = {}, onOpenBluetooth = {}, onBack = {},
            soundTest = SoundTestUi.Done(SoundTest.Result("WH-1000XM4", android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BLUETOOTH_A2DP")),
        )
    }
    @Test fun headphones6_soundTestWrongDevice() = shot {
        HeadphonesScreen(
            listOf(DiagnosticsReport.Output("WH-1000XM4", DiagnosticsReport.Kind.BLUETOOTH, 11)),
            details, namesAllowed = true, onAllow = {}, onOpenBluetooth = {}, onBack = {},
            soundTest = SoundTestUi.Answered(SoundTest.Result("Phone speaker", android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SPEAKER"), heard = false),
        )
    }
    @Test fun setup7_tvBusy() = setup(Ui(movieTitle = "Interstellar", tvs = listOf(Tv("1", "Media Room TV"), Tv("2", "Alfred")), nowCasting = listOf(youtube.copy(tvId = "1", tvName = "Media Room TV"))))
    @Test fun github1_connect() = shot { ConnectGitHubScreen(true, false, null, GitHubState.IDLE, {}, {}, {}, {}) }
    @Test fun github2_code() = shot {
        ConnectGitHubScreen(true, false, GitHubLink.DeviceCode("WDJB-MJHT", "https://github.com/login/device", "x", 5), GitHubState.WAITING, {}, {}, {}, {})
    }
    @Test fun github3_connected() = shot { ConnectGitHubScreen(true, true, null, GitHubState.DONE, {}, {}, {}, {}) }
    @Test fun crashTimeline() = shot { CrashReportsScreen(listOf(handled.copy(reference = "#3"), crash), autoReports = true, onSendGitHub = {}, onShare = {}, onBack = {}) }
    @Test fun crashTimelineEmpty() = shot { CrashReportsScreen(emptyList(), {}, {}, {}) }
}

/** Accessibility: the largest system font size (200 %) must not clip or overlap anything. */
class LargeFontScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(fontScale = 2.0f), theme = "android:Theme.Material.NoActionBar", maxPercentDifference = 0.01)

    @Test fun setup_largeFont() = paparazzi.snapshot {
        QuantariTheme { GridBackground { SetupScreen(Ui(movieTitle = "Interstellar", connectedTv = "Theatre Room TV"), {}, {}, {}, {}, {}, {}) } }
    }

    @Test fun playing_largeFont() = paparazzi.snapshot {
        QuantariTheme {
            GridBackground {
                PlayingScreen(
                    Ui(movieTitle = "Interstellar", connectedTv = "Theatre Room TV", phase = Phase.PLAYING, positionMs = 2_712_000, durationMs = 10_140_000, driftMs = 6, friends = 1, joinAddress = "192.168.1.23:41234"),
                    {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
    }
}

/** Tablets: content stays at a comfortable width, centred. */
class TabletScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_C, theme = "android:Theme.Material.NoActionBar", maxPercentDifference = 0.01)

    @Test fun setup_tablet() = paparazzi.snapshot {
        QuantariTheme { GridBackground { SetupScreen(Ui(movieTitle = "Interstellar", tvs = listOf(Tv("1", "Theatre Room TV"))), {}, {}, {}, {}, {}, {}) } }
    }
}
