package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.DistShape
import com.kadrava.audio.dsp.Fft
import com.kadrava.audio.dsp.HeisenbergEngine
import com.kadrava.audio.dsp.HeisenbergParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.ProcessingCancelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class HeisenbergTest {
    private val sr = 22050

    private fun run(data: FloatArray, ch: Int, p: HeisenbergParams, rate: Int = sr): FloatArray {
        val src = ArrayPcmSource(rate, ch, data)
        val total = HeisenbergEngine(p).outputFrames(src.frames, rate).toInt()
        val sink = MemorySink(2, total)
        assertEquals(total.toLong(), HeisenbergEngine(p).run(src, sink))
        assertEquals(total, sink.framesWritten)
        return sink.data
    }

    private fun left(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2] }
    private fun right(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2 + 1] }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }

    private fun peakHz(a: FloatArray, from: Int, rate: Int = sr): Double {
        val n = 8192
        val re = DoubleArray(n) { a[from + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        var best = 1
        for (i in 1 until n / 2) if (re[i] * re[i] + im[i] * im[i] > re[best] * re[best] + im[best] * im[best]) best = i
        return best * rate.toDouble() / n
    }

    private fun impulse(n: Int) = FloatArray(n).also { it[0] = 1f }

    @Test fun rejectsBadParams() {
        val bad = listOf(
            HeisenbergParams(delayMs = 0.5), HeisenbergParams(delayMs = 3000.0), HeisenbergParams(feedback = 0.99),
            HeisenbergParams(delayMix = 1.5), HeisenbergParams(fbLowHz = 100.0), HeisenbergParams(fbHighHz = 5000.0),
            HeisenbergParams(shift = 20.0), HeisenbergParams(tailSeconds = 11.0), HeisenbergParams(drive = Double.NaN),
            HeisenbergParams(distMix = -0.1), HeisenbergParams(levelDb = 20.0), HeisenbergParams(bits = 0),
            HeisenbergParams(bits = 17), HeisenbergParams(rate = 0), HeisenbergParams(rate = 65),
        )
        for (p in bad) try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        HeisenbergParams().validate()
    }

    @Test fun untouchedSettingsAreAnExactBypass() {
        val x = FloatArray(3000) { sin(it * 0.1f) * 0.5f }
        val out = run(x, 1, HeisenbergParams(delayMix = 0.0, shape = DistShape.OFF, tailSeconds = 0.0))
        for (i in x.indices) { assertEquals(x[i], out[2 * i], 0f); assertEquals(x[i], out[2 * i + 1], 0f) }
        assertTrue(HeisenbergParams(delayMix = 0.0).isNoOp)
    }

    @Test fun echoesAppearAtTheDelayTimeAndDecayByTheFeedback() {
        val p = HeisenbergParams(delayMs = 100.0, feedback = 0.5, delayMix = 0.5, tailSeconds = 1.0)
        val out = left(run(impulse(sr), 1, p))
        val d = 2205
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(0.5f, out[d], 1e-5f)
        assertEquals(0.25f, out[2 * d], 1e-5f)
        assertEquals(0.125f, out[3 * d], 1e-5f)
        assertEquals(0f, out[d / 2], 1e-6f)
    }

    @Test fun pingPongAlternatesChannels() {
        val d = 2205
        val st = FloatArray(sr * 2).also { it[0] = 1f; it[1] = 1f }
        val out = run(st, 2, HeisenbergParams(delayMs = 100.0, feedback = 0.5, delayMix = 1.0, pingPong = true, tailSeconds = 1.0))
        val l = left(out); val r = right(out)
        assertEquals(1f, l[d], 1e-5f); assertEquals(0f, r[d], 1e-5f)
        assertEquals(0f, l[2 * d], 1e-5f); assertEquals(0.5f, r[2 * d], 1e-5f)
        assertEquals(0.25f, l[3 * d], 1e-5f); assertEquals(0f, r[3 * d], 1e-5f)
    }

    @Test fun outputIncludesTheTailAndTheRepeatsDieOut() {
        val x = FloatArray(sr) { (0.5 * sin(2 * PI * 300 * it / sr)).toFloat() }
        val p = HeisenbergParams(delayMs = 100.0, feedback = 0.5, delayMix = 1.0, tailSeconds = 3.0)
        val out = left(run(x, 1, p))
        assertEquals(sr * 4, out.size)
        assertTrue(rms(out, out.size - sr / 2, out.size) < 1e-6)
        assertTrue(rms(out, sr, sr + sr / 4) > 0.05)   // the tail is not silent right after the input ends
    }

    @Test fun highFeedbackWithEverythingOnStaysBounded() {
        val noise = java.util.Random(4)
        val x = FloatArray(sr * 3) { (noise.nextFloat() - 0.5f) }
        val p = HeisenbergParams(delayMs = 40.0, feedback = 0.95, delayMix = 1.0, pingPong = true, shift = 7.0,
            shape = DistShape.SOFT, drive = 1.0, tailSeconds = 4.0)
        val out = run(x, 1, p)
        assertTrue(out.all { it.isFinite() && abs(it) < 60f })
    }

    @Test fun feedbackLowPassDarkensTheRepeatsButNotTheFirstEcho() {
        val burst = FloatArray(sr) { if (it < sr / 5) (0.5 * sin(2 * PI * 5000 * it / sr)).toFloat() else 0f }
        val base = HeisenbergParams(delayMs = 300.0, feedback = 0.8, delayMix = 1.0, tailSeconds = 2.0)
        val open = left(run(burst, 1, base))
        val dark = left(run(burst, 1, base.copy(fbLowHz = 500.0)))
        val first = sr * 33 / 100 to sr * 47 / 100      // first echo: 0.30 - 0.50 s
        val second = sr * 63 / 100 to sr * 77 / 100     // second echo: 0.60 - 0.80 s
        assertEquals(rms(open, first.first, first.second), rms(dark, first.first, first.second), 1e-4)
        assertTrue(rms(dark, second.first, second.second) < rms(open, second.first, second.second) * 0.25)
    }

    @Test fun feedbackPitchShifterRaisesEachRepeat() {
        val x = FloatArray(sr) { if (it < sr / 2) (0.5 * sin(2 * PI * 440 * it / sr)).toFloat() else 0f }
        val p = HeisenbergParams(delayMs = 600.0, feedback = 0.7, delayMix = 1.0, shift = 12.0, tailSeconds = 3.0)
        val out = left(run(x, 1, p))
        assertEquals(440.0, peakHz(out, sr * 65 / 100), 15.0)    // first echo is untouched
        assertEquals(880.0, peakHz(out, sr * 125 / 100), 45.0)   // second echo was shifted once
    }

    @Test fun shapesStayInRangeAndSoftHasNoEvenHarmonicsAsymHas() {
        val rate = 16384
        val x = FloatArray(rate * 2) { (0.5 * sin(2 * PI * 512 * it / rate)).toFloat() }
        fun mag(a: FloatArray, bin: Int, n: Int = 16384): Double {
            val re = DoubleArray(n) { a[rate / 2 + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
            val im = DoubleArray(n)
            Fft(n).transform(re, im)
            return hypot(re[bin], im[bin])
        }
        val base = HeisenbergParams(delayMix = 0.0, tailSeconds = 0.0, drive = 0.6, distMix = 1.0)
        val ratios = HashMap<DistShape, Double>()
        for (shape in listOf(DistShape.SOFT, DistShape.HARD, DistShape.ASYM, DistShape.FOLD)) {
            val l = left(run(x, 1, base.copy(shape = shape), rate))
            assertTrue("$shape range", l.all { abs(it) <= 1.0001f })
            ratios[shape] = mag(l, 1024) / mag(l, 512)
        }
        assertTrue("soft ${ratios[DistShape.SOFT]}", ratios[DistShape.SOFT]!! < 1e-3)
        assertTrue("asym ${ratios[DistShape.ASYM]}", ratios[DistShape.ASYM]!! > 0.02)
        val asym = left(run(x, 1, base.copy(shape = DistShape.ASYM), rate))
        assertTrue(abs(asym.average()) < 0.02)
    }

    @Test fun bitCrushQuantisesAndSampleHoldRepeatsValues() {
        val x = FloatArray(sr) { (0.7 * sin(2 * PI * 200 * it / sr)).toFloat() }
        val two = left(run(x, 1, HeisenbergParams(delayMix = 0.0, tailSeconds = 0.0, shape = DistShape.CRUSH, drive = 0.2, bits = 2, rate = 1)))
        assertTrue("levels ${two.toSet()}", two.toSet().size <= 5)
        val hold = left(run(x, 1, HeisenbergParams(delayMix = 0.0, tailSeconds = 0.0, shape = DistShape.CRUSH, drive = 0.0, bits = 16, rate = 8)))
        for (i in 1 until 8) assertEquals(hold[0], hold[i], 0f)
    }

    @Test fun orderAndLevelChangeTheSound() {
        val x = FloatArray(sr) { (0.5 * sin(2 * PI * 300 * it / sr)).toFloat() }
        val a = HeisenbergParams(delayMs = 80.0, delayMix = 0.8, shape = DistShape.HARD, drive = 0.8, tailSeconds = 0.5)
        val pre = run(x, 1, a)
        assertFalse(pre.contentEquals(run(x, 1, a.copy(distAfter = true))))
        val quiet = left(run(x, 1, a.copy(levelDb = -12.0)))
        assertEquals(rms(left(pre), 0, quiet.size) * 0.251, rms(quiet, 0, quiet.size), 1e-3)
    }

    @Test fun shortEmptyAndCancelled() {
        assertTrue(run(floatArrayOf(0.5f), 1, HeisenbergParams(tailSeconds = 1.0)).all { it.isFinite() })
        try { HeisenbergEngine(HeisenbergParams()).run(ArrayPcmSource(sr, 1, FloatArray(0)), MemorySink(2, 1)); throw AssertionError() }
        catch (_: InvalidParamException) {}
        var calls = 0
        try {
            HeisenbergEngine(HeisenbergParams()).run(ArrayPcmSource(sr, 1, FloatArray(sr * 2)), MemorySink(2, sr * 4), {}, { ++calls > 2 })
            throw AssertionError()
        } catch (_: ProcessingCancelled) {}
        val seen = ArrayList<Float>()
        HeisenbergEngine(HeisenbergParams(tailSeconds = 0.5)).run(ArrayPcmSource(sr, 1, FloatArray(sr)), MemorySink(2, sr * 2), { seen.add(it) })
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
    }
}
