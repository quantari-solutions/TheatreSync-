package com.theatresync.app

import androidx.core.content.edit
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One-time "Connect GitHub" (OAuth device flow, no secret in the app) and automatic
 * crash upload as GitHub issues. The token is encrypted with an Android Keystore key
 * and never leaves the phone except to api.github.com.
 */
object GitHubLink {
    data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val intervalSec: Int)

    private const val PREFS = "github"
    private const val SETUP_PREFS = "github_setup"
    private const val KEY_ALIAS = "theatresync_github"

    /** Overridden only by tests (fake GitHub on the emulator's loopback). */
    @Volatile internal var loginBase = "https://github.com"
    @Volatile internal var apiBase = "https://api.github.com"

    /** One-tap GitHub App registration with everything except "Enable Device Flow" pre-filled. */
    val createAppUrl: String
        get() = "https://github.com/settings/apps/new" +
            "?name=" + URLEncoder.encode("TheatreSync crash reports", "UTF-8").replace("+", "%20") +
            "&description=" + URLEncoder.encode("Uploads TheatreSync crash reports as issues", "UTF-8").replace("+", "%20") +
            "&url=" + URLEncoder.encode("https://github.com/${BuildConfig.CRASH_REPO}", "UTF-8") +
            "&public=false&webhook_active=false&issues=write"

    /** Client ID baked into the build, or pasted in the app (public value, not a secret). */
    fun clientId(context: Context): String =
        BuildConfig.GITHUB_CLIENT_ID.ifBlank {
            context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).getString("client_id", null).orEmpty()
        }

    fun isConfigured(context: Context) = clientId(context).isNotBlank()

    /** GitHub App client IDs look like "Iv23li…"; OAuth app IDs like "Ov23li…" or 20 hex chars. */
    fun isValidClientId(id: String) = Regex("^(Iv|Ov)[0-9A-Za-z.]{8,40}$|^[0-9a-f]{20}$").matches(id.trim())

    fun saveClientId(context: Context, id: String): Boolean {
        if (!isValidClientId(id)) return false
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).edit { putString("client_id", id.trim()) }
        return true
    }

    /** Result of the most recent upload attempt, shown in Settings so the owner can see it works. */
    fun lastUpload(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_upload", null)

    fun isConnected(context: Context) = token(context) != null

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    // ------------------------------------------------------------- device flow (call off the main thread)

    fun requestCode(context: Context): DeviceCode {
        val o = postForm(
            "$loginBase/login/device/code",
            mapOf("client_id" to clientId(context), "scope" to "repo"),
        )
        o.optString("error").takeIf { it.isNotBlank() }?.let { throw IllegalStateException("GitHub: $it") }
        return DeviceCode(o.getString("user_code"), o.getString("verification_uri"), o.getString("device_code"), o.optInt("interval", 5))
    }

    /** Returns true once the owner approved; false while still pending. Throws if expired/denied. */
    fun poll(context: Context, code: DeviceCode): Boolean {
        val o = postForm(
            "$loginBase/login/oauth/access_token",
            mapOf(
                "client_id" to clientId(context),
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        o.optString("access_token").takeIf { it.isNotBlank() }?.let { saveToken(context, it); return true }
        return when (val err = o.optString("error")) {
            "authorization_pending", "slow_down" -> false
            else -> throw IllegalStateException("GitHub: $err")
        }
    }

    // ------------------------------------------------------------- automatic upload

    /** Uploads every report not yet on GitHub. Safe to call often; runs on a background thread. */
    fun uploadPending(context: Context, done: (String?) -> Unit = {}) {
        val app = context.applicationContext
        if (token(app) == null) return
        Thread({ done(uploadPendingNow(app) ?: lastUpload(app)) }, "crash-upload").apply { isDaemon = true }.start()
    }

    private val uploadLock = Any()

    /** Blocking upload (call off the main thread). Returns the new "last upload" line, or null if nothing to do. */
    fun uploadPendingNow(context: Context): String? = synchronized(uploadLock) {
        val app = context.applicationContext
        val token = token(app) ?: return null
        val today = System.currentTimeMillis() / 86_400_000
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var handledToday = if (prefs.getLong("day", -1) == today) prefs.getInt("handled", 0) else 0
        val uploadedTitles = prefs.getStringSet("titles", emptySet())!!.toMutableSet()
        var last: String? = null
        for (e in CrashReporter.entries(app).filter { it.status == CrashReporter.Status.SAVED }.reversed()) {
            val dedupeKey = "${e.version}|${e.kind}|${e.title}"
            val isTest = e.title.startsWith(TEST_TITLE)
            if (!isTest && e.kind == CrashReporter.Kind.HANDLED && (handledToday >= 5 || dedupeKey in uploadedTitles)) {
                // Same handled problem already reported for this version, or daily cap reached: don't spam.
                continue
            }
            val r = createIssue(token, e)
            if (r.number == null) { last = r.message; break } // offline or refused: retry next time
            CrashReporter.mark(app, e.id, CrashReporter.Status.SENT_GITHUB, "#${r.number}")
            last = "Uploaded #${r.number} · ${CrashReporter.formatTime(System.currentTimeMillis())}"
            uploadedTitles += dedupeKey
            if (e.kind == CrashReporter.Kind.HANDLED && !isTest) handledToday++
        }
        prefs.edit {
            putLong("day", today).putInt("handled", handledToday).putStringSet("titles", uploadedTitles)
            if (last != null) putString("last_upload", last)
        }
        last
    }

    const val TEST_TITLE = "Test report"

    /** Adds a harmless test report and uploads it, to prove the whole path works. */
    fun sendTest(context: Context, done: (String?) -> Unit) {
        CrashReporter.handled(context, "Settings → Send test report", IllegalStateException("$TEST_TITLE ${System.currentTimeMillis()}: automatic crash reports work"))
        uploadPending(context, done)
    }

    private data class IssueResult(val number: Int?, val message: String)

    private fun createIssue(token: String, e: CrashReporter.Entry): IssueResult = try {
        val label = if (e.kind == CrashReporter.Kind.CRASH) "crash" else "handled-problem"
        var r = postIssue(token, e, label)
        // Labels can be refused (422) if they don't exist and can't be created: retry without them.
        if (r.first == 422) r = postIssue(token, e, null)
        when (r.first) {
            in 200..299 -> JSONObject(r.second).optInt("number").takeIf { it > 0 }
                ?.let { IssueResult(it, "") } ?: IssueResult(null, "GitHub gave no issue number")
            401 -> IssueResult(null, "GitHub login expired: tap Disconnect, then Connect again")
            403, 404 -> IssueResult(null, "GitHub refused (${r.first}): install the GitHub App on ${BuildConfig.CRASH_REPO}")
            else -> IssueResult(null, "GitHub error ${r.first}")
        }
    } catch (x: Exception) {
        IssueResult(null, "No internet (${x.javaClass.simpleName}); will retry")
    }

    private fun postIssue(token: String, e: CrashReporter.Entry, label: String?): Pair<Int, String> {
        val body = JSONObject()
            .put("title", "${if (e.kind == CrashReporter.Kind.CRASH) "Crash" else "Handled problem"}: ${e.title}".take(150))
            .put("body", "Uploaded automatically by TheatreSync ${e.version} at ${CrashReporter.formatTime(e.timeMs)}.\n\n```\n${e.report.take(60_000)}\n```")
        if (label != null) body.put("labels", org.json.JSONArray().put(label))
        val c = URL("$apiBase/repos/${BuildConfig.CRASH_REPO}/issues").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to text
    }

    // ------------------------------------------------------------- helpers

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { os ->
            os.write(form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray())
        }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun saveToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(token.toByteArray())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("token", Base64.encodeToString(enc, Base64.NO_WRAP))
        }
    }

    private fun token(context: Context): String? = try {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null) ?: return null
        val enc = p.getString("token", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
    } catch (_: Exception) {
        null
    }
}
