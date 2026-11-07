package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.WagnerEngine
import com.kadrava.audio.dsp.WagnerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class WagnerTest {
    private val sr = 22050

    private fun run(data: FloatArray, ch: Int, p: WagnerParams): FloatArray {
        val src = ArrayPcmSource(sr, ch, data)
        val total = WagnerEngine(p).outputFrames(src.frames, sr).toInt()
        val sink = MemorySink(2, total)
        assertEquals(total.toLong(), WagnerEngine(p).run(src, sink))
        assertEquals(total, sink.framesWritten)
        return sink.data
    }

    private fun left(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2] }
    private fun right(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2 + 1] }
    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }
    private fun impulse() = FloatArray(1).also { it[0] = 1f }

    /** Reverberation time from the Schroeder integral (-5 dB to -25 dB, extrapolated to -60 dB). */
    private fun measuredRt60(y: FloatArray): Double {
        val e = DoubleArray(y.size)
        var acc = 0.0
        for (i in y.indices.reversed()) { acc += y[i].toDouble() * y[i]; e[i] = acc }
        fun db(i: Int) = 10 * log10(e[i] / e[0] + 1e-30)
        val i1 = y.indices.first { db(it) < -5 }
        val i2 = y.indices.first { db(it) < -25 }
        val slope = (db(i2) - db(i1)) / ((i2 - i1).toDouble() / sr)
        return -60.0 / slope
    }

    @Test fun rejectsBadParams() {
        val bad = listOf(
            WagnerParams(rt60 = 0.05), WagnerParams(rt60 = 40.0), WagnerParams(size = 0.1), WagnerParams(size = 4.0),
            WagnerParams(damp = 1.0), WagnerParams(preDelayMs = 300.0), WagnerParams(diffusion = 1.5),
            WagnerParams(mod = -0.1), WagnerParams(width = 2.0), WagnerParams(mix = Double.NaN), WagnerParams(tailSeconds = 31.0),
        )
        for (p in bad) try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        WagnerParams().validate()
    }

    @Test fun mixZeroIsAnExactBypass() {
        val x = FloatArray(2000) { kotlin.math.sin(it * 0.07f) * 0.5f }
        val out = run(x, 1, WagnerParams(mix = 0.0, tailSeconds = 0.0))
        for (i in x.indices) { assertEquals(x[i], out[2 * i], 0f); assertEquals(x[i], out[2 * i + 1], 0f) }
        assertTrue(WagnerParams(mix = 0.0).isNoOp)
    }

    @Test fun decayTimeMatchesRt60() {
        for (rt in doubleArrayOf(1.0, 2.5)) {
            val p = WagnerParams(rt60 = rt, damp = 0.0, mod = 0.0, mix = 1.0, preDelayMs = 0.0, tailSeconds = rt * 3 + 2)
            val measured = measuredRt60(left(run(impulse(), 1, p)))
            assertEquals("rt60 $rt measured $measured", rt, measured, rt * 0.2)
        }
    }

    @Test fun preDelayKeepsTheWetSignalSilentUntilItElapses() {
        val p = WagnerParams(mix = 1.0, preDelayMs = 50.0, tailSeconds = 1.0)
        val out = left(run(impulse(), 1, p))
        val pre = (0.05 * sr).toInt()
        for (i in 0 until pre) assertTrue("sample $i", abs(out[i]) < 1e-9f)
        assertTrue(out.drop(pre).any { abs(it) > 1e-4f })
    }

    @Test fun sizeMovesTheFirstArrivalLater() {
        fun first(size: Double): Int {
            val out = left(run(impulse(), 1, WagnerParams(size = size, mix = 1.0, preDelayMs = 0.0, mod = 0.0, tailSeconds = 1.0)))
            return out.indexOfFirst { abs(it) > 1e-6f }
        }
        assertTrue(first(3.0) > 5 * first(0.3))
    }

    @Test fun veryLongDecayWithEverythingOnStaysBounded() {
        val noise = java.util.Random(2)
        val x = FloatArray(sr * 5) { (noise.nextFloat() - 0.5f) * 0.5f }
        val out = run(x, 1, WagnerParams(rt60 = 30.0, size = 3.0, damp = 0.0, mod = 1.0, mix = 1.0, tailSeconds = 5.0))
        assertTrue(out.all { it.isFinite() && abs(it) < 20f })
    }

    @Test fun widthControlsTheStereoImage() {
        val noise = java.util.Random(3)
        val x = FloatArray(sr * 2) { (noise.nextFloat() - 0.5f) * 0.5f }
        val mono = run(x, 1, WagnerParams(mix = 1.0, width = 0.0, tailSeconds = 1.0))
        for (i in 0 until mono.size step 2) assertEquals(mono[i], mono[i + 1], 1e-6f)
        val wide = run(x, 1, WagnerParams(mix = 1.0, width = 1.0, tailSeconds = 1.0))
        var diff = 0.0
        for (i in 0 until wide.size step 2) diff += abs(wide[i] - wide[i + 1])
        assertTrue(diff > 1.0)
    }

    @Test fun dampingDarkensTheTail() {
        val noise = java.util.Random(5)
        val x = FloatArray(sr) { (noise.nextFloat() - 0.5f) * 0.5f }
        val base = WagnerParams(mix = 1.0, rt60 = 2.0, mod = 0.0, tailSeconds = 3.0)
        val open = left(run(x, 1, base.copy(damp = 0.0)))
        val dark = left(run(x, 1, base.copy(damp = 0.9)))
        assertTrue(rms(dark, sr * 3 / 2, sr * 5 / 2) < rms(open, sr * 3 / 2, sr * 5 / 2))
    }

    @Test fun tailIsAddedAndDiesOut() {
        val x = FloatArray(sr) { if (it < 100) 0.5f else 0f }
        val out = left(run(x, 1, WagnerParams(rt60 = 0.5, mix = 1.0, tailSeconds = 4.0)))
        assertEquals(sr * 5, out.size)
        assertTrue(rms(out, out.size - sr / 2, out.size) < 1e-4)
    }

    @Test fun shortEmptyAndCancelled() {
        assertTrue(run(floatArrayOf(0.5f), 1, WagnerParams(tailSeconds = 1.0)).all { it.isFinite() })
        try { WagnerEngine(WagnerParams()).run(ArrayPcmSource(sr, 1, FloatArray(0)), MemorySink(2, 1)); throw AssertionError() }
        catch (_: InvalidParamException) {}
        var calls = 0
        try {
            WagnerEngine(WagnerParams()).run(ArrayPcmSource(sr, 1, FloatArray(sr * 2)), MemorySink(2, sr * 6), {}, { ++calls > 2 })
            throw AssertionError()
        } catch (_: ProcessingCancelled) {}
        val seen = ArrayList<Float>()
        WagnerEngine(WagnerParams(tailSeconds = 0.5)).run(ArrayPcmSource(sr, 1, FloatArray(sr)), MemorySink(2, sr * 2), { seen.add(it) })
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
    }
}
