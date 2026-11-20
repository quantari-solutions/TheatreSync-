package com.theatresync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SubtitleGeneratorTest {
    private val rate = 16_000
    private fun tone(ms: Int) = ShortArray(rate * ms / 1000) { (8000 * sin(2 * PI * 300 * it / rate)).toInt().toShort() }
    private fun silence(ms: Int) = ShortArray(rate * ms / 1000)

    @Test fun cutsSpeechAtPausesWithCorrectTimes() {
        val c = SpeechChunker(rate)
        val audio = silence(1_000) + tone(2_000) + silence(800) + tone(3_000) + silence(800)
        val chunks = c.add(audio) + listOfNotNull(c.flush())
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].startMs in 950L..1_050L)
        assertTrue(chunks[1].startMs in 3_750L..3_850L)
        assertTrue(chunks[1].endMs - chunks[1].startMs in 3_000L..3_500L)
    }

    @Test fun longSpeechIsSplitAtTwelveSeconds() {
        val c = SpeechChunker(rate)
        val chunks = c.add(tone(30_000)) + listOfNotNull(c.flush())
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.endMs - it.startMs <= 12_100 })
    }

    @Test fun silenceProducesNothing() {
        val c = SpeechChunker(rate)
        assertTrue((c.add(silence(10_000)) + listOfNotNull(c.flush())).isEmpty())
    }

    @Test fun downmixAndResample() {
        assertEquals(listOf<Short>(150, 350), SubtitleGenerator.downmix(shortArrayOf(100, 200, 300, 400), 2).toList())
        val out = Resampler().process(ShortArray(48_000) { 1000 }, 48_000, 16_000)
        assertTrue(out.size in 15_990..16_010)
    }
}
