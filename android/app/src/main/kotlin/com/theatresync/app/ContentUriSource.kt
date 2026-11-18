package com.theatresync.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.theatresync.core.MediaSource
import java.io.IOException
import java.io.InputStream

/** Serves a movie the user picked with the system file picker. */
class ContentUriSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val title: String,
    override val mimeType: String,
    override val sizeBytes: Long,
) : MediaSource {

    override fun open(offset: Long): InputStream {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) {
            try {
                stream.channel.position(offset)
            } catch (_: IOException) {
                // Not seekable (e.g. some cloud providers): fall back to skipping.
                var left = offset
                while (left > 0) {
                    val n = stream.skip(left)
                    if (n <= 0) throw IOException("cannot skip to $offset")
                    left -= n
                }
            }
        }
        return stream
    }

    companion object {
        fun from(context: Context, uri: Uri): ContentUriSource? {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
            if (size <= 0) {
                size = try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) { -1L }
            }
            if (size <= 0) return null
            val title = (name ?: uri.lastPathSegment ?: "Movie").substringBeforeLast('.')
            val mime = resolver.getType(uri) ?: guessMime(name)
            return ContentUriSource(resolver, uri, title, mime, size)
        }

        private fun guessMime(name: String?): String = when (name?.substringAfterLast('.', "")?.lowercase()) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov", "m4v" -> "video/mp4"
            else -> "video/mp4"
        }
    }
}
