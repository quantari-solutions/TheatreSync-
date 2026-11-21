package com.theatresync.core

/** A live HLS stream the media server can serve at /live.m3u8 and /seg/N.ts. */
interface LiveStream {
    fun playlist(): String
    fun segment(n: Long): ByteArray
}

/**
 * The Signature Intro as a short *live* stream: the intro once, then black until the film is loaded.
 *
 * Why live: the Chromecast's media player shows a progress bar and the time ("0:04") over any normal
 * video, which made the intro look like a promo clip; it shows neither for a live stream (same as the
 * screensaver). It also can't play animated pictures. The playlist lists only the intro until the TV
 * fetches it, so the TV always begins at its first frame (a player joins a live stream near its end);
 * from then on it grows by a black segment every [tailSec] seconds, a little ahead of time.
 */
class IntroHls(
    private val intro: () -> ByteArray,
    private val introMs: Long,
    /** A black MPEG-TS segment, encoded like the intro (same size, frame rate, start time). */
    private val tail: () -> ByteArray,
    private val tailSec: Int = 2,
    private val clockMs: () -> Long,
) : LiveStream {
    @Volatile private var startMs = -1L

    /** Segments the TV may play now: the intro, plus black ones up to a little ahead of "now". */
    private fun lastSegment(): Long {
        val start = startMs
        if (start < 0) return 0 // the intro hasn't been fetched yet
        val afterIntro = clockMs() - start - introMs + LEAD_MS
        return if (afterIntro < 0) 0 else 1 + afterIntro / (tailSec * 1000L)
    }

    override fun playlist(): String {
        val last = lastSegment()
        val target = maxOf((introMs + 999) / 1000, tailSec.toLong())
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", introMs / 1000.0)).append(",\nseg/0.ts\n")
            for (n in 1..last) append("#EXTINF:").append(tailSec).append(".000,\nseg/").append(n).append(".ts\n")
        }
    }

    override fun segment(n: Long): ByteArray = when {
        n == 0L -> intro().also { synchronized(this) { if (startMs < 0) startMs = clockMs() } }
        n > 0 -> TsRetimer.shift(tail(), (introMs + (n - 1) * tailSec * 1000L) * 90L)
        else -> throw IllegalArgumentException("segment $n")
    }

    private companion object {
        /**
         * Black segments are listed this far ahead (the first ones as soon as the intro is fetched), so the TV,
         * which re-reads the list only every few seconds, never runs out before the film arrives.
         */
        const val LEAD_MS = 9_000L
    }
}
