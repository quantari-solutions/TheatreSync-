package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCuesTest {
    // Real output of Vosk (small English model) for a synthetic two-sentence clip.
    private val first = """{"result": [{"conf": 1.0, "end": 0.57, "start": 0.15, "word": "hello"}, {"conf": 1.0, "end": 0.72, "start": 0.57, "word": "and"}, {"conf": 1.0, "end": 1.17, "start": 0.72, "word": "welcome"}, {"conf": 1.0, "end": 1.29, "start": 1.17, "word": "to"}, {"conf": 1.0, "end": 1.38, "start": 1.29, "word": "the"}, {"conf": 1.0, "end": 1.98, "start": 1.38, "word": "theater"}, {"conf": 1.0, "end": 2.58, "start": 2.13, "word": "tonight"}, {"conf": 1.0, "end": 2.76, "start": 2.61, "word": "we"}, {"conf": 1.0, "end": 2.94, "start": 2.76, "word": "are"}, {"conf": 1.0, "end": 3.39, "start": 2.94, "word": "watching"}, {"conf": 1.0, "end": 3.45, "start": 3.39, "word": "a"}, {"conf": 1.0, "end": 3.75, "start": 3.45, "word": "movie"}, {"conf": 1.0, "end": 4.29, "start": 3.75, "word": "together"}], "text": "hello and welcome to the theater tonight we are watching a movie together"}"""
    private val second = """{"result": [{"conf": 1.0, "end": 6.21, "start": 6.06, "word": "the"}, {"conf": 0.648349, "end": 6.72, "start": 6.21, "word": "headphone"}, {"conf": 1.0, "end": 7.17, "start": 6.741099, "word": "stay"}, {"conf": 1.0, "end": 7.29, "start": 7.17, "word": "in"}, {"conf": 1.0, "end": 7.59, "start": 7.29, "word": "sync"}], "text": "the headphone stay in sync"}"""

    @Test fun wordsAreReadWithTheirTimes() {
        val w = SpeechCues.words(first)
        assertEquals(13, w.size)
        assertEquals(SpeechCues.Word(150, 570, "hello"), w[0])
        assertEquals(10_150L, SpeechCues.words(first, offsetMs = 10_000)[0].startMs)
        assertTrue(SpeechCues.words("""{"text": ""}""").isEmpty())
        assertTrue(SpeechCues.words("not json").isEmpty())
    }

    @Test fun pausesStartNewLinesAndTimingFollowsSpeech() {
        val cues = SpeechCues.cues(SpeechCues.words(first) + SpeechCues.words(second))
        assertEquals(2, cues.size)
        assertEquals("Hello and welcome to the theater tonight we are watching a movie together.", cues[0].text)
        assertEquals(150L, cues[0].startMs)
        assertEquals(4_290L, cues[0].endMs)
        assertEquals("The headphone stay in sync.", cues[1].text)
        assertEquals(6_060L, cues[1].startMs)
    }

    @Test fun longSpeechIsSplitIntoReadableLines() {
        val words = (0 until 40).map { SpeechCues.Word(it * 300L, it * 300L + 250, "word$it") }
        val cues = SpeechCues.cues(words)
        assertTrue(cues.size > 1)
        cues.forEach { assertTrue(it.text, it.text.length <= 85); assertTrue(it.endMs - it.startMs <= 6_300) }
        cues.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
    }

    @Test fun unknownWordsAreDropped() {
        val w = SpeechCues.words("""{"result":[{"word":"[unk]","start":0,"end":0.2},{"word":"yes","start":0.3,"end":0.5}]}""")
        assertEquals(listOf("yes"), w.map { it.text })
    }
}
