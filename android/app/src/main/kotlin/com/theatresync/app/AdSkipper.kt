package com.theatresync.app

import com.theatresync.core.AdMute
import com.theatresync.core.LoungeEvents
import com.theatresync.core.SponsorBlock
import com.theatresync.core.YouTubeLounge
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube on the TV: presses "Skip ad" the moment YouTube allows it, mutes ads that can't be
 * skipped (sound comes back when the video resumes), and skips SponsorBlock segments (sponsors,
 * self-promotion, "like and subscribe"). Same approach as the open-source iSponsorBlockTV:
 * it listens to the TV's YouTube remote-control events and sends ordinary remote commands.
 *
 * One background thread per TV with its own Lounge link, so it never gets in the remote's way.
 */
class AdSkipper(
    /** Opens a fresh Lounge link to that TV's YouTube (blocking). */
    private val connect: (tvId: String) -> Pair<YouTubeLounge, YouTubeLounge.Session>,
    /** Where the TV's video is now, if known from the Chromecast's own status. */
    private val positionSec: (tvId: String) -> Double?,
    private val onAction: (tvId: String, what: Action) -> Unit,
    /** YouTube on the TV played, paused, sought or changed video (instant, from its remote-control events). */
    private val onPlayerChange: (tvId: String) -> Unit = {},
    /**
     * Mutes / unmutes the TV directly (Chromecast volume: instant, and the volume level is left alone).
     * Returns false if it can't (e.g. a smart TV found by DIAL): YouTube's own volume is used instead.
     */
    private val setTvMuted: (tvId: String, muted: Boolean) -> Boolean = { _, _ -> false },
) {
    enum class Action { SKIPPED_AD, MUTED_AD, SKIPPED_SPONSOR }

    private val running = ConcurrentHashMap<String, Thread>()

    /** Run for exactly these TVs (those playing YouTube right now). */
    fun sync(tvIds: Set<String>) {
        (running.keys - tvIds).forEach { stop(it) }
        (tvIds - running.keys).forEach { start(it) }
    }

    fun stopAll() = running.keys.toList().forEach { stop(it) }

    private fun stop(tvId: String) { running.remove(tvId)?.interrupt() }

    private fun start(tvId: String) {
        val t = Thread({ loop(tvId) }, "ad-skipper").apply { isDaemon = true }
        running[tvId] = t
        t.start()
    }

    private fun loop(tvId: String) {
        val me = Thread.currentThread()
        var link: Pair<YouTubeLounge, YouTubeLounge.Session>? = null
        var afterId = 0L
        val ad = AdMute()
        var volume = "100"
        var videoId: String? = null
        var segments = emptyList<SponsorBlock.Segment>()
        val skipped = HashSet<SponsorBlock.Segment>()
        var lastPos = 0.0
        var lastPosAt = 0L
        var playing = false
        var failures = 0
        var lastMuteAt = 0L
        while (running[tvId] === me && !me.isInterrupted) {
            try {
                val (lounge, session) = link ?: connect(tvId).also { link = it; afterId = 0 }
                fun command(name: String, params: Map<String, String> = emptyMap()) = runCatching { lounge.command(session, name, params) }
                // Belt and braces (owner: "the mute isn't working for the ads"): a Chromecast on an AV receiver
                // usually has fixed volume and ignores mute, so YouTube on the TV is muted too, every way it
                // accepts ("mute"/"unMute" and setVolume muted), and the mute is re-applied while the ad lasts.
                fun mute(on: Boolean) {
                    setTvMuted(tvId, on)
                    command(if (on) "mute" else "unMute")
                    command("setVolume", mapOf("volume" to volume, "muted" to on.toString()))
                    lastMuteAt = System.currentTimeMillis()
                }
                // While an advert is muted, check more often so the sound comes back right on time.
                for (e in LoungeEvents.parse(lounge.events(session, waitMs = if (ad.muted) 700 else 1_500, afterId = afterId))) {
                    afterId = maxOf(afterId, e.id)
                    val f = e.fields
                    val now = System.currentTimeMillis()
                    when (e.name) {
                        "onAdStateChange", "adPlaying" -> {
                            val ended = e.name == "onAdStateChange" && f["adState"] in setOf("0", "1082") && f["currentTime"] != "0"
                            val length = (f["duration"] ?: f["adDuration"] ?: f["videoDuration"])?.toDoubleOrNull()
                            val at = f["currentTime"]?.toDoubleOrNull()
                            when {
                                ended -> if (ad.adOver()) mute(false)
                                f["isSkipEnabled"] == "true" -> {
                                    command("skipAd")
                                    onAction(tvId, Action.SKIPPED_AD)
                                    if (ad.adOver()) mute(false)
                                }
                                else -> if (ad.adPlaying(now, length, at)) { mute(true); onAction(tvId, Action.MUTED_AD) }
                            }
                        }
                        "onStateChange", "nowPlaying" -> {
                            onPlayerChange(tvId)
                            // The film is playing again: the advert is over.
                            if (f["state"] == "1" && ad.adOver()) mute(false)
                            playing = f["state"] == "1"
                            f["currentTime"]?.toDoubleOrNull()?.let { lastPos = it; lastPosAt = System.currentTimeMillis() }
                            f["videoId"]?.takeIf { it.isNotBlank() && it != videoId }?.let { id ->
                                videoId = id
                                segments = fetchSegments(id)
                                skipped.clear()
                            }
                        }
                        "onVolumeChanged" -> f["volume"]?.let { if (!ad.muted) volume = it }
                    }
                }
                // The advert's time is up (or the safety limit passed): sound back, never forgotten.
                if (ad.tick(System.currentTimeMillis())) mute(false)
                // Still in the advert: keep it muted (in case the TV or YouTube dropped the first mute).
                else if (ad.muted && System.currentTimeMillis() - lastMuteAt > 3_000) mute(true)
                // Sponsor segments: jump to the end of the one we're in.
                val pos = positionSec(tvId) ?: (lastPos + if (playing) (System.currentTimeMillis() - lastPosAt) / 1000.0 else 0.0)
                SponsorBlock.current(segments, pos)?.takeIf { it !in skipped }?.let { seg ->
                    command("seekTo", mapOf("newTime" to "%.1f".format(java.util.Locale.US, seg.endSec)))
                    skipped += seg
                    onAction(tvId, Action.SKIPPED_SPONSOR)
                }
                failures = 0
            } catch (_: InterruptedException) {
                if (ad.adOver()) unmuteAll(tvId, link) // stopped (ad skipper off / YouTube closed): never leave it muted
                return
            } catch (_: Exception) {
                // Lost YouTube mid-advert: unmute rather than leave the TV silent.
                if (ad.connectionLost(System.currentTimeMillis())) unmuteAll(tvId, link)
                link = null // the link expired or the TV went away: reconnect after a pause
                failures++
                try { Thread.sleep(minOf(30_000L, 2_000L * failures)) } catch (_: InterruptedException) { if (ad.adOver()) unmuteAll(tvId, link); return }
            }
        }
        if (ad.adOver()) unmuteAll(tvId, link) // finished for this TV: never leave it muted
    }

    /** Sound back on every path an advert could have been muted on (TV and YouTube), best effort. */
    private fun unmuteAll(tvId: String, link: Pair<YouTubeLounge, YouTubeLounge.Session>?) {
        setTvMuted(tvId, false)
        link?.let { (l, s) -> runCatching { l.command(s, "unMute") } }
    }

    private fun fetchSegments(videoId: String): List<SponsorBlock.Segment> = runCatching {
        val c = URL(SponsorBlock.url(videoId)).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 5_000
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) return@runCatching emptyList() // 404 = no segments for this video
        SponsorBlock.parse(c.inputStream.use { it.readBytes().decodeToString() }, videoId)
    }.getOrDefault(emptyList())
}
