package com.theatresync.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theatresync.core.MediaServer
import com.theatresync.core.Payload
import com.theatresync.core.Platform
import com.theatresync.core.Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OnDeviceSyncTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The phone serves a picked file over HTTP exactly as the Chromecast will request it. */
    @Test
    fun serverStreamsPickedFileWithRanges() {
        val file = TestMedia.wav(context.cacheDir, 2)
        val source = ContentUriSource.from(context, Uri.fromFile(file))
        assertNotNull(source)
        val server = MediaServer(
            "device-test-1", { SystemClock.elapsedRealtime() }, { null },
            { Payload.Hello("Test", Platform.ANDROID, Role.HOST, "0.2.0", listOf(1)) },
        )
        server.media = source
        val port = server.start()
        try {
            val c = URL("http://127.0.0.1:$port/media").openConnection() as HttpURLConnection
            c.setRequestProperty("Range", "bytes=100-199")
            assertEquals(206, c.responseCode)
            assertArrayEquals(file.readBytes().copyOfRange(100, 200), c.inputStream.readBytes())
        } finally {
            server.stop()
        }
    }

    /**
     * Real audio playback on Android: the headphone player starts 300 ms off a
     * simulated TV clock and must lock on within a few seconds and stay locked.
     */
    @Test
    fun headphonePlayerLocksOntoTvClock() {
        val file = TestMedia.wav(context.cacheDir, 20)
        lateinit var player: HeadphonePlayer
        instrumentation.runOnMainSync {
            player = HeadphonePlayer(context, Uri.fromFile(file), object : HeadphonePlayer.Events {
                override fun onShouldPause() {}
                override fun onInterrupted(interrupted: Boolean) {}
                override fun onError(message: String) = throw AssertionError(message)
            })
        }
        val tvStart = SystemClock.elapsedRealtime()
        fun tvPosition() = 2_000 + (SystemClock.elapsedRealtime() - tvStart) // TV is 2 s into the "movie"
        var startedAhead = false
        val drifts = mutableListOf<Long>()
        try {
            val end = tvStart + 12_000
            while (SystemClock.elapsedRealtime() < end) {
                instrumentation.runOnMainSync {
                    // First call positions the player 300 ms ahead to prove correction works.
                    val target = if (!startedAhead) tvPosition() + 300 else tvPosition()
                    startedAhead = true
                    player.follow(target, shouldPlay = true)
                    if (SystemClock.elapsedRealtime() - tvStart > 6_000) player.lastDriftMs?.let { drifts += it }
                }
                SystemClock.sleep(200)
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
        assertTrue("player never reported drift — audio did not play", drifts.size >= 10)
        val worst = drifts.maxOf { abs(it) }
        assertTrue("worst drift after settling was $worst ms (samples $drifts)", worst <= 80)
    }
}
