package com.theatresync.app

import android.content.Context
import com.theatresync.core.MediaSource
import java.io.FileInputStream
import java.io.InputStream

/** A video bundled in res/raw (stored uncompressed), served to the TV like a movie file. */
class RawResourceSource(context: Context, private val resId: Int, override val title: String, override val mimeType: String = "video/mp4") : MediaSource {
    private val app = context.applicationContext
    override val sizeBytes: Long = app.resources.openRawResourceFd(resId).use { it.length }

    override fun open(offset: Long): InputStream {
        val fd = app.resources.openRawResourceFd(resId)
        val stream = FileInputStream(fd.fileDescriptor)
        stream.channel.position(fd.startOffset + offset)
        // Stop at the end of this resource, not the end of the APK.
        return object : java.io.FilterInputStream(stream) {
            private var left = sizeBytes - offset
            override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = super.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() { super.close(); fd.close() }
        }
    }
}
