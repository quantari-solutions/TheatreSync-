package com.theatresync.app

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.core.content.edit

/**
 * Headphone equalizer and bass boost for TheatreSync's movie sound (Android's built-in audio
 * effects, attached to the headphone player only: the TV's sound is never changed).
 */
object HeadphoneFx {
    /** Five bands, as most phones offer: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz. */
    const val BANDS = 5
    val BAND_LABELS = listOf("60", "230", "910", "3.6k", "14k")
    /** Band gain range in dB the sliders offer (phones typically allow ±15). */
    const val MAX_DB = 12

    data class Settings(
        val enabled: Boolean = false,
        /** 0..1000, Android's BassBoost strength. */
        val bass: Int = 0,
        /** dB per band, -MAX_DB..MAX_DB. */
        val bandsDb: List<Int> = List(BANDS) { 0 },
        val preset: Preset = Preset.FLAT,
    )

    enum class Preset(val bass: Int, val bandsDb: List<Int>) {
        FLAT(0, listOf(0, 0, 0, 0, 0)),
        BASS_BOOST(700, listOf(6, 3, 0, 0, 1)),
        DIALOGUE(0, listOf(-2, 0, 4, 3, 0)),
        CINEMA(450, listOf(4, 1, 0, 2, 3)),
        CUSTOM(0, listOf(0, 0, 0, 0, 0)),
    }

    private const val PREFS = "headphone_fx"

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean("enabled", false),
            bass = p.getInt("bass", 0).coerceIn(0, 1000),
            bandsDb = List(BANDS) { p.getInt("band$it", 0).coerceIn(-MAX_DB, MAX_DB) },
            preset = runCatching { Preset.valueOf(p.getString("preset", "FLAT")!!) }.getOrDefault(Preset.FLAT),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", s.enabled)
            putInt("bass", s.bass)
            s.bandsDb.forEachIndexed { i, db -> putInt("band$i", db) }
            putString("preset", s.preset.name)
        }
    }

    fun withPreset(s: Settings, preset: Preset): Settings =
        if (preset == Preset.CUSTOM) s.copy(preset = preset) else s.copy(preset = preset, bass = preset.bass, bandsDb = preset.bandsDb, enabled = true)

    /** Live effects on one audio session; recreate when the player is recreated. */
    class Attached(sessionId: Int) {
        private val eq: Equalizer? = runCatching { Equalizer(0, sessionId) }.getOrNull()
        private val bassBoost: BassBoost? = runCatching { BassBoost(0, sessionId) }.getOrNull()

        val available: Boolean get() = eq != null

        fun apply(s: Settings) {
            eq?.let { e ->
                runCatching {
                    e.enabled = s.enabled
                    val bands = e.numberOfBands.toInt()
                    val range = e.bandLevelRange // millibels
                    for (b in 0 until bands) {
                        // Map the phone's bands onto our five by centre frequency.
                        val hz = e.getCenterFreq(b.toShort()) / 1000
                        val ours = nearestBand(hz)
                        val mb = (s.bandsDb[ours] * 100).coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(b.toShort(), mb.toShort())
                    }
                }
            }
            bassBoost?.let { b ->
                runCatching {
                    b.enabled = s.enabled && s.bass > 0
                    if (b.strengthSupported) b.setStrength(s.bass.toShort())
                }
            }
        }

        fun release() {
            runCatching { eq?.release() }
            runCatching { bassBoost?.release() }
        }
    }

    private val CENTRES_HZ = listOf(60, 230, 910, 3_600, 14_000)

    internal fun nearestBand(hz: Int): Int =
        CENTRES_HZ.indices.minBy { kotlin.math.abs(kotlin.math.ln(hz.coerceAtLeast(1).toDouble()) - kotlin.math.ln(CENTRES_HZ[it].toDouble())) }
}
