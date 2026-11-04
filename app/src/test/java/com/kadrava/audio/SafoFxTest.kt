package com.kadrava.audio

import com.kadrava.audio.dsp.DelayLine
import com.kadrava.audio.dsp.MagicEffect
import com.kadrava.audio.dsp.MagicFx
import com.kadrava.audio.dsp.OnePole
import com.kadrava.audio.dsp.Reverb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SafoFxTest {
    private val sr = 22050

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }

    @Test fun onePoleAttenuatesAboveTheCutoff() {
        val f = OnePole.forCutoff(500.0, 44100)
        val x = FloatArray(44100) { sin(2 * PI * 5000.0 * it / 44100).toFloat() }
        val y = FloatArray(x.size) { f.process(x[it]) }
        val db = 20 * log10(rms(y, 4000, y.size) / rms(x, 4000, x.size))
        assertTrue("gain $db dB", db in -23.0..-17.0)
        val g = OnePole.forCutoff(500.0, 44100)
        val low = FloatArray(44100) { g.process(sin(2 * PI * 50.0 * it / 44100).toFloat()) }
        assertEquals(0.0, 20 * log10(rms(low, 4000, low.size) / (1 / sqrt(2.0))), 0.5)
    }

    @Test fun delayLineReturnsTheSampleFromNSamplesAgo() {
        val d = DelayLine(100)
        for (i in 0 until 50) { d.write(i.toFloat()); if (i >= 10) assertEquals((i - 10).toFloat(), d.read(10f), 1e-6f) }
        d.write(77f)
        assertEquals(77f, d.read(0f), 0f)
        assertEquals(0.5f * (77f + 49f), d.read(0.5f), 1e-5f)
    }

    @Test fun reverbTailDecaysAndFreezeHoldsIt() {
        val rv = Reverb(sr)
        val noise = java.util.Random(1)
        val out = FloatArray(sr * 8)
        for (i in out.indices) {
            val x = if (i < sr / 2) (noise.nextFloat() - 0.5f) * 0.5f else 0f
            rv.process(x, x, 0.5f, false); out[i] = rv.outL
        }
        assertTrue(out.all { it.isFinite() })
        val early = rms(out, 0, sr); val late = rms(out, sr * 3, sr * 4)
        assertTrue("tail should decay: $early -> $late", late < early * 0.01)

        val fz = Reverb(sr)
        val held = FloatArray(sr * 7)
        for (i in held.indices) {
            val x = if (i < sr) (noise.nextFloat() - 0.5f) * 0.5f else 0f
            fz.process(x, x, 0.5f, i >= sr); held[i] = fz.outL
        }
        val first = rms(held, sr, sr * 2); val last = rms(held, sr * 6, sr * 7)
        assertTrue("freeze should sustain: $first -> $last", last > first * 0.6)
        assertTrue(held.all { it.isFinite() && abs(it) < 5f })
    }

    @Test fun magicEffectsBypassAtZeroAndStayFinite() {
        for (fx in MagicEffect.entries) {
            val off = MagicFx(fx, 0f, sr, 1)
            val on = MagicFx(fx, 1f, sr, 1)
            var diff = false
            for (i in 0 until sr) {
                val x = sin(2 * PI * 300.0 * i / sr).toFloat() * 0.5f
                off.process(x, -x); on.process(x, -x)
                assertEquals("$fx bypass L", x, off.outL, 0f)
                assertEquals("$fx bypass R", -x, off.outR, 0f)
                assertTrue(on.outL.isFinite() && on.outR.isFinite())
                if (abs(on.outL - x) > 1e-4f || abs(on.outR + x) > 1e-4f) diff = true
            }
            if (fx != MagicEffect.OFF) assertTrue("$fx should change the signal", diff)
        }
    }

    @Test fun haasDelaysOnlyTheRightChannelByTwentyMilliseconds() {
        val fx = MagicFx(MagicEffect.HAAS, 1f, sr, 1)
        val d = Math.round(0.02f * sr)
        var rPos = -1
        for (i in 0 until 2000) {
            val x = if (i == 100) 1f else 0f
            fx.process(x, x)
            if (i == 100) assertEquals(1f, fx.outL, 0f)
            if (fx.outR > 0.5f) rPos = i
        }
        assertEquals(100 + d, rPos)
    }

    @Test fun tremoloLowersTheAverageLevel() {
        val fx = MagicFx(MagicEffect.TREMOLO, 1f, sr, 1)
        val y = FloatArray(sr * 2)
        val x = FloatArray(sr * 2) { sin(2 * PI * 440.0 * it / sr).toFloat() * 0.5f }
        for (i in y.indices) { fx.process(x[i], x[i]); y[i] = fx.outL }
        assertTrue(rms(y, 0, y.size) < rms(x, 0, x.size) * 0.85)
    }
}
