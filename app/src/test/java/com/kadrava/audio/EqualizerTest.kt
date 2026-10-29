package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.BandParam
import com.kadrava.audio.dsp.BandType
import com.kadrava.audio.dsp.BiquadDesign
import com.kadrava.audio.dsp.EqEngine
import com.kadrava.audio.dsp.EqParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.OfflineRenderer
import com.kadrava.audio.dsp.ProcessingCancelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sr = 44100

    private fun sine(frames: Int, hz: Double, amp: Double) = FloatArray(frames) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    private fun rms(a: FloatArray, from: Int, to: Int, step: Int = 1): Double {
        var s = 0.0; var n = 0
        var i = from
        while (i < to) { s += a[i].toDouble() * a[i]; n++; i += step }
        return sqrt(s / n)
    }

    @Test fun rejectsInvalidBands() {
        val bad = listOf(
            BandParam(BandType.PEAK, 10.0, 0.0), BandParam(BandType.PEAK, 25000.0, 0.0),
            BandParam(BandType.PEAK, 1000.0, 19.0), BandParam(BandType.PEAK, 1000.0, Double.NaN),
            BandParam(BandType.PEAK, 1000.0, 0.0, 0.1), BandParam(BandType.PEAK, 1000.0, 0.0, 9.0),
        )
        for (b in bad) try { b.validate(); throw AssertionError("should reject $b") } catch (_: InvalidParamException) {}
        try { EqParams(emptyList()).validate(); throw AssertionError() } catch (_: InvalidParamException) {}
        try { EqParams(List(17) { BandParam(BandType.PEAK, 1000.0, 0.0) }).validate(); throw AssertionError() } catch (_: InvalidParamException) {}
        EqParams.default.validate()
    }

    @Test fun peakingBandHasRequestedGainAtCentreAndIsLocal() {
        val f = BiquadDesign.design(BandParam(BandType.PEAK, 1000.0, 6.0, 1.0), sr)
        assertEquals(6.0, f.magnitudeDb(1000.0, sr), 1e-6)
        assertEquals(0.0, f.magnitudeDb(50.0, sr), 0.1)
        assertEquals(0.0, f.magnitudeDb(15000.0, sr), 0.1)
    }

    @Test fun shelvesReachTheirGainAtTheExtremes() {
        val ls = BiquadDesign.design(BandParam(BandType.LOW_SHELF, 80.0, -9.0, 0.7071), sr)
        assertEquals(-9.0, ls.magnitudeDb(5.0, sr), 0.05)
        assertEquals(-4.5, ls.magnitudeDb(80.0, sr), 0.05)
        assertEquals(0.0, ls.magnitudeDb(5000.0, sr), 0.01)
        val hs = BiquadDesign.design(BandParam(BandType.HIGH_SHELF, 12000.0, 8.0, 0.7071), sr)
        assertEquals(8.0, hs.magnitudeDb(20000.0, sr), 0.05)
        assertEquals(0.0, hs.magnitudeDb(200.0, sr), 0.01)
    }

    @Test fun allExtremeDesignsAreStable() {
        for (rate in intArrayOf(8000, 44100, 96000)) for (type in BandType.entries)
            for (g in doubleArrayOf(-18.0, 18.0)) for (q in doubleArrayOf(0.3, 1.0, 8.0))
                for (f in doubleArrayOf(20.0, 100.0, 1000.0, 3000.0, 15000.0)) {
                    if (f >= rate * 0.45) continue
                    assertTrue("$type $g $q $f @$rate", BiquadDesign.design(BandParam(type, f, g, q), rate).isStable)
                }
    }

    @Test fun engineAppliesTheGainToASineAtTheCentre() {
        val x = sine(sr * 2, 1000.0, 0.25)
        val p = EqParams(listOf(BandParam(BandType.PEAK, 1000.0, 6.0, 1.0)))
        val sink = MemorySink(1, x.size)
        assertEquals(x.size.toLong(), EqEngine(p).run(ArrayPcmSource(sr, 1, x), sink))
        val db = 20 * log10(rms(sink.data, sr / 2, x.size) / rms(x, sr / 2, x.size))
        assertEquals(6.0, db, 0.1)
    }

    @Test fun flatEqualiserIsBitExactAndChannelsAreIndependent() {
        val l = sine(5000, 440.0, 0.5)
        val st = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else 0f }
        val flat = MemorySink(2, l.size)
        EqEngine(EqParams.default).run(ArrayPcmSource(sr, 2, st), flat)
        assertTrue(flat.data.contentEquals(st))
        val boosted = MemorySink(2, l.size)
        EqEngine(EqParams.default.withGains(listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0))).run(ArrayPcmSource(sr, 2, st), boosted)
        for (i in 1 until boosted.data.size step 2) assertEquals(0f, boosted.data[i], 0f)
    }

    @Test fun extremeSettingsStayFinite() {
        val impulse = FloatArray(20000).also { it[0] = 1f }
        val p = EqParams.default.withGains(listOf(18.0, -18.0, 18.0, -18.0, 18.0, 18.0))
        val sink = MemorySink(1, impulse.size)
        EqEngine(p).run(ArrayPcmSource(sr, 1, impulse), sink)
        assertTrue(sink.data.all { it.isFinite() })
        assertTrue(kotlin.math.abs(sink.data.last()) < 1e-3f)   // impulse response has decayed
    }

    @Test fun bandsNearNyquistAreSkippedNotFailed() {
        val p = EqParams.default.withGains(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 6.0))
        assertEquals(1, EqEngine.skipped(p, 8000).size)
        val x = sine(4000, 300.0, 0.5)
        val sink = MemorySink(1, x.size)
        EqEngine(p).run(ArrayPcmSource(8000, 1, x), sink)
        assertTrue(sink.data.contentEquals(x))
    }

    @Test fun cancellationAndEmptyInput() {
        val src = ArrayPcmSource(sr, 1, FloatArray(30000))
        var calls = 0
        try { EqEngine(EqParams.default).run(src, MemorySink(1, 30000), {}, { ++calls > 1 }); throw AssertionError() }
        catch (_: ProcessingCancelled) {}
        assertEquals(2, calls)
        try { EqEngine(EqParams.default).run(ArrayPcmSource(sr, 1, FloatArray(0)), MemorySink(1, 1)); throw AssertionError() }
        catch (_: InvalidParamException) {}
    }

    @Test fun rendersAWavThroughTheOfflineRenderer() {
        val x = sine(sr, 1000.0, 0.25)
        val p = EqParams(listOf(BandParam(BandType.PEAK, 1000.0, 6.0, 1.0)))
        val out = File(tmp.root, "eq.wav")
        val r = OfflineRenderer.render(1, sr, out, File(tmp.root, "w")) { sink, prog ->
            EqEngine(p).run(ArrayPcmSource(sr, 1, x), sink, prog)
        }
        assertEquals(sr.toLong(), r.outputFrames)
        assertEquals(44L + sr * 2L, out.length())
        assertEquals(1f, r.gain, 0f)
        assertTrue(!File(tmp.root, "w/stretched.f32").exists())
    }
}
