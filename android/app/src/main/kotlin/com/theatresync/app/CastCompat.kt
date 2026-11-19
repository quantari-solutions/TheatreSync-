package com.theatresync.app

/**
 * Can this Chromecast show this video as it is? Pure rules, unit-tested.
 *
 * Chromecast (2nd/3rd gen): H.264 up to 1080p 60 fps, VP8; AAC/MP3/Opus/Vorbis/FLAC audio.
 * Chromecast Ultra, Chromecast with Google TV, Google TV Streamer: also HEVC/H.265, VP9, 4K.
 * (developers.google.com/cast/docs/media)
 */
object CastCompat {
    data class Media(
        val videoMime: String?,
        val width: Int,
        val height: Int,
        val fps: Float?,
        val audioMime: String?,
        val mbps: Double?,
        /** 6 = 5.1 surround. Older Chromecasts only decode stereo AAC. */
        val audioChannels: Int? = null,
    ) {
        val label: String
            get() = buildList {
                if (height > 0) add(resolutionName(width, height))
                videoMime?.let { add(codecName(it)) }
                fps?.takeIf { it > 0 }?.let { add("${it.toInt()} fps") }
                mbps?.let { add("%.0f Mbit/s".format(it)) }
                audioChannels?.takeIf { it > 2 }?.let { add(if (it == 6) "5.1 sound" else if (it == 8) "7.1 sound" else "$it-ch sound") }
            }.joinToString(" · ")
    }

    enum class Problem { CODEC, RESOLUTION, AUDIO, BITRATE }

    data class Verdict(val playable: Boolean, val problems: List<Problem>)

    /** Bitrate above which a typical home Wi-Fi link to an older Chromecast tends to stall. */
    const val COMFORT_MBPS = 20.0

    fun isAdvancedReceiver(model: String?): Boolean {
        val m = model?.lowercase() ?: return false
        return "ultra" in m || "google tv" in m || "streamer" in m || "nest hub" in m
    }

    fun verdict(media: Media, receiverModel: String?): Verdict {
        val advanced = isAdvancedReceiver(receiverModel)
        val problems = mutableListOf<Problem>()
        val v = media.videoMime?.lowercase()
        val videoOk = when (v) {
            null -> true // audio-only file
            "video/avc", "video/x-vnd.on2.vp8" -> true
            "video/hevc", "video/x-vnd.on2.vp9" -> advanced
            else -> false
        }
        if (!videoOk) problems += Problem.CODEC
        val longSide = maxOf(media.width, media.height)
        val shortSide = minOf(media.width, media.height)
        if (v != null && (longSide > 1920 || shortSide > 1080) && !advanced) problems += Problem.RESOLUTION
        val a = media.audioMime?.lowercase()
        val audioOk = a == null || a in setOf("audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/vorbis", "audio/flac", "audio/raw") ||
            (advanced && a in setOf("audio/ac3", "audio/eac3"))
        val channelsOk = advanced || (media.audioChannels ?: 2) <= 2
        if (!audioOk || !channelsOk) problems += Problem.AUDIO
        val playable = problems.isEmpty()
        if (playable && (media.mbps ?: 0.0) > COMFORT_MBPS && !advanced) problems += Problem.BITRATE // playable but may stall
        return Verdict(playable, problems)
    }

    /** What has to be done before this Chromecast can show the movie at [quality]. */
    enum class Plan { NONE, SOUND_ONLY, FULL }

    /**
     * NONE: cast the file as it is (instant). SOUND_ONLY: keep the picture untouched and only
     * turn the sound into stereo AAC (fast: no video re-encoding). FULL: re-encode the picture.
     * Picking a quality at or above the movie's own never re-encodes the picture.
     */
    fun plan(media: Media?, verdict: Verdict?, scaleTo: Int?): Plan {
        if (media == null || verdict == null) return if (scaleTo == null) Plan.NONE else Plan.FULL
        val p = verdict.problems
        val shortSide = minOf(media.width, media.height)
        val scaling = scaleTo != null && shortSide > scaleTo
        return when {
            Problem.CODEC in p || Problem.RESOLUTION in p || scaling -> Plan.FULL
            Problem.AUDIO in p -> Plan.SOUND_ONLY
            else -> Plan.NONE
        }
    }

    fun resolutionName(w: Int, h: Int): String {
        val s = minOf(w, h)
        return when {
            s >= 2000 -> "4K"
            s >= 1400 -> "1440p"
            s >= 1000 -> "1080p"
            s >= 700 -> "720p"
            s > 0 -> "${s}p"
            else -> "?"
        }
    }

    fun codecName(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        else -> mime.substringAfter('/').uppercase()
    }
}
