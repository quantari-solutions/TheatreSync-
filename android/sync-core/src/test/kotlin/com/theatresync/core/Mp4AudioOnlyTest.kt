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

class Mp4AudioOnlyTest {
    private class Bytes(val data: ByteArray) : MediaSource {
        override val title = "t"; override val mimeType = "video/mp4"
        override val sizeBytes get() = data.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    private fun i32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return i32(8 + body.size) + type.toByteArray() + body
    }
    private fun full(vararg parts: ByteArray) = byteArrayOf(0, 0, 0, 0) + parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun hdlr(kind: String) = box("hdlr", full(i32(0), kind.toByteArray(), ByteArray(12), "x\u0000".toByteArray()))
    private fun trak(kind: String, offsets: List<Int>, samplesPerChunk: Int, sampleSize: Int, codec: String? = null) = box(
        "trak", box("tkhd", ByteArray(20)),
        box("mdia", hdlr(kind), box("minf", box("stbl",
            *listOfNotNull(codec?.let { box("stsd", full(i32(1), box(it, ByteArray(28)))) }).toTypedArray(),
            box("stsc", full(i32(1), i32(1), i32(samplesPerChunk), i32(1))),
            box("stsz", full(i32(sampleSize), i32(offsets.size * samplesPerChunk))),
            box("stco", full(i32(offsets.size), *offsets.map { i32(it) }.toTypedArray())),
        ))),
    )

    /** Interleaved movie: V A V A V A in mdat; returns file + the audio bytes in order. */
    private fun movie(fastStart: Boolean): Pair<ByteArray, ByteArray> {
        val ftyp = box("ftyp", "isom".toByteArray(), i32(0))
        val v = (0 until 3).map { i -> ByteArray(40) { (0x10 + i).toByte() } }
        val a = (0 until 3).map { i -> ByteArray(12) { (0xA0 + i).toByte() } } // 2 samples × 6 bytes per chunk
        val mdatBody = v[0] + a[0] + v[1] + a[1] + v[2] + a[2]
        fun moovFor(base: Int) = box("moov", box("mvhd", ByteArray(100)),
            trak("vide", (0 until 3).map { base + it * 52 }, 1, 40),
            trak("soun", (0 until 3).map { base + 40 + it * 52 }, 2, 6))
        val audio = a[0] + a[1] + a[2]
        return if (fastStart) {
            val moovSize = moovFor(0).size
            val base = ftyp.size + moovSize + 8
            (ftyp + moovFor(base) + box("mdat", mdatBody)) to audio
        } else {
            val base = ftyp.size + 8
            (ftyp + box("mdat", mdatBody) + moovFor(base)) to audio
        }
    }

    private fun check(fastStart: Boolean) {
        val (file, audio) = movie(fastStart)
        val m = Mp4AudioOnly.of(Bytes(file))
        assertNotNull(m)
        val out = m!!.open(0).use { it.readBytes() }
        assertEquals(m.sizeBytes, out.size.toLong())
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse("no picture track", text.contains("vide"))
        assertTrue(text.contains("soun"))
        // mdat is the last box and holds exactly the sound chunks, in order.
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
        // The rewritten chunk offsets point at the right bytes.
        val stco = text.indexOf("stco")
        val first = java.io.DataInputStream(ByteArrayInputStream(out, stco + 12, 4)).readInt()
        assertEquals(0xA0.toByte(), out[first])
        val second = java.io.DataInputStream(ByteArrayInputStream(out, stco + 16, 4)).readInt()
        assertEquals(0xA1.toByte(), out[second])
        // Smaller than the original (no picture bytes), and range reads match.
        assertTrue(out.size < file.size)
        for (from in listOf(0, 7, out.size - 30, out.size - 13, out.size - 1)) {
            assertArrayEquals("from $from", out.copyOfRange(from, out.size), m.open(from.toLong()).use { it.readBytes() })
        }
    }

    @Test fun fastStartMovieBecomesSoundOnly() = check(true)
    @Test fun indexAtEndMovieBecomesSoundOnly() = check(false)

    @Test fun prefersTheSoundTrackBrowsersCanPlay() {
        val ftyp = box("ftyp", "isom".toByteArray(), i32(0))
        val dolby = ByteArray(8) { 0xD0.toByte() }; val aac = ByteArray(8) { 0xA0.toByte() }
        fun moov(base: Int, vararg codecs: String) = box("moov", box("mvhd", ByteArray(100)),
            *codecs.mapIndexed { i, c -> trak("soun", listOf(base + i * 8), 1, 8, c) }.toTypedArray())
        val base = ftyp.size + 8
        val both = Mp4AudioOnly.of(Bytes(ftyp + box("mdat", dolby + aac) + moov(base, "ac-3", "mp4a")))!!
        assertEquals("mp4a", both.codec)
        assertTrue(both.browserPlayable)
        val out = both.open(0).use { it.readBytes() }
        assertArrayEquals(aac, out.copyOfRange(out.size - 8, out.size))
        // Dolby only: still served (some browsers manage), but flagged.
        val dolbyOnly = Mp4AudioOnly.of(Bytes(ftyp + box("mdat", dolby) + moov(base, "ac-3")))!!
        assertEquals("ac-3", dolbyOnly.codec)
        assertFalse(dolbyOnly.browserPlayable)
    }

    @Test fun refusesWhatItCantHandle() {
        assertNull(Mp4AudioOnly.of(Bytes("just text, not an mp4".toByteArray())))
        val ftyp = box("ftyp", "isom".toByteArray(), i32(0))
        val pictureOnly = ftyp + box("moov", trak("vide", listOf(0), 1, 4)) + box("mdat", ByteArray(4))
        assertNull(Mp4AudioOnly.of(Bytes(pictureOnly)))
    }
}

/** Real files made with ffmpeg (H.264 + AAC 5.1): writes the sound-only view for ffprobe to check. */
class Mp4AudioOnlyRealFileTest {
    private class FileSource(val f: java.io.File) : MediaSource {
        override val title = f.name; override val mimeType = "video/mp4"
        override val sizeBytes = f.length()
        override fun open(offset: Long): java.io.InputStream = java.io.FileInputStream(f).also { it.channel.position(offset) }
    }

    @org.junit.Test fun realMoviesBecomeValidSoundOnlyFiles() {
        for (name in listOf("sample_av.mp4", "sample_av_moovend.mp4")) {
            val f = java.io.File(javaClass.classLoader!!.getResource(name)!!.toURI())
            val m = Mp4AudioOnly.of(FileSource(f))
            org.junit.Assert.assertNotNull(name, m)
            val out = java.io.File("build/tmp/audio_only_$name").apply { parentFile.mkdirs() }
            m!!.open(0).use { input -> out.outputStream().use { input.copyTo(it) } }
            org.junit.Assert.assertEquals(m.sizeBytes, out.length())
            org.junit.Assert.assertTrue("$name: ${out.length()} vs ${f.length()}", out.length() < f.length())
            org.junit.Assert.assertEquals("mp4a", m.codec)
        }
        // Manual check with a longer movie (used for the TapIn browser stress test): TS_EXTRA_MP4=/path/movie.mp4
        System.getenv("TS_EXTRA_MP4")?.let { path ->
            val m = Mp4AudioOnly.of(FileSource(java.io.File(path)))!!
            val out = java.io.File("build/tmp/audio_only_extra.mp4").apply { parentFile.mkdirs() }
            m.open(0).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}
