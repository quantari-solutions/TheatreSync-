package com.theatresync.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * TapIn guests: the movie's **sound only**, as a small MP4 built on the fly from the original file.
 *
 * Layout of the virtual file: `[ftyp][moov with one sound track][mdat: that track's chunks, in order]`.
 * The chunk bytes are streamed straight from the original (nothing is converted or copied); only the
 * sound track's chunk offsets are rewritten to their new positions. A guest's phone downloads a few
 * percent of the movie instead of all of it, so playback starts and seeks almost instantly.
 *
 * Returns null (the caller serves the whole file instead) for anything unusual: no moov, fragmented
 * MP4, no sound track, or an index larger than [MAX_MOOV_BYTES].
 */
class Mp4AudioOnly private constructor(
    private val source: MediaSource,
    private val header: ByteArray,           // ftyp + moov + mdat header
    private val chunkOffsets: LongArray,     // in the original file
    private val chunkSizes: LongArray,
    /** The chosen sound track's codec ("mp4a" = AAC; "ac-3"/"ec-3" = Dolby; "dtsc" = DTS). */
    val codec: String?,
) : MediaSource {
    /** False for Dolby/DTS sound, which phone browsers can't play. */
    val browserPlayable: Boolean get() = codec in BROWSER_CODECS

    override val title: String get() = source.title
    override val mimeType: String get() = "audio/mp4"
    override val sizeBytes: Long = header.size + chunkSizes.sum()

    /** Start of each chunk in the virtual file. */
    private val chunkStarts = LongArray(chunkSizes.size).also { a ->
        var at = header.size.toLong()
        for (i in a.indices) { a[i] = at; at += chunkSizes[i] }
    }

    override fun open(offset: Long): InputStream {
        val parts = mutableListOf<InputStream>()
        if (offset < header.size) parts += ByteArrayInputStream(header, offset.toInt(), header.size - offset.toInt())
        parts += ChunkStream(maxOf(offset, header.size.toLong()))
        return java.io.SequenceInputStream(java.util.Collections.enumeration(parts))
    }

    /** Streams the sound chunks from [from] (virtual offset) to the end, reading the original in order. */
    private inner class ChunkStream(from: Long) : InputStream() {
        private var index = run {
            var i = java.util.Arrays.binarySearch(chunkStarts, from)
            if (i < 0) i = -i - 2
            i.coerceAtLeast(0)
        }
        private var inChunk = from - (chunkStarts.getOrNull(index) ?: from) // bytes already passed in this chunk
        private var src: InputStream? = null
        private var srcPos = -1L

        private fun position(target: Long) {
            val s = src
            if (s != null && target >= srcPos) {
                var skip = target - srcPos
                while (skip > 0) { val n = s.skip(skip); if (n <= 0) break; skip -= n; srcPos += n }
                if (srcPos == target) return
            }
            s?.close()
            src = source.open(target)
            srcPos = target
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (index < chunkSizes.size && inChunk >= chunkSizes[index]) { index++; inChunk = 0 }
            if (index >= chunkSizes.size) return -1
            position(chunkOffsets[index] + inChunk)
            val want = minOf(len.toLong(), chunkSizes[index] - inChunk).toInt()
            val n = src!!.read(b, off, want)
            if (n <= 0) return -1
            inChunk += n; srcPos += n
            return n
        }

        override fun close() { src?.close() }
    }

    companion object {
        const val MAX_MOOV_BYTES = 64L * 1024 * 1024

        private data class Box(val type: String, val offset: Long, val size: Long, val header: Int)

        fun of(source: MediaSource): Mp4AudioOnly? = try { build(source) } catch (_: Exception) { null }

        private fun build(source: MediaSource): Mp4AudioOnly? {
            val top = topLevel(source)
            val moov = top.firstOrNull { it.type == "moov" } ?: return null
            if (moov.size > MAX_MOOV_BYTES) return null
            val ftyp = top.firstOrNull { it.type == "ftyp" }
            val buf = read(source, moov.offset, moov.size.toInt())
            val children = boxes(buf, moov.header, buf.size)
            if (children.any { it.type == "mvex" }) return null
            val traks = children.filter { it.type == "trak" }
            // Prefer a sound track browsers can play (AAC, MP3, Opus, FLAC) over Dolby/DTS ones.
            val sounds = traks.filter { handler(buf, it) == "soun" }
            val sound = sounds.firstOrNull { codec(buf, it) in BROWSER_CODECS } ?: sounds.firstOrNull() ?: return null
            val stbl = stbl(buf, sound) ?: return null

            // Chunk positions and sizes of the sound track, from stco/co64 + stsc + stsz.
            val stco = child(buf, stbl, "stco"); val co64 = child(buf, stbl, "co64")
            val offsets = when {
                stco != null -> table(buf, stco, 4)
                co64 != null -> table(buf, co64, 8)
                else -> return null
            }
            val stsc = child(buf, stbl, "stsc") ?: return null
            val stsz = child(buf, stbl, "stsz") ?: return null
            val samplesPerChunk = samplesPerChunk(buf, stsc, offsets.size)
            val sampleSizes = sampleSizes(buf, stsz)
            val sizes = LongArray(offsets.size)
            var sample = 0
            for (c in offsets.indices) {
                var bytes = 0L
                repeat(samplesPerChunk[c]) { if (sample < sampleSizes.size) bytes += sampleSizes[sample++] }
                sizes[c] = bytes
            }

            // New moov: everything except the other tracks.
            val drop = traks.filter { it !== sound }
            val removed = drop.sumOf { it.size }
            val newMoovSize = buf.size - removed
            val moovOut = java.io.ByteArrayOutputStream(newMoovSize.toInt())
            moovOut.write(u32bytes(newMoovSize)); moovOut.write("moov".toByteArray())
            var pos = moov.header.toLong()
            var soundAt = -1L
            for (c in children) {
                if (c.offset > pos) moovOut.write(buf, pos.toInt(), (c.offset - pos).toInt())
                if (c === sound) soundAt = moovOut.size().toLong()
                if (c !in drop) moovOut.write(buf, c.offset.toInt(), c.size.toInt())
                pos = c.offset + c.size
            }
            if (pos < buf.size) moovOut.write(buf, pos.toInt(), buf.size - pos.toInt())
            val newMoov = moovOut.toByteArray()

            val ftypBytes = ftyp?.let { read(source, it.offset, it.size.toInt()) } ?: ByteArray(0)
            val total = sizes.sum()
            val mdatHeader = if (total + 8 <= 0xFFFFFFFFL) u32bytes(total + 8) + "mdat".toByteArray()
                else u32bytes(1) + "mdat".toByteArray() + u64bytes(total + 16)
            // Rewrite the sound track's chunk offsets to where its chunks now sit (same table width).
            val dataStart = ftypBytes.size + newMoov.size + mdatHeader.size
            val shift = soundAt - sound.offset // where the sound trak moved inside the moov
            val tableBox = stco ?: co64!!
            val width = if (stco != null) 4 else 8
            val tableAt = (tableBox.offset + shift + tableBox.header + 8).toInt()
            var at = dataStart.toLong()
            for (c in offsets.indices) {
                if (width == 4) putU32(newMoov, tableAt + c * 4, at) else putU64(newMoov, tableAt + c * 8, at)
                at += sizes[c]
            }
            if (width == 4 && at > 0xFFFFFFFFL) return null
            return Mp4AudioOnly(source, ftypBytes + newMoov + mdatHeader, offsets, sizes, codec(buf, sound))
        }

        /** Sample-entry types phone browsers decode in an MP4. */
        val BROWSER_CODECS = setOf("mp4a", ".mp3", "Opus", "fLaC")

        private fun stbl(buf: ByteArray, trak: Box): Box? =
            child(buf, trak, "mdia")?.let { child(buf, it, "minf") }?.let { child(buf, it, "stbl") }

        /** The track's codec: the first sample entry's type in stsd ("mp4a", "ac-3", "ec-3", "dtsc"…). */
        private fun codec(buf: ByteArray, trak: Box): String? {
            val stsd = stbl(buf, trak)?.let { child(buf, it, "stsd") } ?: return null
            val p = (stsd.offset + stsd.header + 8 + 4).toInt() // version/flags + entry count, then size
            return if (p + 4 <= stsd.offset + stsd.size) String(buf, p, 4, Charsets.ISO_8859_1) else null
        }

        private fun read(source: MediaSource, offset: Long, size: Int): ByteArray =
            ByteArray(size).also { b -> DataInputStream(source.open(offset)).use { it.readFully(b) } }

        private fun topLevel(source: MediaSource): List<Box> {
            val list = mutableListOf<Box>()
            var offset = 0L
            while (offset + 8 <= source.sizeBytes && list.size < 64) {
                val head = ByteArray(16)
                val n = source.open(offset).use { s -> var k = 0; while (k < 16) { val r = s.read(head, k, 16 - k); if (r < 0) break; k += r }; k }
                if (n < 8) break
                var size = u32(head, 0); var header = 8
                if (size == 1L) { if (n < 16) break; size = u64(head, 8); header = 16 }
                if (size == 0L) size = source.sizeBytes - offset
                if (size < header) break
                list += Box(String(head, 4, 4, Charsets.ISO_8859_1), offset, size, header)
                offset += size
            }
            return list
        }

        private fun boxes(buf: ByteArray, start: Int, end: Int): List<Box> {
            val list = mutableListOf<Box>()
            var p = start
            while (p + 8 <= end) {
                var size = u32(buf, p); var header = 8
                if (size == 1L) { size = u64(buf, p + 8); header = 16 }
                if (size == 0L) size = (end - p).toLong()
                if (size < header || p + size > end) break
                list += Box(String(buf, p + 4, 4, Charsets.ISO_8859_1), p.toLong(), size, header)
                p += size.toInt()
            }
            return list
        }

        private fun child(buf: ByteArray, parent: Box, type: String): Box? =
            boxes(buf, (parent.offset + parent.header).toInt(), (parent.offset + parent.size).toInt()).firstOrNull { it.type == type }

        private fun handler(buf: ByteArray, trak: Box): String? {
            val hdlr = child(buf, trak, "mdia")?.let { child(buf, it, "hdlr") } ?: return null
            return String(buf, (hdlr.offset + hdlr.header + 8).toInt(), 4, Charsets.ISO_8859_1)
        }

        private fun table(buf: ByteArray, box: Box, width: Int): LongArray {
            val base = (box.offset + box.header + 4).toInt()
            val count = u32(buf, base).toInt()
            return LongArray(count) { i -> if (width == 4) u32(buf, base + 4 + i * 4) else u64(buf, base + 4 + i * 8) }
        }

        /** stsc runs (first chunk, samples per chunk) expanded to one count per chunk. */
        private fun samplesPerChunk(buf: ByteArray, stsc: Box, chunks: Int): IntArray {
            val base = (stsc.offset + stsc.header + 4).toInt()
            val runs = u32(buf, base).toInt()
            val out = IntArray(chunks)
            for (r in 0 until runs) {
                val first = u32(buf, base + 4 + r * 12).toInt() - 1
                val per = u32(buf, base + 8 + r * 12).toInt()
                val next = if (r + 1 < runs) u32(buf, base + 4 + (r + 1) * 12).toInt() - 1 else chunks
                for (c in first until minOf(next, chunks)) out[c] = per
            }
            return out
        }

        private fun sampleSizes(buf: ByteArray, stsz: Box): LongArray {
            val base = (stsz.offset + stsz.header + 4).toInt()
            val fixed = u32(buf, base)
            val count = u32(buf, base + 4).toInt()
            return LongArray(count) { i -> if (fixed != 0L) fixed else u32(buf, base + 8 + i * 4) }
        }

        private fun u32(b: ByteArray, p: Int): Long =
            ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
        private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
        private fun putU32(b: ByteArray, p: Int, v: Long) { for (i in 0..3) b[p + i] = (v shr (24 - 8 * i)).toByte() }
        private fun putU64(b: ByteArray, p: Int, v: Long) { putU32(b, p, v ushr 32); putU32(b, p + 4, v and 0xFFFFFFFFL) }
        private fun u32bytes(v: Long) = ByteArray(4).also { putU32(it, 0, v) }
        private fun u64bytes(v: Long) = ByteArray(8).also { putU64(it, 0, v) }
    }
}
