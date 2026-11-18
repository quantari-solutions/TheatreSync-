package com.theatresync.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Announces the movie on the Wi-Fi (Bonjour / DNS-SD) so friends' iPhones find it
 * without typing anything. The TXT record carries the address so iOS can connect by IP.
 */
class HostAnnouncer(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun announce(name: String, ip: String, port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("ip", ip)
            setAttribute("port", port.toString())
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd?.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_theatresync._tcp"
    }
}
