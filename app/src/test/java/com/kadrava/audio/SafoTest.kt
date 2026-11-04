package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.Fft
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.LoopMode
import com.kadrava.audio.dsp.MagicEffect
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.OfflinePlanner
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.SafoEngine
import com.kadrava.audio.dsp.SafoMode
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.SafoSynth
import com.kadrava.audio.dsp.TruncatedPcmSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Safo's output is always stereo (interleaved L/R). */
class SafoTest {
    private val sr = 22050

    private fun sine(frames: Int, hz: Double = 440.0, amp: Double = 0.5) =
        FloatArray(frames) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    /** 1 s of silence followed by 1 s of tone. */
    private fun silenceThenTone() =
        FloatArray(sr * 2) { if (it < sr) 0f else (0.5 * sin(2 * PI * 440.0 * it / sr)).toFloat() }

    private fun run(data: FloatArray, ch: Int, p: SafoParams): FloatArray {
        val src = ArrayPcmSource(sr, ch, data)
        val sink = MemorySink(2, p.outputFrames(sr).toInt())
        assertEquals(p.outputFrames(sr), SafoEngine(p).run(src, sink))
        assertEquals(p.outputFrames(sr).toInt(), sink.framesWritten)
        return sink.data
    }

    private fun left(a: FloatArray) = FloatArray(a.size / 2) { a[it * 2] }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }

    private fun peakHz(a: FloatArray, from: Int): Double {
        val n = 8192
        val re = DoubleArray(n) { a[from + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        var best = 1
        for (i in 1 until n / 2) if (re[i] * re[i] + im[i] * im[i] > re[best] * re[best] + im[best] * im[best]) best = i
        return best * sr.toDouble() / n
    }

    @Test fun rejectsBadParams() {
        val bad = listOf(
            SafoParams(durationSeconds = 0.5), SafoParams(durationSeconds = 601.0), SafoParams(loopStart = -0.1),
            SafoParams(loopEnd = 1.5), SafoParams(loopStart = 0.6, loopEnd = 0.6), SafoParams(speed = 17.0),
            SafoParams(grainMs = 4.0), SafoParams(grainMs = 1500.0), SafoParams(densityHz = 0.5), SafoParams(densityHz = 600.0),
            SafoParams(deviation = 1.5), SafoParams(rndRead = Double.NaN), SafoParams(panning = 2.0),
            SafoParams(envCycles = 0), SafoParams(envCycles = 33), SafoParams(envPhase = 1.5),
            SafoParams(pitch = 30.0), SafoParams(gliss = 20.0), SafoParams(reverse = -0.1),
            SafoParams(burstGrains = 0), SafoParams(burstSilence = -1), SafoParams(burstSilence = 65),
            SafoParams(hold = 0), SafoParams(hold = 17), SafoParams(pitchSpread = 20.0), SafoParams(pitchJitter = -1.0),
            SafoParams(decay = 1.5), SafoParams(ramp = -2.0), SafoParams(shape = 2.0), SafoParams(smoothHz = 50.0),
            SafoParams(reverbAmount = 1.5), SafoParams(freezeAt = -0.1), SafoParams(magicAmount = 2.0),
        )
        for (p in bad) try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        SafoParams().validate()
    }

    @Test fun everyModeGivesStereoOfTheRequestedLengthAndFinite() {
        for (mode in SafoMode.entries) {
            val out = run(sine(sr * 2), 1, SafoParams(mode = mode, durationSeconds = 3.0))
            assertEquals(sr * 3 * 2, out.size)
            assertTrue("$mode", out.all { it.isFinite() })
            assertTrue("$mode not silent", rms(out) > 0.01)
        }
    }

    @Test fun pitchIsPreservedAndShiftedByTheRequestedAmount() {
        val x = sine(sr * 2)
        assertEquals(440.0, peakHz(left(run(x, 1, SafoParams(durationSeconds = 4.0))), sr), 20.0)
        assertEquals(880.0, peakHz(left(run(x, 1, SafoParams(durationSeconds = 4.0, pitch = 12.0))), sr), 25.0)
    }

    @Test fun levelStaysNearTheInputAtDefaultDensity() {
        val x = sine(sr * 2)
        val l = left(run(x, 1, SafoParams(durationSeconds = 4.0, panning = 0.0)))
        val ratio = rms(l, sr, l.size) / rms(x)
        assertTrue("ratio $ratio", ratio in 0.7..1.4)
    }

    @Test fun silenceInSilenceOut() {
        assertTrue(run(FloatArray(sr), 1, SafoParams(durationSeconds = 2.0)).all { it == 0f })
    }

    @Test fun deterministicForSameSeedAndDifferentForAnother() {
        val x = sine(sr)
        val a = run(x, 1, SafoParams(durationSeconds = 2.0, seed = 5))
        assertTrue(a.contentEquals(run(x, 1, SafoParams(durationSeconds = 2.0, seed = 5))))
        assertFalse(a.contentEquals(run(x, 1, SafoParams(durationSeconds = 2.0, seed = 6))))
    }

    @Test fun loopRegionAndSpeedDecideWhereGrainsAreRead() {
        val x = silenceThenTone()
        // frozen read offset inside the silent half / inside the tone half
        assertTrue(run(x, 1, SafoParams(durationSeconds = 4.0, loopStart = 0.0, loopEnd = 0.4, speed = 0.0)).all { it == 0f })
        assertTrue(rms(run(x, 1, SafoParams(durationSeconds = 4.0, loopStart = 0.6, loopEnd = 1.0, speed = 0.0))) > 0.05)
        // scrubbing forward at 1x reaches the tone at t = 1 s and wraps back to silence at t = 2 s
        val scrub = left(run(x, 1, SafoParams(durationSeconds = 4.0, panning = 0.0)))
        assertTrue(rms(scrub, 0, sr / 2) < 1e-6)
        assertTrue(rms(scrub, sr * 12 / 10, sr * 19 / 10) > 0.05)
        assertTrue(rms(scrub, sr * 22 / 10, sr * 29 / 10) < 1e-6)
    }

    @Test fun pingPongBouncesBackThroughTheTone() {
        val x = silenceThenTone()
        val l = left(run(x, 1, SafoParams(durationSeconds = 4.0, panning = 0.0, loopMode = LoopMode.PING_PONG)))
        assertTrue(rms(l, sr * 12 / 10, sr * 28 / 10) > 0.05)   // tone going up and coming back
        assertTrue(rms(l, sr * 32 / 10, sr * 39 / 10) < 1e-6)   // back in the silent half
        val rev = run(x, 1, SafoParams(durationSeconds = 4.0, loopMode = LoopMode.PING_PONG_REV))
        assertTrue(rev.all { it.isFinite() })
    }

    @Test fun probabilityModeFollowsLevelAndRespectsTheLoop() {
        val x = silenceThenTone()
        val uniform = run(x, 1, SafoParams(mode = SafoMode.PROBABILITY, durationSeconds = 4.0, shape = 0.0))
        val focused = run(x, 1, SafoParams(mode = SafoMode.PROBABILITY, durationSeconds = 4.0, shape = 1.0))
        assertTrue(rms(focused) > rms(uniform) * 1.05)
        val onlySilent = run(x, 1, SafoParams(mode = SafoMode.PROBABILITY, durationSeconds = 4.0, shape = 1.0, loopStart = 0.0, loopEnd = 0.4))
        assertTrue(onlySilent.all { it == 0f })
    }

    @Test fun vectorFieldIsDeterministicAndBounded() {
        val x = silenceThenTone()
        val p = SafoParams(mode = SafoMode.VECTOR_FIELD, durationSeconds = 6.0, shape = 0.6)
        val a = run(x, 1, p)
        assertTrue(a.contentEquals(run(x, 1, p)))
        assertTrue(rms(a) > 0.02 && rms(a) < 0.5)
    }

    @Test fun panningAndStereoSources() {
        val mono = run(sine(sr), 1, SafoParams(durationSeconds = 2.0, panning = 0.0))
        for (i in mono.indices step 2) assertEquals(mono[i], mono[i + 1], 0f)          // centred mono: L == R
        val wide = run(sine(sr), 1, SafoParams(durationSeconds = 3.0, panning = 1.0))
        assertFalse(wide.indices.step(2).all { wide[it] == wide[it + 1] })
        val l = sine(sr)
        val st = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else 0f }
        val out = run(st, 2, SafoParams(durationSeconds = 2.0, panning = 0.0))
        for (i in 1 until out.size step 2) assertEquals(0f, out[i], 0f)
        assertTrue(rms(left(out)) > 0.05)
    }

    @Test fun grainShapingControlsChangeTheSoundAndStayFinite() {
        val x = sine(sr)
        val base = run(x, 1, SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0))
        val variants = listOf(
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, decay = 1.0, ramp = 1.0),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, envCycles = 4),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, envPhase = 0.5, envCycles = 3),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, gliss = 7.0),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, reverse = 1.0),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, deviation = 1.0),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, rndRead = 0.5),
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, hold = 8, pitchSpread = 12.0, pitchJitter = 6.0),
        ) + com.kadrava.audio.dsp.EnvShape.entries.map {
            SafoParams(durationSeconds = 3.0, densityHz = 5.0, grainMs = 200.0, envShape = it)
        }.drop(1)
        for (v in variants) {
            val o = run(x, 1, v)
            assertTrue("$v finite", o.all { it.isFinite() })
            assertFalse("$v changes the sound", o.contentEquals(base))
        }
    }

    @Test fun grainsAndSilencePatternSkipsGrains() {
        val x = sine(sr * 2)
        val full = left(run(x, 1, SafoParams(durationSeconds = 4.0, panning = 0.0)))
        val gated = left(run(x, 1, SafoParams(durationSeconds = 4.0, panning = 0.0, burstGrains = 1, burstSilence = 3)))
        assertTrue("gated ${rms(gated)} full ${rms(full)}", rms(gated) < rms(full) * 0.8)
    }

    @Test fun smoothingFilterRemovesHighFrequencies() {
        val x = sine(sr * 2, hz = 5000.0)
        val open = rms(left(run(x, 1, SafoParams(durationSeconds = 3.0, panning = 0.0))))
        val smooth = rms(left(run(x, 1, SafoParams(durationSeconds = 3.0, panning = 0.0, smoothHz = 500.0))))
        assertTrue("open $open smooth $smooth", smooth < open * 0.25)
    }

    @Test fun reverbAndFreezeStayStable() {
        val x = sine(sr)
        val g = SafoParams(durationSeconds = 4.0, densityHz = 5.0, grainMs = 100.0)
        val dry = run(x, 1, g)
        val wet = run(x, 1, g.copy(reverbAmount = 0.8))
        val frozen = run(x, 1, g.copy(durationSeconds = 8.0, reverbAmount = 0.8, freezeAt = 0.4))
        for (a in listOf(wet, frozen)) assertTrue(a.all { it.isFinite() && abs(it) < 20f })
        assertFalse(dry.contentEquals(wet))
    }

    @Test fun everyMagicEffectIsFiniteAndAmountZeroIsABypass() {
        val x = sine(sr)
        val base = run(x, 1, SafoParams(durationSeconds = 2.0))
        for (fx in MagicEffect.entries) {
            assertTrue("$fx bypass", run(x, 1, SafoParams(durationSeconds = 2.0, magic = fx, magicAmount = 0.0)).contentEquals(base))
            val on = run(x, 1, SafoParams(durationSeconds = 2.0, magic = fx, magicAmount = 1.0))
            assertTrue("$fx finite", on.all { it.isFinite() })
            if (fx != MagicEffect.OFF) assertFalse("$fx changes the sound", on.contentEquals(base))
        }
    }

    // ---- the streaming synth (what the live player runs)

    private fun renderInBlocks(p: SafoParams, block: Int, seconds: Int): FloatArray {
        val total = sr.toLong() * seconds
        val synth = SafoSynth(ArrayPcmSource(sr, 1, sine(sr * 2)), p, total, block)
        val out = FloatArray((total * 2).toInt())
        val buf = FloatArray(block * 2)
        var pos = 0
        while (pos < total) {
            val m = min(block.toLong(), total - pos).toInt()
            synth.render(buf, m, false)
            System.arraycopy(buf, 0, out, pos * 2, m * 2)
            pos += m
        }
        return out
    }

    @Test fun outputDoesNotDependOnTheBlockSize() {
        val p = SafoParams(durationSeconds = 3.0, deviation = 0.8, rndRead = 0.2, pitchSpread = 3.0, seed = 3)
        assertTrue(renderInBlocks(p, 16384, 3).contentEquals(renderInBlocks(p, 1000, 3)))
        assertTrue(renderInBlocks(p, 16384, 3).contentEquals(renderInBlocks(p, 1024, 3)))
    }

    @Test fun parametersCanChangeWhileStreaming() {
        val src = ArrayPcmSource(sr, 1, sine(sr * 2))
        val synth = SafoSynth(src, SafoParams(), Long.MAX_VALUE, 1024)
        val buf = FloatArray(2048)
        repeat(30) { synth.render(buf, 1024, false) }
        synth.params = SafoParams(pitch = 12.0, grainMs = 30.0, densityHz = 100.0, mode = SafoMode.PROBABILITY, reverbAmount = 0.5, smoothHz = 4000.0)
        repeat(60) { synth.render(buf, 1024, it > 30) }
        synth.params = SafoParams(mode = SafoMode.VECTOR_FIELD, magic = MagicEffect.CHORUS, magicAmount = 0.7)
        repeat(30) { synth.render(buf, 1024, false) }
        assertTrue(buf.all { it.isFinite() })
        assertTrue(synth.readFraction in 0.0..1.0)
        assertTrue(synth.position == 120L * 1024)
    }

    @Test fun veryShortAndEmptyInput() {
        assertTrue(run(floatArrayOf(0.5f), 1, SafoParams(durationSeconds = 1.0)).all { it.isFinite() })
        try { SafoEngine(SafoParams()).run(ArrayPcmSource(sr, 1, FloatArray(0)), MemorySink(2, 1)); throw AssertionError() }
        catch (_: InvalidParamException) {}
    }

    @Test fun progressIsMonotonicAndCancellable() {
        val src = ArrayPcmSource(sr, 1, sine(sr))
        val p = SafoParams(durationSeconds = 3.0)
        val seen = ArrayList<Float>()
        SafoEngine(p).run(src, MemorySink(2, p.outputFrames(sr).toInt()), { seen.add(it) })
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
        var calls = 0
        try { SafoEngine(p).run(src, MemorySink(2, p.outputFrames(sr).toInt()), {}, { ++calls > 2 }); throw AssertionError() }
        catch (_: ProcessingCancelled) {}
    }

    @Test fun truncatedSourceStopsAtTheLimitAndZeroPads() {
        val base = ArrayPcmSource(sr, 1, FloatArray(1000) { 1f })
        val t = TruncatedPcmSource(base, 600)
        assertEquals(600L, t.frames)
        val out = FloatArray(10)
        t.read(595, 10, out)
        for (i in 0 until 5) assertEquals(1f, out[i], 0f)
        for (i in 5 until 10) assertEquals(0f, out[i], 0f)
        assertEquals(1000L, TruncatedPcmSource(base, 5000).frames)
    }

    @Test fun offlinePlannerLimits() {
        val ok = OfflinePlanner.plan(sr * 10L, sr * 60L, 2, sr, 10_000_000_000L)
        assertEquals(sr * 60L, ok.outputFrames)
        try { OfflinePlanner.plan(sr * 10L, sr * 4000L, 2, sr, Long.MAX_VALUE); throw AssertionError() } catch (_: LimitException) {}
        try { OfflinePlanner.plan(sr * 10L, sr * 60L, 2, sr, 1_000_000L); throw AssertionError() } catch (_: LimitException) {}
        try { OfflinePlanner.plan(sr * 10L, sr * 60L, 6, sr, Long.MAX_VALUE); throw AssertionError() } catch (_: LimitException) {}
        try { OfflinePlanner.plan(0L, sr * 60L, 1, sr, Long.MAX_VALUE); throw AssertionError() } catch (_: InvalidParamException) {}
    }
}
