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
 * and never leaves the phone except to github.com / api.github.com.
 *
 * Staying signed in: GitHub App sign-ins last 8 hours and come with a refresh token valid for
 * 6 months. The app renews the sign-in by itself before it runs out (and once more if GitHub says
 * it expired), and each renewal also renews the 6 months, so it stays connected as long as the app
 * is opened at least once every 6 months. Device-flow sign-ins renew without any secret.
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

    fun isConnected(context: Context) = stored(context) != null

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

    /** Poll result: approved, still waiting, or waiting but GitHub asked to poll more slowly. */
    enum class Poll { APPROVED, PENDING, SLOW_DOWN }

    /** Returns APPROVED once the owner approved. Throws if expired/denied. */
    fun poll(context: Context, code: DeviceCode): Poll {
        val o = postForm(
            "$loginBase/login/oauth/access_token",
            mapOf(
                "client_id" to clientId(context),
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        if (o.optString("access_token").isNotBlank()) { saveTokens(context, o); return Poll.APPROVED }
        return when (val err = o.optString("error")) {
            "authorization_pending" -> Poll.PENDING
            "slow_down" -> Poll.SLOW_DOWN
            else -> throw IllegalStateException("GitHub: $err")
        }
    }

    // ------------------------------------------------------------- staying signed in

    private data class Tokens(val access: String, val refresh: String?, val expiresAtMs: Long, val refreshExpiresAtMs: Long)

    private val refreshLock = Any()

    /**
     * A working access token (call off the main thread): renews it first if it runs out within 30 minutes.
     * Null if not connected, or if GitHub no longer accepts the refresh token (then the owner reconnects).
     */
    fun validToken(context: Context, forceRefresh: Boolean = false): String? = synchronized(refreshLock) {
        val t = stored(context) ?: return null
        val soon = System.currentTimeMillis() + 30 * 60_000
        if (!forceRefresh && (t.refresh == null || t.expiresAtMs == 0L || t.expiresAtMs > soon)) return t.access
        val refresh = t.refresh ?: return t.access
        return try {
            val o = postForm(
                "$loginBase/login/oauth/access_token",
                mapOf("client_id" to clientId(context), "grant_type" to "refresh_token", "refresh_token" to refresh),
            )
            when {
                o.optString("access_token").isNotBlank() -> { saveTokens(context, o); o.getString("access_token") }
                o.optString("error") in setOf("bad_refresh_token", "unauthorized_client") -> { disconnect(context); null }
                else -> t.access // GitHub busy or odd answer: keep the old one and try again later
            }
        } catch (_: Exception) {
            t.access // offline: try again next time
        }
    }

    /** Renews the sign-in if it's due (app start / Settings): keeps it alive with no one noticing. */
    fun keepAlive(context: Context) {
        val app = context.applicationContext
        if (stored(app)?.refresh == null) return
        Thread({ validToken(app) }, "github-keepalive").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------- automatic upload

    /** Uploads every report not yet on GitHub. Safe to call often; runs on a background thread. */
    fun uploadPending(context: Context, done: (String?) -> Unit = {}) {
        val app = context.applicationContext
        if (stored(app) == null) return
        Thread({ done(uploadPendingNow(app) ?: lastUpload(app)) }, "crash-upload").apply { isDaemon = true }.start()
    }

    private val uploadLock = Any()

    /**
     * Blocking upload (call off the main thread). Returns the new "last upload" line, or null if nothing to do.
     *
     * Everything waiting goes in one batch (owner: "not able to send all the issues at once"): one GitHub
     * issue per batch instead of one per report, with repeats of the same problem merged and counted, so
     * GitHub's "too many, too fast" limit is never hit. A batch too big for one issue is split into a few,
     * a couple of seconds apart; if GitHub still asks to slow down, the rest go next time.
     */
    fun uploadPendingNow(context: Context): String? = synchronized(uploadLock) {
        val app = context.applicationContext
        var token = validToken(app) ?: return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = CrashReporter.entries(app).filter { it.status == CrashReporter.Status.SAVED }.reversed() // oldest first
        if (pending.isEmpty()) return null
        var last: String? = null
        for ((i, chunk) in CrashBatch.chunks(pending).withIndex()) {
            if (i > 0) Thread.sleep(2_500)
            val (title, body) = CrashBatch.issue(chunk, BuildConfig.VERSION_NAME)
            val label = if (chunk.any { it.kind == CrashReporter.Kind.CRASH }) "crash" else "handled-problem"
            var r = createIssue(token, title, body, label)
            if (r.status == 401) {
                // Sign-in ran out early: renew it and try once more, without bothering the owner.
                val renewed = validToken(app, forceRefresh = true)
                if (renewed == null) { last = "GitHub sign-in ended: tap Connect GitHub once"; break }
                token = renewed
                r = createIssue(token, title, body, label)
            }
            if (r.number == null) { last = r.message; break } // offline, refused or "slow down": the rest go next time
            chunk.forEach { CrashReporter.mark(app, it.id, CrashReporter.Status.SENT_GITHUB, "#${r.number}") }
            last = "Uploaded ${chunk.size} report${if (chunk.size == 1) "" else "s"} as #${r.number} · ${CrashReporter.formatTime(System.currentTimeMillis())}"
        }
        prefs.edit { if (last != null) putString("last_upload", last) }
        last
    }

    const val TEST_TITLE = "Test report"

    /** Adds a harmless test report and uploads it, to prove the whole path works. */
    fun sendTest(context: Context, done: (String?) -> Unit) {
        CrashReporter.handled(context, "Settings → Send test report", IllegalStateException("$TEST_TITLE ${System.currentTimeMillis()}: automatic crash reports work"))
        uploadPending(context, done)
    }

    private data class IssueResult(val number: Int?, val message: String, val status: Int = 0)

    private fun createIssue(token: String, title: String, body: String, label: String): IssueResult = try {
        var r = postIssue(token, title, body, label)
        // Labels can be refused (422) if they don't exist and can't be created: retry without them.
        if (r.first == 422) r = postIssue(token, title, body, null)
        when (r.first) {
            in 200..299 -> JSONObject(r.second).optInt("number").takeIf { it > 0 }
                ?.let { IssueResult(it, "") } ?: IssueResult(null, "GitHub gave no issue number")
            401 -> IssueResult(null, "GitHub sign-in ended: tap Connect GitHub once", 401)
            403, 429 -> if (r.second.contains("rate limit", ignoreCase = true) || r.first == 429)
                IssueResult(null, "GitHub asked to slow down: the rest go in a few minutes")
                else IssueResult(null, "GitHub refused (${r.first}): install the GitHub App on ${BuildConfig.CRASH_REPO}")
            404 -> IssueResult(null, "GitHub refused (404): install the GitHub App on ${BuildConfig.CRASH_REPO}")
            else -> IssueResult(null, "GitHub error ${r.first}")
        }
    } catch (x: Exception) {
        IssueResult(null, "No internet (${x.javaClass.simpleName}); will retry")
    }

    private fun postIssue(token: String, title: String, text: String, label: String?): Pair<Int, String> {
        val body = JSONObject().put("title", title.take(150)).put("body", text)
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

    /** Saves the access token and, for GitHub Apps, its refresh token and both expiry times (encrypted). */
    private fun saveTokens(context: Context, o: JSONObject) {
        val now = System.currentTimeMillis()
        val blob = JSONObject()
            .put("access", o.getString("access_token"))
            .put("refresh", o.optString("refresh_token").ifBlank { null } ?: JSONObject.NULL)
            .put("exp", if (o.optLong("expires_in") > 0) now + o.getLong("expires_in") * 1000 else 0L)
            .put("rexp", if (o.optLong("refresh_token_expires_in") > 0) now + o.getLong("refresh_token_expires_in") * 1000 else 0L)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(blob.toString().toByteArray())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("token", Base64.encodeToString(enc, Base64.NO_WRAP))
        }
    }

    /** What's saved (no network). Older versions saved just the access token; that still reads. */
    private fun stored(context: Context): Tokens? = try {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null) ?: return null
        val enc = p.getString("token", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val text = String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
        if (!text.startsWith("{")) Tokens(text, null, 0, 0)
        else JSONObject(text).let { o ->
            Tokens(o.getString("access"), o.optString("refresh").takeIf { it.isNotBlank() && it != "null" }, o.optLong("exp"), o.optLong("rexp"))
        }
    } catch (_: Exception) {
        null
    }
}
