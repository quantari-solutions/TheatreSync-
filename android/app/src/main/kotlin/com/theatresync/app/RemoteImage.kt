package com.theatresync.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Loads a thumbnail (HTTPS) with a small memory cache. Shows [placeholder] until it arrives or if it fails. */
@Composable
fun RemoteImage(url: String?, contentDescription: String?, modifier: Modifier, placeholder: @Composable () -> Unit) {
    val bitmap by produceState(initialValue = url?.let { ThumbCache.get(it) }, url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { ThumbCache.load(url) }
    }
    val b = bitmap
    if (b == null) placeholder() else Image(b.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
}

private object ThumbCache {
    private val cache = LruCache<String, Bitmap>(16)

    fun get(url: String): Bitmap? = cache.get(url)

    fun load(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null // thumbnails come from YouTube/Google over HTTPS
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            c.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(url, it) }
        } catch (_: Exception) {
            null
        }
    }
}
