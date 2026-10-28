package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paulstretch-style extreme spectral time stretch (STFT with randomised phase).
 *
 * For output hop k (every N/2 output samples) a Hann-windowed frame of N input samples is read
 * centred at input position k*(N/2)/stretch, its FFT magnitudes are kept, ALL phases are replaced by
 * uniform random values (conjugate-symmetric so the result is real), the frame is inverse-transformed,
 * multiplied by a sine window (power-complementary at 50% overlap) and overlap-added. A constant gain
 * compensates the Hann analysis window so the output RMS tracks the input RMS.
 *
 * Limitations: transients and note onsets are smeared into noise-like texture (phase information is
 * discarded by design); stereo channels use independent random phases (wider image, no phase coherence
 * between channels); the first/last half-window fade in/out; stretch is limited to 1..100.
 * Deterministic for a given (input, params, seed).
 */
class PaulstretchEngine(private val params: PaulstretchParams) {

    /** @return number of output frames written. */
    fun run(
        source: PcmSource,
        sink: SampleSink,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Long {
        params.validate()
        val ch = source.channels
        if (ch !in 1..2) throw LimitException("Somente mono ou estéreo é suportado.")
        if (source.frames <= 0L) throw InvalidParamException("O áudio está vazio.")

        val n = params.fftSize(source.sampleRate)
        val half = n / 2
        val target = params.outputFrames(source.frames)
        val hops = (target + half - 1) / half
        val hopIn = half / params.stretch

        val fft = Fft(n)
        val wa = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }          // Hann (analysis)
        val ws = DoubleArray(n) { sin(PI * (it + 0.5) / n) }                    // sine (synthesis)
        var sumSq = 0.0
        for (w in wa) sumSq += w * w
        val gain = sqrt(n / sumSq)

        val rngs = Array(ch) { XorShift64(params.seed * 31 + it) }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val inFrame = FloatArray(n * ch)
        val tail = Array(ch) { DoubleArray(half) }
        val block = FloatArray(half * ch)
        var written = 0L

        for (k in 0L..hops) {
            if (cancelled()) throw ProcessingCancelled()
            val start = floor(k * hopIn + 0.5).toLong() - half
            source.read(start, n, inFrame)

            for (c in 0 until ch) {
                for (i in 0 until n) { re[i] = inFrame[i * ch + c] * wa[i]; im[i] = 0.0 }
                fft.transform(re, im, false)
                val rng = rngs[c]
                re[0] = sqrt(re[0] * re[0] + im[0] * im[0]); im[0] = 0.0
                re[half] = sqrt(re[half] * re[half] + im[half] * im[half]); im[half] = 0.0
                for (i in 1 until half) {
                    val mag = sqrt(re[i] * re[i] + im[i] * im[i])
                    val ph = rng.nextDouble() * 2.0 * PI
                    re[i] = mag * cos(ph); im[i] = mag * sin(ph)
                    re[n - i] = re[i]; im[n - i] = -im[i]
                }
                fft.transform(re, im, true)
                val t = tail[c]
                for (i in 0 until half) {
                    val y = re[i] * ws[i] * gain
                    val v = (t[i] + y).toFloat()
                    block[i * ch + c] = if (v.isFinite()) v else 0f
                    t[i] = re[half + i] * ws[half + i] * gain
                }
            }

            if (k >= 1) {
                val m = minOf(half.toLong(), target - written).toInt()
                if (m > 0) { sink.write(block, m); written += m }
            }
            onProgress((k.toFloat() / hops).coerceAtMost(1f))
        }
        return written
    }
}
