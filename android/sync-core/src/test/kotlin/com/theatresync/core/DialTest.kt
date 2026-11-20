package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialTest {
    private val ssdp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
        "LOCATION: http://192.168.1.40:7678/nservice/\r\nSERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n" +
        "ST: urn:dial-multiscreen-org:service:dial:1\r\nUSN: uuid:abc::urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:dial-multiscreen-org:device:dialreceiver:1</deviceType>
        <friendlyName>[TV] Samsung The Frame (65)</friendlyName>
        <manufacturer>Samsung Electronics</manufacturer><modelName>QA65LS03B</modelName>
        <UDN>uuid:0ee2d4a5-0000-1000-8000-a0d0dc000000</UDN>
        </device></root>"""

    @Test fun findsLocationInSsdpAnswer() {
        assertEquals("http://192.168.1.40:7678/nservice/", Dial.locationFrom(ssdp))
        assertNull(Dial.locationFrom(ssdp.replace("dial-multiscreen-org:service:dial:1", "schemas-upnp-org:device:MediaRenderer:1")))
    }

    @Test fun parsesSamsungFrame() {
        val d = Dial.deviceFrom("http://192.168.1.40:7678/nservice/", description, "http://192.168.1.40:8080/ws/apps")!!
        assertEquals("[TV] Samsung The Frame (65)", d.name)
        assertEquals("http://192.168.1.40:8080/ws/apps/", d.appsUrl)
        assertEquals("uuid:0ee2d4a5-0000-1000-8000-a0d0dc000000", d.id)
        assertFalse(d.isChromecast)
    }

    @Test fun needsApplicationUrl() {
        assertNull(Dial.deviceFrom("http://x/", description, null))
    }

    @Test fun recognisesChromecastsAnsweringDial() {
        val cc = description.replace("Samsung Electronics", "Google Inc.").replace("QA65LS03B", "Eureka Dongle")
        assertTrue(Dial.deviceFrom("http://x/", cc, "http://x:8008/apps/")!!.isChromecast)
    }

    @Test fun readsYouTubeScreenId() {
        val running = """<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">
            <name>YouTube</name><options allowStop="true"/><state>running</state>
            <additionalData><screenId>abc123lounge</screenId><theme>cl</theme></additionalData></service>"""
        assertEquals(Dial.AppState(true, "abc123lounge"), Dial.appStateFrom(running))
        assertEquals(Dial.AppState(false, null), Dial.appStateFrom("<service><name>YouTube</name><state>stopped</state></service>"))
    }

    @Test fun searchRequestIsValidSsdp() {
        val r = Dial.searchRequest()
        assertTrue(r.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(r.contains("ST: urn:dial-multiscreen-org:service:dial:1"))
        assertTrue(r.endsWith("\r\n\r\n"))
    }
}
