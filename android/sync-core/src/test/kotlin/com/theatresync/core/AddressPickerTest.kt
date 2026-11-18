package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class AddressPickerTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun local(s: String, prefix: Int = 24) = AddressPicker.Local(ip(s), prefix)

    @Test fun prefersAddressOnTheTvsNetwork() {
        // Wi-Fi Direct listed first, home Wi-Fi second: must pick home Wi-Fi.
        val picked = AddressPicker.pick(listOf(local("192.168.49.1"), local("192.168.1.23")), ip("192.168.1.40"))
        assertEquals(ip("192.168.1.23"), picked)
    }

    @Test fun respectsPrefixLength() {
        val picked = AddressPicker.pick(listOf(local("10.0.0.5", 8), local("172.16.0.9", 16)), ip("10.20.30.40"))
        assertEquals(ip("10.0.0.5"), picked)
    }

    @Test fun fallsBackToFirstWhenNoneMatch() {
        assertEquals(ip("192.168.49.1"), AddressPicker.pick(listOf(local("192.168.49.1"), local("10.0.0.2")), ip("172.20.1.1")))
    }

    @Test fun fallsBackToFirstWhenTvUnknown() {
        assertEquals(ip("192.168.1.23"), AddressPicker.pick(listOf(local("192.168.1.23")), null))
    }

    @Test fun emptyIsNull() = assertNull(AddressPicker.pick(emptyList(), ip("192.168.1.40")))

    @Test fun subnetMaths() {
        assertTrue(AddressPicker.sameSubnet(ip("192.168.1.23"), ip("192.168.1.254"), 24))
        assertFalse(AddressPicker.sameSubnet(ip("192.168.1.23"), ip("192.168.2.1"), 24))
        assertTrue(AddressPicker.sameSubnet(ip("192.168.1.23"), ip("192.168.2.1"), 16))
        assertFalse(AddressPicker.sameSubnet(ip("192.168.1.23"), ip("192.168.1.23"), 33))
    }
}
