package com.theatresync.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Writes a mono 16-bit WAV tone so tests have real, decodable media without checked-in files. */
object TestMedia {
    fun wav(dir: File, seconds: Int, rate: Int = 44_100): File {
        val samples = seconds * rate
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) data.putShort((sin(2 * PI * 440 * i / rate) * 3000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        return File(dir, "tone-${seconds}s.wav").apply { writeBytes(header.array() + data.array()) }
    }
}
