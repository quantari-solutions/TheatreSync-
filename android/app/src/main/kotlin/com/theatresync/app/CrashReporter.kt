package com.theatresync.app

import androidx.core.net.toUri
import androidx.core.content.edit
import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Crash and problem timeline, stored only on the phone. Nothing is sent automatically:
 * the owner chooses Share or Send to GitHub (a pre-filled issue they submit with their
 * own GitHub login, so no secret key ever lives in the app).
 */
object CrashReporter {
    enum class Kind { CRASH, HANDLED }
    enum class Status { SAVED, SHARED, SENT_GITHUB }

    data class Entry(
        val id: String,
        val timeMs: Long,
        val version: String,
        val kind: Kind,
        val title: String,
        val report: String,
        val status: Status,
        val statusTimeMs: Long?,
        /** e.g. "#12" once uploaded to GitHub. */
        val reference: String? = null,
    )

    private const val FILE = "crash_timeline.json"
    private const val MAX_ENTRIES = 50
    private val lock = Any()

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, Kind.CRASH, error, "Thread: ${thread.name}")
            } catch (_: Throwable) {
                // Never let the reporter itself get in the way.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A problem that was caught and survived: recorded so it can still be fixed. */
    fun handled(context: Context, where: String, error: Throwable) {
        runCatching {
            record(context.applicationContext, Kind.HANDLED, error, "Where: $where")
            GitHubLink.uploadPending(context)
        }
    }

    fun entries(context: Context): List<Entry> = synchronized(lock) { load(context) }.sortedByDescending { it.timeMs }

    /** The newest crash the owner hasn't acted on yet (shown as a card on the home screen). */
    fun pendingCrash(context: Context): Entry? =
        entries(context).firstOrNull { it.kind == Kind.CRASH && it.status == Status.SAVED && !isDismissed(context, it.id) }

    fun dismiss(context: Context, id: String) {
        context.getSharedPreferences("crash", Context.MODE_PRIVATE).edit { putBoolean("dismissed_$id", true) }
    }

    private fun isDismissed(context: Context, id: String) =
        context.getSharedPreferences("crash", Context.MODE_PRIVATE).getBoolean("dismissed_$id", false)

    fun mark(context: Context, id: String, status: Status, reference: String? = null) = synchronized(lock) {
        val list = load(context).map {
            if (it.id == id) it.copy(status = status, statusTimeMs = System.currentTimeMillis(), reference = reference ?: it.reference) else it
        }
        save(context, list)
    }

    /** A pre-filled "New issue" page in the TheatreSync repository, labelled "crash". */
    fun gitHubIssueUri(e: Entry): Uri {
        val title = "Crash report: ${e.title}".take(120)
        val body = buildString {
            appendLine("Sent from the TheatreSync app (${e.version}).")
            appendLine()
            appendLine("```")
            append(e.report.take(5_500))
            appendLine()
            appendLine("```")
        }
        return "https://github.com/${BuildConfig.CRASH_REPO}/issues/new".toUri().buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("labels", "crash")
            .appendQueryParameter("body", body)
            .build()
    }

    fun formatTime(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun record(app: Context, kind: Kind, error: Throwable, context: String) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val now = System.currentTimeMillis()
        val report = buildString {
            appendLine("TheatreSync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (kind == Kind.CRASH) "crash report" else "handled problem"}")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(context)
            appendLine()
            append(trace.take(16_000))
        }
        val root = generateSequence(error) { it.cause }.last()
        val title = "${root.javaClass.simpleName}${root.message?.let { ": ${it.take(80)}" } ?: ""}"
        val entry = Entry(UUID.randomUUID().toString(), now, BuildConfig.VERSION_NAME, kind, title, report, Status.SAVED, null)
        synchronized(lock) { save(app, (listOf(entry) + load(app)).take(MAX_ENTRIES)) }
    }

    private fun load(context: Context): List<Entry> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return migrateLegacy(context)
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("id"), o.getLong("time"), o.optString("version"),
                    Kind.valueOf(o.getString("kind")), o.optString("title"), o.optString("report"),
                    Status.valueOf(o.getString("status")), if (o.has("statusTime")) o.getLong("statusTime") else null,
                    o.optString("ref").ifBlank { null },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("time", e.timeMs); put("version", e.version); put("kind", e.kind.name)
                put("title", e.title); put("report", e.report); put("status", e.status.name)
                e.statusTimeMs?.let { put("statusTime", it) }
                e.reference?.let { put("ref", it) }
            })
        }
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    /** 0.4.0 saved a single last_crash.txt: bring it into the timeline once. */
    private fun migrateLegacy(context: Context): List<Entry> {
        val legacy = File(context.filesDir, "last_crash.txt")
        if (!legacy.exists()) return emptyList()
        val text = legacy.readText()
        val title = text.lineSequence().firstOrNull { it.startsWith("Caused by:") }?.removePrefix("Caused by:")?.trim()
            ?: text.lineSequence().drop(5).firstOrNull()?.trim() ?: "Crash"
        val entry = Entry(UUID.randomUUID().toString(), legacy.lastModified(), "0.4.0", Kind.CRASH, title.take(100), text, Status.SAVED, null)
        save(context, listOf(entry))
        legacy.delete()
        return listOf(entry)
    }
}
