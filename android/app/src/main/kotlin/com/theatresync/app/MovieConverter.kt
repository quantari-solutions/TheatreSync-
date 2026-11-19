@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.theatresync.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.security.MessageDigest

/** Movie quality the owner can pick before casting. */
enum class Quality(val shortSide: Int?, val videoBitrate: Int?) {
    ORIGINAL(null, null),
    P1080(1080, 8_000_000),
    P720(720, 4_000_000),
}

/** Even-numbered size with the given short side, keeping the aspect ratio. Null = don't scale. */
internal fun sizeFor(w: Int, h: Int, shortSide: Int?): Pair<Int, Int>? {
    if (shortSide == null || w <= 0 || h <= 0) return null
    val currentShort = minOf(w, h)
    if (currentShort <= shortSide) return null // never upscale
    val scale = shortSide.toDouble() / currentShort
    fun even(x: Double) = (Math.round(x / 2.0) * 2).toInt()
    return even(w * scale) to even(h * scale)
}

object MovieInspector {
    /** Reads codec, size, frame rate and bitrate. Blocking: call off the main thread. */
    fun inspect(context: Context, uri: Uri, sizeBytes: Long): CastCompat.Media? = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var videoMime: String? = null
            var audioMime: String? = null
            var w = 0
            var h = 0
            var fps: Float? = null
            var durationUs = 0L
            var channels: Int? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    // Phone videos are often stored landscape with a rotation flag.
                    val rotation = if (f.containsKey("rotation-degrees")) f.getInteger("rotation-degrees") else 0
                    if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                    fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
                        ?: runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null
                }
            }
            val mbps = if (durationUs > 0) sizeBytes * 8.0 / (durationUs / 1_000_000.0) / 1_000_000 else null
            CastCompat.Media(videoMime, w, h, fps, audioMime, mbps, channels)
        } finally {
            ex.release()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Converts a movie to H.264 + AAC at the chosen quality so any Chromecast can show it
 * smoothly. Runs Media3 Transformer (hardware encoder) on the main looper; results are
 * cached so the same movie/quality plays instantly next time.
 */
class MovieConverter(private val context: Context) {
    interface Listener {
        fun onProgress(percent: Int)
        fun onDone(file: File)
        fun onFailed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private val dir = File(context.filesDir, "converted").apply { mkdirs() }

    fun cachedFile(uri: Uri, quality: Quality, plan: CastCompat.Plan): File? =
        target(uri, quality, plan).takeIf { it.exists() && it.length() > 0 }

    /**
     * @param source what MovieInspector found (used to keep the exact shape when scaling down)
     * @param plan SOUND_ONLY copies the picture untouched and only converts the sound to stereo
     *   AAC (minutes, not tens of minutes); FULL also re-encodes the picture as H.264.
     */
    fun start(uri: Uri, quality: Quality, source: CastCompat.Media?, plan: CastCompat.Plan, listener: Listener) {
        cancel()
        val out = target(uri, quality, plan)
        val partial = File(out.path + ".part")
        partial.delete()
        pruneCache(keep = out)
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(quality.videoBitrate ?: 8_000_000).build())
            .build()
        val builder = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
        // Sound only: no video settings, so Transformer copies the picture as it is.
        if (plan == CastCompat.Plan.FULL) builder.setVideoMimeType(MimeTypes.VIDEO_H264).setEncoderFactory(encoders)
        val t = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacksAndMessages(null)
                    if (partial.renameTo(out)) listener.onDone(out) else listener.onFailed("couldn't save the converted movie")
                    transformer = null
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    main.removeCallbacksAndMessages(null)
                    partial.delete()
                    listener.onFailed(exportException.errorCodeName)
                    transformer = null
                }
            })
            .build()
        val audio = stereoDownmix(source?.audioChannels)?.let { listOf(it) } ?: emptyList()
        val video = if (plan != CastCompat.Plan.FULL) emptyList() else outputSize(source, quality)?.let { (w, h) ->
            listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        } ?: emptyList()
        val effects = if (audio.isEmpty() && video.isEmpty()) Effects.EMPTY else Effects(audio, video)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        transformer = t
        t.start(item, partial.path)
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) listener.onProgress(holder.progress)
                main.postDelayed(this, 500)
            }
        })
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
    }

    /** Width × height that keeps the source's shape with the chosen short side; null = keep size. */
    internal fun outputSize(source: CastCompat.Media?, quality: Quality): Pair<Int, Int>? =
        sizeFor(source?.width ?: 0, source?.height ?: 0, quality.shortSide)

    private fun target(uri: Uri, quality: Quality, plan: CastCompat.Plan): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val q = if (plan == CastCompat.Plan.SOUND_ONLY) "SOUND" else quality.name
        return File(dir, "${hash}_$q.mp4")
    }

    /** Converted movies are big: keep only the newest two besides the one being made. */
    private fun pruneCache(keep: File) {
        dir.listFiles()?.filter { it != keep && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
}

/**
 * 5.1 / 7.1 → stereo (ITU-style: centre and surrounds at −3 dB, LFE dropped), for Chromecasts
 * that only decode stereo AAC. Null when no downmix is needed or the layout is unknown.
 * Android's decoder order: FL, FR, FC, LFE, BL, BR (, SL, SR).
 */
internal fun stereoDownmix(channels: Int?): ChannelMixingAudioProcessor? {
    val c = channels ?: return null
    if (c != 6 && c != 8) return null
    val k = 0.7071f
    // coefficients[input * 2 + output]
    val m = FloatArray(c * 2)
    fun put(input: Int, left: Float, right: Float) { m[input * 2] = left; m[input * 2 + 1] = right }
    put(0, 1f, 0f); put(1, 0f, 1f); put(2, k, k); put(3, 0f, 0f); put(4, k, 0f); put(5, 0f, k)
    if (c == 8) { put(6, k, 0f); put(7, 0f, k) }
    val norm = 1f / (1f + k) // loud enough for dialogue, rare peaks are soft-limited by the encoder
    return ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix(c, 2, m).scaleBy(norm))
        // Already-stereo or mono parts of the same file pass through unchanged.
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
    }
}
