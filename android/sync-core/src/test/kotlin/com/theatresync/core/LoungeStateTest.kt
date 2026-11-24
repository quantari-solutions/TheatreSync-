package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoungeStateTest {
    @Test fun readsTheNewestState() {
        val ev = """[[5,["nowPlaying",{"videoId":"dQw4w9WgXcQ","currentTime":"0","state":"3"}]],
            [6,["onStateChange",{"currentTime":"42.5","duration":"212.0","state":"1"}]]]"""
        val s = LoungeState.parse(ev)!!
        assertEquals(LoungeState.Play.PLAYING, s.play)
        assertEquals(42.5, s.positionSec!!, 0.01)
        assertEquals(212.0, s.durationSec!!, 0.01)
        assertEquals("dQw4w9WgXcQ", s.videoId)
    }

    @Test fun pausedAndNothing() {
        assertEquals(LoungeState.Play.PAUSED, LoungeState.parse("""["onStateChange",{"currentTime":"10","state":"2"}]""")!!.play)
        assertNull(LoungeState.parse("""[[1,["noop"]]]"""))
    }
}
