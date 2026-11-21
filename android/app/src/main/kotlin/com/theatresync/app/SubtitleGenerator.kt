package com.theatresync.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.theatresync.core.SpeechCues
import com.theatresync.core.Subtitles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Makes subtitles for a movie on the phone: decodes its sound, keeps mostly the dialogue (the
 * centre channel of surround sound), and runs the open-source Vosk offline speech recognizer over
 * it. Vosk gives each word's time, so every line appears exactly when it is spoken. Works on any
 * phone; each language's speech pack (~40–100 MB) is downloaded once from alphacephei.com, the
 * Vosk project's own site. Nothing is uploaded. Optional translation uses ML Kit on the phone.
 */
class SubtitleGenerator(private val context: Context) {
    interface Listener {
        /** Downloading this language's speech pack (first time only). */
        fun onDownloading(percent: Int) {}
        fun onProgress(percent: Int, etaSec: Long?)
        fun onDone(srtFile: File)
        fun onFailed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
    private val packs = File(context.filesDir, "speech").apply { mkdirs() }

    fun cachedFile(uri: Uri, spoken: String, shownIn: String): File? = target(uri, spoken, shownIn).takeIf { it.exists() && it.length() > 0 }

    fun cancel() { cancelled = true }

    /** Any phone: the recognizer ships with the app; only the language pack is downloaded. */
    fun isSupported(): Boolean = true

    /** @param spoken language spoken in the movie (e.g. "en-US"); @param shownIn language to show ("en", "hi"…) */
    fun start(uri: Uri, spoken: String, shownIn: String, listener: Listener) {
        cancelled = false
        Thread({
            try {
                val model = speechPack(spoken, listener) ?: return@Thread
                val cues = recognise(uri, spoken, model, listener) ?: return@Thread
                val final = if (shownIn.substringBefore('-') == spoken.substringBefore('-')) cues else translate(cues, spoken, shownIn)
                val out = target(uri, spoken, shownIn)
                out.writeText(Subtitles.toSrt(final))
                main.post { listener.onDone(out) }
            } catch (e: Exception) {
                CrashReporter.handled(context, "generate subtitles ($spoken)", e)
                main.post { listener.onFailed(e.message ?: e.javaClass.simpleName) }
            }
        }, "subtitles").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- language packs

    /** The unpacked Vosk model for [spoken], downloading it the first time. Null if cancelled. */
    private fun speechPack(spoken: String, listener: Listener): File? {
        val name = PACKS[spoken.substringBefore('-')] ?: throw IllegalStateException("no offline speech pack for $spoken")
        val home = File(packs, name)
        if (File(home, "ready").exists()) return home
        home.deleteRecursively()
        val zip = File(context.cacheDir, "$name.zip")
        main.post { listener.onDownloading(0) }
        val c = URL("https://alphacephei.com/vosk/models/$name.zip").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("speech pack download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            var got = 0L; var shown = -1
            c.inputStream.use { input -> zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) return null
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    val pct = if (total > 0) (got * 100 / total).toInt() else 0
                    if (pct != shown) { shown = pct; main.post { listener.onDownloading(pct) } }
                }
            } }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("couldn't download the speech pack — check the internet connection", e)
        } finally { c.disconnect(); }
        // Unpack: the zip holds one folder named like the model; strip it.
        val tmp = File(packs, "$name.tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val f = File(tmp, rel)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        if (!tmp.renameTo(home)) throw IllegalStateException("couldn't unpack the speech pack")
        File(home, "ready").writeText("ok")
        return home
    }

    // ---------------------------------------------------------------- speech → timed words

    private fun recognise(uri: Uri, spoken: String, modelDir: File, listener: Listener): List<Subtitles.Cue>? {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); throw IllegalStateException("this movie has no sound track") }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val model = org.vosk.Model(modelDir.absolutePath)
        val rec = org.vosk.Recognizer(model, TARGET_RATE.toFloat()).apply { setWords(true) }
        val words = mutableListOf<SpeechCues.Word>()
        var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val resampler = Resampler()
        val started = System.currentTimeMillis()
        var lastReport = 0L
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!cancelled) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { f -> inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val shorts = ShortArray(info.size / 2)
                    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    codec.releaseOutputBuffer(o, false)
                    val pcm = resampler.process(downmix(shorts, channels), inRate, TARGET_RATE)
                    if (pcm.isNotEmpty() && rec.acceptWaveForm(pcm, pcm.size)) words += SpeechCues.words(rec.result)
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) { lastReport = now; report(info.presentationTimeUs / 1000, durationUs, started, listener) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (cancelled) return null
            words += SpeechCues.words(rec.finalResult)
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
            rec.close(); model.close()
        }
        val cues = SpeechCues.cues(words, joiner = if (spoken.startsWith("ja")) "" else " ")
        if (cues.isEmpty()) throw IllegalStateException("no speech was recognised — is the spoken language right?")
        return cues
    }

    private fun report(doneMs: Long, durationUs: Long, started: Long, listener: Listener) {
        if (durationUs <= 0) return
        val percent = (doneMs * 1000 * 100 / durationUs).toInt().coerceIn(0, 99)
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val eta = if (percent in 2..99 && elapsed > 10) elapsed * (100 - percent) / percent else null
        main.post { listener.onProgress(percent, eta) }
    }

    // ---------------------------------------------------------------- translation (on-device)

    private fun translate(cues: List<Subtitles.Cue>, spoken: String, shownIn: String): List<Subtitles.Cue> {
        val from = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(spoken.substringBefore('-'))
        val to = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(shownIn.substringBefore('-'))
        if (from == null || to == null) throw IllegalStateException("can't translate between these languages")
        val translator = com.google.mlkit.nl.translate.Translation.getClient(
            com.google.mlkit.nl.translate.TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build(),
        )
        try {
            com.google.android.gms.tasks.Tasks.await(translator.downloadModelIfNeeded(), 10, TimeUnit.MINUTES)
            return cues.map { c -> c.copy(text = com.google.android.gms.tasks.Tasks.await(translator.translate(c.text), 30, TimeUnit.SECONDS)) }
        } finally {
            translator.close()
        }
    }

    private fun target(uri: Uri, spoken: String, shownIn: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return File(dir, "${hash}_${spoken}_$shownIn.srt")
    }

    companion object {
        const val TARGET_RATE = 16_000

        /** Vosk's small offline models, one per language the app offers (names on alphacephei.com/vosk/models). */
        val PACKS = mapOf(
            "en" to "vosk-model-small-en-us-0.15", "hi" to "vosk-model-small-hi-0.22", "gu" to "vosk-model-small-gu-0.42",
            "es" to "vosk-model-small-es-0.42", "fr" to "vosk-model-small-fr-0.22", "de" to "vosk-model-small-de-0.15",
            "ja" to "vosk-model-small-ja-0.22", "ko" to "vosk-model-small-ko-0.22",
        )

        /**
         * To mono for speech. Surround sound (Android's decoder order: FL, FR, FC, LFE, BL, BR) keeps mostly
         * the centre channel, where films put the dialogue, so music and effects don't drown it out.
         */
        internal fun downmix(samples: ShortArray, channels: Int): ShortArray {
            if (channels <= 1) return samples
            val frames = samples.size / channels
            return ShortArray(frames) { f ->
                val b = f * channels
                val v = if (channels >= 3) 0.7 * samples[b + 2] + 0.15 * (samples[b] + samples[b + 1])
                    else (0 until channels).sumOf { samples[b + it].toInt() }.toDouble() / channels
                v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}

/** Linear resampler that keeps its position across calls. */
internal class Resampler {
    private var pos = 0.0
    private var last: Short = 0

    fun process(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate || input.isEmpty()) return input
        val step = inRate.toDouble() / outRate
        val out = ArrayList<Short>(((input.size / step) + 2).toInt())
        while (pos < input.size) {
            val i = pos.toInt()
            val frac = pos - i
            val a = if (i - 1 < 0) last.toInt() else input[i - 1].toInt()
            val b = input[i].toInt()
            out += (a + (b - a) * frac).toInt().toShort()
            pos += step
        }
        pos -= input.size
        last = input.last()
        return out.toShortArray()
    }
}
