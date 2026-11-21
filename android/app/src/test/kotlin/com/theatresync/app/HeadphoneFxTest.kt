package com.theatresync.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class HeadphoneFxTest {
    private val fs = 48_000

    private fun tone(hz: Double, amp: Double, ms: Int, ch: Int = 2): ByteBuffer {
        val frames = fs * ms / 1000
        val b = ByteBuffer.allocateDirect(frames * ch * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) { val v = (amp * sin(2 * PI * hz * i / fs) * 32767).toInt().toShort(); repeat(ch) { b.putShort(v) } }
        b.flip(); return b
    }

    private fun processor(s: HeadphoneFx.Settings) = HeadphoneFx.Processor().apply {
        settings = s
        configure(AudioProcessor.AudioFormat(fs, 2, C.ENCODING_PCM_16BIT)); flush()
    }

    private fun run(p: HeadphoneFx.Processor, input: ByteBuffer): ShortArray {
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    /** RMS in dB of the second half (filters settled). */
    private fun rmsDb(x: ShortArray): Double {
        val h = x.copyOfRange(x.size / 2, x.size)
        return 20 * log10(sqrt(h.sumOf { (it / 32768.0) * (it / 32768.0) } / h.size))
    }

    @Test fun offPassesSoundUntouched() {
        val input = tone(1000.0, 0.3, 50)
        val copy = ShortArray(input.remaining() / 2).also { input.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(it) }
        assertTrue(copy.contentEquals(run(processor(HeadphoneFx.Settings(enabled = false)), input)))
    }

    @Test fun bandBoostIsHeardAndOtherFrequenciesStay() {
        val boost = HeadphoneFx.Settings(enabled = true, bandsDb = List(10) { if (it == 5) 12 else 0 }) // +12 dB at 1 kHz
        val pre = HeadphoneFx.preampDb(boost)
        val atBand = rmsDb(run(processor(boost), tone(1000.0, 0.05, 200))) - rmsDb(run(processor(HeadphoneFx.Settings()), tone(1000.0, 0.05, 200)))
        val farAway = rmsDb(run(processor(boost), tone(60.0, 0.05, 400))) - rmsDb(run(processor(HeadphoneFx.Settings()), tone(60.0, 0.05, 400)))
        assertEquals(12 + pre, atBand, 0.6)
        assertEquals(pre, farAway, 0.6)
    }

    @Test fun changesTakeEffectOnTheVeryNextSound() {
        val p = processor(HeadphoneFx.Settings(enabled = true))
        val before = rmsDb(run(p, tone(1000.0, 0.05, 100)))
        p.settings = HeadphoneFx.Settings(enabled = true, bandsDb = List(10) { if (it == 5) -12 else 0 })
        val after = rmsDb(run(p, tone(1000.0, 0.05, 100))) // the next 100 ms
        assertTrue("before $before after $after", after < before - 9)
    }

    @Test fun loudBoostedSoundNeverDistortsPastFullScale() {
        val max = HeadphoneFx.Settings(enabled = true, bass = 1000, bandsDb = List(10) { 12 })
        val out = run(processor(max), tone(60.0, 0.95, 200))
        assertTrue(out.all { it in -32767..32767 })
        assertTrue(out.maxOf { kotlin.math.abs(it.toInt()) } <= 32767)
    }

    @Test fun nightModeEvensOutLoudAndQuiet() {
        val night = processor(HeadphoneFx.Settings(enabled = true, night = true))
        val loud = rmsDb(run(night, tone(500.0, 0.8, 600)))
        val quiet = rmsDb(run(night, tone(500.0, 0.02, 1500)))
        val plain = 20 * log10(0.8 / 0.02) // 32 dB apart without Night mode
        assertTrue("loud $loud quiet $quiet", loud - quiet < plain - 10)
    }

    @Test fun everyPresetHasTenBandsInRangeAndAName() {
        HeadphoneFx.Preset.entries.forEach { p ->
            assertEquals(p.name, 10, p.bandsDb.size)
            assertTrue(p.name, p.bandsDb.all { it in -HeadphoneFx.MAX_DB..HeadphoneFx.MAX_DB })
        }
        assertTrue(HeadphoneFx.Preset.entries.size >= 20)
    }

    @Test fun peakingFilterHitsItsTargetAtTheCentre() {
        val f = HeadphoneFx.Biquad.peaking(1000.0, 6.0, 1.4, fs.toDouble())
        assertEquals(6.0, f.responseDb(1000.0, fs.toDouble()), 0.05)
        assertEquals(0.0, f.responseDb(50.0, fs.toDouble()), 0.3)
    }
}
