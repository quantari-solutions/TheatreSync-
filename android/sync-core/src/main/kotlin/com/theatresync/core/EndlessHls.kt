package com.theatresync.core

/**
 * An endless live video for the TV screensaver, made from a handful of short scenes played in a
 * random order that never ends and never repeats a pattern: each round plays every scene once in a
 * fresh shuffle (no scene twice in a row, even across rounds).
 *
 * Served as an HLS live stream (what Chromecast plays natively): a rolling playlist of the next few
 * 10 s segments, and each segment's timestamps shifted so the TV sees one continuous programme.
 * Every scene starts and ends in the same calm frame, so the joins can't be seen.
 */
class EndlessHls(
    private val sceneCount: Int,
    /** Bytes of scene [i] as an MPEG-TS file (all encoded alike, each starting with a key frame). */
    private val scene: (Int) -> ByteArray,
    private val segmentSec: Int = 10,
    private val clockMs: () -> Long,
    seed: Long = System.nanoTime(),
) : LiveStream {
    private val startMs = clockMs()
    private val rng = java.util.Random(seed)
    private val order = ArrayList<Int>()

    init { require(sceneCount > 0) }

    /** Which scene plays as segment [n]. */
    @Synchronized fun sceneAt(n: Long): Int {
        while (order.size <= n) {
            val round = (0 until sceneCount).shuffled(rng).toMutableList()
            if (order.isNotEmpty() && sceneCount > 1 && round.first() == order.last()) round.add(round.removeAt(0))
            order += round
        }
        return order[n.toInt()]
    }

    /** The live playlist: the latest few segments, moving on every [segmentSec] seconds. */
    override fun playlist(): String {
        val live = (clockMs() - startMs) / (segmentSec * 1000L) + WINDOW
        val first = live - WINDOW
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(segmentSec).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
            for (n in first..live) append("#EXTINF:").append(segmentSec).append(".000,\nseg/").append(n).append(".ts\n")
        }
    }

    /** Segment [n]: its scene, with timestamps moved to follow segment n−1 exactly. */
    override fun segment(n: Long): ByteArray {
        if (n < 0) throw IllegalArgumentException("segment $n")
        return TsRetimer.shift(scene(sceneAt(n)), n * segmentSec * 90_000L)
    }

    companion object {
        /** Segments listed ahead of "now" (players start a few segments back from the live end). */
        const val WINDOW = 4L
    }
}

/** Shifts every timestamp (PCR, PTS, DTS) in an MPEG-TS file by a fixed amount (90 kHz units). */
object TsRetimer {
    private const val PACKET = 188
    private const val WRAP = 1L shl 33

    fun shift(ts: ByteArray, by90k: Long): ByteArray {
        val b = ts.copyOf()
        var p = 0
        while (p + PACKET <= b.size) {
            if (b[p].toInt() and 0xFF != 0x47) { p++; continue } // resync
            val pusi = b[p + 1].toInt() and 0x40 != 0
            val afc = (b[p + 3].toInt() shr 4) and 3
            var i = p + 4
            if (afc == 2 || afc == 3) {
                val len = b[p + 4].toInt() and 0xFF
                if (len > 0 && (b[p + 5].toInt() and 0x10) != 0) shiftPcr(b, p + 6, by90k)
                i = p + 5 + len
            }
            if (pusi && (afc == 1 || afc == 3) && i + 14 <= p + PACKET &&
                b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1
            ) {
                val flags = (b[i + 7].toInt() shr 6) and 3
                if (flags and 2 != 0) writeTs(b, i + 9, readTs(b, i + 9) + by90k)
                if (flags == 3 && i + 19 <= p + PACKET) writeTs(b, i + 14, readTs(b, i + 14) + by90k)
            }
            p += PACKET
        }
        return b
    }

    private fun u(b: ByteArray, i: Int) = b[i].toLong() and 0xFF

    internal fun readTs(b: ByteArray, i: Int): Long =
        (((u(b, i) shr 1) and 7) shl 30) or (u(b, i + 1) shl 22) or ((u(b, i + 2) shr 1) shl 15) or (u(b, i + 3) shl 7) or (u(b, i + 4) shr 1)

    internal fun writeTs(b: ByteArray, i: Int, value: Long) {
        val v = ((value % WRAP) + WRAP) % WRAP
        val prefix = u(b, i).toInt() and 0xF0
        b[i] = (prefix or (((v shr 30) and 7).toInt() shl 1) or 1).toByte()
        b[i + 1] = (v shr 22).toByte()
        b[i + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[i + 3] = (v shr 7).toByte()
        b[i + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun shiftPcr(b: ByteArray, i: Int, by90k: Long) {
        val base = (u(b, i) shl 25) or (u(b, i + 1) shl 17) or (u(b, i + 2) shl 9) or (u(b, i + 3) shl 1) or (u(b, i + 4) shr 7)
        val v = (((base + by90k) % WRAP) + WRAP) % WRAP
        b[i] = (v shr 25).toByte(); b[i + 1] = (v shr 17).toByte(); b[i + 2] = (v shr 9).toByte(); b[i + 3] = (v shr 1).toByte()
        b[i + 4] = (((v and 1).toInt() shl 7) or (u(b, i + 4).toInt() and 0x7F)).toByte()
    }
}
