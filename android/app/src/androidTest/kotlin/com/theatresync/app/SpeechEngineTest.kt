package com.theatresync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The offline speech recognizer for generated subtitles loads its native code on a real Android system. */
@RunWith(AndroidJUnit4::class)
class SpeechEngineTest {
    @Test fun voskNativeCodeLoads() {
        org.vosk.LibVosk.vosk_set_log_level(-1) // links libvosk through JNA, or throws UnsatisfiedLinkError
        // A missing model is reported as an ordinary error (proves calls reach the native library).
        val failed = runCatching { org.vosk.Model("/nonexistent-model") }.exceptionOrNull()
        assertTrue("expected an IOException, got $failed", failed is java.io.IOException)
    }
}
