package com.theatresync.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the cause of a crash on the phone (never sent anywhere automatically) so the next
 * launch can offer "Share crash report". Lets the owner send Claude the exact reason
 * without a computer or developer tools.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val report = buildString {
                    appendLine("TheatreSync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crash report")
                    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(trace.take(16_000))
                }
                File(app.filesDir, FILE).writeText(report)
            } catch (_: Throwable) {
                // Never let the reporter itself get in the way.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
