package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class WindowType(val label: String) {
    HANN("hann"), HAMMING("hamming"), BLACKMAN("blackman"), RECTANGULAR("retangular");

    fun next(): WindowType = entries[(ordinal + 1) % entries.size]
}

object Windows {
    /** Periodic windows (correct for spectral analysis). */
    fun make(type: WindowType, n: Int): DoubleArray = DoubleArray(n) { i ->
        val x = 2.0 * PI * i / n
        when (type) {
            WindowType.HANN -> 0.5 - 0.5 * cos(x)
            WindowType.HAMMING -> 0.54 - 0.46 * cos(x)
            WindowType.BLACKMAN -> 0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)
            WindowType.RECTANGULAR -> 1.0
        }
    }

    fun coherentGain(w: DoubleArray): Double = w.sum() / w.size
}

data class AnalyzerParams(val fftSize: Int = 4096, val window: WindowType = WindowType.HANN) {
    fun validate() {
        if (fftSize !in SIZES) throw InvalidParamException("tamanho de FFT deve ser um de $SIZES (recebido $fftSize).")
    }

    companion object { val SIZES = listOf(1024, 2048, 4096, 8192, 16384) }
}

/** Levels are dBFS: a full-scale sine centred on a bin reads 0 dB. [FLOOR_DB] stands for silence. */
class AnalysisResult(
    val sampleRate: Int,
    val fftSize: Int,
    /** RMS-average (Welch) magnitude per bin, 0..fftSize/2. */
    val avgDb: FloatArray,
    /** Maximum magnitude seen in each bin across all analysed windows. */
    val holdDb: FloatArray,
    /** Single window centred on the requested playhead position. */
    val playheadDb: FloatArray,
    /** Time-domain sample peak over all channels. */
    val peakDb: Float,
    /** Time-domain RMS over all channels. */
    val rmsDb: Float,
    val windowsUsed: Int,
    val dominantHz: Double,
    val durationSec: Double,
) {
    val binHz: Double get() = sampleRate.toDouble() / fftSize
    val crestDb: Float get() = if (peakDb <= SpectrumAnalyzer.FLOOR_DB || rmsDb <= SpectrumAnalyzer.FLOOR_DB) 0f else peakDb - rmsDb
}

/**
 * Cindy: offline FFT analysis of the real signal (channels averaged for the spectrum; peak/RMS use all
 * channels). Up to [MAX_WINDOWS] windows with 50% overlap, evenly spread over longer files.
 * It is an analysis of the file, not a live analyser.
 */
object SpectrumAnalyzer {
    const val FLOOR_DB = -120f
    const val MAX_WINDOWS = 1500
    private const val MIN_MAG = 1e-6

    fun analyze(
        source: PcmSource,
        params: AnalyzerParams,
        playheadFrame: Long = 0L,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): AnalysisResult {
        params.validate()
        val ch = source.channels
        val total = source.frames
        if (ch < 1) throw InvalidParamException("sem canais.")
        if (total <= 0L) throw InvalidParamException("O áudio está vazio.")
        val sr = source.sampleRate

        // pass 1: sample peak and RMS
        var peak = 0.0
        var sumSq = 0.0
        val blk = 65536
        val buf = FloatArray(blk * ch)
        var pos = 0L
        while (pos < total) {
            if (cancelled()) throw ProcessingCancelled()
            val n = min(blk.toLong(), total - pos).toInt()
            source.read(pos, n, buf)
            for (i in 0 until n * ch) {
                val v = buf[i].toDouble()
                if (v.isFinite()) { val a = abs(v); if (a > peak) peak = a; sumSq += v * v }
            }
            pos += n
            onProgress(0.4f * pos.toFloat() / total)
        }
        val rms = sqrt(sumSq / (total * ch.toDouble()))

        // pass 2: spectra
        val n = params.fftSize
        val half = n / 2
        val win = Windows.make(params.window, n)
        val sumW = win.sum()
        val fft = Fft(n)
        val frame = FloatArray(n * ch)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val power = DoubleArray(half + 1)
        val hold = DoubleArray(half + 1)
        val mag = DoubleArray(half + 1)

        fun spectrum(startFrame: Long) {
            source.read(startFrame, n, frame)
            for (i in 0 until n) {
                var m = 0.0
                for (c in 0 until ch) m += frame[i * ch + c]
                re[i] = (m / ch) * win[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in 0..half) {
                val scale = if (k == 0 || k == half) 1.0 / sumW else 2.0 / sumW
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
            }
        }

        val last = max(0L, total - n)
        val hop = (half).toLong()
        val natural = if (total <= n) 1L else (last + hop - 1) / hop + 1
        val count = min(natural, MAX_WINDOWS.toLong()).toInt()
        for (k in 0 until count) {
            if (cancelled()) throw ProcessingCancelled()
            val start = if (natural <= MAX_WINDOWS) k * hop else if (count == 1) 0L else last * k / (count - 1)
            spectrum(start)
            for (b in 0..half) {
                power[b] += mag[b] * mag[b]
                if (mag[b] > hold[b]) hold[b] = mag[b]
            }
            onProgress(0.4f + 0.55f * (k + 1) / count)
        }

        val avgDb = FloatArray(half + 1) { toDb(sqrt(power[it] / count)) }
        val holdDb = FloatArray(half + 1) { toDb(hold[it]) }
        spectrum(playheadFrame.coerceIn(0L, total) - half)
        val playDb = FloatArray(half + 1) { toDb(mag[it]) }
        onProgress(1f)

        return AnalysisResult(
            sampleRate = sr, fftSize = n, avgDb = avgDb, holdDb = holdDb, playheadDb = playDb,
            peakDb = toDb(peak), rmsDb = toDb(rms), windowsUsed = count,
            dominantHz = dominantFrequency(avgDb, sr, n), durationSec = total.toDouble() / sr,
        )
    }

    private fun toDb(linear: Double): Float =
        if (!(linear > MIN_MAG)) FLOOR_DB else max(FLOOR_DB, (20.0 * log10(linear)).toFloat())

    /** Strongest bin (DC excluded) refined by parabolic interpolation on the dB values. */
    fun dominantFrequency(db: FloatArray, sampleRate: Int, fftSize: Int): Double {
        var k = 1
        for (i in 2 until db.size) if (db[i] > db[k]) k = i
        var delta = 0.0
        if (k in 1 until db.size - 1) {
            val a = db[k - 1].toDouble(); val b = db[k].toDouble(); val c = db[k + 1].toDouble()
            val den = a - 2 * b + c
            if (abs(den) > 1e-9) delta = (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        return (k + delta) * sampleRate / fftSize
    }
}

class AsciiChart(val rows: Int, val cols: Int, val chars: CharArray, val level: FloatArray) {
    fun charAt(row: Int, col: Int): Char = chars[row * cols + col]
    fun levelAt(row: Int, col: Int): Float = level[row * cols + col]
}

/** Maps spectrum bins onto terminal columns (log or linear frequency axis) and draws bar charts. */
object SpectrumAscii {
    const val F_MIN = 20.0

    fun fMax(sampleRate: Int): Double = min(20000.0, sampleRate / 2.0)

    fun edge(c: Int, cols: Int, log: Boolean, fMax: Double): Double =
        if (log) F_MIN * (fMax / F_MIN).pow(c.toDouble() / cols) else F_MIN + (fMax - F_MIN) * c / cols

    fun rebin(db: FloatArray, sampleRate: Int, fftSize: Int, cols: Int, log: Boolean): FloatArray {
        require(cols > 0)
        val fMax = fMax(sampleRate)
        val maxBin = db.size - 1
        return FloatArray(cols) { c ->
            val b0 = edge(c, cols, log, fMax) * fftSize / sampleRate
            val b1 = edge(c + 1, cols, log, fMax) * fftSize / sampleRate
            val lo = max(1, ceil(b0).toInt())
            val hi = min(maxBin, floor(b1).toInt())
            if (lo <= hi) {
                var m = db[lo]
                for (i in lo + 1..hi) if (db[i] > m) m = db[i]
                m
            } else {
                val center = ((b0 + b1) / 2).coerceIn(1.0, maxBin.toDouble())
                val i = floor(center).toInt().coerceAtMost(maxBin - 1)
                val f = (center - i).toFloat()
                db[i] * (1 - f) + db[i + 1] * f
            }
        }
    }

    fun chart(levelsDb: FloatArray, holdDb: FloatArray?, rows: Int, floorDb: Float = -90f, topDb: Float = 0f): AsciiChart {
        require(rows > 0 && floorDb < topDb)
        val cols = levelsDb.size
        val chars = CharArray(rows * cols) { ' ' }
        val level = FloatArray(rows * cols)
        val ramp = com.kadrava.audio.viz.AsciiRamp.CHARS
        val n = ramp.length
        fun height(db: Float) = ((db - floorDb) / (topDb - floorDb)).coerceIn(0f, 1f) * rows
        for (c in 0 until cols) {
            val h = height(levelsDb[c])
            for (r in 0 until rows) {
                val fromBottom = rows - 1 - r
                val fill = (h - fromBottom).coerceIn(0f, 1f)
                if (fill >= 0.999f) { chars[r * cols + c] = '#'; level[r * cols + c] = 1f }
                else if (fill > 0f) {
                    chars[r * cols + c] = ramp[(1 + (fill * (n - 3)).roundToInt()).coerceIn(1, n - 2)]
                    level[r * cols + c] = fill
                }
            }
            if (holdDb != null) {
                val hh = height(holdDb[c])
                if (hh > 0f) {
                    val row = rows - 1 - min(rows - 1, floor(hh).toInt())
                    if (chars[row * cols + c] == ' ') { chars[row * cols + c] = '-'; level[row * cols + c] = 0.3f }
                }
            }
        }
        return AsciiChart(rows, cols, chars, level)
    }

    /** Frequency labels aligned under the chart columns; labels that would overlap are dropped. */
    fun axisLine(cols: Int, sampleRate: Int, log: Boolean): String {
        val fMax = fMax(sampleRate)
        val marks = if (log) listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")
        else listOf(5000.0 to "5k", 10000.0 to "10k", 15000.0 to "15k")
        val line = CharArray(cols) { ' ' }
        var nextFree = 0
        for ((f, label) in marks) {
            if (f >= fMax) continue
            val frac = if (log) ln(f / F_MIN) / ln(fMax / F_MIN) else (f - F_MIN) / (fMax - F_MIN)
            val start = (frac * cols).roundToInt() - label.length / 2
            if (start < nextFree || start + label.length > cols) continue
            label.forEachIndexed { i, ch -> line[start + i] = ch }
            nextFree = start + label.length + 1
        }
        return String(line)
    }
}
