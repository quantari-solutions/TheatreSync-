package com.theatresync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns timed words from an offline speech recognizer (Vosk's JSON results) into subtitle cues:
 * a new line starts at a pause in speech, when a line would be on screen too long, or when it
 * would no longer fit on two short lines. Timings come from the words themselves, so subtitles
 * appear exactly when each sentence is spoken.
 */
object SpeechCues {
    data class Word(val startMs: Long, val endMs: Long, val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** Words from one Vosk result (`{"result":[{"word":"…","start":0.1,"end":0.5,"conf":1.0}],"text":"…"}`), shifted by [offsetMs]. */
    fun words(resultJson: String, offsetMs: Long = 0): List<Word> = runCatching {
        val o = json.parseToJsonElement(resultJson).jsonObject
        val list = o["result"]?.jsonArray ?: return emptyList()
        list.mapNotNull { e ->
            val w = e as? JsonObject ?: return@mapNotNull null
            val text = w["word"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "[unk]" } ?: return@mapNotNull null
            val s = w["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val e2 = w["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Word(offsetMs + (s * 1000).toLong(), offsetMs + (e2 * 1000).toLong(), text)
        }
    }.getOrDefault(emptyList())

    fun cues(
        words: List<Word>,
        pauseMs: Long = 700,
        maxDurationMs: Long = 6_000,
        maxChars: Int = 84,
        /** Languages written without spaces between words (Japanese…). */
        joiner: String = " ",
    ): List<Subtitles.Cue> {
        val out = mutableListOf<Subtitles.Cue>()
        var line = mutableListOf<Word>()
        fun flush(sentenceEnds: Boolean) {
            if (line.isEmpty()) return
            var text = line.joinToString(joiner) { it.text }
            text = text.replaceFirstChar { it.uppercase() }
            if (sentenceEnds && joiner == " " && text.last().isLetterOrDigit()) text += "."
            // Keep a cue on screen at least ~1 s so it can be read.
            out += Subtitles.Cue(line.first().startMs, maxOf(line.last().endMs, line.first().startMs + 1_000), text)
            line = mutableListOf()
        }
        for (w in words) {
            val prev = line.lastOrNull()
            if (prev != null) {
                val gap = w.startMs - prev.endMs
                val length = line.sumOf { it.text.length + joiner.length } + w.text.length
                when {
                    gap >= pauseMs -> flush(sentenceEnds = true)
                    w.endMs - line.first().startMs > maxDurationMs || length > maxChars -> flush(sentenceEnds = false)
                }
            }
            line += w
        }
        flush(sentenceEnds = true)
        // A cue never overlaps the next one.
        return out.mapIndexed { i, c -> out.getOrNull(i + 1)?.let { n -> if (c.endMs > n.startMs) c.copy(endMs = n.startMs) else c } ?: c }
    }
}
