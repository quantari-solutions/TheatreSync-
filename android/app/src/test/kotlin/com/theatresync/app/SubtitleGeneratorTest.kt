package com.theatresync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleGeneratorTest {
    @Test fun stereoIsAveraged() {
        assertEquals(listOf<Short>(150, 350), SubtitleGenerator.downmix(shortArrayOf(100, 200, 300, 400), 2).toList())
    }

    @Test fun surroundKeepsMostlyTheDialogueChannel() {
        // FL, FR, FC (dialogue), LFE, BL, BR: loud music on the sides, speech in the centre.
        val frame = shortArrayOf(1000, 1000, 10000, 8000, 1000, 1000)
        assertEquals(7300, SubtitleGenerator.downmix(frame, 6)[0].toInt())
        // Never overflows.
        assertEquals(Short.MAX_VALUE, SubtitleGenerator.downmix(shortArrayOf(32767, 32767, 32767, 0, 0, 0), 6)[0])
    }

    @Test fun resampleTo16k() {
        val out = Resampler().process(ShortArray(48_000) { 1000 }, 48_000, 16_000)
        assertTrue(out.size in 15_990..16_010)
    }

    @Test fun everyOfferedLanguageHasASpeechPack() {
        listOf("en", "hi", "gu", "es", "fr", "de", "ja", "ko").forEach { assertTrue(it, SubtitleGenerator.PACKS.containsKey(it)) }
    }
}
