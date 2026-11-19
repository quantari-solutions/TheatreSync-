package com.theatresync.app

import com.theatresync.app.CastCompat.Media
import com.theatresync.app.CastCompat.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    // ---- 0.4.7: surround sound and conversion plans (owner's Red Eye 720p H.264 + AAC 5.1)

    private val redEye = CastCompat.Media("video/avc", 1280, 720, 24f, "audio/mp4a-latm", 3.0, audioChannels = 6)

    @Test fun surroundAacNeedsFixingOnOlderChromecast() {
        val v = CastCompat.verdict(redEye, "Chromecast")
        assertFalse(v.playable)
        assertEquals(listOf(CastCompat.Problem.AUDIO), v.problems)
        assertTrue(CastCompat.verdict(redEye, "Chromecast Ultra").playable)
        assertTrue(CastCompat.verdict(redEye.copy(audioChannels = 2), "Chromecast").playable)
        assertTrue(redEye.label.contains("5.1 sound"))
    }

    @Test fun onlyTheSoundIsConvertedWhenThePictureIsFine() {
        val v = CastCompat.verdict(redEye, "Chromecast")
        // Original, or a quality at/above the movie's own, never re-encodes the picture.
        assertEquals(CastCompat.Plan.SOUND_ONLY, CastCompat.plan(redEye, v, null))
        assertEquals(CastCompat.Plan.SOUND_ONLY, CastCompat.plan(redEye, v, 1080))
        assertEquals(CastCompat.Plan.SOUND_ONLY, CastCompat.plan(redEye, v, 720))
        // Scaling down below the movie's own size does.
        assertEquals(CastCompat.Plan.FULL, CastCompat.plan(redEye, v, 480))
    }

    @Test fun compatibleMoviesPlayInstantly() {
        val ok = redEye.copy(audioChannels = 2)
        assertEquals(CastCompat.Plan.NONE, CastCompat.plan(ok, CastCompat.verdict(ok, "Chromecast"), null))
        assertEquals(CastCompat.Plan.NONE, CastCompat.plan(ok, CastCompat.verdict(ok, "Chromecast"), 1080))
    }

    @Test fun unplayablePictureNeedsFullConversion() {
        val hevc4k = CastCompat.Media("video/hevc", 3840, 2160, 24f, "audio/mp4a-latm", 40.0, audioChannels = 2)
        assertEquals(CastCompat.Plan.FULL, CastCompat.plan(hevc4k, CastCompat.verdict(hevc4k, "Chromecast"), null))
    }

    @Test fun downmixOnlyForSurround() {
        assertNull(stereoDownmix(2))
        assertNull(stereoDownmix(null))
        assertNotNull(stereoDownmix(6))
        assertNotNull(stereoDownmix(8))
    }
}
