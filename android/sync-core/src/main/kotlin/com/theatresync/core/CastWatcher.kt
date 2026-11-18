package com.theatresync.core

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one connection to a Chromecast and reports what it's showing.
 * Runs on its own thread; call [close] to stop. Reconnects on errors.
 *
 * @param connect opens the socket (TLS to port 8009 in the app; plain in tests)
 * @param onUpdate called (on the watcher thread) whenever the activity changes; null = TV idle
 */
class CastWatcher(
    private val connect: () -> Socket,
    private val onUpdate: (CastActivity?) -> Unit,
    private val pollMs: Long = 4_000,
    private val retryMs: Long = 5_000,
) : Closeable {
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    private val requestId = AtomicInteger(1)
    private val sender = "sender-ts"
    private val thread = Thread({ loop() }, "cast-watcher").apply { isDaemon = true }

    @Volatile var current: CastActivity? = null
        private set

    @Volatile private var pollRequested = false

    /** Ask the TV for its status right away (e.g. just after we started something on it). */
    fun pollNow() { pollRequested = true }

    fun start(): CastWatcher { thread.start(); return this }

    /**
     * Stops watching. Safe to call from the main thread: closing a TLS socket sends a
     * close_notify message to the Chromecast, which is network I/O, and Android kills
     * apps that do network I/O on the main thread (NetworkOnMainThreadException: the
     * 0.4.0 crash when opening the file picker). So the socket is closed on a background
     * thread, never on the caller's.
     */
    override fun close() {
        running = false
        val s = socket
        thread.interrupt()
        if (s != null) {
            Thread({ try { s.close() } catch (_: Exception) {} }, "cast-watcher-close").apply { isDaemon = true }.start()
        }
    }

    /** Sends PLAY or PAUSE to the media session on the TV (works for YouTube and other media apps). */
    fun playPause(play: Boolean) = mediaCommand(if (play) """{"type":"PLAY"""" else """{"type":"PAUSE"""")

    /** Stops whatever is playing on the TV (closes the app there; the TV returns to its idle screen). */
    fun stopApp() {
        val session = current?.sessionId ?: return
        val s = socket ?: return
        Thread {
            try {
                synchronized(this) {
                    CastMessage.write(s.getOutputStream(), CastMessage(sender, "receiver-0", CastMessage.NS_RECEIVER,
                        """{"type":"STOP","sessionId":"$session","requestId":${requestId.incrementAndGet()}}"""))
                }
            } catch (_: IOException) {}
        }.start()
    }

    /** Seeks the media session to [seconds]. */
    fun seekTo(seconds: Double) = mediaCommand("""{"type":"SEEK","currentTime":$seconds""")

    private fun mediaCommand(prefix: String) {
        val a = current ?: return
        val session = a.mediaSessionId ?: return
        val transport = a.transportId ?: return
        val s = socket ?: return
        Thread {
            try {
                synchronized(this) {
                    CastMessage.write(s.getOutputStream(), CastMessage(sender, transport, CastMessage.NS_MEDIA,
                        """$prefix,"mediaSessionId":$session,"requestId":${requestId.incrementAndGet()}}"""))
                }
            } catch (_: IOException) {}
        }.start()
    }

    private fun send(s: Socket, dest: String, ns: String, payload: String) = synchronized(this) {
        CastMessage.write(s.getOutputStream(), CastMessage(sender, dest, ns, payload))
    }

    private fun publish(a: CastActivity?) {
        if (a != current) {
            current = a
            onUpdate(a)
        }
    }

    private fun loop() {
        while (running) {
            try {
                val s = connect()
                socket = s
                s.soTimeout = 1_000
                session(s)
            } catch (_: Exception) {
                // fall through to retry
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                socket = null
            }
            if (!running) break
            publish(null)
            try { Thread.sleep(retryMs) } catch (_: InterruptedException) { break }
        }
    }

    private fun session(s: Socket) {
        val input = s.getInputStream()
        send(s, "receiver-0", CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
        var connectedTransport: String? = null
        var lastPoll = 0L
        var lastPing = 0L
        var lastHeard = System.currentTimeMillis()
        var base: CastActivity? = null
        while (running) {
            val now = System.currentTimeMillis()
            if (pollRequested || now - lastPoll >= pollMs) {
                pollRequested = false
                send(s, "receiver-0", CastMessage.NS_RECEIVER, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}""")
                connectedTransport?.let { t ->
                    send(s, t, CastMessage.NS_MEDIA, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}""")
                }
                lastPoll = now
            }
            if (now - lastPing >= 5_000) {
                send(s, "receiver-0", CastMessage.NS_HEARTBEAT, """{"type":"PING"}""")
                lastPing = now
            }
            if (now - lastHeard > 20_000) throw IOException("Chromecast stopped answering")
            val msg = try { CastMessage.read(input) } catch (_: SocketTimeoutException) { continue }
            lastHeard = System.currentTimeMillis()
            when (msg.namespace) {
                CastMessage.NS_HEARTBEAT -> if (CastStatus.type(msg.payloadUtf8) == "PING") {
                    send(s, msg.sourceId, CastMessage.NS_HEARTBEAT, """{"type":"PONG"}""")
                }
                CastMessage.NS_RECEIVER -> if (CastStatus.type(msg.payloadUtf8) == "RECEIVER_STATUS") {
                    val app = CastStatus.parseReceiver(msg.payloadUtf8)
                    if (app == null) {
                        base = null
                        connectedTransport = null
                        publish(null)
                    } else {
                        val sameApp = base?.appId == app.appId && base?.transportId == app.transportId
                        base = if (sameApp) current?.copy(statusText = app.statusText, appName = app.appName) ?: app else app
                        if (app.hasMedia && app.transportId != null && app.transportId != connectedTransport) {
                            connectedTransport = app.transportId
                            send(s, app.transportId, CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
                            send(s, app.transportId, CastMessage.NS_MEDIA, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}""")
                        }
                        publish(base)
                    }
                }
                CastMessage.NS_MEDIA -> if (CastStatus.type(msg.payloadUtf8) == "MEDIA_STATUS") {
                    base?.let { b ->
                        val merged = CastStatus.mergeMedia(current ?: b, msg.payloadUtf8)
                        base = merged
                        publish(merged)
                    }
                }
                CastMessage.NS_CONNECTION -> if (CastStatus.type(msg.payloadUtf8) == "CLOSE" && msg.sourceId == connectedTransport) {
                    connectedTransport = null
                }
            }
        }
    }
}
