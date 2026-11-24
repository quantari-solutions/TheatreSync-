package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastModelTest {
    @Test fun googleTvByItsRemoteService() {
        // Chromecast with Google TV reports its model as plain "Chromecast": the remote service tells them apart.
        assertEquals(CastModel.Kind.GOOGLE_TV, CastModel.classify("Chromecast", isSpeaker = false, hasRemoteService = true))
        assertTrue(CastModel.canHostHub(CastModel.Kind.GOOGLE_TV))
    }

    @Test fun classicChromecast() {
        assertEquals(CastModel.Kind.CHROMECAST, CastModel.classify("Chromecast Ultra", isSpeaker = false, hasRemoteService = false))
        assertFalse(CastModel.canHostHub(CastModel.Kind.CHROMECAST))
    }

    @Test fun byNameAndSpeakers() {
        assertEquals(CastModel.Kind.GOOGLE_TV, CastModel.classify("Google TV Streamer", false, false))
        assertEquals(CastModel.Kind.SPEAKER, CastModel.classify("Nest Audio", true, false))
        assertEquals(CastModel.Kind.OTHER, CastModel.classify(null, false, false))
    }
}
