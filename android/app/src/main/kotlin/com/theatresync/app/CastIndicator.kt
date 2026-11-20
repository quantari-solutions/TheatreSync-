package com.theatresync.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A cast icon in the status bar while TheatreSync is casting, so it's obvious the
 * session is still active. Android only lets apps add icons on the left (notification)
 * side; the right side (Wi-Fi, NFC, system cast) is reserved for the system.
 */
object CastIndicator {
    private const val CHANNEL = "casting"
    private const val ID = 2
    const val ACTION_STOP = "com.theatresync.app.STOP_CASTING"
    const val EXTRA_TV = "tv"

    fun show(context: Context, text: String, tvId: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.cast_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, StopCastReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TV, tvId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setColor(context.getColor(R.color.brand_gold))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.remote_short), MainActivity.remoteIntent(context))
            .addAction(0, context.getString(R.string.stop_casting), stop)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }
    }

    fun hide(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}

/** The notification's "Stop casting" button (not exported: only our own notification can call it). */
class StopCastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CastIndicator.ACTION_STOP) return
        val session = (context.applicationContext as TheatreSyncApplication).session
        val tv = intent.getStringExtra(CastIndicator.EXTRA_TV)
        if (tv != null) session.castStop(tv) else session.disconnectTv()
        CastIndicator.hide(context)
    }
}
