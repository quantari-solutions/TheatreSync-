package com.theatresync.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
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
    /** TapIn guests on the host: sync for each, sound/volume/remove; long names stay on one line. */
    @Test fun guests1() = shot {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            val g = com.theatresync.core.Guests.Guest("web-1", "Priya", "AirPods Pro", 0, 0, 14, "playing", hidden = false)
            GuestRow(g, { _, _ -> }, { _, _ -> }, {})
            GuestRow(g.copy(id = "web-2", name = "Android phone · Samsung Internet with a very long name", device = null, driftMs = 230, muted = true, volume = 60), { _, _ -> }, { _, _ -> }, {})
            GuestRow(g.copy(id = "web-3", name = "iPhone · Safari", device = "WH-1000XM4", hidden = true, driftMs = -8), { _, _ -> }, { _, _ -> }, {})
        }
    }
    @Test fun setup2_movieChosen_tvsFound() = setup(Ui(movieTitle = "Interstellar", tvs = listOf(Tv("1", "Theatre Room TV"), Tv("2", "Bedroom TV"))))
    @Test fun setup3_ready() = setup(Ui(movieTitle = "Interstellar", connectedTv = "Theatre Room TV"))
    @Test fun setup8_needsConversion() = setup(
        Ui(
            movieTitle = "Interstellar", connectedTv = "Media Room TV",
            movieInfo = CastCompat.Media("video/hevc", 3840, 2160, 24f, "audio/eac3", 48.0).label,
            movieVerdict = CastCompat.Verdict(false, listOf(CastCompat.Problem.CODEC, CastCompat.Problem.RESOLUTION)),
            quality = Quality.P1080,
        ),
    )
    @Test fun setup9_preparing() = setup(
        Ui(movieTitle = "Interstellar", connectedTv = "Media Room TV", quality = Quality.P1080, prepareProgress = 42),
    )
    @Test fun setup10_surroundSound() = setup(
        Ui(
            movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV",
            movieInfo = CastCompat.Media("video/avc", 1280, 720, 24f, "audio/mp4a-latm", 3.0, 6).label,
            movieVerdict = CastCompat.Verdict(false, listOf(CastCompat.Problem.AUDIO)),
            moviePlan = CastCompat.Plan.SOUND_ONLY,
        ),
    )
    @Test fun setup11_fixingSound() = setup(
        Ui(movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV", prepareProgress = 64, prepareEtaSec = 38, prepareSoundOnly = true),
    )
    @Test fun remote1() = shot {
        RemoteScreen(
            Ui(tvs = listOf(Tv("1", "Media Room TV"), Tv("2", "Projector")), connectedTv = "Media Room TV", nowCasting = listOf(youtube.copy(activity = youtube.activity.copy(volumeLevel = 0.4, muted = false)))),
            {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {},
        )
    }
    /** Long and many TV names must never overflow or wrap letter by letter. */
    @Test fun youtube3_smartTvLongNames() = shot {
        YouTubeScreen(
            Ui(tvs = listOf(Tv("1", "Media Room TV"), Tv("2", "Living Room"), Tv("dial:x", "Samsung The Frame (65)", youTubeOnly = true), Tv("4", "Upstairs Bedroom Projector With A Very Long Name"))),
            "https://youtu.be/dQw4w9WgXcQ", { _, _ -> }, {}, {},
        )
    }
    @Test fun setup12_moviePlayingCard() = setup(
        Ui(movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV", phase = Phase.PLAYING, positionMs = 600_000, durationMs = 5_130_000),
    )
    @Test fun headphones3_settings() = shot {
        HeadphoneSettingsScreen(
            Ui(preferredOutputId = 12, soundDelayMs = 20, driftMs = 8, fx = HeadphoneFx.withPreset(HeadphoneFx.Settings(), HeadphoneFx.Preset.CINEMA)),
            listOf(DiagnosticsReport.Output("MOMENTUM 4", DiagnosticsReport.Kind.BLUETOOTH, 12), DiagnosticsReport.Output("Phone speaker", DiagnosticsReport.Kind.SPEAKER, 2)),
            {}, {}, {}, {}, {}, {}, {},
        )
    }
    @Test fun setup13_surroundInstant() = setup(
        Ui(
            movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV",
            movieInfo = CastCompat.Media("video/avc", 1280, 720, 24f, "audio/mp4a-latm", 3.0, 6).label,
            movieVerdict = CastCompat.Verdict(false, listOf(CastCompat.Problem.AUDIO)),
            moviePlan = CastCompat.Plan.SOUND_ONLY,
        ),
    )
    @Test fun experimental1() = shot { ExperimentalScreen(Ui(signatureIntro = true), {}, {}) }
    @Test fun multiAudio1_unsupported() = shot { MultiAudioScreen(AudioSharing.UNSUPPORTED, listOf("MOMENTUM 4", "WH-1000XM4"), {}, {}, {}) }
    @Test fun casting4_upNext() = shot {
        NowCastingScreen(
            youtube, {}, {}, {},
            upNext = listOf(
                MovieSession.UpNextVideo("aaaaaaaaaaa", "Interstellar – Official Trailer 2", "Warner Bros. Pictures"),
                MovieSession.UpNextVideo("bbbbbbbbbbb", "Hans Zimmer – No Time for Caution (Live)", "Hans Zimmer"),
            ),
        )
    }
    @Test fun setup14_smartTvListed() = setup(Ui(movieTitle = "Interstellar", tvs = listOf(Tv("1", "Media Room TV"), Tv("dial:x", "Samsung The Frame (65)", youTubeOnly = true))))
    @Test fun setup15_subtitlesGenerating() = setup(Ui(movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV", subtitleProgress = 37, subtitleEtaSec = 540))
    @Test fun setup16_subtitlesLoaded() = setup(Ui(movieTitle = "Red Eye (2005)", connectedTv = "Media Room TV", subtitleName = "Red.Eye.2005.en.srt"))
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
    @Test fun casting3_youtubeVolume() = shot { NowCastingScreen(youtube.copy(activity = youtube.activity.copy(volumeLevel = 0.6, muted = false)), {}, {}, {}) }
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
    @Test fun github3_connected() = shot { ConnectGitHubScreen(true, true, null, GitHubState.DONE, {}, {}, {}, {}, lastUpload = "Uploaded #21 · 18 Nov 2026, 22:14") }
    @Test fun github4_setup() = shot { ConnectGitHubScreen(false, false, null, GitHubState.IDLE, {}, {}, {}, {}) }
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
