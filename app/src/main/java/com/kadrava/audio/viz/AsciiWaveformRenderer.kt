package com.kadrava.audio.viz

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object AsciiRamp {
    /** Index 0 is blank; density grows to the right. */
    const val CHARS = " .,:;irsXA253hMHGS#9B&@"
}

class AsciiGrid(val cols: Int, val rows: Int, val chars: CharArray, val intensity: FloatArray) {
    fun charAt(row: Int, col: Int): Char = chars[row * cols + col]
    fun intensityAt(row: Int, col: Int): Float = intensity[row * cols + col]
}

/**
 * Maps per-bucket peak amplitudes (waveform PEAK data, not a spectrum) to a
 * symmetric ASCII envelope. Each column is the max peak of the buckets it covers;
 * the whole picture is normalised to the loudest column. Silence is drawn as a
 * faint baseline ('.') on the centre row so the display never looks broken.
 */
object AsciiWaveformRenderer {

    internal fun columnPeaks(peaks: FloatArray, cols: Int): FloatArray {
        val out = FloatArray(cols)
        val n = peaks.size
        if (n == 0) return out
        for (c in 0 until cols) {
            val s = (c.toLong() * n / cols).toInt().coerceIn(0, n - 1)
            val e = (((c + 1).toLong() * n) / cols).toInt().coerceIn(s + 1, n)
            var m = 0f
            for (i in s until e) {
                val v = peaks[i]
                if (v.isFinite()) m = max(m, abs(v))
            }
            out[c] = m
        }
        return out
    }

    fun render(peaks: FloatArray, cols: Int, rows: Int): AsciiGrid {
        require(cols > 0 && rows > 0) { "cols and rows must be positive" }
        val chars = CharArray(cols * rows) { ' ' }
        val inten = FloatArray(cols * rows)
        val colPeaks = columnPeaks(peaks, cols)
        var globalMax = 0f
        for (v in colPeaks) if (v > globalMax) globalMax = v
        val scale = if (globalMax > 1e-6f) 1f / globalMax else 0f

        val n = AsciiRamp.CHARS.length
        val half = rows / 2f
        val span = 1f / half
        val baseline = span * 0.5f

        for (c in 0 until cols) {
            val a0 = colPeaks[c] * scale
            val a = max(a0, baseline)
            val weight = (a0 / baseline).coerceIn(0.02f, 1f)
            for (r in 0 until rows) {
                val dist = abs(r + 0.5f - half) / half
                val coverage = ((a - (dist - span / 2f)) / span).coerceIn(0f, 1f)
                if (coverage <= 0f) continue
                val body = 1f - 0.6f * (dist / a).coerceIn(0f, 1f)
                val i = (coverage * body * weight).coerceIn(0f, 1f)
                val idx = (1 + (i * (n - 2)).roundToInt()).coerceIn(1, n - 1)
                chars[r * cols + c] = AsciiRamp.CHARS[idx]
                inten[r * cols + c] = i
            }
        }
        return AsciiGrid(cols, rows, chars, inten)
    }
}
