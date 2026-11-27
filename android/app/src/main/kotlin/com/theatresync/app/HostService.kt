package com.theatresync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps TheatreSync running in the background for as long as the owner needs it (owner: "let the app run
 * in the background, no issues, for however long I need it"): the TV card, notification Stop, ad muting,
 * headphones and movies keep working with the app closed or the screen off. Started whenever the app is
 * opened (never at phone start-up); ends with Quit in its notification, or by itself after the idle time
 * chosen in Experimental (default 4 h, owner: "when it's not opened that day there is no reason to run"),
 * but never while something is playing through it. The notification shows the movie controls during a
 * movie, otherwise Remote and Quit.
 */
class HostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> (application as TheatreSyncApplication).session.notificationPlayPause()
            ACTION_STOP -> {
                (application as TheatreSyncApplication).session.stopMovie()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                running = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ServiceCompat.startForeground(this, ID, build(this, movie), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        running = true
        idle.removeCallbacks(idleCheck); idle.postDelayed(idleCheck, 60_000)
        return START_NOT_STICKY
    }

    private val idle = android.os.Handler(android.os.Looper.getMainLooper())
    /** Once a minute: idle in the background for longer than the owner allows (and nothing playing)? Stop. */
    private val idleCheck = object : Runnable {
        override fun run() {
            val session = (application as TheatreSyncApplication).session
            val busy = movie || onScreen || session.busyInBackground()
            if (busy) lastActiveMs = System.currentTimeMillis()
            if (!busy && BackgroundLimit.expired(this@HostService, lastActiveMs, System.currentTimeMillis())) {
                running = false
                ServiceCompat.stopForeground(this@HostService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            idle.postDelayed(this, 60_000)
        }
    }

    override fun onDestroy() {
        running = false
        idle.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "movie"
        private const val ID = 1
        private const val ACTION_QUIT = "com.theatresync.app.QUIT"
        /** A movie is playing (movie controls in the notification), else Remote and Quit. */
        @Volatile private var movie = false

        /** Notification buttons go back to this (non-exported) service only. */
        private fun action(context: Context, name: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            context, requestCode, Intent(context, HostService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun build(context: Context, movie: Boolean): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_connected)
                .setContentTitle(context.getString(if (movie) R.string.notif_title else R.string.notif_title_ready))
                .setContentText(context.getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setColor(context.getColor(R.color.brand_gold))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Android shows three buttons at most.
            if (movie) {
                b.addAction(0, context.getString(R.string.notif_play_pause), action(context, ACTION_PLAY_PAUSE, 1))
                    .addAction(0, context.getString(R.string.remote_short), MainActivity.remoteIntent(context))
                    .addAction(0, context.getString(R.string.pip_short), MainActivity.pipIntent(context))
            } else {
                b.addAction(0, context.getString(R.string.remote_short), MainActivity.remoteIntent(context))
                    .addAction(0, context.getString(R.string.notif_quit), action(context, ACTION_QUIT, 2))
            }
            return b.build()
        }

        /** A movie started or ended: swap the notification's buttons (the service keeps running). */
        fun setMovie(context: Context, playing: Boolean) {
            movie = playing
            if (running) runCatching {
                context.getSystemService(NotificationManager::class.java).notify(ID, build(context, playing))
            }
        }
        private const val ACTION_PLAY_PAUSE = "com.theatresync.app.PLAY_PAUSE"
        /** Broadcast used by the picture-in-picture window's play/pause button. */
        const val ACTION_PLAY_PAUSE_PUBLIC = "com.theatresync.app.PIP_PLAY_PAUSE"
        private const val ACTION_STOP = "com.theatresync.app.STOP"
        @Volatile var running = false
            private set
        /** TheatreSync on screen (set by the main screen); the idle clock starts when it leaves the screen. */
        @Volatile var onScreen = false
            set(v) { field = v; lastActiveMs = System.currentTimeMillis() }
        @Volatile private var lastActiveMs = System.currentTimeMillis()

        /**
         * Start while the app is on screen (Android refuses from the background, e.g. when a
         * conversion finishes after the owner switched apps: crash report #20). No-op if running.
         */
        fun start(context: Context, movie: Boolean = true) {
            if (movie) setMovie(context, true)
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HostService::class.java)) }
                .onFailure { CrashReporter.handled(context, "start movie service", it) }
        }
        /** The movie ended: keep running in the background (owner), just without the movie controls. */
        fun stop(context: Context) = setMovie(context, false)
    }
}
