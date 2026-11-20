package com.theatresync.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A movie the host can serve. Implemented on Android by a content:// URI. */
interface MediaSource {
    val title: String
    val mimeType: String
    val sizeBytes: Long

    /** Opens the file positioned at [offset]. Caller closes it. */
    fun open(offset: Long): InputStream
}

/**
 * Tiny HTTP/1.1 server run by the host phone on the home Wi-Fi.
 *
 *  GET|HEAD /media                → the movie bytes (supports Range, which Chromecast and AVPlayer need)
 *  GET /clock?seq=&t0=&id=        → clock_pong message (contracts/)
 *  GET /state                     → playback_state message: where the TV picture is right now
 *  GET /hello                     → hello message
 *  GET /guest?id=&name=&drift=&status=&hidden= → TapIn guest report; answers the host's controls
 *  GET|POST /leave?id=            → TapIn guest stopped or closed the page
 *
 * See contracts/HTTP-API.md. One instance per movie session.
 */
class MediaServer(
    private val senderId: String,
    private val clockMs: () -> Long,
    private val stateProvider: () -> Payload.PlaybackState?,
    private val helloProvider: () -> Payload.Hello,
) {
    @Volatile var media: MediaSource? = null

    /** The movie with its sound, for TapIn guests (the TV may get a picture-only copy). Defaults to [media]. */
    @Volatile var audioMedia: MediaSource? = null

    @Volatile private var audioServed = false

    /**
     * Swaps in a better TapIn sound source (e.g. a sound-only view) — but only if no guest has
     * fetched /audio yet, so a guest who is already listening never gets bytes from a different file.
     */
    @Synchronized fun offerAudio(better: MediaSource): Boolean {
        if (audioServed) return false
        audioMedia = better
        return true
    }

    @Synchronized private fun audioSource(): MediaSource? { audioServed = true; return audioMedia ?: media }

    /** Subtitles for the TV as WebVTT (loaded from a file or generated), or null. */
    @Volatile var subtitlesVtt: String? = null

    /** Optional intro clip played on the TV before the movie (Settings → Experimental). */
    @Volatile var intro: MediaSource? = null

    private var serverSocket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "theatresync-http").apply { isDaemon = true }
    }
    private val listenerLastSeen = ConcurrentHashMap<String, Long>()

    /** TapIn guests listening in a browser: who, how well in step, and the host's controls for each. */
    val guests = Guests(clockMs)
    private val mediaRequestCount = java.util.concurrent.atomic.AtomicInteger(0)

    private val bytesSent = java.util.concurrent.atomic.AtomicLong(0)
    private val keepAliveForThisRequest = ThreadLocal<Boolean>()

    /** Total movie bytes sent (to the Chromecast and listeners). Sample over time for the speed. */
    val totalBytesSent: Long get() = bytesSent.get()

    /** How many times anyone (normally the Chromecast) asked for the movie. 0 = the TV never reached us. */
    val mediaRequests: Int get() = mediaRequestCount.get()

    val port: Int get() = serverSocket?.localPort ?: -1

    /** Starts listening on all interfaces. Returns the bound port. */
    fun start(port: Int = 0): Int {
        check(serverSocket == null) { "already started" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        pool.execute { acceptLoop(ss) }
        return ss.localPort
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        pool.shutdownNow()
    }

    /** Number of listener devices (friends' phones) that synced their clock recently. */
    fun activeListeners(windowMs: Long = 5_000): Int {
        val now = clockMs()
        // TapIn browser pages ("web-…") are counted in [guests] instead.
        return listenerLastSeen.entries.count { !it.key.startsWith("web-") && now - it.value <= windowMs }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            try {
                pool.execute {
                    try { handle(socket) } catch (_: Throwable) { runCatching { socket.close() } }
                }
            } catch (_: Exception) {
                socket.close()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            // Security: the movie is only ever for devices on the same home network.
            if (!isLocalNetwork(s.inetAddress)) return
            try {
                s.soTimeout = KEEP_ALIVE_IDLE_MS
                s.tcpNoDelay = true
                // Speed: a large send buffer keeps Wi-Fi busy while the TV drains data.
                runCatching { s.sendBufferSize = 1 shl 20 }
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
                // HTTP keep-alive: the Chromecast fetches the movie as many range requests.
                // Reusing one connection avoids a fresh TCP handshake and slow start for
                // every chunk, which is what made playback stall every few seconds.
                var served = 0
                while (served < MAX_REQUESTS_PER_CONNECTION) {
                    if (!serveOne(input, out)) break
                    served++
                }
            } catch (_: SocketException) {
                // Client went away mid-response — normal for Chromecast, which abandons ranges when seeking.
            } catch (_: IOException) {
                // Includes the idle timeout ending a kept-alive connection.
            } catch (_: Exception) {
                // Anything else (file provider errors, bad requests): drop this connection only.
                // An uncaught exception here would kill the whole app and stop the movie.
            }
        }
    }

    /** Reads and answers one request. Returns true if the connection should stay open for another. */
    private fun serveOne(input: BufferedInputStream, out: BufferedOutputStream): Boolean {
        val requestLine = readLine(input) ?: return false
        if (requestLine.isBlank()) return true // stray CRLF between kept-alive requests
        val receivedAt = clockMs()
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return false
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts.getOrNull(2)?.uppercase() ?: "HTTP/1.0"
        // Our requests never have bodies, but skip one if a client sends it so the next request parses.
        headers["content-length"]?.toLongOrNull()?.takeIf { it in 1..65_536 }?.let { n -> var left = n; while (left > 0) { val k = input.skip(left); if (k <= 0) break; left -= k } }
        val connection = headers["connection"]?.lowercase()
        val keepAlive = if (version == "HTTP/1.1") connection != "close" else connection == "keep-alive"
        keepAliveForThisRequest.set(keepAlive)
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        run {
            when {
                path == "/media" && (method == "GET" || method == "HEAD") -> {
                        mediaRequestCount.incrementAndGet()
                        serveMedia(out, headers["range"], head = method == "HEAD")
                    }
                    path == "/intro" && (method == "GET" || method == "HEAD") ->
                        serveMedia(out, headers["range"], head = method == "HEAD", source = intro)
                    path == "/audio" && (method == "GET" || method == "HEAD") ->
                        serveMedia(out, headers["range"], head = method == "HEAD", source = audioSource())
                    method == "OPTIONS" -> writeHead(out, 204, "No Content", "text/plain", 0, mapOf("Access-Control-Allow-Methods" to "GET, HEAD", "Access-Control-Allow-Headers" to "*"))
                    path == "/leave" && (method == "POST" || method == "GET") -> { // POST: the page's sendBeacon on close
                        query["id"]?.let { guests.leave(it) }
                        writeHead(out, 204, "No Content", "text/plain", 0, mapOf("Cache-Control" to "no-store"))
                    }
                    method != "GET" -> respond(out, 405, "Method Not Allowed", "text/plain", "method not allowed")
                    path == "/subs.vtt" -> subtitlesVtt?.let { respond(out, 200, "OK", "text/vtt; charset=utf-8", it) }
                        ?: respond(out, 404, "Not Found", "text/plain", "no subtitles")
                    path == "/clock" -> serveClock(out, query, receivedAt)
                    path == "/state" -> serveJson(
                        out,
                        Payload.PlaybackState(Payload.PlaybackState.State.STOPPED, 0, clockMs(), 1.0)
                            .let { stateProvider() ?: it },
                    )
                path == "/hello" -> serveJson(out, helloProvider())
                path == "/guest" -> {
                    val id = query["id"].orEmpty()
                    if (id.isBlank()) respond(out, 400, "Bad Request", "text/plain", "need id")
                    else respond(out, 200, "OK", "application/json", guests.report(
                        id, query["name"], query["drift"]?.toDoubleOrNull()?.toLong(),
                        query["status"].orEmpty(), hidden = query["hidden"] == "1",
                    ).toJson())
                }
                path == "/listen" || path == "/" -> respond(out, 200, "OK", "text/html; charset=utf-8", ListenPage.HTML)
                else -> respond(out, 404, "Not Found", "text/plain", "not found")
            }
        }
        out.flush()
        return keepAlive
    }

    private fun serveClock(out: OutputStream, query: Map<String, String>, receivedAt: Long) {
        val seq = query["seq"]?.toIntOrNull()
        val t0 = query["t0"]?.toLongOrNull()
        if (seq == null || t0 == null || seq < 0 || t0 < 0) {
            respond(out, 400, "Bad Request", "text/plain", "need seq and t0")
            return
        }
        query["id"]?.takeIf { it.isNotBlank() && it.length <= 64 }?.let { listenerLastSeen[it] = receivedAt }
        val pong = Payload.ClockPong(seq, t0, t1 = receivedAt, t2 = clockMs())
        serveJson(out, pong, sentAt = pong.t2)
    }

    private fun serveJson(out: OutputStream, payload: Payload, sentAt: Long = clockMs()) {
        val body = SyncCodec.encode(SyncMessage(senderId, sentAt, payload))
        respond(out, 200, "OK", "application/json", body)
    }

    private fun serveMedia(out: OutputStream, rangeHeader: String?, head: Boolean, source: MediaSource? = media) {
        val m = source
        if (m == null) {
            respond(out, 404, "Not Found", "text/plain", "no movie loaded")
            return
        }
        val size = m.sizeBytes
        when (val range = HttpRange.parse(rangeHeader, size)) {
            is HttpRange.Unsatisfiable -> {
                writeHead(out, 416, "Range Not Satisfiable", "text/plain", 0, mapOf("Content-Range" to "bytes */$size"))
            }
            is HttpRange.Full -> {
                writeHead(out, 200, "OK", m.mimeType, size, mapOf("Accept-Ranges" to "bytes"))
                if (!head) m.open(0).use { copy(it, out, size) }
            }
            is HttpRange.Partial -> {
                val len = range.endInclusive - range.start + 1
                writeHead(
                    out, 206, "Partial Content", m.mimeType, len,
                    mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size"),
                )
                if (!head) m.open(range.start).use { copy(it, out, len) }
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, code, reason, "$type; charset=utf-8", bytes.size.toLong(), mapOf("Cache-Control" to "no-store"))
        out.write(bytes)
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, type: String, length: Long, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: $length\r\n")
        val keep = keepAliveForThisRequest.get() == true
        sb.append(if (keep) "Connection: keep-alive\r\nKeep-Alive: timeout=${KEEP_ALIVE_IDLE_MS / 1000}\r\n" else "Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            bytesSent.addAndGet(n.toLong())
            remaining -= n
        }
    }

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val KEEP_ALIVE_IDLE_MS = 15_000
        private const val MAX_REQUESTS_PER_CONNECTION = 10_000

        /** Loopback, link-local, or private (RFC 1918 / IPv6 ULA) addresses only. */
        fun isLocalNetwork(a: java.net.InetAddress?): Boolean {
            if (a == null) return false
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
            if (a is java.net.Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC // fc00::/7
            return false
        }

        internal fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > MAX_LINE) throw IOException("header line too long")
            }
        }

        internal fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { it.isNotEmpty() }.associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}

/** Parses a single-range HTTP `Range: bytes=...` header. Multi-range requests are served in full. */
sealed interface HttpRange {
    data object Full : HttpRange
    data object Unsatisfiable : HttpRange
    data class Partial(val start: Long, val endInclusive: Long) : HttpRange

    companion object {
        fun parse(header: String?, size: Long): HttpRange {
            if (header.isNullOrBlank()) return Full
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return Full
            val value = spec.substring(6).trim()
            if (value.contains(',')) return Full
            val dash = value.indexOf('-')
            if (dash < 0) return Full
            val a = value.substring(0, dash).trim()
            val b = value.substring(dash + 1).trim()
            if (size <= 0) return Unsatisfiable
            return if (a.isEmpty()) {
                val suffix = b.toLongOrNull() ?: return Full
                if (suffix <= 0) Unsatisfiable else Partial(maxOf(0, size - suffix), size - 1)
            } else {
                val start = a.toLongOrNull() ?: return Full
                val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return Full).coerceAtMost(size - 1)
                if (start >= size || end < start) Unsatisfiable else Partial(start, end)
            }
        }
    }
}
