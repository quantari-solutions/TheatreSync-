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
        if (!running) { running = true; main.post(tick) }
    }

    fun stop() {
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
            main.postDelayed(this, 1_000)
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
        when (val a = sync.decide(phonePos, playing, tvPos, tvPlaying, now)) {
            PlayAlongSync.Action.Play -> controls.play()
            PlayAlongSync.Action.Pause -> controls.pause()
            is PlayAlongSync.Action.SeekTo -> controls.seekTo(a.positionMs)
            PlayAlongSync.Action.None -> Unit
        }
        return Status(on = true, app = name, driftMs = sync.driftMs)
    }

    private fun controller(): MediaController? = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(context, PlayAlongListener::class.java)).firstOrNull { it.packageName in packages }
    }.getOrNull()

    companion object {
        val NETFLIX = listOf("com.netflix.mediaclient")
        val PRIME = listOf("com.amazon.avod.thirdpartyclient", "com.amazon.avod")

        /** "netflix" / "prime" for a TV app name, else null. */
        fun appFor(appName: String?): String? = when {
            appName == null -> null
            appName.contains("netflix", true) -> "netflix"
            appName.contains("prime", true) || appName.contains("amazon", true) -> "prime"
            else -> null
        }
    }
}
