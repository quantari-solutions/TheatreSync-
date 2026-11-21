package com.theatresync.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.theatresync.core.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates (Settings → About): checks the public releases repository for a newer TheatreSync,
 * downloads it with progress, checks it's exactly the file that was published (SHA-256), and hands it
 * to Android's installer. Android then asks "Update this app?" once, which no app can skip.
 *
 * Updates install over the current app only if they're signed with the same key, which every
 * TheatreSync release is.
 */
object AppUpdates {
    /**
     * Where the newest release is described, tried in order: a public releases-only repository (finished app
     * files only, never code), or, if the owner makes the code repository public instead, its releases/ folder.
     */
    val LATEST_URLS = listOf(
        "https://raw.githubusercontent.com/quantari-solutions/theatresync-releases/main/latest.json",
        "https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases/latest.json",
    )

    data class State(
        val checking: Boolean = false,
        val available: UpdateInfo? = null,
        val upToDate: Boolean = false,
        /** 0..100 while downloading. */
        val downloadPercent: Int? = null,
        val installing: Boolean = false,
        /** Android needs the owner to allow TheatreSync to install updates (once). */
        val needsPermission: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val main = Handler(Looper.getMainLooper())
    private var lastCheckMs = 0L

    /** Looks for a newer version (at most every 10 minutes unless [force]). */
    fun check(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_state.value.checking || _state.value.downloadPercent != null || (!force && now - lastCheckMs < 10 * 60_000)) return
        lastCheckMs = now
        _state.update { it.copy(checking = true, error = null) }
        Thread({
            val result = runCatching {
                var newest: UpdateInfo? = null
                var reached = false
                for (url in LATEST_URLS) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8_000; c.readTimeout = 8_000; c.useCaches = false
                    c.setRequestProperty("Cache-Control", "no-cache")
                    try {
                        val code = c.responseCode
                        reached = true
                        if (code == 200) UpdateInfo.parse(c.inputStream.use { it.readBytes().decodeToString() })?.let { u ->
                            if (newest == null || u.versionCode > newest!!.versionCode) newest = u
                        }
                    } catch (_: java.io.IOException) {
                    } finally { c.disconnect() }
                }
                if (!reached) throw java.io.IOException("offline")
                newest
            }
            main.post {
                val info = result.getOrNull()
                val newer = info?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
                _state.update {
                    it.copy(
                        checking = false, available = newer, upToDate = result.isSuccess && newer == null,
                        error = if (result.isFailure && force) context.getString(R.string.update_check_failed) else null,
                    )
                }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /** Download, verify, install. Asks for Android's "install unknown apps" permission first if needed. */
    fun update(context: Context) {
        val u = _state.value.available ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(needsPermission = true) }
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.update { it.copy(needsPermission = false, downloadPercent = 0, error = null) }
        val app = context.applicationContext
        Thread({
            try {
                val file = download(app, u) { p -> main.post { _state.update { it.copy(downloadPercent = p) } } }
                main.post { _state.update { it.copy(downloadPercent = null, installing = true) } }
                install(app, file)
            } catch (e: Exception) {
                CrashReporter.handled(app, "app update ${u.versionName}", e)
                main.post { _state.update { it.copy(downloadPercent = null, installing = false, error = app.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)) } }
            }
        }, "update-download").apply { isDaemon = true }.start()
    }

    /** Back from Android's permission screen: carry on if it was allowed. */
    fun onResume(context: Context) {
        if (_state.value.needsPermission && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())) update(context)
    }

    private fun download(context: Context, u: UpdateInfo, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "TheatreSync-${u.versionName}.apk")
        val c = URL(u.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        val sha = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IllegalStateException("download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: u.sizeBytes
            var got = 0L; var shown = -1
            c.inputStream.use { input -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); sha.update(buf, 0, n); got += n
                    val p = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (p != shown) { shown = p; progress(p) }
                }
            } }
        } finally { c.disconnect() }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        if (!digest.equals(u.sha256, ignoreCase = true)) { out.delete(); throw IllegalStateException("the download was damaged; try again") }
        return out
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("TheatreSync.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(result.intentSender)
        }
    }

    internal fun onInstallResult(context: Context, status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        _state.update { it.copy(installing = false, error = context.getString(R.string.update_failed, message ?: "status $status")) }
    }
}

/** Android's installer reports here: shows its "Update this app?" screen, or reports a failure. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        AppUpdates.onInstallResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
