package com.theatresync.app

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Headphone equalizer for TheatreSync's movie sound. It runs inside TheatreSync's own player (not
 * Android's shared effects, which some phones override), so every change is heard the moment it's
 * made, on any phone. Only the headphone sound changes, never the TV.
 *
 * 10 bands, a bass shelf, 20 presets for different kinds of sound, and Night mode (evens out loud
 * and quiet scenes). A safety stage keeps boosted sound from distorting.
 */
object HeadphoneFx {
    val CENTRES_HZ = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val BANDS = CENTRES_HZ.size
    const val MAX_DB = 12

    data class Settings(
        val enabled: Boolean = false,
        /** 0..1000: strength of the low-shelf bass boost (up to +10 dB below ~90 Hz). */
        val bass: Int = 0,
        /** dB per band, -MAX_DB..MAX_DB. */
        val bandsDb: List<Int> = List(BANDS) { 0 },
        val preset: Preset = Preset.FLAT,
        /** Night mode: quieter loud bits, clearer quiet bits (dynamic range compression). */
        val night: Boolean = false,
    )

    /** Presets for different kinds of sound. Bands: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz. */
    enum class Preset(val label: Int, val group: Group, val bass: Int, val bandsDb: List<Int>, val night: Boolean = false) {
        FLAT(R.string.eq_flat, Group.MOVIES, 0, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        CINEMA(R.string.eq_cinema, Group.MOVIES, 400, listOf(4, 4, 2, 0, 0, 1, 2, 2, 3, 3)),
        ACTION(R.string.eq_action, Group.MOVIES, 600, listOf(6, 5, 3, 0, -1, 1, 2, 3, 3, 2)),
        DIALOGUE(R.string.eq_dialogue, Group.MOVIES, 0, listOf(-4, -3, -2, 0, 2, 4, 5, 4, 1, 0)),
        NIGHT(R.string.eq_night, Group.MOVIES, 0, listOf(-2, -1, 0, 0, 1, 3, 3, 2, 0, 0), night = true),
        HORROR(R.string.eq_horror, Group.MOVIES, 500, listOf(5, 5, 3, 0, -2, 0, 1, 3, 4, 4)),
        ANIMATION(R.string.eq_animation, Group.MOVIES, 200, listOf(2, 2, 1, 0, 1, 2, 3, 3, 3, 2)),
        BASS_BOOST(R.string.eq_bass, Group.SOUND, 700, listOf(6, 6, 4, 2, 0, 0, 0, 0, 1, 1)),
        DEEP_BASS(R.string.eq_deep_bass, Group.SOUND, 1000, listOf(9, 8, 5, 1, -1, 0, 0, 0, 0, 0)),
        TREBLE(R.string.eq_treble, Group.SOUND, 0, listOf(0, 0, 0, 0, 0, 1, 3, 5, 6, 6)),
        LOUDNESS(R.string.eq_loudness, Group.SOUND, 400, listOf(5, 4, 1, 0, -1, 0, 0, 1, 3, 4)),
        WARM(R.string.eq_warm, Group.SOUND, 300, listOf(3, 3, 2, 1, 0, 0, -1, -2, -2, -3)),
        BRIGHT(R.string.eq_bright, Group.SOUND, 0, listOf(-2, -1, 0, 0, 0, 1, 2, 4, 5, 5)),
        ROCK(R.string.eq_rock, Group.MUSIC, 300, listOf(5, 4, 2, -1, -2, -1, 2, 3, 4, 4)),
        POP(R.string.eq_pop, Group.MUSIC, 200, listOf(-1, 1, 3, 4, 3, 0, -1, -1, 1, 2)),
        HIP_HOP(R.string.eq_hiphop, Group.MUSIC, 600, listOf(6, 5, 2, 3, -1, -1, 1, 0, 2, 3)),
        ELECTRONIC(R.string.eq_electronic, Group.MUSIC, 500, listOf(6, 5, 1, 0, -2, 1, 0, 2, 4, 5)),
        JAZZ(R.string.eq_jazz, Group.MUSIC, 0, listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3)),
        CLASSICAL(R.string.eq_classical, Group.MUSIC, 0, listOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4)),
        ACOUSTIC(R.string.eq_acoustic, Group.MUSIC, 0, listOf(3, 3, 2, 1, 1, 1, 2, 2, 2, 1)),
        VOCAL(R.string.eq_vocal, Group.MUSIC, 0, listOf(-2, -1, 0, 1, 3, 4, 4, 3, 1, 0)),
        PODCAST(R.string.eq_podcast, Group.MUSIC, 0, listOf(-6, -5, -3, 0, 2, 4, 4, 3, 0, -2)),
        GAMING(R.string.eq_gaming, Group.SOUND, 300, listOf(4, 3, 1, 0, 0, 2, 3, 4, 3, 2)),
        CUSTOM(R.string.eq_custom, Group.SOUND, 0, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    }

    enum class Group(val label: Int) { MOVIES(R.string.eq_group_movies), SOUND(R.string.eq_group_sound), MUSIC(R.string.eq_group_music) }

    private const val PREFS = "headphone_fx"

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 0.5.7 and earlier kept 5 bands: start those from their preset on the new 10.
        val preset = runCatching { Preset.valueOf(p.getString("preset", "FLAT")!!) }.getOrDefault(Preset.FLAT)
        val tenBands = p.getInt("bands", 5) == BANDS
        return Settings(
            enabled = p.getBoolean("enabled", false),
            bass = p.getInt("bass", 0).coerceIn(0, 1000),
            bandsDb = if (tenBands) List(BANDS) { p.getInt("band$it", 0).coerceIn(-MAX_DB, MAX_DB) } else preset.bandsDb,
            preset = preset,
            night = p.getBoolean("night", preset.night),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", s.enabled)
            putInt("bass", s.bass)
            putInt("bands", BANDS)
            s.bandsDb.forEachIndexed { i, db -> putInt("band$i", db) }
            putString("preset", s.preset.name)
            putBoolean("night", s.night)
        }
    }

    fun withPreset(s: Settings, preset: Preset): Settings =
        if (preset == Preset.CUSTOM) s.copy(preset = preset, enabled = true)
        else s.copy(preset = preset, bass = preset.bass, bandsDb = preset.bandsDb, night = preset.night, enabled = true)

    // ------------------------------------------------------------------------------- the sound

    /** One second-order filter (RBJ "audio EQ cookbook"), normalised so a0 = 1. */
    internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        /** Gain in dB at [hz] for sample rate [fs]. */
        fun responseDb(hz: Double, fs: Double): Double {
            val w = 2 * PI * hz / fs
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }

        companion object {
            fun peaking(hz: Double, db: Double, q: Double, fs: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val alpha = sin(w) / (2 * q); val c = cos(w)
                val a0 = 1 + alpha / a
                return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
            }

            fun lowShelf(hz: Double, db: Double, fs: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w); val s = sin(w)
                val alpha = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
                val a0 = (a + 1) + (a - 1) * c + sa
                return Biquad(
                    a * ((a + 1) - (a - 1) * c + sa) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0, a * ((a + 1) - (a - 1) * c - sa) / a0,
                    -2 * ((a - 1) + (a + 1) * c) / a0, ((a + 1) + (a - 1) * c - sa) / a0,
                )
            }
        }
    }

    /** The filters for [s] at sample rate [fs] (bands at 0 dB are left out: they change nothing). */
    internal fun design(s: Settings, fs: Double): List<Biquad> {
        val out = mutableListOf<Biquad>()
        if (s.bass > 0) out += Biquad.lowShelf(90.0, s.bass / 1000.0 * 10, fs)
        CENTRES_HZ.forEachIndexed { i, hz -> val db = s.bandsDb.getOrElse(i) { 0 }; if (db != 0 && hz < fs * 0.45) out += Biquad.peaking(hz.toDouble(), db.toDouble(), 1.4, fs) }
        return out
    }

    /** Headroom so boosts don't distort: turn everything down by part of the biggest boost. */
    internal fun preampDb(s: Settings): Double {
        val most = (s.bandsDb.maxOrNull() ?: 0).coerceAtLeast(0) + s.bass / 1000.0 * 10 * 0.6
        return -most * 0.6
    }

    /**
     * The equalizer as a step in the player's own sound chain: changes to [settings] take effect on
     * the next few milliseconds of sound. Works on 16-bit or float sound, any number of channels.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    class Processor : BaseAudioProcessor() {
        @Volatile var settings: Settings = Settings()

        private var applied: Settings? = null
        private var filters: List<Biquad> = emptyList()
        private var state = DoubleArray(0) // per channel per filter: x1, x2, y1, y2
        private var gain = 1.0
        private var env = 0.0 // Night mode level follower

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            applied = null
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            if (size == 0) return
            val out = replaceOutputBuffer(size)
            val s = settings
            if (!s.enabled) { out.put(inputBuffer); out.flip(); applied = null; return }
            val fmt = inputAudioFormat
            val ch = fmt.channelCount
            if (s != applied) {
                val newFilters = design(s, fmt.sampleRate.toDouble())
                if (newFilters.size != filters.size || state.size != newFilters.size * ch * 4) state = DoubleArray(newFilters.size * ch * 4)
                filters = newFilters
                gain = 10.0.pow(preampDb(s) / 20)
                applied = s
            }
            val float = fmt.encoding == C.ENCODING_PCM_FLOAT
            val inB = inputBuffer.order(ByteOrder.nativeOrder())
            val frames = size / (ch * if (float) 4 else 2)
            val nf = filters.size
            val attack = exp(-1.0 / (0.005 * fmt.sampleRate)); val release = exp(-1.0 / (0.25 * fmt.sampleRate))
            val frame = DoubleArray(ch)
            for (f in 0 until frames) {
                var peak = 0.0
                for (c in 0 until ch) {
                    var x = if (float) inB.getFloat().toDouble() else inB.getShort() / 32768.0
                    for (k in 0 until nf) {
                        val q = filters[k]; val o = (c * nf + k) * 4
                        val y = q.b0 * x + q.b1 * state[o] + q.b2 * state[o + 1] - q.a1 * state[o + 2] - q.a2 * state[o + 3]
                        state[o + 1] = state[o]; state[o] = x; state[o + 3] = state[o + 2]; state[o + 2] = y
                        x = y
                    }
                    x *= gain
                    frame[c] = x
                    peak = max(peak, abs(x))
                }
                var g = 1.0
                if (s.night) {
                    env = if (peak > env) attack * env + (1 - attack) * peak else release * env + (1 - release) * peak
                    val levelDb = 20 * log10(env + 1e-9)
                    val over = levelDb + 26 // threshold -26 dBFS, 3:1, +8 dB make-up
                    g = 10.0.pow(((if (over > 0) -over * (2.0 / 3.0) else 0.0) + 8) / 20)
                }
                for (c in 0 until ch) {
                    val v = softClip(frame[c] * g)
                    if (float) out.putFloat(v.toFloat()) else out.putShort((v * 32767).toInt().toShort())
                }
            }
            out.flip()
        }

        override fun onFlush() { state.fill(0.0); env = 0.0 }
        override fun onReset() { applied = null; filters = emptyList(); state = DoubleArray(0) }
    }

    /** Leaves normal levels alone and rounds off peaks above 0.8 instead of hard clipping. */
    internal fun softClip(x: Double): Double {
        val a = abs(x)
        if (a <= 0.8) return x
        return Math.signum(x) * (0.8 + 0.2 * tanh((a - 0.8) / 0.2))
    }
}
