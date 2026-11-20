package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    private val srt = "﻿1\r\n00:00:01,500 --> 00:00:04,000\r\nHello there.\r\n\r\n2\r\n00:01:02,000 --> 00:01:05,250\r\n<font color=\"#ffff00\">Two</font>\r\nlines\r\n\r\n"

    @Test fun parsesSrtWithBomAndCrlf() {
        val cues = Subtitles.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(Subtitles.Cue(1_500, 4_000, "Hello there."), cues[0])
        assertEquals(62_000L, cues[1].startMs)
        assertEquals("Two\nlines", cues[1].text)
    }

    @Test fun writesVttTheChromecastAccepts() {
        val vtt = Subtitles.toVtt(Subtitles.parse(srt))
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:04.000\nHello there."))
        assertEquals(Subtitles.parse(srt), Subtitles.parse(vtt))
    }

    @Test fun roundTripsSrt() = assertEquals(Subtitles.parse(srt), Subtitles.parse(Subtitles.toSrt(Subtitles.parse(srt))))

    @Test fun parsesVttShortTimesAndSettings() {
        val cues = Subtitles.parse("WEBVTT\n\n01:02.500 --> 01:04.000 align:start\nHi\n")
        assertEquals(listOf(Subtitles.Cue(62_500, 64_000, "Hi")), cues)
    }

    @Test fun splitsLongTextIntoReadableCues() {
        val words = (1..40).joinToString(" ") { "word$it" }
        val cues = Subtitles.split(10_000, 20_000, words)
        assertTrue(cues.size >= 3)
        assertEquals(10_000L, cues.first().startMs)
        assertTrue(cues.last().endMs <= 20_000)
        assertTrue(cues.all { c -> c.text.split('\n').all { it.length <= 50 } })
    }
}
