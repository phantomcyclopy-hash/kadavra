package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.Fft
import com.kadrava.audio.dsp.PcmSource
import com.kadrava.audio.session.MixTrack
import com.kadrava.audio.session.Mixer
import com.kadrava.audio.session.MixerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MixerTest {
    private val sr = 44100
    private val c = 0.70710678f   // mono centre gain (-3 dB)

    private fun mono(rate: Int, data: FloatArray) = ArrayPcmSource(rate, 1, data)
    private fun dc(v: Float, n: Int = 4000) = mono(sr, FloatArray(n) { v })

    private fun render(m: Mixer, frames: Int, block: Int = frames): FloatArray {
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var pos = 0
        while (pos < frames) {
            val n = minOf(block, frames - pos)
            m.render(buf, n)
            System.arraycopy(buf, 0, out, pos * 2, n * 2)
            pos += n
        }
        return out
    }

    private fun mixer(vararg t: MixTrack, master: Float = 1f, loop: Boolean = false, ls: Long = 0, le: Long = 0) =
        Mixer(sr, MixerState(t.toList(), master, loop, ls, le))

    @Test fun emptyMixerIsSilent() {
        assertTrue(render(Mixer(sr), 1000).all { it == 0f })
    }

    @Test fun monoTrackUsesAConstantPowerPanLaw() {
        val out = render(mixer(MixTrack(1, dc(0.5f))), 100)
        assertEquals(0.5f * c, out[0], 1e-6f); assertEquals(0.5f * c, out[1], 1e-6f)
        for (pan in floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val o = render(mixer(MixTrack(1, dc(0.5f), pan = pan)), 10)
            val l = o[0] / 0.5f; val r = o[1] / 0.5f
            assertEquals("pan $pan", 1f, l * l + r * r, 1e-5f)
        }
        val hardLeft = render(mixer(MixTrack(1, dc(0.5f), pan = -1f)), 10)
        assertEquals(0.5f, hardLeft[0], 1e-6f); assertEquals(0f, hardLeft[1], 1e-6f)
    }

    @Test fun trackAndMasterGainMultiply() {
        val out = render(mixer(MixTrack(1, dc(0.4f), gain = 0.5f), master = 0.5f), 10)
        assertEquals(0.4f * c * 0.5f * 0.5f, out[0], 1e-6f)
    }

    @Test fun stereoTracksUseABalanceWithUnityAtCentre() {
        val st = ArrayPcmSource(sr, 2, FloatArray(8000) { 0.5f })
        fun at(pan: Float) = render(mixer(MixTrack(1, st, pan = pan)), 10)
        assertEquals(0.5f, at(0f)[0], 1e-6f); assertEquals(0.5f, at(0f)[1], 1e-6f)
        assertEquals(0f, at(1f)[0], 1e-6f); assertEquals(0.5f, at(1f)[1], 1e-6f)
        assertEquals(0.5f, at(-1f)[0], 1e-6f); assertEquals(0f, at(-1f)[1], 1e-6f)
    }

    @Test fun muteAndSoloFollowTheDocumentedRule() {
        fun l(a: MixTrack, b: MixTrack) = render(mixer(a, b), 10)[0]
        fun t(id: Int, v: Float, mute: Boolean = false, solo: Boolean = false) = MixTrack(id, dc(v), mute = mute, solo = solo)
        assertEquals((0.2f + 0.4f) * c, l(t(1, 0.2f), t(2, 0.4f)), 1e-6f)
        assertEquals(0.4f * c, l(t(1, 0.2f, mute = true), t(2, 0.4f)), 1e-6f)
        assertEquals(0.2f * c, l(t(1, 0.2f, solo = true), t(2, 0.4f)), 1e-6f)
        assertEquals(0.2f * c, l(t(1, 0.2f, mute = true, solo = true), t(2, 0.4f)), 1e-6f)   // solo wins over mute
        assertEquals((0.2f + 0.4f) * c, l(t(1, 0.2f, solo = true), t(2, 0.4f, solo = true)), 1e-6f)
    }

    @Test fun aMutedTrackIsNeverRead() {
        val boom = object : PcmSource {
            override val frames = 1000L; override val channels = 1; override val sampleRate = sr
            override fun read(startFrame: Long, count: Int, out: FloatArray) = throw IllegalStateException("must not be read")
        }
        val out = render(mixer(MixTrack(1, boom, mute = true), MixTrack(2, dc(0.2f))), 50)
        assertEquals(0.2f * c, out[0], 1e-6f)
    }

    @Test fun tracksAreSampleAlignedThroughOffsets() {
        val a = FloatArray(400).also { it[100] = 0.4f }
        val b = FloatArray(400).also { it[0] = 0.4f }
        val out = render(mixer(MixTrack(1, mono(sr, a)), MixTrack(2, mono(sr, b), offsetFrames = 100)), 300)
        for (i in 0 until 300) {
            val expected = if (i == 100) 0.8f * c else 0f
            assertEquals("frame $i", expected, out[2 * i], 1e-6f)
        }
    }

    @Test fun otherSampleRatesKeepPitchDurationAndStartPoint() {
        val src = mono(22050, FloatArray(22050) { (0.5 * sin(2 * PI * 441.0 * it / 22050)).toFloat() })
        val out = render(mixer(MixTrack(1, src)), 60000)
        val n = 16384
        val re = DoubleArray(n) { out[2 * (8000 + it)] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        var best = 1
        for (i in 1 until n / 2) if (re[i] * re[i] + im[i] * im[i] > re[best] * re[best] + im[best] * im[best]) best = i
        assertEquals(441.0, best * sr.toDouble() / n, 3.0)
        fun rms(from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += out[2 * i].toDouble() * out[2 * i]; return sqrt(s / (to - from)) }
        assertTrue(rms(40000, 44000) > 0.1)
        assertTrue(rms(45000, 50000) < 1e-9)

        val imp = mono(22050, FloatArray(400).also { it[100] = 0.4f })
        val o = render(mixer(MixTrack(1, imp, offsetFrames = 1000)), 1400)
        for (i in 0 until 1199) assertEquals("before the impulse at $i", 0f, o[2 * i], 1e-7f)
        assertEquals(0.4f * c, o[2 * 1200], 1e-6f)
        assertEquals(0.2f * c, o[2 * 1199], 1e-6f)
        assertEquals(0.2f * c, o[2 * 1201], 1e-6f)
    }

    @Test fun outputDoesNotDependOnTheBlockSize() {
        val a = mono(sr, FloatArray(20000) { (0.3 * sin(it * 0.05)).toFloat() })
        val b = ArrayPcmSource(22050, 2, FloatArray(30000) { (0.3 * sin(it * 0.021)).toFloat() })
        val d = mono(sr, FloatArray(5000) { (it % 50) / 100f })
        fun go(block: Int) = render(
            mixer(MixTrack(1, a, pan = -0.4f), MixTrack(2, b, gain = 0.8f), MixTrack(3, d, offsetFrames = 777, pan = 0.6f),
                master = 0.9f, loop = true, ls = 500, le = 3000), 9000, block)
        val ref = go(4096)
        assertTrue(ref.contentEquals(go(777)))
        assertTrue(ref.contentEquals(go(64)))
    }

    @Test fun loopWrapsTheTransportExactly() {
        val m = mixer(MixTrack(1, mono(sr, FloatArray(1000) { it * 0.0005f })), loop = true, ls = 100, le = 200)
        val out = render(m, 450)
        for (k in 0 until 450) {
            val pos = if (k < 200) k else 100 + (k - 200) % 100
            assertEquals("frame $k", pos * 0.0005f * c, out[2 * k], 1e-6f)
        }
        assertEquals(150L, m.position)
        assertEquals(3L, m.wrapCount)
    }

    @Test fun seekMovesTheTransport() {
        val m = mixer(MixTrack(1, mono(sr, FloatArray(1000) { it * 0.0005f })))
        render(m, 20)
        m.seek(300)
        val out = render(m, 10)
        assertEquals(300 * 0.0005f * c, out[0], 1e-6f)
        assertEquals(310L, m.position)
    }

    @Test fun soundingPositionSubtractsTheBufferedFramesAcrossALoop() {
        val m = mixer(loop = true, ls = 1000, le = 2000)
        m.seek(1050); render(m, 100)
        assertEquals(1150L, m.position)
        assertEquals(1050L, m.soundingPosition(100))
        m.seek(1900); render(m, 200)
        assertEquals(1100L, m.position)
        assertEquals(1L, m.wrapCount)
        assertEquals(1950L, m.soundingPosition(150))     // that audio was before the wrap: end of the loop
        m.seek(1010); render(m, 10)
        assertEquals(990L, m.soundingPosition(30))        // no wrap yet: plain subtraction
    }

    @Test fun theLimiterIsTransparentBelowTheKneeAndNeverClips() {
        assertEquals(0.5f, Mixer.softClip(0.5f), 0f)
        assertEquals(-0.79f, Mixer.softClip(-0.79f), 0f)
        assertEquals(0.8001f, Mixer.softClip(0.8001f), 1e-3f)
        var last = 0f
        for (i in 0..400) {
            val x = i * 0.025f
            val y = Mixer.softClip(x)
            assertTrue("monotonic at $x", y >= last)
            assertTrue("bounded at $x", y < 1.0000001f)
            assertEquals(-y, Mixer.softClip(-x), 0f)
            last = y
        }
        val loud = Array(7) { MixTrack(it + 1, dc(0.9f)) }
        val out = render(Mixer(sr, MixerState(loud.toList())), 100)
        assertTrue(out.all { abs(it) <= 1f && abs(it) > 0.8f })   // saturates at exactly 1, never above
    }

    @Test fun metersReportTrackAndMasterPeaks() {
        val m = mixer(MixTrack(1, dc(0.5f)), MixTrack(2, dc(0.9f), mute = true))
        val out = render(m, 200)
        assertEquals(0.5f * c, m.trackPeak(1, 0), 1e-6f)
        assertEquals(0f, m.trackPeak(2, 0), 0f)
        assertEquals(out.maxOf { abs(it) }, maxOf(m.masterPeakL, m.masterPeakR), 1e-6f)
    }

    @Test fun stateChangesApplyOnTheNextBlock() {
        val t = MixTrack(1, dc(0.5f))
        val m = mixer(t)
        assertTrue(render(m, 50)[0] > 0.1f)
        m.state = MixerState(listOf(MixTrack(1, dc(0.5f), mute = true)))
        assertTrue(render(m, 50).all { it == 0f })
    }
}
