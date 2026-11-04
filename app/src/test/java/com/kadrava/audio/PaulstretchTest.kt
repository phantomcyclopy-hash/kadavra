package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.Fft
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.PaulstretchEngine
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.ProcessingCancelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PaulstretchTest {
    private val sr = 44100

    private fun sine(seconds: Int, hz: Double = 440.0, amp: Double = 0.5) =
        FloatArray(sr * seconds) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    private fun run(data: FloatArray, ch: Int, params: PaulstretchParams): MemorySink {
        val src = ArrayPcmSource(sr, ch, data)
        val sink = MemorySink(ch, params.outputFrames(src.frames).toInt())
        val written = PaulstretchEngine(params).run(src, sink)
        assertEquals(params.outputFrames(src.frames), written)
        return sink
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }

    // ---- validation
    @Test fun rejectsBadParams() {
        for (p in listOf(
            PaulstretchParams(stretch = 0.5), PaulstretchParams(stretch = Double.NaN),
            PaulstretchParams(stretch = 101.0), PaulstretchParams(windowSeconds = 0.01),
            PaulstretchParams(windowSeconds = 2.0), PaulstretchParams(windowSeconds = Double.POSITIVE_INFINITY),
        )) {
            try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        }
        PaulstretchParams().validate()
    }

    @Test fun fftSizeIsPowerOfTwoCoveringWindow() {
        assertEquals(16384, PaulstretchParams(windowSeconds = 0.25).fftSize(44100))
        assertEquals(4096, PaulstretchParams(windowSeconds = 0.05).fftSize(44100))
        assertEquals(131072, PaulstretchParams(windowSeconds = 1.0).fftSize(192000))
    }

    // ---- planner limits
    @Test fun plannerRejectsTooLongOutput() {
        try { PaulstretchPlanner.plan(PaulstretchParams(stretch = 100.0), sr * 600L, 2, sr, Long.MAX_VALUE); throw AssertionError() }
        catch (_: LimitException) {}
    }

    @Test fun plannerRejectsInsufficientDiskAndBadFormats() {
        try { PaulstretchPlanner.plan(PaulstretchParams(), sr * 60L, 2, sr, 1_000_000L); throw AssertionError() } catch (_: LimitException) {}
        try { PaulstretchPlanner.plan(PaulstretchParams(), sr * 6L, 6, sr, Long.MAX_VALUE); throw AssertionError() } catch (_: LimitException) {}
        try { PaulstretchPlanner.plan(PaulstretchParams(), 0L, 2, sr, Long.MAX_VALUE); throw AssertionError() } catch (_: InvalidParamException) {}
    }

    @Test fun plannerAcceptsReasonableJob() {
        val p = PaulstretchPlanner.plan(PaulstretchParams(stretch = 8.0), sr * 60L, 2, sr, 10_000_000_000L)
        assertEquals(sr * 60L * 8, p.outputFrames)
        assertEquals(16384, p.fftSize)
    }

    // ---- DSP behaviour
    @Test fun outputLengthMatchesStretchAndSamplesAreFinite() {
        val sink = run(sine(2), 1, PaulstretchParams(stretch = 3.0))
        assertEquals(sr * 2 * 3, sink.framesWritten)
        assertTrue(sink.data.all { it.isFinite() })
    }

    @Test fun stereoLengthAndFinite() {
        val mono = sine(1)
        val st = FloatArray(mono.size * 2) { mono[it / 2] }
        val sink = run(st, 2, PaulstretchParams(stretch = 2.0))
        assertEquals(sr * 2, sink.framesWritten)
        assertTrue(sink.data.all { it.isFinite() })
    }

    @Test fun stretchedSineKeepsPitchAndLevel() {
        val input = sine(3)
        val sink = run(input, 1, PaulstretchParams(stretch = 4.0))
        // pitch: dominant FFT bin of a 32768-sample segment in the middle
        val n = 32768
        val re = DoubleArray(n) { sink.data[5 * sr + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        var best = 1
        for (i in 1 until n / 2) if (re[i] * re[i] + im[i] * im[i] > re[best] * re[best] + im[best] * im[best]) best = i
        val hz = best * sr.toDouble() / n
        assertTrue("dominant frequency $hz Hz", abs(hz - 440.0) < 6.0)
        // level: RMS of the steady middle part within ~1.5 dB of the input RMS
        val ratio = rms(sink.data, sr * 3, sr * 9) / rms(input, 0, input.size)
        assertTrue("rms ratio $ratio", ratio in 0.85..1.18)
    }

    @Test fun silenceStaysSilent() {
        val sink = run(FloatArray(sr), 1, PaulstretchParams(stretch = 2.0))
        assertTrue(sink.data.all { it == 0f })
    }

    @Test fun deterministicForSameSeedAndDifferentForOtherSeed() {
        val x = sine(1)
        val a = run(x, 1, PaulstretchParams(stretch = 2.0, seed = 7)).data
        val b = run(x, 1, PaulstretchParams(stretch = 2.0, seed = 7)).data
        val c = run(x, 1, PaulstretchParams(stretch = 2.0, seed = 8)).data
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test fun extremelyShortInputStillProducesTargetLength() {
        val sink = run(floatArrayOf(0.5f), 1, PaulstretchParams(stretch = 4.0))
        assertEquals(4, sink.framesWritten)
        assertTrue(sink.data.all { it.isFinite() })
    }

    @Test(expected = InvalidParamException::class) fun emptyInputIsRejected() {
        PaulstretchEngine(PaulstretchParams()).run(ArrayPcmSource(sr, 1, FloatArray(0)), MemorySink(1, 1))
    }

    @Test fun progressIsMonotonicAndCompletes() {
        val src = ArrayPcmSource(sr, 1, sine(1))
        val p = PaulstretchParams(stretch = 4.0)
        val sink = MemorySink(1, p.outputFrames(src.frames).toInt())
        val seen = ArrayList<Float>()
        PaulstretchEngine(p).run(src, sink, { seen.add(it) })
        assertTrue(seen.size > 2)
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
    }

    @Test fun cancellationStopsPromptly() {
        val src = ArrayPcmSource(sr, 1, sine(2))
        val p = PaulstretchParams(stretch = 10.0)
        val sink = MemorySink(1, p.outputFrames(src.frames).toInt())
        var calls = 0
        try {
            PaulstretchEngine(p).run(src, sink, {}, { ++calls > 2 })
            throw AssertionError("expected cancellation")
        } catch (_: ProcessingCancelled) {}
        assertEquals(3, calls)
    }
}
