package com.theatresync.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.theatresync.core.PlayAlongSync

/**
 * Lets TheatreSync see and steer the Netflix / Prime Video app's playback on this phone (Android's
 * "Notification access"). It does nothing with notifications themselves.
 */
class PlayAlongListener : NotificationListenerService()

/**
 * "Play along" for Netflix and Prime Video: the TV plays the picture; the same title plays in the app on
 * this phone, its sound in the headphones, and this keeps it in step with the TV every second.
 */
class PlayAlong(private val context: Context) {
    data class Status(val on: Boolean = false, val app: String? = null, val driftMs: Long? = null, val problem: Problem? = null)
    enum class Problem { NEEDS_ACCESS, OPEN_APP, TV_NO_POSITION }

    private val main = Handler(Looper.getMainLooper())
    private var sync = PlayAlongSync()
    private var tvTarget: () -> Pair<Long, Boolean>? = { null }
    private var packages: List<String> = emptyList()
    var onStatus: (Status) -> Unit = {}
    var running = false
        private set

    fun hasAccess(): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.contains(ComponentName(context, PlayAlongListener::class.java).flattenToString()) == true

    fun accessIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts following the TV. [tvApp]: "netflix" or "prime"; [target]: the TV's position (ms) and playing. */
    fun start(tvApp: String, target: () -> Pair<Long, Boolean>?) {
        packages = if (tvApp == "netflix") NETFLIX else PRIME
        tvTarget = target
        sync = PlayAlongSync()
        speedIgnored = false; speedAskedAt = 0
        if (!running) { running = true; main.post(tick) }
    }

    fun stop() {
        if (running && sync.rate != 1f && android.os.Build.VERSION.SDK_INT >= 29) runCatching { controller()?.transportControls?.setPlaybackSpeed(1f) }
        running = false
        main.removeCallbacks(tick)
        onStatus(Status(on = false))
    }

    /** Opens the Netflix / Prime app (or its store page) so the same title can be started. */
    fun openAppIntent(tvApp: String): Intent {
        val pm = context.packageManager
        val pkgs = if (tvApp == "netflix") NETFLIX else PRIME
        return pkgs.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=${pkgs.first()}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            onStatus(step())
            main.postDelayed(this, 500)
        }
    }

    private fun step(): Status {
        if (!hasAccess()) return Status(on = true, problem = Problem.NEEDS_ACCESS)
        val controller = controller() ?: return Status(on = true, problem = Problem.OPEN_APP)
        val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(controller.packageName, 0)).toString() }.getOrNull()
        val (tvPos, tvPlaying) = tvTarget() ?: return Status(on = true, app = name, problem = Problem.TV_NO_POSITION)
        val state = controller.playbackState ?: return Status(on = true, app = name, problem = Problem.OPEN_APP)
        val now = SystemClock.elapsedRealtime()
        val playing = state.state == PlaybackState.STATE_PLAYING
        val phonePos = state.position + if (playing && state.lastPositionUpdateTime > 0) ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        val controls = controller.transportControls
        // Speed nudges where the app accepts them (Android 10+, and it actually changes speed when asked).
        if (speedAskedAt > 0 && now - speedAskedAt > 3_000) {
            if (kotlin.math.abs(state.playbackSpeed - sync.rate) > 0.01f) speedIgnored = true
            speedAskedAt = 0
        }
        val canSpeed = !speedIgnored && android.os.Build.VERSION.SDK_INT >= 29 &&
            (state.actions and PlaybackState.ACTION_SET_PLAYBACK_SPEED) != 0L
        // Aim ahead by the headphones' own delay (Bluetooth plays a little late), so you hear it with the picture.
        val target = tvPos + headphoneDelayMs()
        when (val a = sync.decide(phonePos, playing, target, tvPlaying, now, canSpeed)) {
            PlayAlongSync.Action.Play -> controls.play()
            PlayAlongSync.Action.Pause -> controls.pause()
            is PlayAlongSync.Action.SeekTo -> controls.seekTo(a.positionMs)
            is PlayAlongSync.Action.SetSpeed -> if (android.os.Build.VERSION.SDK_INT >= 29) { controls.setPlaybackSpeed(a.rate); speedAskedAt = now }
            PlayAlongSync.Action.None -> Unit
        }
        return Status(on = true, app = name, driftMs = sync.driftMs)
    }

    private var speedIgnored = false
    private var speedAskedAt = 0L

    /** How late the current headphones play sound: Android's estimate, else ~200 ms for Bluetooth, 0 otherwise. */
    private fun headphoneDelayMs(): Long {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return 0
        val bt = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val reported = runCatching {
            (android.media.AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType).invoke(am, android.media.AudioManager.STREAM_MUSIC) as Int).toLong()
        }.getOrDefault(0L)
        return when {
            bt && reported >= 100 -> reported
            bt -> 200
            else -> reported.coerceIn(0, 100)
        }
    }

    private fun controller(): MediaController? = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(context, PlayAlongListener::class.java)).firstOrNull { it.packageName in packages }
    }.getOrNull()

    companion object {
        val NETFLIX = listOf("com.netflix.mediaclient")
        val PRIME = listOf("com.amazon.avod.thirdpartyclient", "com.amazon.avod")

        /** "netflix" / "prime" for a link shared from those apps (title pages), else null. */
        fun appForLink(text: String): String? = when {
            Regex("netflix\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "netflix"
            Regex("primevideo\\.com/|amazon\\.[a-z.]+/(gp/video|.*/dp/)|watch\\.amazon|app\\.primevideo", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "prime"
            else -> null
        }

        /** The first web address in shared text ("Watch X on Netflix https://..."). */
        fun linkIn(text: String): String? = Regex("https?://\\S+").find(text)?.value

        /** "netflix" / "prime" for a TV app name, else null. */
        fun appFor(appName: String?): String? = when {
            appName == null -> null
            appName.contains("netflix", true) -> "netflix"
            appName.contains("prime", true) || appName.contains("amazon", true) -> "prime"
            else -> null
        }
    }
}
