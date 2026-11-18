package com.theatresync.app

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.theatresync.app.MovieSession.Phase
import com.theatresync.app.MovieSession.Tv
import com.theatresync.app.MovieSession.Ui
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

    @Test fun diagnostics() = shot { DiagnosticsScreen("TheatreSync 0.2.0 · protocol v1\nDevice: motorola moto g\n\nAudio outputs (2)\n  • WH-1000XM4\n    type=8 route=BLUETOOTH_A2DP", {}, {}) }
}
