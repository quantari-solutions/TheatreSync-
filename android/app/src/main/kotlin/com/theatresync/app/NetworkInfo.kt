package com.theatresync.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.theatresync.core.AddressPicker
import java.net.Inet4Address
import java.net.InetAddress

object NetworkInfo {
    /**
     * All of this phone's IPv4 addresses on Wi-Fi/Ethernet (with prefix lengths).
     * Skips VPNs: with a VPN on, the tunnel's address is unreachable for the Chromecast.
     */
    fun candidates(context: Context): List<AddressPicker.Local> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }.flatMap { n ->
            cm.getLinkProperties(n)?.linkAddresses.orEmpty().mapNotNull { la ->
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) AddressPicker.Local(a, la.prefixLength) else null
            }
        }
    }

    /** The address the Chromecast at [tv] can reach: same subnet first, else the first candidate. */
    fun localIpv4For(context: Context, tv: InetAddress?): String? =
        AddressPicker.pick(candidates(context), tv)?.hostAddress

    /** Without a TV to match (diagnostics, friends' address). */
    fun localIpv4(context: Context): String? = localIpv4For(context, null)
}
