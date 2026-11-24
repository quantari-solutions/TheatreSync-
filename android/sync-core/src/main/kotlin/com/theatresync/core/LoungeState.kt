package com.theatresync.core

/**
 * What the TV's YouTube says it is doing, read from YouTube's remote-control events ("onStateChange",
 * "nowPlaying"). This is how a cast over the internet is confirmed: the TV itself reports playing,
 * paused or buffering, and where it is in the video.
 */
object LoungeState {
    enum class Play { PLAYING, PAUSED, BUFFERING, STOPPED, UNKNOWN }

    data class State(val play: Play, val positionSec: Double?, val durationSec: Double?, val videoId: String?)

    /** The newest state in [events], or null if the TV reported nothing. */
    fun parse(events: String): State? {
        val code = last(""""state"\s*:\s*"(-?\d+)"""", events)
        val time = last(""""currentTime"\s*:\s*"([0-9.]+)"""", events)?.toDoubleOrNull()
        val duration = last(""""duration"\s*:\s*"([0-9.]+)"""", events)?.toDoubleOrNull()?.takeIf { it > 0 }
        val video = last(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""", events)
        if (code == null && time == null && video == null) return null
        val play = when (code) {
            "1" -> Play.PLAYING
            "2" -> Play.PAUSED
            "3" -> Play.BUFFERING
            "0", "4" -> Play.STOPPED
            else -> Play.UNKNOWN
        }
        return State(play, time, duration, video)
    }

    private fun last(pattern: String, s: String) = Regex(pattern).findAll(s).lastOrNull()?.groupValues?.get(1)
}
