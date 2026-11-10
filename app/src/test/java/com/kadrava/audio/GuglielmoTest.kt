package com.kadrava.audio

import com.kadrava.audio.dsp.Fft
import com.kadrava.audio.dsp.GuglielmoEngine
import com.kadrava.audio.dsp.GuglielmoParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.ProcessingCancelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

class GuglielmoTest {
    private val sr = GuglielmoEngine.SAMPLE_RATE

    private fun gen(p: GuglielmoParams): FloatArray {
        val total = p.outputFrames().toInt()
        val sink = MemorySink(2, total)
        assertEquals(total.toLong(), GuglielmoEngine(p).generate(sink))
        assertEquals(total, sink.framesWritten)
        return sink.data
    }

    private fun left(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2] }
    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }

    private fun spectrum(a: FloatArray, from: Int, n: Int = 16384): DoubleArray {
        val re = DoubleArray(n) { a[from + it] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        return DoubleArray(n / 2) { kotlin.math.hypot(re[it], im[it]) }
    }

    private fun peakIn(sp: DoubleArray, lo: Double, hi: Double, n: Int = 16384): Double {
        val a = (lo * n / sr).toInt(); val b = (hi * n / sr).toInt()
        var best = a
        for (i in a..b) if (sp[i] > sp[best]) best = i
        return best * sr.toDouble() / n
    }

    private fun tone(root: Double, modes: Int = 1, stretch: Double = 0.0, secs: Double = 8.0) = GuglielmoParams(
        durationSeconds = secs, rootHz = root, modes = modes, stretch = stretch, q = 500.0, noiseLevel = 1.0,
        wallCount = 0, chaos = 0.0, feedback = 0.0, width = 0.0, fadeSeconds = 0.0,
    )

    @Test fun rejectsBadParams() {
        val bad = listOf(
            GuglielmoParams(durationSeconds = 0.5), GuglielmoParams(durationSeconds = 700.0), GuglielmoParams(rootHz = 10.0),
            GuglielmoParams(rootHz = 3000.0), GuglielmoParams(modes = 0), GuglielmoParams(modes = 17), GuglielmoParams(stretch = 2.0),
            GuglielmoParams(q = 1.0), GuglielmoParams(q = 5000.0), GuglielmoParams(noiseColor = -1.0), GuglielmoParams(noiseLevel = 2.0),
            GuglielmoParams(wallCount = -1), GuglielmoParams(wallCount = 129), GuglielmoParams(wallLevel = 2.0),
            GuglielmoParams(wallSpread = 200.0), GuglielmoParams(chaos = 2.0), GuglielmoParams(chaosRate = 0.0),
            GuglielmoParams(chaosRate = 20.0), GuglielmoParams(feedback = 2.0), GuglielmoParams(width = Double.NaN),
            GuglielmoParams(levelDb = 20.0), GuglielmoParams(fadeSeconds = 20.0),
        )
        for (p in bad) try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        GuglielmoParams().validate()
    }

    @Test fun defaultsAreAudibleBoundedAndStereo() {
        val out = gen(GuglielmoParams(durationSeconds = 6.0))
        assertEquals(sr * 6 * 2, out.size)
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
        val r = rms(left(out), sr, sr * 6)
        assertTrue("rms $r", r in 0.01..0.7)
        var diff = 0.0
        for (i in 0 until out.size step 2) diff += abs(out[i] - out[i + 1])
        assertTrue(diff > 1.0)
    }

    @Test fun deterministicForASeedAndDifferentForAnother() {
        val p = GuglielmoParams(durationSeconds = 2.0, seed = 4)
        val a = gen(p)
        assertTrue(a.contentEquals(gen(p)))
        assertFalse(a.contentEquals(gen(p.copy(seed = 5))))
    }

    @Test fun theBankRingsAtTheRootFrequency() {
        val out = left(gen(tone(440.0)))
        val sp = spectrum(out, sr * 2)
        val hz = peakIn(sp, 200.0, 900.0)
        assertEquals(440.0, hz, 4.0)
        val sorted = sp.sortedArray()
        assertTrue("peak/median", sp.max() / sorted[sorted.size / 2] > 100.0)
    }

    @Test fun stretchMovesTheUpperPartialsAwayFromHarmonic() {
        val harmonic = spectrum(left(gen(tone(100.0, modes = 2, stretch = 0.0))), sr * 3)
        val stretched = spectrum(left(gen(tone(100.0, modes = 2, stretch = 1.0))), sr * 3)
        assertEquals(200.0, peakIn(harmonic, 150.0, 260.0), 5.0)
        assertEquals(203.96, peakIn(stretched, 150.0, 260.0), 5.0)
    }

    /** Power-weighted spectral centroid (Hz) of a window: a stable estimate of where a narrow resonance sits. */
    private fun centroid(a: FloatArray, from: Int, n: Int = 16384, lo: Double = 250.0, hi: Double = 800.0): Double {
        val sp = spectrum(a, from, n)
        var num = 0.0; var den = 0.0
        for (i in (lo * n / sr).toInt()..(hi * n / sr).toInt()) {
            val f = i * sr.toDouble() / n
            num += sp[i] * sp[i] * f; den += sp[i] * sp[i]
        }
        return num / den
    }

    private fun centroids(a: FloatArray) = (0..16).map { centroid(a, (sr * (1.0 + 0.5 * it)).toInt()) }

    private fun chaosTone() = tone(440.0).copy(q = 100.0, chaos = 1.0, chaosRate = 1.0, durationSeconds = 11.0)

    @Test fun chaosBendsTheBankOverTimeButNotWithoutChaos() {
        val bent = centroids(left(gen(chaosTone())))
        val still = centroids(left(gen(chaosTone().copy(chaos = 0.0))))
        val bentSpread = bent.max() - bent.min()
        val stillSpread = still.max() - still.min()
        assertTrue("chaos centroids ${bent.map { round(it) }} spread $bentSpread", bentSpread > 60.0)
        assertTrue("chaos 0 centroids ${still.map { round(it) }} spread $stillSpread", stillSpread < 16.0)
        assertTrue("chaos must dominate the estimation jitter: $bentSpread vs $stillSpread", bentSpread > 3 * stillSpread)
        assertTrue("bend stays within +-3 semitones: ${bent.min()}..${bent.max()}", bent.min() > 440.0 * 0.82 && bent.max() < 440.0 * 1.22)
    }

    @Test fun theSeedChoosesTheChaosTrajectory() {
        val a = centroids(left(gen(chaosTone().copy(seed = 1))))
        val b = centroids(left(gen(chaosTone().copy(seed = 2))))
        val maxDiff = a.indices.maxOf { abs(a[it] - b[it]) }
        assertTrue("seed 1 ${a.map { round(it) }} seed 2 ${b.map { round(it) }}", maxDiff > 25.0)
        assertTrue(a == centroids(left(gen(chaosTone().copy(seed = 1)))))   // and each seed is repeatable
    }

    @Test fun theWallSoundsAtHarmonicsOfTheRoot() {
        val p = GuglielmoParams(
            durationSeconds = 8.0, rootHz = 100.0, noiseLevel = 0.0, wallCount = 16, wallLevel = 1.0, wallSpread = 0.0,
            chaos = 0.0, feedback = 0.0, width = 0.0, fadeSeconds = 0.0,
        )
        val out = left(gen(p))
        assertTrue(rms(out, sr, sr * 8) > 0.01)
        val hz = peakIn(spectrum(out, sr * 2), 90.0, 1700.0)
        assertTrue("peak $hz", abs(hz - round(hz / 100.0) * 100.0) < 4.0)
    }

    @Test fun fadesStartAndEndAtSilence() {
        val out = left(gen(GuglielmoParams(durationSeconds = 8.0, fadeSeconds = 2.0)))
        val mid = rms(out, sr * 3, sr * 5)
        assertTrue(rms(out, 0, 200) < mid * 0.05)
        assertTrue(rms(out, out.size - 200, out.size) < mid * 0.05)
    }

    @Test fun feedbackLoopStaysBoundedAndChangesTheSound() {
        val base = GuglielmoParams(durationSeconds = 6.0)
        val loud = gen(base.copy(feedback = 1.0))
        assertTrue(loud.all { it.isFinite() && abs(it) <= 1f })
        assertFalse(loud.contentEquals(gen(base)))
    }

    @Test fun nothingSwitchedOnIsSilence() {
        val out = gen(GuglielmoParams(durationSeconds = 2.0, noiseLevel = 0.0, wallCount = 0, feedback = 0.0))
        assertTrue(out.all { it == 0f })
    }

    @Test fun modesAboveNyquistAreSkippedNotFailed() {
        val out = gen(GuglielmoParams(durationSeconds = 2.0, rootHz = 2000.0, modes = 16, stretch = 1.0, wallCount = 8))
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test fun progressIsMonotonicAndCancellable() {
        val p = GuglielmoParams(durationSeconds = 3.0)
        val seen = ArrayList<Float>()
        GuglielmoEngine(p).generate(MemorySink(2, p.outputFrames().toInt()), { seen.add(it) })
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
        var calls = 0
        try { GuglielmoEngine(p).generate(MemorySink(2, p.outputFrames().toInt()), {}, { ++calls > 2 }); throw AssertionError() }
        catch (_: ProcessingCancelled) {}
    }
}
