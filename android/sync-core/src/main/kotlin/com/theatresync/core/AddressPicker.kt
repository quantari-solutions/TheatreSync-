package com.theatresync.core

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Chooses which of this phone's addresses to give the Chromecast.
 *
 * A phone can have several IPv4 addresses at once (home Wi-Fi, Wi-Fi Direct,
 * hotspot). The Chromecast can only reach the one on its own network, so prefer
 * an address in the same subnet as the TV; otherwise fall back to the first
 * candidate (previous behaviour).
 */
object AddressPicker {
    /** A local address with its network prefix length, e.g. 192.168.1.23/24. */
    data class Local(val address: Inet4Address, val prefixLength: Int)

    fun pick(candidates: List<Local>, tv: InetAddress?): Inet4Address? {
        if (candidates.isEmpty()) return null
        val tv4 = tv as? Inet4Address
        if (tv4 != null) {
            candidates.firstOrNull { sameSubnet(it.address, tv4, it.prefixLength) }?.let { return it.address }
        }
        return candidates.first().address
    }

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    private fun toInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
}
