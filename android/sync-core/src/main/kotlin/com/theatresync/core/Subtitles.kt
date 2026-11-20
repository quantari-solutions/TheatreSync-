package com.theatresync.core

/**
 * Subtitle cues: read .srt / .vtt files, write WebVTT for the Chromecast (its subtitle format)
 * and .srt for saving generated captions. Pure, unit-tested.
 */
object Subtitles {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})|(\d{1,2}):(\d{2})[,.](\d{1,3})""")

    private fun ms(t: String): Long? {
        val m = TIME.find(t.trim()) ?: return null
        val g = m.groupValues
        return if (g[1].isNotEmpty()) {
            g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1_000 + g[4].padEnd(3, '0').toLong()
        } else {
            g[5].toLong() * 60_000 + g[6].toLong() * 1_000 + g[7].padEnd(3, '0').toLong()
        }
    }

    /** Parses SRT or WebVTT text (BOM, CRLF and cue settings tolerated). */
    fun parse(text: String): List<Cue> {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if ("-->" in line) {
                val (a, b) = line.split("-->", limit = 2)
                val start = ms(a)
                val end = ms(b.trim().substringBefore(' '))
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { body += lines[i]; i++ }
                if (start != null && end != null && end > start && body.isNotEmpty()) {
                    cues += Cue(start, end, body.joinToString("\n").replace(Regex("</?font[^>]*>"), "").trim())
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun stamp(ms: Long, sep: Char): String {
        val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1_000 % 60; val f = ms % 1_000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, f)
    }

    fun toVtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { c -> append(stamp(c.startMs, '.')).append(" --> ").append(stamp(c.endMs, '.')).append('\n').append(c.text).append("\n\n") }
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { n, c -> append(n + 1).append('\n').append(stamp(c.startMs, ',')).append(" --> ").append(stamp(c.endMs, ',')).append('\n').append(c.text).append("\n\n") }
    }

    /** Splits long recognised text into readable cues (≤ 2 lines of ~42 characters) over its time span. */
    fun split(startMs: Long, endMs: Long, text: String, maxChars: Int = 84): List<Cue> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            if (current.isNotEmpty() && current.length + 1 + w.length > maxChars) { chunks += current.toString(); current = StringBuilder() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(w)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        val total = chunks.sumOf { it.length }.coerceAtLeast(1)
        var t = startMs
        return chunks.map { c ->
            val len = (endMs - startMs) * c.length / total
            Cue(t, (t + len).coerceAtMost(endMs), wrap(c)).also { t += len }
        }
    }

    private fun wrap(s: String, width: Int = 42): String {
        if (s.length <= width) return s
        val cut = s.lastIndexOf(' ', width).takeIf { it > 0 } ?: width
        return s.substring(0, cut) + "\n" + s.substring(cut).trim()
    }
}
