package com.theatresync.app

import androidx.core.net.toUri
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.theatresync.core.CastActivity
import com.theatresync.core.CastStatus
import com.theatresync.core.Dial
import com.theatresync.core.MediaServer
import com.theatresync.core.MediaSource
import com.theatresync.core.Mp4VideoOnly
import com.theatresync.core.YouTubeLounge
import com.theatresync.core.YouTubeScreenFinder
import com.theatresync.core.Payload
import com.theatresync.core.Platform
import com.theatresync.core.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Everything that happens during a movie night, on the host phone:
 *  - finds the Chromecast and sends it the movie (picture) from this phone,
 *  - plays the movie's sound in this phone's headphones, kept in step with the TV,
 *  - mutes the TV/receiver speakers,
 *  - lets friends' iPhones/iPads join over Wi-Fi.
 * All methods must be called on the main thread.
 */
class MovieSession(private val app: Application) {

    enum class Phase { SETUP, STARTING, PLAYING, PAUSED, ENDED }

    enum class YouTubeState { IDLE, STARTING, PLAYING, FAILED }

    data class NewDevice(val id: Int, val name: String)

    /** @param youTubeOnly a smart TV found via DIAL (e.g. Samsung The Frame): YouTube only, no movies. */
    data class Tv(val id: String, val name: String, val isSpeaker: Boolean = false, val youTubeOnly: Boolean = false)

    data class Ui(
        val castAvailable: Boolean = true,
        val movieTitle: String? = null,
        val tvs: List<Tv> = emptyList(),
        val connectedTv: String? = null,
        val phase: Phase = Phase.SETUP,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val soundDelayMs: Int = 0,
        val headphonesHere: Boolean = true,
        val muteTv: Boolean = true,
        val headphoneName: String? = null,
        val friends: Int = 0,
        val joinAddress: String? = null,
        val driftMs: Long? = null,
        val message: String? = null,
        /** What each Chromecast is showing right now (YouTube, a mirrored tab, …), from any device. */
        val nowCasting: List<TvWatchers.NowCasting> = emptyList(),
        /** YouTube casting started from TheatreSync. */
        val youTube: YouTubeState = YouTubeState.IDLE,
        val youTubeVideoId: String? = null,
        /** The TV the YouTube video was sent to (so the app can open its player at once). */
        val youTubeTvId: String? = null,
        /** Output the owner picked for TheatreSync's sound (AudioDeviceInfo id), or null. */
        val preferredOutputId: Int? = null,
        /** The Chromecast is loading (buffering) the movie right now. */
        val tvBuffering: Boolean = false,
        /** e.g. "4K · H.265 · 30 fps · 48 Mbit/s" for the chosen movie. */
        val movieInfo: String? = null,
        val movieVerdict: CastCompat.Verdict? = null,
        val quality: Quality = Quality.ORIGINAL,
        /** 0–100 while converting the movie for the TV; null when not converting. */
        val prepareProgress: Int? = null,
        /** Estimated seconds left while preparing, once it can be estimated. */
        val prepareEtaSec: Long? = null,
        /** True when only the sound is converted (the picture is copied as it is). */
        val prepareSoundOnly: Boolean = false,
        /** What Start will do with the chosen movie at the chosen quality on the connected TV. */
        val moviePlan: CastCompat.Plan? = null,
        /** Headphone equalizer / bass boost. */
        val fx: HeadphoneFx.Settings = HeadphoneFx.Settings(),
        /** Surround-sound movies: also give the TV (room speakers) sound, which needs a slower conversion. */
        val roomSoundToo: Boolean = false,
        /** Headphones that just connected (asks "use them?" while a movie plays). */
        val newDevice: NewDevice? = null,
        /** Bumped whenever outputs appear/disappear, so lists refresh at once. */
        val audioDevicesVersion: Int = 0,
        /** Switch to newly connected headphones without asking. */
        val autoSwitch: Boolean = false,
        /** true = TV confirmed muted, false = TV ignored the mute, null = unknown / not muting. */
        val tvMuteConfirmed: Boolean? = null,
    ) {
        val canStart: Boolean get() = movieTitle != null && connectedTv != null
    }

    private val prefs = app.getSharedPreferences("theatresync", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString().take(12))
        .also { prefs.edit { putString("deviceId", it) } }

    private val _ui = MutableStateFlow(Ui(soundDelayMs = prefs.getInt("soundDelayMs", 0), fx = HeadphoneFx.load(app)))
    val ui: StateFlow<Ui> = _ui

    private val main = Handler(Looper.getMainLooper())
    private val castContext: CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(app)
    } catch (_: Exception) {
        null
    }

    private val router = MediaRouter.getInstance(app)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private var movie: ContentUriSource? = null
    private var castSession: CastSession? = null
    private var player: HeadphonePlayer? = null
    private var server: MediaServer? = null
    private val announcer = HostAnnouncer(app)
    private val tvWatchers = TvWatchers { list -> publishNowCasting(list) }
    private var rawNowCasting: List<TvWatchers.NowCasting> = emptyList()
    private var knownTvs: List<TvWatchers.Tv> = emptyList()
    /** Cast sessions the user just stopped: hidden at once, before the TV confirms. */
    private val stoppedSessions = mutableSetOf<String>()

    /** Shown at once after a YouTube cast succeeds, until the TV's own status arrives. */
    private var optimistic: TvWatchers.NowCasting? = null
    private var optimisticUntilMs = 0L

    /** What we told the TV, for the ⓘ technical details if the picture never appears. */
    data class Diagnostics(val movieUrl: String, val phoneIp: String, val tvIp: String?)

    @Volatile var lastDiagnostics: Diagnostics? = null
        private set

    /** Why the last YouTube cast failed, for the ⓘ technical details. */
    @Volatile var lastYouTubeError: String? = null
        private set
    private var startedAtMs = 0L
    private var playingSinceMs = 0L
    // Streaming speed check
    private var lastBytes = 0L
    private var lastBytesAtMs = 0L
    private val speedSamples = ArrayDeque<Double>()
    private val bufferingStarts = ArrayDeque<Long>()
    private var wasBuffering = false
    private var speedWarned = false

    /** Average speed the movie reached the TV recently (Mbit/s), and what the video needs. */
    @Volatile var streamMbps: Double? = null
        private set
    @Volatile var videoMbps: Double? = null
        private set
    private var headphoneWarned = false

    /** Result of the last headphone sound test, for the ⓘ technical details. */
    @Volatile var lastSoundTest: String? = null
        private set

    fun recordSoundTest(summary: String) { lastSoundTest = summary }

    @Volatile var lastSpeedProblem: String? = null
        private set

    /** Why headphone sound didn't start, for the ⓘ technical details. */
    @Volatile var lastHeadphoneProblem: String? = null
        private set
    private var tvEverPlayed = false

    /** Number of times the TV fetched the movie in this session (0 = it never reached the phone). */
    val tvMovieRequests: Int get() = server?.mediaRequests ?: 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var interrupted = false
    private var tickCount = 0

    @Volatile private var snapshot: Payload.PlaybackState? = null

    // ---------------------------------------------------------------- setup

    private fun chooseMovieUnsafe(uri: Uri) {
        // Some file providers throw (permission revoked, cloud file offline): never crash for that.
        val source = try { ContentUriSource.from(app, uri) } catch (_: Exception) { null }
        if (source == null) {
            say(R.string.msg_bad_file)
            return
        }
        movie = source
        movieMedia = null
        qualityChosenByUser = false
        prefs.edit { putString(KEY_MOVIE_URI, uri.toString()) }
        _ui.update { it.copy(movieTitle = source.title, message = null, movieInfo = null, movieVerdict = null, prepareProgress = null) }
        inspectMovie(source)
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = guard("routes") { refreshTvs() }
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = guard("routes") { refreshTvs() }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = guard("routes") { refreshTvs() }
    }

    /** Call while the setup screen is visible. */
    private fun startTvSearchUnsafe() {
        scanDial()
        if (castContext == null) return
        applyScanMode()
        refreshTvs()
    }

    // ------------------------------------------------------------- smart TVs via DIAL (YouTube only)

    private var castTvs: List<Tv> = emptyList()
    private val dialTvs = java.util.concurrent.ConcurrentHashMap<String, Dial.Device>()
    @Volatile private var dialScanning = false
    private var lastDialScanMs = 0L

    private fun publishTvs() {
        val castNames = castTvs.map { it.name.lowercase() }.toSet()
        val smart = dialTvs.map { (id, d) -> Tv(id, d.name.removePrefix("[TV] ").trim(), youTubeOnly = true) }
            .filter { it.name.lowercase() !in castNames }
            .sortedBy { it.name }
        _ui.update { it.copy(tvs = castTvs + smart) }
    }

    /** Looks for Samsung/LG/Sony… TVs on the Wi-Fi (at most once every 30 s). */
    private fun scanDial() {
        val now = SystemClock.elapsedRealtime()
        if (dialScanning || now - lastDialScanMs < 30_000) return
        dialScanning = true
        lastDialScanMs = now
        Thread({
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = runCatching { wifi.createMulticastLock("theatresync-dial").apply { setReferenceCounted(false); acquire() } }.getOrNull()
            try {
                val found = Dial.discover()
                main.post {
                    dialTvs.clear()
                    found.forEach { dialTvs["dial:${it.id}"] = it }
                    publishTvs()
                }
            } catch (e: Exception) {
                lastYouTubeError = "TV search (DIAL) failed: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { lock?.release() }
                dialScanning = false
            }
        }, "dial-scan").apply { isDaemon = true }.start()
    }

    private fun isDial(tvId: String) = tvId.startsWith("dial:")

    private fun tvName(tvId: String): String? =
        dialTvs[tvId]?.name?.removePrefix("[TV] ")?.trim() ?: knownTvs.firstOrNull { it.id == tvId }?.name

    /** The YouTube "screen ID" of a Chromecast (Cast connection) or a smart TV (DIAL). Blocking. */
    private fun youTubeScreenId(tvId: String): String {
        dialTvs[tvId]?.let { return Dial.youTubeScreenId(it) }
        val tv = knownTvs.firstOrNull { it.id == tvId } ?: throw java.io.IOException("TV not found")
        return YouTubeScreenFinder({ TvWatchers.openTls(tv.address, tv.port) }).screenId()
    }

    /**
     * Battery: an active network scan only while the user still has to pick a TV.
     * Once connected (or a movie is playing) passive discovery is enough.
     */
    private fun applyScanMode() {
        val active = _ui.value.connectedTv == null && _ui.value.phase == Phase.SETUP
        router.addCallback(
            selector, routerCallback,
            if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )
    }

    private fun stopTvSearchUnsafe() {
        router.removeCallback(routerCallback)
        tvWatchers.stopAll()
    }

    private fun refreshTvs() {
        val routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
        castTvs = routes.map { r ->
            val device = CastDevice.getFromBundle(r.extras)
            // Speakers (e.g. Nest Audio) can't show video: use a speaker icon for them.
            Tv(r.id, r.name, isSpeaker = device != null && !device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT))
        }
        publishTvs()
        knownTvs = routes.mapNotNull { r ->
            val device = CastDevice.getFromBundle(r.extras) ?: return@mapNotNull null
            val address = device.inetAddress ?: return@mapNotNull null
            TvWatchers.Tv(r.id, r.name, address, device.servicePort.takeIf { it > 0 } ?: 8009)
        }
        tvWatchers.update(
            routes.mapNotNull { r ->
                val device = CastDevice.getFromBundle(r.extras) ?: return@mapNotNull null
                val address = device.inetAddress ?: return@mapNotNull null
                TvWatchers.Tv(r.id, r.name, address, device.servicePort.takeIf { it > 0 } ?: 8009)
            },
        )
    }

    private fun publishNowCasting(list: List<TvWatchers.NowCasting>) {
        rawNowCasting = list
        // Our own movie is shown on the playing screen, not as "now casting".
        // Also hide Google's media player left open with nothing loaded after a movie ends.
        val withOptimistic = optimistic?.let { o ->
            val real = list.any { it.tvId == o.tvId && it.activity.appId == o.activity.appId }
            if (real || SystemClock.elapsedRealtime() > optimisticUntilMs) { optimistic = null; list } else list.filterNot { it.tvId == o.tvId } + o
        } ?: list
        val now = SystemClock.elapsedRealtime()
        val visible = withOptimistic.filterNot {
            val a = it.activity
            val isOptimistic = it === optimistic
            (a.appId == CastStatus.APP_DEFAULT_MEDIA_RECEIVER && server != null) ||
                (a.sessionId != null && a.sessionId in stoppedSessions) ||
                // Connected but nothing playing (e.g. Google's player opened just by connecting,
                // or YouTube left open on another TV): the TV row says "Connected" instead.
                (!isOptimistic && a.kind != CastActivity.Kind.MIRRORING && !a.hasMedia) ||
                (!isOptimistic && a.kind != CastActivity.Kind.MIRRORING && a.playerState in setOf(null, "IDLE")) ||
                isStale(it, now)
        }
        _ui.update { it.copy(nowCasting = visible) }
    }

    /** Last position seen per TV, to spot TVs that claim "playing" but haven't moved for a while. */
    private val lastMove = HashMap<String, Pair<Double, Long>>()

    private fun isStale(item: TvWatchers.NowCasting, now: Long): Boolean {
        val a = item.activity
        val t = a.currentTimeSec ?: return false
        val key = "${item.tvId}|${a.title}"
        val prev = lastMove[key]
        if (prev == null || prev.first != t || a.playerState != "PLAYING") {
            lastMove[key] = t to now
            return false
        }
        return now - prev.second > 45_000 // "playing" with the clock stuck for 45 s: a stale report
    }

    /** What a TV is doing: its real status, or (smart TVs, which report nothing) our own record. */
    private fun activityFor(tvId: String): CastActivity? =
        rawNowCasting.firstOrNull { it.tvId == tvId }?.activity ?: optimistic?.takeIf { it.tvId == tvId }?.activity

    /** YouTube on any TV is controlled through YouTube's own remote link (the TV's media channel ignores us). */
    private fun usesYouTubeRemote(tvId: String) = isDial(tvId) || activityFor(tvId)?.kind == CastActivity.Kind.YOUTUBE

    /** Where a smart TV's video is now (it reports nothing back), estimated from our own commands. */
    private var dialPosSec = 0.0
    private var dialPosAtMs = 0L
    private fun dialPositionSec(tvId: String): Double {
        val playing = activityFor(tvId)?.isPlaying == true
        return dialPosSec + if (playing) (SystemClock.elapsedRealtime() - dialPosAtMs) / 1000.0 else 0.0
    }
    private fun setDialPosition(sec: Double, playing: Boolean? = null) {
        dialPosSec = sec.coerceAtLeast(0.0)
        dialPosAtMs = SystemClock.elapsedRealtime()
        optimistic?.let { o ->
            optimistic = o.copy(activity = o.activity.copy(currentTimeSec = dialPosSec, playerState = playing?.let { if (it) "PLAYING" else "PAUSED" } ?: o.activity.playerState))
            publishNowCasting(rawNowCasting)
        }
    }

    private fun castPlayPauseUnsafe(tvId: String) {
        val a = activityFor(tvId) ?: return
        if (usesYouTubeRemote(tvId)) {
            if (isDial(tvId)) setDialPosition(dialPositionSec(tvId), playing = !a.isPlaying)
            youTubeRemote(tvId, if (a.isPlaying) "pause" else "play")
        } else {
            tvWatchers.playPause(tvId, play = !a.isPlaying)
        }
    }

    private fun castSeekToUnsafe(tvId: String, seconds: Double) {
        val t = seconds.coerceAtLeast(0.0)
        if (usesYouTubeRemote(tvId)) {
            if (isDial(tvId)) setDialPosition(t)
            youTubeRemote(tvId, "seekTo", mapOf("newTime" to "%.1f".format(java.util.Locale.US, t)))
        } else {
            tvWatchers.seekTo(tvId, t)
        }
    }

    private fun castSkipUnsafe(tvId: String, deltaSec: Double) {
        val now = if (isDial(tvId)) dialPositionSec(tvId) else activityFor(tvId)?.currentTimeSec ?: return
        castSeekToUnsafe(tvId, now + deltaSec)
    }


    /** Stops whatever is on that TV. If it's our own Cast session, end that cleanly too. */
    private fun castStopUnsafe(tvId: String) {
        if (isDial(tvId)) {
            youTubeRemote(tvId, "stopVideo")
            optimistic = null
            publishNowCasting(rawNowCasting)
            resetYouTube()
            CastIndicator.hide(app)
            return
        }
        val a = rawNowCasting.firstOrNull { it.tvId == tvId }?.activity
        // Close the card immediately; the TV goes back to its "Ready to cast" screen.
        a?.sessionId?.let { stoppedSessions += it }
        publishNowCasting(rawNowCasting)
        if (server != null && a?.appId == CastStatus.APP_DEFAULT_MEDIA_RECEIVER) {
            stopMovie()
            return
        }
        tvWatchers.stopCasting(tvId)
        if (a?.appId == CastStatus.APP_YOUTUBE) resetYouTube()
        if (castSession == null) CastIndicator.hide(app)
    }

    /** One YouTube remote link per TV, reused so Next/Previous/captions respond quickly. */
    private val youTubeRemotes = HashMap<String, Pair<YouTubeLounge, YouTubeLounge.Session>>()

    /** Next / previous video on the TV's YouTube app. */
    fun youTubeNext(tvId: String) = youTubeRemote(tvId, "next")
    fun youTubePrevious(tvId: String) = youTubeRemote(tvId, "previous")

    /** Captions on (in [language], e.g. "en") or off (null) for the video playing on the TV. */
    fun youTubeCaptions(tvId: String, videoId: String?, language: String?) =
        youTubeRemote(tvId, "setSubtitlesTrack", mapOf("videoId" to (videoId ?: ""), "languageCode" to (language ?: "")))

    private fun youTubeRemote(tvId: String, command: String, params: Map<String, String> = emptyMap()) = guard("YouTube $command") {
        if (tvName(tvId) == null) return@guard say(R.string.yt_no_tv)
        Thread({
            fun link(): Pair<YouTubeLounge, YouTubeLounge.Session> {
                val screenId = youTubeScreenId(tvId)
                val lounge = YouTubeLounge()
                return (lounge to lounge.bind(lounge.loungeToken(screenId))).also { synchronized(youTubeRemotes) { youTubeRemotes[tvId] = it } }
            }
            try {
                val (lounge, s) = synchronized(youTubeRemotes) { youTubeRemotes[tvId] } ?: link()
                try { lounge.command(s, command, params) } catch (_: Exception) {
                    // The link expires after a while: make a fresh one and try once more.
                    val (l2, s2) = link()
                    l2.command(s2, command, params)
                }
                if (!isDial(tvId)) main.postDelayed({ tvWatchers.pollNow(tvId) }, 800)
            } catch (e: Exception) {
                lastYouTubeError = "YouTube $command failed: ${e.javaClass.simpleName}: ${e.message}"
                CrashReporter.handled(app, "YouTube $command", e)
                main.post { say(R.string.yt_remote_failed) }
            }
        }, "youtube-remote").start()
    }

    /**
     * Plays a YouTube video on the chosen TV: starts the YouTube app on the Chromecast and
     * tells it which video to play (Lounge API). Runs off the main thread.
     */
    fun castYouTube(videoId: String, tvId: String) = guard("castYouTube") { castYouTubeUnsafe(videoId, tvId) }

    private fun castYouTubeUnsafe(videoId: String, tvId: String) {
        if (server != null) return say(R.string.yt_stop_movie_first)
        val name = tvName(tvId) ?: return say(R.string.yt_no_tv)
        val tv = object { val id = tvId; val name = name }
        _ui.update { it.copy(youTube = YouTubeState.STARTING, youTubeVideoId = videoId, youTubeTvId = tv.id, message = null) }
        Thread({
            var step = "starting YouTube on the TV"
            val ok = try {
                val screenId = youTubeScreenId(tvId)
                step = "asking YouTube to play the video"
                val lounge = YouTubeLounge()
                val session = lounge.bind(lounge.loungeToken(screenId))
                lounge.setPlaylist(session, videoId)
                synchronized(youTubeRemotes) { youTubeRemotes[tvId] = lounge to session }
                lastYouTubeError = null
                true
            } catch (e: Exception) {
                lastYouTubeError = "Failed while $step: ${e.javaClass.simpleName}: ${e.message}"
                CrashReporter.handled(app, "YouTube cast ($step)", e)
                false
            }
            main.post {
                if (ok) {
                    // Show the player straight away with the video's thumbnail; the TV's real
                    // status (title, channel, position) replaces it within a couple of seconds.
                    optimistic = TvWatchers.NowCasting(
                        tv.id, tv.name,
                        CastActivity(
                            appId = CastStatus.APP_YOUTUBE, appName = "YouTube", kind = CastActivity.Kind.YOUTUBE,
                            imageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", playerState = "BUFFERING",
                        ),
                    )
                    // Smart TVs (DIAL) send no status back, so keep their card until stopped.
                    optimisticUntilMs = SystemClock.elapsedRealtime() + if (isDial(tv.id)) 6 * 3_600_000L else 20_000L
                    if (isDial(tv.id)) { dialPosSec = 0.0; dialPosAtMs = SystemClock.elapsedRealtime() }
                    if (isDial(tv.id)) optimistic = optimistic?.copy(activity = optimistic!!.activity.copy(playerState = "PLAYING", contentId = videoId, title = null))
                    publishNowCasting(rawNowCasting)
                    if (!isDial(tv.id)) tvWatchers.pollNow(tv.id)
                    CastIndicator.show(app, app.getString(R.string.casting_youtube_to, tv.name), tv.id)
                }
                _ui.update { it.copy(youTube = if (ok) YouTubeState.PLAYING else YouTubeState.FAILED) }
            }
        }, "youtube-cast").start()
    }

    fun resetYouTube() = _ui.update { it.copy(youTube = YouTubeState.IDLE) }


    private fun connectTvUnsafe(id: String) {
        router.routes.firstOrNull { it.id == id }?.select()
    }

    private fun disconnectTvUnsafe() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun sessionListener() = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = guard("tv connected") { onTvConnected(session) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = guard("tv resumed") { onTvConnected(session) }
        override fun onSessionEnded(session: CastSession, error: Int) = guard("tv disconnected") { onTvDisconnected() }
        override fun onSessionStartFailed(session: CastSession, error: Int) = say(R.string.msg_tv_failed)
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun onTvConnected(session: CastSession) {
        castSession = session
        restoreTvSoundIfWeMutedIt(session)
        val tvName = session.castDevice?.friendlyName ?: "Chromecast"
        _ui.update { it.copy(connectedTv = tvName, message = null) }
        if (server == null) CastIndicator.show(app, app.getString(R.string.casting_to, tvName))
        if (castContext != null) applyScanMode()
        refreshVerdict()
    }

    private fun onTvDisconnected() {
        castSession = null
        CastIndicator.hide(app)
        if (_ui.value.phase != Phase.SETUP) teardown()
        _ui.update { it.copy(connectedTv = null, phase = Phase.SETUP) }
    }

    // ---------------------------------------------------------------- the movie

    /** What must happen before the connected TV can show the chosen movie at the chosen quality. */
    private fun currentPlan(): CastCompat.Plan {
        val ui = _ui.value
        return CastCompat.plan(movieMedia, ui.movieVerdict, ui.quality.shortSide)
    }

    private fun startMovieUnsafe() {
        val original = movie ?: return
        castSession ?: return
        // Start the movie service now, while the app is on screen: Android won't allow it later
        // from the background (e.g. when a conversion finishes after switching apps).
        HostService.start(app)
        val q = _ui.value.quality
        val plan = currentPlan()
        if (plan == CastCompat.Plan.NONE) return castMovie(original)
        if (plan == CastCompat.Plan.SOUND_ONLY && !_ui.value.roomSoundToo) {
            // Instant: the TV gets the picture only (index rewritten on the fly, nothing copied);
            // the sound plays in the headphones from the original file. Falls back to converting.
            _ui.update { it.copy(prepareProgress = 0, prepareEtaSec = null, prepareSoundOnly = false, message = null) }
            Thread({
                val pictureOnly = Mp4VideoOnly.of(original)
                main.post {
                    guard("cast picture only") {
                        _ui.update { it.copy(prepareProgress = null) }
                        if (pictureOnly != null) castMovie(original, tvSource = pictureOnly)
                        else { _ui.update { it.copy(roomSoundToo = true) }; startMovieUnsafe() }
                    }
                }
            }, "picture-only").start()
            return
        }
        converter.cachedFile(original.uri, q, plan)?.let { f ->
            return castMovie(ContentUriSource.from(app, Uri.fromFile(f))?.withTitle(original.title) ?: return say(R.string.msg_bad_file))
        }
        // Convert once on the phone, then cast. Progress + time left show on the Start button.
        val startedAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(prepareProgress = 0, prepareEtaSec = null, prepareSoundOnly = plan == CastCompat.Plan.SOUND_ONLY, message = null) }
        converter.start(original.uri, q, movieMedia, plan, object : MovieConverter.Listener {
            override fun onProgress(percent: Int) {
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                val eta = if (percent in 3..99 && elapsed >= 5) elapsed * (100 - percent) / percent else null
                _ui.update { it.copy(prepareProgress = percent, prepareEtaSec = eta) }
            }
            override fun onDone(file: java.io.File) = guard("cast converted movie") {
                _ui.update { it.copy(prepareProgress = null, prepareEtaSec = null) }
                val src = ContentUriSource.from(app, Uri.fromFile(file))?.withTitle(original.title)
                if (src == null) say(R.string.msg_bad_file) else castMovie(src)
            }
            override fun onFailed(reason: String) {
                CrashReporter.handled(app, "convert movie ($q, $plan)", IllegalStateException(reason))
                if (server == null) HostService.stop(app)
                _ui.update { it.copy(prepareProgress = null, prepareEtaSec = null, message = app.getString(R.string.msg_convert_failed, reason)) }
            }
        })
    }

    /** The X on the chosen movie: forget it (and stop any preparation). */
    fun clearMovie() = guard("clearMovie") {
        converter.cancel()
        if (server == null) HostService.stop(app)
        movie = null
        movieMedia = null
        qualityChosenByUser = false
        prefs.edit { remove(KEY_MOVIE_URI) }
        _ui.update { it.copy(movieTitle = null, movieInfo = null, movieVerdict = null, prepareProgress = null, prepareEtaSec = null, quality = Quality.ORIGINAL, moviePlan = null) }
    }

    /** Sends [m] to the TV and starts headphone sound. */
    private fun castMovie(m: ContentUriSource, tvSource: MediaSource = m) {
        val session = castSession ?: return
        val client = session.remoteMediaClient ?: return say(R.string.msg_tv_failed)
        // Give the TV the address on its own network (a phone can have several).
        val tvAddress = session.castDevice?.inetAddress
        val ip = NetworkInfo.localIpv4For(app, tvAddress) ?: return say(R.string.msg_no_wifi)

        val srv = MediaServer(
            senderId = deviceId,
            clockMs = { SystemClock.elapsedRealtime() },
            stateProvider = { snapshot },
            helloProvider = {
                Payload.Hello(Build.MODEL.take(64).ifBlank { "Android" }, Platform.ANDROID, Role.HOST, BuildConfig.VERSION_NAME, listOf(1))
            },
        )
        srv.media = tvSource
        val port = try { srv.start() } catch (_: Exception) { return say(R.string.msg_no_wifi) }
        server = srv

        player = HeadphonePlayer(app, m.uri, playerEvents).also { it.setPreferredOutput(preferredOutputId) }

        val url = "http://$ip:$port/media?t=${System.currentTimeMillis()}"
        lastDiagnostics = Diagnostics(url, ip, tvAddress?.hostAddress)
        startedAtMs = SystemClock.elapsedRealtime()
        tvEverPlayed = false
        lastBytes = 0L
        lastBytesAtMs = startedAtMs
        speedSamples.clear()
        bufferingStarts.clear()
        speedWarned = false
        playingSinceMs = 0L
        headphoneWarned = false
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, m.title) }
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(tvSource.mimeType)
            .setMetadata(meta)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { result ->
                if (!result.status.isSuccess) say(R.string.msg_tv_cant_play)
            }

        applyTvMute()
        announcer.announce("TheatreSync · ${m.title}".take(60), ip, port)
        wifiLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "theatresync").apply { acquire() }
        CastIndicator.hide(app) // the movie's own notification takes over (same cast icon)
        HostService.start(app)
        interrupted = false
        _ui.update { it.copy(phase = Phase.STARTING, joinAddress = "$ip:$port", message = null, positionMs = 0) }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun stopMovieUnsafe() {
        castSession?.remoteMediaClient?.stop()
        teardown()
        // Close the media player on the Chromecast so it returns to "Ready to cast".
        castContext?.sessionManager?.endCurrentSession(true)
        _ui.update { it.copy(phase = Phase.SETUP, joinAddress = null, friends = 0, driftMs = null) }
        publishNowCasting(rawNowCasting)
    }

    private fun teardown() {
        main.removeCallbacks(tick)
        castSession?.let { s -> if (runCatching { s.setMute(false) }.isSuccess) prefs.edit { putBoolean(KEY_WE_MUTED, false) } }
        player?.release()
        player = null
        server?.stop()
        server = null
        snapshot = null
        announcer.stop()
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        HostService.stop(app)
    }

    /** From the notification: pause or resume the movie on the TV. */
    fun notificationPlayPause() = togglePause()

    private fun togglePauseUnsafe() {
        val c = castSession?.remoteMediaClient ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Drag on the movie's progress bar: jump the TV there (the headphones follow). */
    fun seekTo(positionMs: Long) = guard("seekTo") {
        val c = castSession?.remoteMediaClient ?: return@guard
        c.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    private fun skipUnsafe(deltaMs: Long) {
        val c = castSession?.remoteMediaClient ?: return
        val duration = c.streamDuration
        val wanted = (c.approximateStreamPosition + deltaMs).coerceAtLeast(0)
        // Duration is 0 until the Chromecast reports it — don't clamp to 0 then.
        val pos = if (duration > 0) wanted.coerceAtMost(duration) else wanted
        c.seek(MediaSeekOptions.Builder().setPosition(pos).build())
    }

    /** Positive = make the sound later. Bound matches the protocol's userOffsetMs range. */
    fun adjustSoundDelay(stepMs: Int) {
        val v = (_ui.value.soundDelayMs + stepMs).coerceIn(-1000, 1000)
        prefs.edit { putInt("soundDelayMs", v) }
        _ui.update { it.copy(soundDelayMs = v) }
        player?.onDelayChanged()
    }

    fun setFx(settings: HeadphoneFx.Settings) = guard("setFx") {
        HeadphoneFx.save(app, settings)
        player?.applyFx(settings)
        _ui.update { it.copy(fx = settings) }
    }

    /** Headphone settings → Reset sync: sound delay back to 0 and re-lock onto the TV. */
    fun resetHeadphoneSync() = guard("resetHeadphoneSync") {
        prefs.edit { putInt("soundDelayMs", 0) }
        _ui.update { it.copy(soundDelayMs = 0) }
        player?.resetSync()
    }

    fun setRoomSoundToo(on: Boolean) = guard("setRoomSoundToo") { _ui.update { it.copy(roomSoundToo = on) } }

    fun setHeadphonesHere(on: Boolean) = _ui.update { it.copy(headphonesHere = on) }

    private fun setMuteTvUnsafe(on: Boolean) {
        _ui.update { it.copy(muteTv = on) }
        applyTvMute()
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun applyTvMute() {
        val s = castSession ?: return
        val mute = _ui.value.muteTv && server != null // only while a movie is running
        // Many Chromecasts on an AV receiver have fixed (HDMI/CEC) device volume and ignore
        // device mute, so also mute the movie's own audio stream on the receiver.
        runCatching { s.remoteMediaClient?.setStreamMute(mute) }
        // Belt and braces: also turn the movie stream's own volume to zero (restore when unmuting).
        runCatching { s.remoteMediaClient?.setStreamVolume(if (mute) 0.0 else 1.0) }
        if (runCatching { s.setMute(mute) }.isSuccess) prefs.edit { putBoolean(KEY_WE_MUTED, mute) }
        muteRequestedAtMs = if (mute) SystemClock.elapsedRealtime() else 0L
        if (!mute) _ui.update { it.copy(tvMuteConfirmed = null) }
    }

    private var muteRequestedAtMs = 0L

    /** Did the TV actually go quiet? Checked from the Chromecast's own status. */
    private fun checkMute(client: com.google.android.gms.cast.framework.media.RemoteMediaClient) {
        if (muteRequestedAtMs == 0L || SystemClock.elapsedRealtime() - muteRequestedAtMs < 2_000) return
        val status = client.mediaStatus ?: return
        val session = castSession ?: return
        val quiet = status.isMute || status.streamVolume <= 0.01 || runCatching { session.isMute }.getOrDefault(false)
        if (_ui.value.tvMuteConfirmed != quiet) _ui.update { it.copy(tvMuteConfirmed = quiet) }
    }

    /** If the app was killed mid-movie, the TV may still be muted by us: undo that. */
    private fun restoreTvSoundIfWeMutedIt(session: CastSession) {
        if (server == null && prefs.getBoolean(KEY_WE_MUTED, false)) {
            if (runCatching { session.setMute(false) }.isSuccess) prefs.edit { putBoolean(KEY_WE_MUTED, false) }
        }
    }

    private val playerEvents = object : HeadphonePlayer.Events {
        override fun onShouldPause() {
            castSession?.remoteMediaClient?.pause()
            say(R.string.msg_paused_headphones)
        }

        override fun onInterrupted(interrupted: Boolean) {
            val c = castSession?.remoteMediaClient ?: return
            if (interrupted && c.isPlaying) {
                this@MovieSession.interrupted = true
                c.pause()
            } else if (!interrupted && this@MovieSession.interrupted) {
                this@MovieSession.interrupted = false
                c.play()
            }
        }

        override fun onError(message: String) {
            lastHeadphoneProblem = message
            CrashReporter.handled(app, "headphone player", IllegalStateException(message))
            _ui.update { it.copy(message = message) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
                CrashReporter.handled(app, "sync loop", e)
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val client = castSession?.takeIf { it.isConnected }?.remoteMediaClient
        if (client == null) {
            // Wi-Fi blip / TV connection suspended: hold the headphone sound until the TV is back.
            player?.follow(0, shouldPlay = false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val castPos = client.approximateStreamPosition.coerceAtLeast(0)
        val castState = client.playerState
        val playing = castState == MediaStatus.PLAYER_STATE_PLAYING
        val title = movie?.title

        snapshot = Payload.PlaybackState(
            state = when (castState) {
                MediaStatus.PLAYER_STATE_PLAYING -> Payload.PlaybackState.State.PLAYING
                MediaStatus.PLAYER_STATE_PAUSED -> Payload.PlaybackState.State.PAUSED
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> Payload.PlaybackState.State.BUFFERING
                else -> Payload.PlaybackState.State.STOPPED
            },
            mediaPositionMs = castPos,
            hostTimeMs = now,
            playbackRate = 1.0,
            mediaTitle = title?.take(256),
        )

        val ui = _ui.value
        val target = (castPos - ui.soundDelayMs).coerceAtLeast(0)
        player?.follow(target, shouldPlay = playing && ui.headphonesHere)

        val finished = castState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_FINISHED
        val failed = castState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_ERROR
        if (failed && ui.message == null) say(R.string.msg_tv_cant_play)

        checkMute(client)

        // Speed check: how fast is the movie reaching the TV, and does it keep stalling?
        val buffering = castState == MediaStatus.PLAYER_STATE_BUFFERING
        if (buffering && !wasBuffering && tvEverPlayed) bufferingStarts.addLast(now)
        wasBuffering = buffering
        while (bufferingStarts.isNotEmpty() && now - bufferingStarts.first() > 60_000) bufferingStarts.removeFirst()
        val srv = server
        if (srv != null && now - lastBytesAtMs >= 2_000) {
            val bytes = srv.totalBytesSent
            speedSamples.addLast((bytes - lastBytes) * 8.0 / ((now - lastBytesAtMs) / 1000.0) / 1_000_000)
            while (speedSamples.size > 10) speedSamples.removeFirst()
            streamMbps = speedSamples.average()
            lastBytes = bytes
            lastBytesAtMs = now
            val durationSec = client.streamDuration / 1000.0
            movie?.let { m -> if (durationSec > 0) videoMbps = m.sizeBytes * 8.0 / durationSec / 1_000_000 }
        }
        val need = videoMbps
        val got = streamMbps
        if (!speedWarned && bufferingStarts.size >= 3 && need != null && got != null && ui.message == null) {
            speedWarned = true
            lastSpeedProblem = "TV stalled ${bufferingStarts.size}x in 60 s; video needs %.1f Mbit/s, TV got %.1f Mbit/s".format(need, got)
            CrashReporter.handled(app, "streaming too slow", IllegalStateException(lastSpeedProblem))
            _ui.update { it.copy(message = app.getString(R.string.msg_wifi_too_slow, need, got)) }
        }

        // Watchdog: the TV says nothing when it can't reach the phone; it just waits.
        if (playing && !tvEverPlayed) applyTvMute() // stream mute needs loaded media
        if (playing) tvEverPlayed = true

        // Headphone watchdog: the TV is playing but no sound in the headphones.
        if (playing && ui.headphonesHere) {
            if (playingSinceMs == 0L) playingSinceMs = SystemClock.elapsedRealtime()
            val p = player
            val silent = p == null || !p.exo.isPlaying
            if (silent && SystemClock.elapsedRealtime() - playingSinceMs > 8_000 && !headphoneWarned) {
                headphoneWarned = true
                val why = p?.lastError ?: "player state ${p?.exo?.playbackState}, suppression ${p?.exo?.playbackSuppressionReason}, seeks ${p?.seeks}, lead ${p?.startLeadMs} ms"
                lastHeadphoneProblem = why
                CrashReporter.handled(app, "headphone sound didn't start", IllegalStateException(why))
                say(R.string.msg_headphones_silent)
            }
        } else {
            playingSinceMs = 0L
        }
        val waitedMs = SystemClock.elapsedRealtime() - startedAtMs
        if (!tvEverPlayed && waitedMs > TV_START_TIMEOUT_MS && ui.message == null) {
            say(if ((server?.mediaRequests ?: 0) == 0) R.string.msg_tv_cant_reach else R.string.msg_tv_cant_play)
        }

        val phase = when {
            finished -> Phase.ENDED
            playing -> Phase.PLAYING
            castState == MediaStatus.PLAYER_STATE_PAUSED -> Phase.PAUSED
            else -> if (ui.phase == Phase.SETUP) Phase.STARTING else ui.phase
        }
        if (tickCount++ % 10 == 0) _ui.update { it.copy(headphoneName = currentHeadphones()) }
        _ui.update {
            it.copy(
                phase = phase,
                tvBuffering = buffering || castState == MediaStatus.PLAYER_STATE_LOADING,
                positionMs = castPos,
                durationMs = maxOf(0, client.streamDuration),
                friends = server?.activeListeners() ?: 0,
                driftMs = player?.lastDriftMs,
            )
        }
    }

    private fun currentHeadphones(): String? {
        val am = app.getSystemService(AudioManager::class.java) ?: return null
        val bt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || AudioOutputs.routeFor(it.type) == com.theatresync.core.OutputRoute.BLUETOOTH_LE_AUDIO ||
                AudioOutputs.routeFor(it.type) == com.theatresync.core.OutputRoute.WIRED
        } ?: return null
        return bt.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun say(res: Int) = _ui.update { it.copy(message = app.getString(res)) }
    private fun say(text: String) = _ui.update { it.copy(message = text) }

    // ---------------------------------------------------------------- crash safety
    //
    // Every action that talks to the TV, the network or a file provider goes through
    // guard(): an unexpected problem is recorded in the crash timeline, the user sees a
    // calm message, and the app keeps running instead of closing.

    fun chooseMovie(uri: Uri) = guard("chooseMovie") { chooseMovieUnsafe(uri) }
    fun startTvSearch() = guard("startTvSearch") { startTvSearchUnsafe() }
    fun stopTvSearch() = guard("stopTvSearch") { stopTvSearchUnsafe() }
    fun connectTv(id: String) = guard("connectTv") { connectTvUnsafe(id) }
    fun disconnectTv() = guard("disconnectTv") { disconnectTvUnsafe() }
    fun castPlayPause(tvId: String) = guard("castPlayPause") { castPlayPauseUnsafe(tvId) }
    fun castStop(tvId: String) = guard("castStop") { castStopUnsafe(tvId) }
    fun startMovie() = guard("startMovie") { startMovieUnsafe() }
    fun stopMovie() = guard("stopMovie") { stopMovieUnsafe() }
    fun togglePause() = guard("togglePause") { togglePauseUnsafe() }
    fun skip(deltaMs: Long) = guard("skip") { skipUnsafe(deltaMs) }
    fun setMuteTv(on: Boolean) = guard("setMuteTv") { setMuteTvUnsafe(on) }

    fun castSkip(tvId: String, deltaSec: Double) = guard("castSkip") { castSkipUnsafe(tvId, deltaSec) }
    fun castSeekTo(tvId: String, seconds: Double) = guard("castSeekTo") { castSeekToUnsafe(tvId, seconds) }
    fun castVolume(tvId: String, level: Double) = guard("castVolume") {
        lastVolume[tvId] = level
        // Smart TVs: YouTube's own volume (0–100). Chromecasts: the Chromecast's volume.
        if (isDial(tvId)) youTubeRemote(tvId, "setVolume", mapOf("volume" to "${(level * 100).toInt()}")) else tvWatchers.setVolume(tvId, level)
    }
    /** Remote − / + : change the TV volume by [delta] (0..1 scale). */
    fun castVolumeStep(tvId: String, delta: Double) = guard("castVolumeStep") {
        val now = rawNowCasting.firstOrNull { it.tvId == tvId }?.activity?.volumeLevel ?: lastVolume[tvId] ?: 0.5
        val next = (now + delta).coerceIn(0.0, 1.0)
        castVolume(tvId, next)
    }
    private val lastVolume = HashMap<String, Double>()

    fun castMute(tvId: String, muted: Boolean) = guard("castMute") {
        if (isDial(tvId)) youTubeRemote(tvId, "setVolume", mapOf("volume" to if (muted) "0" else "${((lastVolume[tvId] ?: 0.5) * 100).toInt()}"))
        else tvWatchers.setMuted(tvId, muted)
    }

    private inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CrashReporter.handled(app, where, e)
            _ui.update { it.copy(message = app.getString(R.string.msg_problem_handled)) }
        }
    }

    companion object {
        private const val TICK_MS = 200L
        private const val KEY_MOVIE_URI = "movieUri"
        private const val TV_START_TIMEOUT_MS = 20_000L
        private const val KEY_WE_MUTED = "weMutedTv"
    }

    /** If Android closed the app in the background, bring back the movie the owner had chosen. */
    private fun restoreMovie() {
        val saved = prefs.getString(KEY_MOVIE_URI, null) ?: return
        val uri = saved.toUri()
        val stillAllowed = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!stillAllowed) return
        val source = try { ContentUriSource.from(app, uri) } catch (_: Exception) { null } ?: return
        movie = source
        _ui.update { it.copy(movieTitle = source.title) }
        inspectMovie(source)
    }

    // ---------------------------------------------------------------- quality & conversion

    private var movieMedia: CastCompat.Media? = null
    private var qualityChosenByUser = false
    private val converter by lazy { MovieConverter(app) }

    private fun inspectMovie(source: ContentUriSource) {
        Thread({
            val media = MovieInspector.inspect(app, source.uri, source.sizeBytes)
            main.post {
                if (movie?.uri != source.uri) return@post
                movieMedia = media
                refreshVerdict()
            }
        }, "inspect-movie").apply { isDaemon = true }.start()
    }

    /** Re-judge the movie against the connected TV and pick a sensible default quality. */
    private fun refreshVerdict() {
        val media = movieMedia ?: return
        val verdict = CastCompat.verdict(media, castSession?.castDevice?.modelName)
        // Default to the fastest choice the TV can show: the original picture whenever possible
        // (only the sound is fixed if needed); scale down only if the picture itself can't play.
        val default = when {
            CastCompat.Problem.RESOLUTION in verdict.problems -> Quality.P1080
            else -> Quality.ORIGINAL
        }
        _ui.update {
            it.copy(
                movieInfo = media.label.ifBlank { null },
                movieVerdict = verdict,
                quality = if (qualityChosenByUser) it.quality else default,
            )
        }
        _ui.update { it.copy(moviePlan = currentPlan()) }
    }

    fun setQuality(q: Quality) = guard("setQuality") {
        qualityChosenByUser = true
        _ui.update { it.copy(quality = q) }
        _ui.update { it.copy(moviePlan = currentPlan()) }
    }

    fun cancelPrepare() = guard("cancelPrepare") {
        converter.cancel()
        if (server == null) HostService.stop(app)
        _ui.update { it.copy(prepareProgress = null, prepareEtaSec = null) }
    }

    /** Which output TheatreSync's sound plays on (null = Android decides). */
    private var preferredOutputId: Int? = null

    fun setPreferredOutput(id: Int?) = guard("choose output") {
        preferredOutputId = id
        _ui.update { it.copy(preferredOutputId = id, newDevice = null) }
        player?.setPreferredOutput(id)
    }

    fun dismissNewDevice() = _ui.update { it.copy(newDevice = null) }

    fun setAutoSwitch(on: Boolean) = guard("setAutoSwitch") {
        prefs.edit { putBoolean("autoSwitch", on) }
        _ui.update { it.copy(autoSwitch = on) }
    }

    // ------------------------------------------------------------- headphones coming and going

    private val audioManager = app.getSystemService(android.media.AudioManager::class.java)
    private var deviceWatchStartedAt = 0L

    // The BLE types are plain int constants: on older Android they simply never match.
    @android.annotation.SuppressLint("InlinedApi")
    private fun isHeadphone(d: android.media.AudioDeviceInfo) = d.isSink && d.type in setOf(
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = guard("headphones added") {
            _ui.update { it.copy(audioDevicesVersion = it.audioDevicesVersion + 1) }
            // The first callback lists devices that were already there: not "new".
            if (SystemClock.elapsedRealtime() - deviceWatchStartedAt < 1_500) return@guard
            val d = added.firstOrNull(::isHeadphone) ?: return@guard
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: app.getString(R.string.headphones)
            when {
                player == null -> Unit // nothing of ours playing: Android routes new headphones itself
                _ui.value.autoSwitch -> { setPreferredOutput(d.id); say(app.getString(R.string.now_on_device, name)) }
                else -> _ui.update { it.copy(newDevice = NewDevice(d.id, name)) }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = guard("headphones removed") {
            _ui.update { it.copy(audioDevicesVersion = it.audioDevicesVersion + 1) }
            if (removed.any { it.id == preferredOutputId }) setPreferredOutput(null) // follow Android to what's left
            if (removed.any { it.id == _ui.value.newDevice?.id }) dismissNewDevice()
        }
    }

    // IMPORTANT: this init block must stay LAST in the class. Kotlin runs property
    // initialisers and init blocks top to bottom, so anything it touches (routerCallback,
    // tick, playerEvents, …) must already be declared above. Having it earlier caused a
    // crash on start-up whenever a Chromecast was already connected (e.g. after Android
    // restarted the app while the file picker was open).
    init {
        main.post { guard("restore movie") { restoreMovie() } }
        _ui.update { it.copy(autoSwitch = prefs.getBoolean("autoSwitch", false)) }
        deviceWatchStartedAt = SystemClock.elapsedRealtime()
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, main) }
        if (castContext == null) {
            _ui.update { it.copy(castAvailable = false, message = app.getString(R.string.msg_no_cast)) }
        } else {
            castContext.sessionManager.addSessionManagerListener(sessionListener(), CastSession::class.java)
            // Defer: let construction finish before reacting to an already-running session.
            main.post { guard("resume tv") { castContext.sessionManager.currentCastSession?.let { onTvConnected(it) } } }
        }
    }
}
