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
    private const val KEY_ALIAS = "theatresync_github"

    val isConfigured: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

    fun isConnected(context: Context) = token(context) != null

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    // ------------------------------------------------------------- device flow (call off the main thread)

    fun requestCode(): DeviceCode {
        val o = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to BuildConfig.GITHUB_CLIENT_ID, "scope" to "repo"),
        )
        return DeviceCode(o.getString("user_code"), o.getString("verification_uri"), o.getString("device_code"), o.optInt("interval", 5))
    }

    /** Returns true once the owner approved; false while still pending. Throws if expired/denied. */
    fun poll(context: Context, code: DeviceCode): Boolean {
        val o = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to BuildConfig.GITHUB_CLIENT_ID,
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
    fun uploadPending(context: Context) {
        val app = context.applicationContext
        val token = token(app) ?: return
        Thread({
            val today = System.currentTimeMillis() / 86_400_000
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var handledToday = if (prefs.getLong("day", -1) == today) prefs.getInt("handled", 0) else 0
            val uploadedTitles = prefs.getStringSet("titles", emptySet())!!.toMutableSet()
            for (e in CrashReporter.entries(app).filter { it.status == CrashReporter.Status.SAVED }.reversed()) {
                val dedupeKey = "${e.version}|${e.kind}|${e.title}"
                if (e.kind == CrashReporter.Kind.HANDLED && (handledToday >= 5 || dedupeKey in uploadedTitles)) {
                    // Same handled problem already reported for this version, or daily cap reached: don't spam.
                    continue
                }
                val number = try { createIssue(token, e) } catch (_: Exception) { null } ?: break // offline: retry next time
                CrashReporter.mark(app, e.id, CrashReporter.Status.SENT_GITHUB, "#$number")
                uploadedTitles += dedupeKey
                if (e.kind == CrashReporter.Kind.HANDLED) handledToday++
            }
            prefs.edit { putLong("day", today).putInt("handled", handledToday).putStringSet("titles", uploadedTitles) }
        }, "crash-upload").apply { isDaemon = true }.start()
    }

    private fun createIssue(token: String, e: CrashReporter.Entry): Int? {
        val body = JSONObject()
            .put("title", "${if (e.kind == CrashReporter.Kind.CRASH) "Crash" else "Handled problem"}: ${e.title}".take(150))
            .put("body", "Uploaded automatically by TheatreSync ${e.version} at ${CrashReporter.formatTime(e.timeMs)}.\n\n```\n${e.report.take(60_000)}\n```")
            .put("labels", org.json.JSONArray().put(if (e.kind == CrashReporter.Kind.CRASH) "crash" else "handled-problem"))
        val c = URL("https://api.github.com/repos/${BuildConfig.CRASH_REPO}/issues").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) return null
        return JSONObject(c.inputStream.use { it.readBytes().decodeToString() }).optInt("number").takeIf { it > 0 }
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
