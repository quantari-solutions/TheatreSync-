package com.theatresync.app

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
import com.theatresync.core.MediaServer
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

    data class Tv(val id: String, val name: String)

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
    ) {
        val canStart: Boolean get() = movieTitle != null && connectedTv != null
    }

    private val prefs = app.getSharedPreferences("theatresync", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString().take(12))
        .also { prefs.edit { putString("deviceId", it) } }

    private val _ui = MutableStateFlow(Ui(soundDelayMs = prefs.getInt("soundDelayMs", 0)))
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var interrupted = false
    private var tickCount = 0

    @Volatile private var snapshot: Payload.PlaybackState? = null

    init {
        if (castContext == null) {
            _ui.update { it.copy(castAvailable = false, message = app.getString(R.string.msg_no_cast)) }
        } else {
            castContext.sessionManager.addSessionManagerListener(sessionListener(), CastSession::class.java)
            castContext.sessionManager.currentCastSession?.let { onTvConnected(it) }
        }
    }

    // ---------------------------------------------------------------- setup

    fun chooseMovie(uri: Uri) {
        val source = ContentUriSource.from(app, uri)
        if (source == null) {
            say(R.string.msg_bad_file)
            return
        }
        movie = source
        _ui.update { it.copy(movieTitle = source.title, message = null) }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
    }

    /** Call while the setup screen is visible. */
    fun startTvSearch() {
        if (castContext == null) return
        applyScanMode()
        refreshTvs()
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

    fun stopTvSearch() {
        router.removeCallback(routerCallback)
        tvWatchers.stopAll()
    }

    private fun refreshTvs() {
        val routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
        _ui.update { it.copy(tvs = routes.map { r -> Tv(r.id, r.name) }) }
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
        val visible = list.filterNot {
            val a = it.activity
            a.appId == CastStatus.APP_DEFAULT_MEDIA_RECEIVER && (server != null || a.title == null || a.playerState == "IDLE")
        }
        _ui.update { it.copy(nowCasting = visible) }
    }

    fun castPlayPause(tvId: String) {
        val a = rawNowCasting.firstOrNull { it.tvId == tvId }?.activity ?: return
        tvWatchers.playPause(tvId, play = !a.isPlaying)
    }

    fun castSkip(tvId: String, deltaSec: Double) = tvWatchers.seekBy(tvId, deltaSec)


    fun connectTv(id: String) {
        router.routes.firstOrNull { it.id == id }?.select()
    }

    fun disconnectTv() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun sessionListener() = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onTvConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onTvConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onTvDisconnected()
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
        _ui.update { it.copy(connectedTv = session.castDevice?.friendlyName ?: "Chromecast", message = null) }
        if (castContext != null) applyScanMode()
    }

    private fun onTvDisconnected() {
        castSession = null
        if (_ui.value.phase != Phase.SETUP) teardown()
        _ui.update { it.copy(connectedTv = null, phase = Phase.SETUP) }
    }

    // ---------------------------------------------------------------- the movie

    fun startMovie() {
        val m = movie ?: return
        val session = castSession ?: return
        val client = session.remoteMediaClient ?: return say(R.string.msg_tv_failed)
        val ip = NetworkInfo.localIpv4(app) ?: return say(R.string.msg_no_wifi)

        val srv = MediaServer(
            senderId = deviceId,
            clockMs = { SystemClock.elapsedRealtime() },
            stateProvider = { snapshot },
            helloProvider = {
                Payload.Hello(Build.MODEL.take(64).ifBlank { "Android" }, Platform.ANDROID, Role.HOST, BuildConfig.VERSION_NAME, listOf(1))
            },
        )
        srv.media = m
        val port = try { srv.start() } catch (_: Exception) { return say(R.string.msg_no_wifi) }
        server = srv

        player = HeadphonePlayer(app, m.uri, playerEvents)

        val url = "http://$ip:$port/media?t=${System.currentTimeMillis()}"
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, m.title) }
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(m.mimeType)
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
        HostService.start(app)
        interrupted = false
        _ui.update { it.copy(phase = Phase.STARTING, joinAddress = "$ip:$port", message = null, positionMs = 0) }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stopMovie() {
        castSession?.remoteMediaClient?.stop()
        teardown()
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

    fun togglePause() {
        val c = castSession?.remoteMediaClient ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skip(deltaMs: Long) {
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

    fun setHeadphonesHere(on: Boolean) = _ui.update { it.copy(headphonesHere = on) }

    fun setMuteTv(on: Boolean) {
        _ui.update { it.copy(muteTv = on) }
        applyTvMute()
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun applyTvMute() {
        val s = castSession ?: return
        val mute = _ui.value.muteTv && server != null // only while a movie is running
        if (runCatching { s.setMute(mute) }.isSuccess) prefs.edit { putBoolean(KEY_WE_MUTED, mute) }
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

        override fun onError(message: String) = _ui.update { it.copy(message = message) }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
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

    companion object {
        private const val TICK_MS = 200L
        private const val KEY_WE_MUTED = "weMutedTv"
    }
}
