package com.theatresync.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import com.theatresync.core.CastActivity
import com.theatresync.core.CastWatcher
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Watches every Chromecast on the Wi-Fi and reports what each is showing
 * (YouTube, a mirrored tab, …), including casts started from other devices.
 */
class TvWatchers(private val onChange: (List<NowCasting>) -> Unit) {
    data class Tv(val id: String, val name: String, val address: InetAddress, val port: Int)
    data class NowCasting(val tvId: String, val tvName: String, val activity: CastActivity)

    private val main = Handler(Looper.getMainLooper())
    private val watchers = HashMap<String, Pair<Tv, CastWatcher>>()
    private val latest = HashMap<String, CastActivity>()

    /** Call on the main thread with the TVs currently visible. Starts/stops watchers to match. */
    fun update(tvs: List<Tv>) {
        val wanted = tvs.associateBy { it.id }
        (watchers.keys - wanted.keys).forEach { id -> stop(id) }
        for (tv in wanted.values) {
            val existing = watchers[tv.id]
            if (existing != null && existing.first.address == tv.address && existing.first.port == tv.port) continue
            existing?.let { stop(tv.id) }
            val w = CastWatcher(
                connect = { openTls(tv.address, tv.port) },
                onUpdate = { a -> main.post { onActivity(tv.id, a) } },
                pollMs = 2_000,
            ).start()
            watchers[tv.id] = tv to w
        }
    }

    fun stopAll() {
        watchers.keys.toList().forEach { stop(it) }
    }

    fun playPause(tvId: String, play: Boolean) = watchers[tvId]?.second?.playPause(play)

    fun stopCasting(tvId: String) = watchers[tvId]?.second?.stopApp()

    fun pollNow(tvId: String) = watchers[tvId]?.second?.pollNow()

    fun setVolume(tvId: String, level: Double) = watchers[tvId]?.second?.setVolume(level)

    fun setMuted(tvId: String, muted: Boolean) = watchers[tvId]?.second?.setMuted(muted)

    fun seekTo(tvId: String, seconds: Double) = watchers[tvId]?.second?.seekTo(seconds.coerceAtLeast(0.0))

    fun seekBy(tvId: String, deltaSec: Double) {
        val w = watchers[tvId]?.second ?: return
        val now = w.current?.currentTimeSec ?: return
        w.seekTo((now + deltaSec).coerceAtLeast(0.0))
    }

    private fun stop(id: String) {
        watchers.remove(id)?.second?.close()
        if (latest.remove(id) != null) emit()
    }

    private fun onActivity(tvId: String, a: CastActivity?) {
        if (!watchers.containsKey(tvId)) return
        if (a == null) latest.remove(tvId) else latest[tvId] = a
        emit()
    }

    private fun emit() {
        onChange(latest.mapNotNull { (id, a) -> watchers[id]?.first?.let { NowCasting(id, it.name, a) } })
    }

    companion object {
        /**
         * Chromecasts use self-signed certificates on the local network, so the normal
         * certificate check can't apply. This trust manager is used ONLY for this
         * socket to a Chromecast found on the user's own Wi-Fi, never for internet traffic.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val chromecastTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        private val tls: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(chromecastTrust), null) }
        }

        fun openTls(address: InetAddress, port: Int): Socket {
            val raw = Socket()
            raw.connect(InetSocketAddress(address, port), 5_000)
            val s = tls.socketFactory.createSocket(raw, address.hostAddress, port, true) as SSLSocket
            s.startHandshake()
            return s
        }
    }
}
