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
 * Keeps TheatreSync running while the phone screen is off during the movie
 * (the TV and friends' phones fetch the movie from this phone).
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
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(getColor(R.color.brand_gold))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notif_play_pause), action(ACTION_PLAY_PAUSE, 1))
            .addAction(0, getString(R.string.remote_short), MainActivity.remoteIntent(this))
            .addAction(0, getString(R.string.notif_stop), action(ACTION_STOP, 2))
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Notification buttons go back to this (non-exported) service only. */
    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, HostService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "movie"
        private const val ACTION_PLAY_PAUSE = "com.theatresync.app.PLAY_PAUSE"
        private const val ACTION_STOP = "com.theatresync.app.STOP"
        @Volatile var running = false
            private set

        /**
         * Start while the app is on screen (Android refuses from the background, e.g. when a
         * conversion finishes after the owner switched apps: crash report #20). No-op if running.
         */
        fun start(context: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HostService::class.java)) }
                .onFailure { CrashReporter.handled(context, "start movie service", it) }
        }
        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, HostService::class.java))
        }
    }
}
