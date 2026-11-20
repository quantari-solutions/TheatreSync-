package com.theatresync.app

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
        var mutedForAd = false
        var volume = "100"
        var videoId: String? = null
        var segments = emptyList<SponsorBlock.Segment>()
        val skipped = HashSet<SponsorBlock.Segment>()
        var lastPos = 0.0
        var lastPosAt = 0L
        var playing = false
        var failures = 0
        while (running[tvId] === me && !me.isInterrupted) {
            try {
                val (lounge, session) = link ?: connect(tvId).also { link = it; afterId = 0 }
                fun command(name: String, params: Map<String, String> = emptyMap()) = runCatching { lounge.command(session, name, params) }
                for (e in LoungeEvents.parse(lounge.events(session, waitMs = 1_500, afterId = afterId))) {
                    afterId = maxOf(afterId, e.id)
                    val f = e.fields
                    when (e.name) {
                        "onAdStateChange", "adPlaying" -> {
                            val ended = e.name == "onAdStateChange" && f["adState"] in setOf("0", "1082") && f["currentTime"] != "0"
                            when {
                                ended -> if (mutedForAd) { command("setVolume", mapOf("volume" to volume, "muted" to "false")); mutedForAd = false }
                                f["isSkipEnabled"] == "true" -> {
                                    command("skipAd")
                                    onAction(tvId, Action.SKIPPED_AD)
                                    if (mutedForAd) { command("setVolume", mapOf("volume" to volume, "muted" to "false")); mutedForAd = false }
                                }
                                !mutedForAd -> {
                                    command("setVolume", mapOf("volume" to volume, "muted" to "true"))
                                    mutedForAd = true
                                    onAction(tvId, Action.MUTED_AD)
                                }
                            }
                        }
                        "onStateChange", "nowPlaying" -> {
                            if (f["state"] == "1" && mutedForAd) { command("setVolume", mapOf("volume" to volume, "muted" to "false")); mutedForAd = false }
                            playing = f["state"] == "1"
                            f["currentTime"]?.toDoubleOrNull()?.let { lastPos = it; lastPosAt = System.currentTimeMillis() }
                            f["videoId"]?.takeIf { it.isNotBlank() && it != videoId }?.let { id ->
                                videoId = id
                                segments = fetchSegments(id)
                                skipped.clear()
                            }
                        }
                        "onVolumeChanged" -> f["volume"]?.let { if (!mutedForAd) volume = it }
                    }
                }
                // Sponsor segments: jump to the end of the one we're in.
                val pos = positionSec(tvId) ?: (lastPos + if (playing) (System.currentTimeMillis() - lastPosAt) / 1000.0 else 0.0)
                SponsorBlock.current(segments, pos)?.takeIf { it !in skipped }?.let { seg ->
                    command("seekTo", mapOf("newTime" to "%.1f".format(java.util.Locale.US, seg.endSec)))
                    skipped += seg
                    onAction(tvId, Action.SKIPPED_SPONSOR)
                }
                failures = 0
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                link = null // the link expired or the TV went away: reconnect after a pause
                failures++
                try { Thread.sleep(minOf(30_000L, 2_000L * failures)) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun fetchSegments(videoId: String): List<SponsorBlock.Segment> = runCatching {
        val c = URL(SponsorBlock.url(videoId)).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 5_000
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) return@runCatching emptyList() // 404 = no segments for this video
        SponsorBlock.parse(c.inputStream.use { it.readBytes().decodeToString() }, videoId)
    }.getOrDefault(emptyList())
}
