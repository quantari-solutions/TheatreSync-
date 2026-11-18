package com.theatresync.app

import com.theatresync.app.CastCompat.Media
import com.theatresync.app.CastCompat.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastCompatTest {
    private val phone4kHevc = Media("video/hevc", 3840, 2160, 30f, "audio/mp4a-latm", 48.0)
    private val phone1080Avc = Media("video/avc", 1920, 1080, 30f, "audio/mp4a-latm", 17.0)

    @Test fun olderChromecastCantShowPhoneHevc4k() {
        val v = CastCompat.verdict(phone4kHevc, "Chromecast")
        assertFalse(v.playable)
        assertTrue(Problem.CODEC in v.problems)
        assertTrue(Problem.RESOLUTION in v.problems)
    }

    @Test fun ultraCanShowHevc4kButWarnsNothing() {
        val v = CastCompat.verdict(phone4kHevc, "Chromecast Ultra")
        assertTrue(v.playable)
        assertTrue(v.problems.isEmpty())
    }

    @Test fun h264At1080pPlaysEverywhere() {
        assertEquals(CastCompat.Verdict(true, emptyList()), CastCompat.verdict(phone1080Avc, "Chromecast"))
    }

    @Test fun portraitPhoneVideoIsJudgedBySides() {
        assertTrue(CastCompat.verdict(phone1080Avc.copy(width = 1080, height = 1920), "Chromecast").playable)
    }

    @Test fun highBitrateIsPlayableButFlagged() {
        val v = CastCompat.verdict(phone1080Avc.copy(mbps = 35.0), "Chromecast")
        assertTrue(v.playable)
        assertEquals(listOf(Problem.BITRATE), v.problems)
    }

    @Test fun ac3AudioNeedsConversionOnOlderChromecast() {
        val v = CastCompat.verdict(phone1080Avc.copy(audioMime = "audio/ac3"), "Chromecast")
        assertFalse(v.playable)
        assertEquals(listOf(Problem.AUDIO), v.problems)
    }

    @Test fun labelIsReadable() {
        assertEquals("4K · H.265 · 30 fps · 48 Mbit/s", phone4kHevc.label)
    }

    @Test fun scalingKeepsShapeAndNeverUpscales() {
        assertEquals(1920 to 1080, sizeFor(3840, 2160, 1080))
        assertEquals(1080 to 1920, sizeFor(2160, 3840, 1080)) // portrait phone video
        assertEquals(1280 to 720, sizeFor(1920, 1080, 720))
        assertEquals(null, sizeFor(1280, 720, 1080)) // already smaller: keep
        assertEquals(null, sizeFor(1920, 1080, null)) // original quality
    }
}
