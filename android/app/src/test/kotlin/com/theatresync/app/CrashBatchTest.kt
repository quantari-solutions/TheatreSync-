package com.theatresync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashBatchTest {
    private fun e(i: Int, title: String, kind: CrashReporter.Kind = CrashReporter.Kind.HANDLED, report: String = "stack $i") =
        CrashReporter.Entry("$i", 1_763_456_941_000 + i * 60_000L, "0.7.$i", kind, title, report, CrashReporter.Status.SAVED, null)

    @Test fun fiftyReportsGoAsOneIssueWithRepeatsMerged() {
        val pending = (0 until 50).map { e(it, if (it % 2 == 0) "SocketException: connection abort" else "IOException: YouTube answered 403") }
        val chunks = CrashBatch.chunks(pending)
        assertEquals(1, chunks.size)
        val (title, body) = CrashBatch.issue(chunks[0], "0.7.9")
        assertTrue(title, title.startsWith("TheatreSync reports: 50 (2 different)"))
        assertTrue(body.contains("(×25)"))
    }

    @Test fun bigReportsSplitIntoAFewIssuesThatFit() {
        val pending = (0 until 30).map { e(it, "Problem $it", report = "x".repeat(10_000)) }
        val chunks = CrashBatch.chunks(pending)
        assertTrue(chunks.size in 2..5)
        assertEquals(30, chunks.sumOf { it.size })
        chunks.forEach { assertTrue(CrashBatch.issue(it, "0.7.9").second.length <= CrashBatch.MAX_BODY) }
    }

    @Test fun singleReportKeepsTheOldTitle() {
        val (title, _) = CrashBatch.issue(listOf(e(1, "Boom", CrashReporter.Kind.CRASH)), "0.7.9")
        assertEquals("Crash: Boom", title)
    }
}
