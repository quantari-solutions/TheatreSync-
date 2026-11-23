package com.theatresync.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SamsungTvTest {
    private val frame = """{"device":{"FrameTVSupport":"true","name":"[TV] Samsung The Frame 55","type":"Samsung SmartTV","ip":"192.168.1.40"},"name":"[TV] Samsung The Frame 55","type":"Samsung SmartTV","version":"2.0.25"}"""

    @Test fun readsTheFrame() {
        val i = SamsungTv.parse(frame)!!
        assertEquals("Samsung The Frame 55", i.name)
        assertTrue(i.isFrame)
    }

    @Test fun ignoresOtherSamsungDevices() {
        assertNull(SamsungTv.parse("""{"device":{"name":"Soundbar","type":"Samsung Speaker"}}"""))
        assertNull(SamsungTv.parse("not json"))
    }

    @Test fun dialAddressUsesPort8080() {
        assertEquals("http://192.168.1.40:8080/ws/apps/", SamsungTv.device("192.168.1.40", "Frame").appsUrl)
    }
}
