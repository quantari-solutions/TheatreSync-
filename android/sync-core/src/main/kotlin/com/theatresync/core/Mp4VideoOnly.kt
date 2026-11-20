package com.theatresync.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Serves an MP4 *without its sound tracks*, instantly, by rewriting only the index ("moov"
 * box) and streaming the original media bytes untouched.
 *
 * Why: older Chromecasts refuse files whose sound they can't decode (5.1 AAC, AC-3, DTS…) even
 * when the picture is fine. With TheatreSync the sound plays in the headphones from the original
 * file anyway, so the TV only needs the picture — no conversion, no waiting.
 *
 * Layout of the virtual file: [bytes before moov][rewritten moov][bytes after moov]. When the
 * moov comes before the media data ("fast start"), removing tracks moves the media data
 * earlier, so the kept video track's chunk offsets (stco/co64) are shifted by the same amount.
 *
 * Returns null (caller falls back to conversion) for anything unusual: no moov, fragmented MP4,
 * no video track, or an index larger than [MAX_MOOV_BYTES].
 */
class Mp4VideoOnly private constructor(
    private val source: MediaSource,
    private val moovOffset: Long,
    private val oldMoovSize: Long,
    private val newMoov: ByteArray,
) : MediaSource {
    override val title: String get() = source.title
    override val mimeType: String get() = "video/mp4"
    override val sizeBytes: Long = source.sizeBytes - oldMoovSize + newMoov.size

    /** Bytes removed from the index (the sound tracks' tables), for diagnostics. */
    val removedBytes: Long get() = oldMoovSize - newMoov.size

    override fun open(offset: Long): InputStream {
        val afterMoovStart = moovOffset + newMoov.size // in virtual coordinates
        val streams = mutableListOf<InputStream>()
        if (offset < moovOffset) {
            streams += LimitedStream(source.open(offset), moovOffset - offset)
            streams += ByteArrayInputStream(newMoov)
            streams += source.open(moovOffset + oldMoovSize)
        } else if (offset < afterMoovStart) {
            val skip = (offset - moovOffset).toInt()
            streams += ByteArrayInputStream(newMoov, skip, newMoov.size - skip)
            streams += source.open(moovOffset + oldMoovSize)
        } else {
            streams += source.open(offset - afterMoovStart + moovOffset + oldMoovSize)
        }
        return SequenceInputStream(java.util.Collections.enumeration(streams))
    }

    private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inner.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = inner.close()
    }

    companion object {
        const val MAX_MOOV_BYTES = 64L * 1024 * 1024

        private data class Box(val type: String, val offset: Long, val size: Long, val header: Int)

        /** Tries to build the picture-only view; null if this file can't be handled this way. */
        fun of(source: MediaSource): Mp4VideoOnly? = try {
            build(source)
        } catch (_: Exception) {
            null
        }

        private fun build(source: MediaSource): Mp4VideoOnly? {
            val top = topLevelBoxes(source)
            val moov = top.firstOrNull { it.type == "moov" } ?: return null
            if (moov.size > MAX_MOOV_BYTES) return null
            val firstMdat = top.firstOrNull { it.type == "mdat" } ?: return null
            val buf = ByteArray(moov.size.toInt())
            DataInputStream(source.open(moov.offset)).use { it.readFully(buf) }

            val children = boxes(buf, moov.header, buf.size)
            if (children.any { it.type == "mvex" }) return null // fragmented MP4: tracks live in moof boxes
            val traks = children.filter { it.type == "trak" }
            val keep = traks.filter { handler(buf, it) == "vide" }
            if (keep.isEmpty()) return null
            val drop = traks - keep.toSet()
            if (drop.isEmpty()) return null // nothing to remove: the original is already picture-only

            val removed = drop.sumOf { it.size }
            val newSize = buf.size - removed
            // Media data before the index never moves; after a fast-start index it moves up.
            val delta = if (moov.offset < firstMdat.offset) -removed else 0L
            if (delta != 0L) keep.forEach { shiftChunkOffsets(buf, it, delta) }

            val out = java.io.ByteArrayOutputStream(newSize.toInt())
            // Rewrite the moov header with the new size (32-bit form; a 64-bit header keeps its form).
            if (moov.header == 16) {
                out.write(int32(1)); out.write("moov".toByteArray()); out.write(int64(newSize))
            } else {
                out.write(int32(newSize)); out.write("moov".toByteArray())
            }
            var pos = moov.header.toLong()
            for (c in children) {
                if (c.offset > pos) out.write(buf, pos.toInt(), (c.offset - pos).toInt())
                if (c !in drop) out.write(buf, c.offset.toInt(), c.size.toInt())
                pos = c.offset + c.size
            }
            if (pos < buf.size) out.write(buf, pos.toInt(), buf.size - pos.toInt())
            return Mp4VideoOnly(source, moov.offset, moov.size, out.toByteArray())
        }

        private fun topLevelBoxes(source: MediaSource): List<Box> {
            val list = mutableListOf<Box>()
            var offset = 0L
            while (offset + 8 <= source.sizeBytes && list.size < 64) {
                val head = ByteArray(16)
                val n = source.open(offset).use { readUpTo(it, head) }
                if (n < 8) break
                var size = u32(head, 0)
                val type = String(head, 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { if (n < 16) break; size = u64(head, 8); header = 16 }
                if (size == 0L) size = source.sizeBytes - offset
                if (size < header) break
                list += Box(type, offset, size, header)
                offset += size
            }
            return list
        }

        private fun boxes(buf: ByteArray, start: Int, end: Int): List<Box> {
            val list = mutableListOf<Box>()
            var p = start
            while (p + 8 <= end) {
                var size = u32(buf, p)
                val type = String(buf, p + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { size = u64(buf, p + 8); header = 16 }
                if (size == 0L) size = (end - p).toLong()
                if (size < header || p + size > end) break
                list += Box(type, p.toLong(), size, header)
                p += size.toInt()
            }
            return list
        }

        private fun child(buf: ByteArray, parent: Box, type: String): Box? =
            boxes(buf, (parent.offset + parent.header).toInt(), (parent.offset + parent.size).toInt()).firstOrNull { it.type == type }

        /** trak → mdia → hdlr → handler type ("vide", "soun", "text", "sbtl"…). */
        private fun handler(buf: ByteArray, trak: Box): String? {
            val mdia = child(buf, trak, "mdia") ?: return null
            val hdlr = child(buf, mdia, "hdlr") ?: return null
            val p = (hdlr.offset + hdlr.header + 8).toInt() // version/flags (4) + pre_defined (4)
            return String(buf, p, 4, Charsets.ISO_8859_1)
        }

        private fun shiftChunkOffsets(buf: ByteArray, trak: Box, delta: Long) {
            val stbl = child(buf, trak, "mdia")?.let { child(buf, it, "minf") }?.let { child(buf, it, "stbl") } ?: return
            child(buf, stbl, "stco")?.let { stco ->
                val base = (stco.offset + stco.header + 4).toInt()
                val count = u32(buf, base).toInt()
                for (i in 0 until count) {
                    val at = base + 4 + i * 4
                    putU32(buf, at, u32(buf, at) + delta)
                }
            }
            child(buf, stbl, "co64")?.let { co ->
                val base = (co.offset + co.header + 4).toInt()
                val count = u32(buf, base).toInt()
                for (i in 0 until count) {
                    val at = base + 4 + i * 8
                    putU64(buf, at, u64(buf, at) + delta)
                }
            }
        }

        private fun readUpTo(input: InputStream, b: ByteArray): Int {
            var n = 0
            while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) break; n += r }
            return n
        }

        private fun u32(b: ByteArray, p: Int): Long =
            ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
        private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
        private fun putU32(b: ByteArray, p: Int, v: Long) { for (i in 0..3) b[p + i] = (v shr (24 - 8 * i)).toByte() }
        private fun putU64(b: ByteArray, p: Int, v: Long) { putU32(b, p, v ushr 32); putU32(b, p + 4, v and 0xFFFFFFFFL) }
        private fun int32(v: Long) = ByteArray(4).also { putU32(it, 0, v) }
        private fun int64(v: Long) = ByteArray(8).also { putU64(it, 0, v) }
    }
}
