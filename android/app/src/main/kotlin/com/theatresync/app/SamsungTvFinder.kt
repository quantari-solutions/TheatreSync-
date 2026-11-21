package com.theatresync.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.theatresync.core.Dial

/**
 * Finds Samsung smart TVs (e.g. The Frame) by the "Samsung Multiscreen" service they announce on the
 * Wi-Fi (mDNS: _samsungmsf._tcp). Some Samsung TVs don't answer the usual DIAL search, but always
 * announce this. Each one found is checked for a YouTube app (DIAL at :8080/ws/apps/) before it's listed.
 */
class SamsungTvFinder(context: Context, private val onFound: (Dial.Device) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        runCatching { nsd.discoverServices("_samsungmsf._tcp", NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION") // resolveService still works on every version this app supports
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val ip = resolved.host?.hostAddress ?: return
                    val device = Dial.Device(
                        id = "samsung:$ip",
                        name = resolved.serviceName.ifBlank { "Samsung TV" },
                        appsUrl = "http://$ip:8080/ws/apps/",
                        manufacturer = "Samsung",
                        model = null,
                    )
                    Thread({
                        // Only TVs with YouTube (leaves soundbars and other Samsung devices out).
                        if (runCatching { Dial.youTubeState(device) }.isSuccess) onFound(device)
                    }, "samsung-check").apply { isDaemon = true }.start()
                }
            })
        }
    }
}
