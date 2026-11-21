package com.theatresync.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Automatic crash reports, end to end on a real Android system: Client ID entry → device
 * code → approval polling → token stored in the Android Keystore → report uploaded as an
 * issue. GitHub is replaced by a fake server on loopback that answers like the real API.
 */
@RunWith(AndroidJUnit4::class)
class CrashUploadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // "METHOD path\nheaders" to body
    private val polls = AtomicInteger()
    private val issueNumber = AtomicInteger(40)

    /** How the fake GitHub answers issue creation: null = normal, else a fixed status. */
    @Volatile private var issueStatus: Int? = null
    @Volatile private var refuseLabelsOnce = false
    /** Answer like a GitHub App: 8-hour sign-in plus a refresh token. */
    @Volatile private var appStyleTokens = false
    @Volatile private var expiresInSec = 28_800
    /** The fake GitHub refuses this token with 401 (it "ran out"). */
    @Volatile private var rejectToken: String? = null

    @Before fun setUp() {
        context.getSharedPreferences("github", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("github_setup", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "crash_timeline.json").writeText("[]") // start from an empty timeline
        server = ServerSocket(0)
        thread(isDaemon = true) { while (!server.isClosed) runCatching { serve() } }
        GitHubLink.loginBase = "http://127.0.0.1:${server.localPort}"
        GitHubLink.apiBase = GitHubLink.loginBase
    }

    @After fun tearDown() {
        server.close()
        GitHubLink.loginBase = "https://github.com"
        GitHubLink.apiBase = "https://api.github.com"
        context.getSharedPreferences("github", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("github_setup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Reads one header line as raw bytes (the body may be UTF-8, so never mix char and byte counts). */
    private fun java.io.InputStream.readLineBytes(): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
    }

    private fun serve() {
        server.accept().use { s ->
            val input = s.getInputStream()
            val requestLine = input.readLineBytes() ?: return
            val headers = generateSequence { input.readLineBytes()?.takeIf { it.isNotEmpty() } }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val raw = ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }
            val body = raw.toString(Charsets.UTF_8)
            requests += (requestLine + "\n" + headers.joinToString("\n")) to body
            val path = requestLine.split(" ")[1]
            val (status, json) = when {
                path == "/login/device/code" ->
                    200 to """{"device_code":"dev123","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","interval":5}"""
                path == "/login/oauth/access_token" && body.contains("grant_type=refresh_token") ->
                    if (body.contains("refresh_token=ghr_")) 200 to """{"access_token":"ghu_renewed","refresh_token":"ghr_2","expires_in":28800,"refresh_token_expires_in":15897600,"token_type":"bearer"}"""
                    else 200 to """{"error":"bad_refresh_token"}"""
                path == "/login/oauth/access_token" ->
                    if (polls.incrementAndGet() == 1) 200 to """{"error":"authorization_pending"}"""
                    else if (appStyleTokens) 200 to """{"access_token":"ghu_testtoken","refresh_token":"ghr_1","expires_in":$expiresInSec,"refresh_token_expires_in":15897600,"token_type":"bearer"}"""
                    else 200 to """{"access_token":"ghu_testtoken","token_type":"bearer"}"""
                path.endsWith("/issues") -> when {
                    rejectToken != null && headers.any { it.contains("Bearer $rejectToken") } -> 401 to """{"message":"Bad credentials"}"""
                    issueStatus != null -> issueStatus!! to """{"message":"Resource not accessible by integration"}"""
                    refuseLabelsOnce && body.contains("\"labels\"") -> { refuseLabelsOnce = false; 422 to """{"message":"Validation Failed"}""" }
                    else -> 201 to """{"number":${issueNumber.incrementAndGet()}}"""
                }
                else -> 404 to "{}"
            }
            val bytes = json.toByteArray()
            s.getOutputStream().write(
                ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
            )
            s.getOutputStream().flush()
        }
    }

    private fun connect() {
        assertFalse(GitHubLink.saveClientId(context, "not an id"))
        assertTrue(GitHubLink.saveClientId(context, "Iv23liTestClient01"))
        assertTrue(GitHubLink.isConfigured(context))
        val code = GitHubLink.requestCode(context)
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("still waiting for approval", GitHubLink.Poll.PENDING, GitHubLink.poll(context, code))
        assertEquals("approved", GitHubLink.Poll.APPROVED, GitHubLink.poll(context, code))
        assertTrue(GitHubLink.isConnected(context))
        assertTrue(requests.any { it.second.contains("client_id=Iv23liTestClient01") })
    }

    private fun entry(titlePart: String) = CrashReporter.entries(context).first { it.title.contains(titlePart) }

    @Test fun reportIsUploadedAsAnIssueAfterConnecting() {
        connect()
        val marker = "UploadTest-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        GitHubLink.uploadPendingNow(context)
        val e = entry(marker)
        assertEquals(CrashReporter.Status.SENT_GITHUB, e.status)
        assertNotNull(e.reference)
        assertTrue(e.reference!!.startsWith("#"))
        val issue = requests.last { it.first.contains("/issues") && it.second.contains(marker) }
        assertTrue("token sent", issue.first.contains("Authorization: Bearer ghu_testtoken"))
        assertTrue("labelled", issue.second.contains("handled-problem"))
        assertTrue(GitHubLink.lastUpload(context)!!.startsWith("Uploaded #"))
    }

    @Test fun refusedLabelsAreRetriedWithoutLabels() {
        connect()
        refuseLabelsOnce = true
        val marker = "LabelTest-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        GitHubLink.uploadPendingNow(context)
        assertEquals(CrashReporter.Status.SENT_GITHUB, entry(marker).status)
    }

    @Test fun appNotInstalledIsExplainedAndRetriedLater() {
        connect()
        issueStatus = 403
        val marker = "RefusedTest-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        GitHubLink.uploadPendingNow(context)
        assertEquals(CrashReporter.Status.SAVED, entry(marker).status)
        assertTrue(GitHubLink.lastUpload(context)!!.contains("install the GitHub App"))
        issueStatus = null
        GitHubLink.uploadPendingNow(context)
        assertEquals(CrashReporter.Status.SENT_GITHUB, entry(marker).status)
    }

    @Test fun testReportButtonPathUploads() {
        connect()
        val done = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        GitHubLink.sendTest(context) { result = it; done.countDown() }
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("got: $result", result!!.startsWith("Uploaded #"))
        assertEquals(CrashReporter.Status.SENT_GITHUB, entry(GitHubLink.TEST_TITLE).status)
    }

    @Test fun signInRenewsItselfBeforeItRunsOut() {
        appStyleTokens = true
        expiresInSec = 600 // runs out within the 30-minute margin: renewed before uploading
        connect()
        val marker = "RenewTest-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        GitHubLink.uploadPendingNow(context)
        assertEquals(CrashReporter.Status.SENT_GITHUB, entry(marker).status)
        assertTrue("renewed without the owner", requests.any { it.second.contains("grant_type=refresh_token") && it.second.contains("refresh_token=ghr_1") })
        assertTrue("used the new sign-in", requests.last { it.first.contains("/issues") }.first.contains("Bearer ghu_renewed"))
        assertTrue(GitHubLink.isConnected(context))
    }

    @Test fun signInThatEndedEarlyIsRenewedAndRetried() {
        appStyleTokens = true
        connect()
        rejectToken = "ghu_testtoken" // GitHub says the 8-hour sign-in is over
        val marker = "Expired401-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        GitHubLink.uploadPendingNow(context)
        assertEquals(CrashReporter.Status.SENT_GITHUB, entry(marker).status)
        assertTrue(GitHubLink.isConnected(context))
    }

    @Test fun oldReportsAreDeletedAfter69Days() {
        val marker = "OldReport-${System.nanoTime()}"
        CrashReporter.handled(context, "test", IllegalStateException(marker))
        val now = System.currentTimeMillis()
        assertEquals(0, CrashReporter.prune(context, now + 68L * 86_400_000))
        assertTrue(CrashReporter.entries(context).any { it.title.contains(marker) })
        assertEquals(1, CrashReporter.prune(context, now + 70L * 86_400_000))
        assertTrue(CrashReporter.entries(context).none { it.title.contains(marker) })
    }
}
