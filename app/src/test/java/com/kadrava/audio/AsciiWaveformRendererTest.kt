package com.kadrava.audio

import com.kadrava.audio.viz.AsciiRamp
import com.kadrava.audio.viz.AsciiWaveformRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiWaveformRendererTest {
    private fun nonBlank(g: com.kadrava.audio.viz.AsciiGrid, col: Int) =
        (0 until g.rows).count { g.charAt(it, col) != ' ' }

    @Test fun gridHasRequestedSize() {
        val g = AsciiWaveformRenderer.render(FloatArray(100) { 0.5f }, 40, 9)
        assertEquals(40, g.cols); assertEquals(9, g.rows); assertEquals(360, g.chars.size)
    }

    @Test fun onlyRampCharactersAreUsed() {
        val g = AsciiWaveformRenderer.render(FloatArray(300) { (it % 7) / 7f }, 50, 9)
        assertTrue(g.chars.all { it in AsciiRamp.CHARS })
    }

    @Test fun silenceIsOnlyAFaintBaseline() {
        val g = AsciiWaveformRenderer.render(FloatArray(64), 16, 9)
        assertTrue(g.chars.all { it == ' ' || it == '.' })
        assertTrue(g.chars.any { it == '.' })
    }

    @Test fun fullScaleFillsEveryCell() {
        val g = AsciiWaveformRenderer.render(FloatArray(64) { 1f }, 16, 9)
        assertTrue(g.chars.none { it == ' ' })
    }

    @Test fun louderColumnIsTaller() {
        val g = AsciiWaveformRenderer.render(floatArrayOf(0.1f, 1f), 2, 9)
        assertTrue(nonBlank(g, 1) > nonBlank(g, 0))
    }

    @Test fun columnPeakIsMaxOfItsBuckets() {
        val c = AsciiWaveformRenderer.columnPeaks(floatArrayOf(0f, 0f, 0f, 1f), 2)
        assertEquals(0f, c[0], 0f); assertEquals(1f, c[1], 0f)
    }

    @Test fun nonFiniteAndEmptyInputAreSafe() {
        val g = AsciiWaveformRenderer.render(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.2f), 3, 5)
        assertTrue(g.intensity.all { it.isFinite() })
        AsciiWaveformRenderer.render(FloatArray(0), 4, 5)
    }
}
