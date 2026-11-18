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
                path == "/login/oauth/access_token" ->
                    if (polls.incrementAndGet() == 1) 200 to """{"error":"authorization_pending"}"""
                    else 200 to """{"access_token":"ghu_testtoken","token_type":"bearer"}"""
                path.endsWith("/issues") -> when {
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
        assertFalse("still waiting for approval", GitHubLink.poll(context, code))
        assertTrue("approved", GitHubLink.poll(context, code))
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
}
