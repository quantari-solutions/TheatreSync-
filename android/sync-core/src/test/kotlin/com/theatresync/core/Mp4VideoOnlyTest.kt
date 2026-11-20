package com.theatresync.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class Mp4VideoOnlyTest {
    private class Bytes(val data: ByteArray) : MediaSource {
        override val title = "t"
        override val mimeType = "video/mp4"
        override val sizeBytes get() = data.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(i32(8 + body.size)); write(type.toByteArray()); write(body)
        }.toByteArray()
    }
    private fun i32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun full(vararg parts: ByteArray) = byteArrayOf(0, 0, 0, 0) + parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun hdlr(kind: String) = box("hdlr", full(i32(0), kind.toByteArray(), ByteArray(12), "x\u0000".toByteArray()))
    private fun stco(vararg offsets: Int) = box("stco", full(i32(offsets.size), *offsets.map { i32(it) }.toTypedArray()))
    private fun trak(kind: String, vararg offsets: Int) =
        box("trak", box("tkhd", ByteArray(20)), box("mdia", hdlr(kind), box("minf", box("stbl", stco(*offsets)))))

    private val videoSample = "VIDEO-FRAME-0001".toByteArray()
    private val audioSample = "audio-6ch-aac-xx".toByteArray()

    /** ftyp, [moov], mdat(video, audio), [moov]; returns the file and the video sample offset in it. */
    private fun movie(fastStart: Boolean): Pair<ByteArray, Int> {
        val ftyp = box("ftyp", "isom".toByteArray(), i32(0))
        fun moov(v: Int, a: Int) = box("moov", box("mvhd", ByteArray(100)), trak("vide", v), trak("soun", a))
        val mdatHeader = 8
        return if (fastStart) {
            val moovSize = moov(0, 0).size
            val v = ftyp.size + moovSize + mdatHeader
            val a = v + videoSample.size
            (ftyp + moov(v, a) + box("mdat", videoSample, audioSample)) to v
        } else {
            val v = ftyp.size + mdatHeader
            val a = v + videoSample.size
            (ftyp + box("mdat", videoSample, audioSample) + moov(v, a)) to v
        }
    }

    private fun readAll(s: MediaSource, from: Long = 0) = s.open(from).use { it.readBytes() }

    private fun videoOffsetIn(file: ByteArray): Int {
        val i = String(file, Charsets.ISO_8859_1).indexOf("stco")
        return ByteArrayInputStream(file, i + 12, 4).let { java.io.DataInputStream(it).readInt() }
    }

    private fun check(fastStart: Boolean) {
        val (original, _) = movie(fastStart)
        val v = Mp4VideoOnly.of(Bytes(original))
        assertNotNull(v)
        val out = readAll(v!!)
        assertEquals(v.sizeBytes, out.size.toLong())
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("video track kept", text.contains("vide"))
        assertFalse("sound track removed", text.contains("soun"))
        // The kept chunk offset must still point at the same picture bytes.
        val at = videoOffsetIn(out)
        assertArrayEquals(videoSample, out.copyOfRange(at, at + videoSample.size))
        // Range reads anywhere match the whole virtual file.
        for (from in listOf(0, 5, 30, at - 3, at, out.size - 4)) {
            assertArrayEquals("from $from", out.copyOfRange(from, out.size), readAll(v, from.toLong()))
        }
    }

    @Test fun fastStartMp4KeepsPictureAndDropsSound() = check(fastStart = true)

    @Test fun indexAtEndMp4KeepsPictureAndDropsSound() = check(fastStart = false)

    @Test fun refusesFilesItCannotHandle() {
        assertNull(Mp4VideoOnly.of(Bytes("not an mp4 at all, just text".toByteArray())))
        val ftyp = box("ftyp", "isom".toByteArray(), i32(0))
        // Fragmented MP4 (mvex): tracks live in moof boxes.
        val fragmented = ftyp + box("moov", box("mvex", ByteArray(8)), trak("vide", 0), trak("soun", 0)) + box("mdat", videoSample)
        assertNull(Mp4VideoOnly.of(Bytes(fragmented)))
        // Picture-only already: nothing to do.
        val pictureOnly = ftyp + box("moov", trak("vide", 0)) + box("mdat", videoSample)
        assertNull(Mp4VideoOnly.of(Bytes(pictureOnly)))
    }
}
