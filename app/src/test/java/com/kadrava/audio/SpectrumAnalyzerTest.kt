package com.kadrava.audio

import com.kadrava.audio.dsp.AnalyzerParams
import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.SpectrumAnalyzer
import com.kadrava.audio.dsp.SpectrumAscii
import com.kadrava.audio.dsp.WindowType
import com.kadrava.audio.dsp.Windows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {
    private val sr = 8192

    private fun sine(frames: Int, hz: Double = 1000.0, amp: Double = 0.5) =
        FloatArray(frames) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    @Test fun windowCoherentGains() {
        assertEquals(0.5, Windows.coherentGain(Windows.make(WindowType.HANN, 1024)), 1e-9)
        assertEquals(0.54, Windows.coherentGain(Windows.make(WindowType.HAMMING, 1024)), 1e-9)
        assertEquals(0.42, Windows.coherentGain(Windows.make(WindowType.BLACKMAN, 1024)), 1e-9)
        assertEquals(1.0, Windows.coherentGain(Windows.make(WindowType.RECTANGULAR, 1024)), 1e-9)
    }

    @Test fun sineOnABinReadsItsTrueLevelWithEveryWindow() {
        for (w in WindowType.entries) {
            val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 2)), AnalyzerParams(1024, w))
            assertEquals("${w.label} bin", 125, r.avgDb.indices.maxByOrNull { r.avgDb[it] })
            assertEquals("${w.label} level", -6.02, r.avgDb[125].toDouble(), 0.1)
            assertEquals(1000.0, r.dominantHz, 1.0)
        }
    }

    @Test fun peakRmsAndCrestAreMeasuredInTheTimeDomain() {
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 2)), AnalyzerParams(1024))
        assertEquals(-6.02, r.peakDb.toDouble(), 0.1)
        assertEquals(-9.03, r.rmsDb.toDouble(), 0.1)
        assertEquals(3.0, r.crestDb.toDouble(), 0.15)
        assertEquals(2.0, r.durationSec, 1e-9)
    }

    @Test fun holdIsNeverBelowAverageAndPlayheadSnapshotSeesTheTone() {
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 2)), AnalyzerParams(1024), playheadFrame = sr.toLong())
        for (i in r.avgDb.indices) assertTrue(r.holdDb[i] >= r.avgDb[i] - 1e-4f)
        assertEquals(125, r.playheadDb.indices.maxByOrNull { r.playheadDb[it] })
    }

    @Test fun stereoSpectrumIsTheChannelAverageButPeakUsesAllChannels() {
        val l = sine(sr * 2)
        val st = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else 0f }
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 2, st), AnalyzerParams(1024))
        assertEquals(-12.04, r.avgDb[125].toDouble(), 0.1)
        assertEquals(-6.02, r.peakDb.toDouble(), 0.1)
        assertEquals(-12.04, r.rmsDb.toDouble(), 0.1)
    }

    @Test fun silenceReadsAsFloor() {
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, FloatArray(sr)), AnalyzerParams(1024))
        assertTrue(r.avgDb.all { it == SpectrumAnalyzer.FLOOR_DB })
        assertEquals(SpectrumAnalyzer.FLOOR_DB, r.peakDb, 0f)
        assertEquals(0f, r.crestDb, 0f)
    }

    @Test fun veryShortInputUsesOnePaddedWindow() {
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(100)), AnalyzerParams(1024))
        assertEquals(1, r.windowsUsed)
        assertTrue(r.avgDb.all { it.isFinite() })
    }

    @Test fun windowCountIsCappedOnLongFiles() {
        val r = SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 400)), AnalyzerParams(1024))
        assertEquals(SpectrumAnalyzer.MAX_WINDOWS, r.windowsUsed)
        assertEquals(125, r.avgDb.indices.maxByOrNull { r.avgDb[it] })
    }

    @Test fun invalidInputAndParams() {
        try { SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, FloatArray(0)), AnalyzerParams()); throw AssertionError() } catch (_: InvalidParamException) {}
        try { SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, FloatArray(10)), AnalyzerParams(fftSize = 1000)); throw AssertionError() } catch (_: InvalidParamException) {}
    }

    @Test fun progressIsMonotonicAndCancellable() {
        val seen = ArrayList<Float>()
        SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 5)), AnalyzerParams(1024), 0L, { seen.add(it) })
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(1f, seen.last(), 0f)
        var calls = 0
        try { SpectrumAnalyzer.analyze(ArrayPcmSource(sr, 1, sine(sr * 5)), AnalyzerParams(1024), 0L, {}, { ++calls > 2 }); throw AssertionError() }
        catch (_: ProcessingCancelled) {}
    }

    // ---- ASCII axis / chart

    @Test fun logRebinPutsTheToneInTheRightColumn() {
        val db = FloatArray(2049) { -120f }.also { it[500] = -6f }       // 1000 Hz at 8192 Hz / 4096
        val cols = 40
        val out = SpectrumAscii.rebin(db, sr, 4096, cols, log = true)
        assertEquals(cols, out.size)
        val c = out.indices.maxByOrNull { out[it] }!!
        val fMax = SpectrumAscii.fMax(sr)
        assertTrue(SpectrumAscii.edge(c, cols, true, fMax) <= 1000.0 && 1000.0 < SpectrumAscii.edge(c + 1, cols, true, fMax))
    }

    @Test fun rebinHandlesColumnsNarrowerThanOneBin() {
        val out = SpectrumAscii.rebin(FloatArray(513) { -50f }, 44100, 1024, 60, log = true)
        assertTrue(out.all { kotlin.math.abs(it + 50f) < 1e-3f })
        val lin = SpectrumAscii.rebin(FloatArray(513) { -50f }, 44100, 1024, 60, log = false)
        assertEquals(60, lin.size)
    }

    @Test fun chartFillsFromTheBottomAndMarksHold() {
        val full = SpectrumAscii.chart(FloatArray(4) { 0f }, null, 10)
        assertTrue(full.chars.all { it == '#' })
        val empty = SpectrumAscii.chart(FloatArray(4) { -120f }, null, 10)
        assertTrue(empty.chars.all { it == ' ' })
        val half = SpectrumAscii.chart(FloatArray(2) { -45f }, null, 10)
        for (r in 5 until 10) assertEquals('#', half.charAt(r, 0))
        for (r in 0 until 5) assertEquals(' ', half.charAt(r, 0))
        val mark = SpectrumAscii.chart(FloatArray(2) { -120f }, FloatArray(2) { -45f }, 10)
        assertEquals('-', mark.charAt(4, 1))
    }

    @Test fun axisLabelsAppear() {
        val a = SpectrumAscii.axisLine(46, 44100, log = true)
        assertEquals(46, a.length)
        assertTrue(a.contains("100") && a.contains("1k") && a.contains("10k"))
    }
}
