package com.theatresync.app

/** Groups waiting crash reports into as few GitHub issues as possible (repeats merged and counted). */
object CrashBatch {
    /** GitHub allows ~65,000 characters per issue; stay under it. */
    const val MAX_BODY = 60_000
    private const val PER_REPORT = 6_000

    private fun key(e: CrashReporter.Entry) = "${e.kind}|${e.title}"

    /** Splits [pending] (oldest first) into batches whose issue text fits, keeping repeats together. */
    fun chunks(pending: List<CrashReporter.Entry>): List<List<CrashReporter.Entry>> {
        val groups = pending.groupBy(::key).values.toList()
        val out = mutableListOf<List<CrashReporter.Entry>>()
        var cur = mutableListOf<CrashReporter.Entry>(); var size = 0
        for (g in groups) {
            val cost = minOf(g.first().report.length, PER_REPORT) + 400
            if (cur.isNotEmpty() && size + cost > MAX_BODY - 2_000) { out += cur; cur = mutableListOf(); size = 0 }
            cur += g; size += cost
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** The issue title and text for one batch. */
    fun issue(batch: List<CrashReporter.Entry>, appVersion: String): Pair<String, String> {
        val groups = batch.groupBy(::key).values.toList()
        if (batch.size == 1) {
            val e = batch[0]
            val title = "${if (e.kind == CrashReporter.Kind.CRASH) "Crash" else "Handled problem"}: ${e.title}"
            return title to "Uploaded automatically by TheatreSync ${e.version} at ${CrashReporter.formatTime(e.timeMs)}.\n\n```\n${e.report.take(MAX_BODY - 500)}\n```"
        }
        val crashes = batch.count { it.kind == CrashReporter.Kind.CRASH }
        val title = "TheatreSync reports: ${batch.size} (${groups.size} different${if (crashes > 0) ", $crashes crash${if (crashes == 1) "" else "es"}" else ""}) · $appVersion"
        val sb = StringBuilder("Uploaded automatically by TheatreSync $appVersion: ${batch.size} reports, ${groups.size} different problems (repeats merged).\n\n")
        sb.append("| # | Problem | Times | Versions | First | Last |\n|---|---|---|---|---|---|\n")
        groups.forEachIndexed { i, g ->
            val kind = if (g[0].kind == CrashReporter.Kind.CRASH) "**Crash**" else "Handled"
            sb.append("| ${i + 1} | $kind: ${g[0].title.replace("|", "/").take(90)} | ${g.size} | ${g.map { it.version }.distinct().joinToString(", ")} | ${CrashReporter.formatTime(g.minOf { it.timeMs })} | ${CrashReporter.formatTime(g.maxOf { it.timeMs })} |\n")
        }
        groups.forEachIndexed { i, g ->
            val e = g.maxBy { it.timeMs } // the latest occurrence
            sb.append("\n### ${i + 1}. ${e.title.take(120)} (×${g.size})\n```\n${e.report.take(PER_REPORT)}\n```\n")
        }
        return title to sb.toString().take(MAX_BODY)
    }
}
