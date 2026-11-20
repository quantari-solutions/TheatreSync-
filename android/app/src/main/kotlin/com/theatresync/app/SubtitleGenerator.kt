package com.theatresync.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.theatresync.core.Subtitles
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Makes subtitles for a movie on the phone: decodes its sound, cuts it at pauses in speech, and
 * runs Android's on-device speech recognition on each piece (Android 13+ can read audio from a
 * file instead of the microphone). Nothing is uploaded. Optional translation uses ML Kit's
 * on-device translator (one-time language download).
 */
class SubtitleGenerator(private val context: Context) {
    interface Listener {
        fun onProgress(percent: Int, etaSec: Long?)
        fun onDone(srtFile: File)
        fun onFailed(reason: String)
    }

    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, "subtitles").apply { mkdirs() }

    fun cachedFile(uri: Uri, spoken: String, shownIn: String): File? = target(uri, spoken, shownIn).takeIf { it.exists() && it.length() > 0 }

    fun cancel() { cancelled = true }

    /** Android 13+ with an on-device recognizer (e.g. the Google app's offline speech models). */
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** @param spoken language spoken in the movie (e.g. "en-US"); @param shownIn language to show ("en", "hi"…) */
    fun start(uri: Uri, spoken: String, shownIn: String, listener: Listener) {
        cancelled = false
        Thread({
            try {
                val cues = recognise(uri, spoken, listener) ?: return@Thread
                val final = if (shownIn.substringBefore('-') == spoken.substringBefore('-')) cues else translate(cues, spoken, shownIn)
                val out = target(uri, spoken, shownIn)
                out.writeText(Subtitles.toSrt(final))
                main.post { listener.onDone(out) }
            } catch (e: Exception) {
                CrashReporter.handled(context, "generate subtitles", e)
                main.post { listener.onFailed(e.message ?: e.javaClass.simpleName) }
            }
        }, "subtitles").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- speech → text

    private fun recognise(uri: Uri, spoken: String, listener: Listener): List<Subtitles.Cue>? {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalStateException("this movie has no sound track")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val cues = mutableListOf<Subtitles.Cue>()
        val chunker = SpeechChunker(TARGET_RATE)
        var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val resampler = Resampler()
        val started = System.currentTimeMillis()
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
                    val mono = downmix(shorts, channels)
                    chunker.add(resampler.process(mono, inRate, TARGET_RATE)).forEach { chunk ->
                        recogniseChunk(chunk.pcm, spoken)?.let { text -> cues += Subtitles.split(chunk.startMs, chunk.endMs, text) }
                        report(chunk.endMs, durationUs, started, listener)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (cancelled) return null
            chunker.flush()?.let { chunk -> recogniseChunk(chunk.pcm, spoken)?.let { cues += Subtitles.split(chunk.startMs, chunk.endMs, it) } }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        if (cues.isEmpty()) throw IllegalStateException("no speech was recognised")
        return cues
    }

    private fun report(doneMs: Long, durationUs: Long, started: Long, listener: Listener) {
        if (durationUs <= 0) return
        val percent = (doneMs * 1000 * 100 / durationUs).toInt().coerceIn(0, 99)
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val eta = if (percent in 2..99 && elapsed > 10) elapsed * (100 - percent) / percent else null
        main.post { listener.onProgress(percent, eta) }
    }

    /** One piece of speech → text, via the on-device recognizer reading from a pipe. Null if nothing heard. */
    private fun recogniseChunk(pcm: ShortArray, spoken: String): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        var text: String? = null
        var recognizer: SpeechRecognizer? = null
        main.post {
            runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                        done.countDown()
                    }
                    override fun onError(error: Int) { done.countDown() }
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                r.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, spoken)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE),
                )
            }.onFailure { done.countDown() }
        }
        // Feed the piece (plus a little silence so the recognizer knows it ended), then close.
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                val bytes = java.nio.ByteBuffer.allocate((pcm.size + TARGET_RATE / 2) * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                pcm.forEach { bytes.putShort(it) }
                out.write(bytes.array())
            }
        }
        done.await(60, TimeUnit.SECONDS)
        main.post { runCatching { recognizer?.destroy() } }
        runCatching { pipe[0].close() }
        return text
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

        /** Average all channels into one (5.1 → mono keeps the dialogue from the centre channel). */
        internal fun downmix(samples: ShortArray, channels: Int): ShortArray {
            if (channels <= 1) return samples
            val frames = samples.size / channels
            return ShortArray(frames) { f ->
                var sum = 0
                for (c in 0 until channels) sum += samples[f * channels + c]
                (sum / channels).toShort()
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

/**
 * Cuts continuous 16 kHz audio into pieces of speech at pauses (energy-based voice activity), so
 * each subtitle gets the right time. Pieces are 1.5–12 s; pure silence is dropped.
 */
internal class SpeechChunker(private val rate: Int) {
    class Chunk(val startMs: Long, val endMs: Long, val pcm: ShortArray)

    private val frame = rate * 30 / 1000 // 30 ms
    private var pending = ShortArray(0)
    private var current = ShortArray(rate * 13)
    private var currentLen = 0
    private var currentStart = 0L
    private var consumed = 0L // samples seen
    private var silentFrames = 0
    private var voicedFrames = 0
    private var noiseFloor = 300.0

    fun add(samples: ShortArray): List<Chunk> {
        val all = if (pending.isEmpty()) samples else pending + samples
        val out = mutableListOf<Chunk>()
        var at = 0
        while (all.size - at >= frame) {
            var sum = 0.0
            for (k in at until at + frame) sum += all[k] * all[k].toDouble()
            val rms = sqrt(sum / frame)
            val voiced = rms > noiseFloor * 2.5
            if (!voiced) noiseFloor = noiseFloor * 0.995 + rms * 0.005
            if (currentLen == 0 && !voiced) { consumed += frame; at += frame; continue }
            if (currentLen == 0) currentStart = consumed * 1000 / rate
            if (currentLen + frame > current.size) current = current.copyOf(current.size * 2)
            System.arraycopy(all, at, current, currentLen, frame)
            currentLen += frame
            consumed += frame
            at += frame
            if (voiced) { voicedFrames++; silentFrames = 0 } else silentFrames++
            val lengthMs = currentLen * 1000L / rate
            if ((silentFrames >= 13 && lengthMs >= 1_500) || lengthMs >= 12_000) emit()?.let { out += it }
        }
        pending = all.copyOfRange(at, all.size)
        return out
    }

    fun flush(): Chunk? = emit()

    private fun emit(): Chunk? {
        if (currentLen == 0) return null
        val chunk = if (voicedFrames >= 10) Chunk(currentStart, currentStart + currentLen * 1000L / rate, current.copyOf(currentLen)) else null
        currentLen = 0; silentFrames = 0; voicedFrames = 0
        return chunk
    }
}
