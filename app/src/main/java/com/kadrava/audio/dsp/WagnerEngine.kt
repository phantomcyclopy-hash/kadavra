package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Wagner: feedback-delay-network reverb. Eight delay lines with mutually unrelated lengths, a Householder
 * feedback matrix (orthogonal, so the loop is lossless before the per-line decay gains), a damping low-pass in
 * every line, four input allpass diffusers, slow delay modulation and a pre-delay.
 * [rt60] is the decay time to -60 dB (measured to within a few percent with damping 0), [size] scales every
 * delay line, [damp] makes highs decay faster, [mix] crossfades dry and wet (0 = exact bypass),
 * [tailSeconds] extends the output so the reverb rings out. Convolution with an impulse response is not done yet.
 */
data class WagnerParams(
    val rt60: Double = 2.5,
    val size: Double = 1.0,
    val damp: Double = 0.3,
    val preDelayMs: Double = 20.0,
    val diffusion: Double = 0.7,
    val mod: Double = 0.3,
    val width: Double = 1.0,
    val mix: Double = 0.35,
    val tailSeconds: Double = 3.0,
) {
    fun validate() {
        fun r(name: String, v: Double, lo: Double, hi: Double) {
            if (!v.isFinite() || v < lo || v > hi) throw InvalidParamException("$name must be between $lo and $hi (got $v).")
        }
        r("rt60 (s)", rt60, 0.1, 30.0)
        r("size", size, 0.3, 3.0)
        r("damp", damp, 0.0, 0.95)
        r("pre-delay (ms)", preDelayMs, 0.0, 250.0)
        r("diffusion", diffusion, 0.0, 1.0)
        r("mod", mod, 0.0, 1.0)
        r("width", width, 0.0, 1.0)
        r("mix", mix, 0.0, 1.0)
        r("tail (s)", tailSeconds, 0.0, 30.0)
    }

    val isNoOp: Boolean get() = mix == 0.0
    fun tailFrames(sampleRate: Int): Long = Math.round(tailSeconds * sampleRate)
}

private class Allpass(size: Int) {
    private val buf = FloatArray(size + 1)
    private var i = 0
    fun process(v: Float, coef: Float): Float {
        val z = buf[i]
        val o = -coef * v + z
        buf[i] = v + coef * o
        i = (i + 1) % buf.size
        return o
    }
}

/** Per-sample FDN reverb, stereo in/out through [outL]/[outR]. */
class WagnerFx(private val sr: Int, initial: WagnerParams) {
    var outL = 0f
        private set
    var outR = 0f
        private set

    private val n = 8
    private val baseMs = doubleArrayOf(29.7, 37.1, 41.1, 43.7, 53.3, 59.9, 67.1, 79.3)
    private val maxLen = (0.0793 * 3.0 * sr + 0.01 * sr).toInt() + 8
    private val lines = Array(n) { DelayLine(maxLen) }
    private val pre = DelayLine((0.25 * sr).toInt() + 8)
    private val allpass = Array(4) { Allpass(max(2, (sr * doubleArrayOf(5.1, 7.7, 11.3, 13.9)[it] / 1000).toInt())) }
    private val lp = FloatArray(n)
    private val lens = FloatArray(n)
    private val gains = FloatArray(n)
    private val inGain = FloatArray(n) { (if (it % 2 == 0) 1f else -1f) / 2.828427f }
    private val phase = DoubleArray(n)
    private val phaseInc = DoubleArray(n) { 2 * PI * (0.1 + 0.07 * it) / sr }
    private val vv = FloatArray(n)
    private val dd = FloatArray(n)
    private var p = initial
    private var damp = 0f
    private var coef = 0.5f
    private var preSamples = 0f
    private var modDepth = 0f
    private var width = 1f
    private var mix = 0f

    init { setParams(initial) }

    fun setParams(np: WagnerParams) {
        p = np
        for (i in 0 until n) {
            val len = max(8.0, Math.round(baseMs[i] * sr / 1000.0 * np.size).toDouble())
            lens[i] = len.toFloat()
            gains[i] = 10.0.pow(-3.0 * len / sr / np.rt60).toFloat()
        }
        damp = np.damp.toFloat()
        coef = (0.3 + 0.4 * np.diffusion).toFloat()
        preSamples = (np.preDelayMs * sr / 1000.0).toFloat()
        modDepth = (np.mod * 0.001 * sr).toFloat()
        width = np.width.toFloat()
        mix = np.mix.toFloat()
    }

    fun process(inL: Float, inR: Float) {
        pre.write((inL + inR) * 0.5f)
        var v = pre.read(preSamples)
        for (a in allpass) v = a.process(v, coef)

        var s = 0f
        for (i in 0 until n) {
            val m = if (modDepth > 0f) modDepth * sin(phase[i]).toFloat() else 0f
            phase[i] += phaseInc[i]
            val d = lines[i].read(lens[i] - 1f + m)
            dd[i] = d
            lp[i] = (1f - damp) * d + damp * lp[i]
            vv[i] = lp[i] * gains[i]
            s += vv[i]
        }
        s *= 2f / n
        for (i in 0 until n) lines[i].write((vv[i] - s + inGain[i] * v).coerceIn(-8f, 8f))

        val wetL = (dd[0] + dd[2] + dd[4] + dd[6]) * 0.5f
        val wetR = (dd[1] + dd[3] + dd[5] + dd[7]) * 0.5f
        val mid = (wetL + wetR) * 0.5f
        val wl = mid + (wetL - mid) * width
        val wr = mid + (wetR - mid) * width
        outL = (1f - mix) * inL + mix * wl
        outR = (1f - mix) * inR + mix * wr
    }
}

/** Offline run: stereo output, length = input + tail. */
class WagnerEngine(private val p: WagnerParams) {

    fun outputFrames(inputFrames: Long, sampleRate: Int): Long = inputFrames + p.tailFrames(sampleRate)

    fun run(
        source: PcmSource,
        sink: SampleSink,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Long {
        p.validate()
        val ch = source.channels
        if (ch !in 1..2) throw LimitException("Only mono or stereo audio is supported.")
        if (source.frames <= 0L) throw InvalidParamException("The audio is empty.")
        val total = outputFrames(source.frames, source.sampleRate)
        val fx = WagnerFx(source.sampleRate, p)
        val block = 4096
        val inBuf = FloatArray(block * ch)
        val out = FloatArray(block * 2)
        var pos = 0L
        while (pos < total) {
            if (cancelled()) throw ProcessingCancelled()
            val m = minOf(block.toLong(), total - pos).toInt()
            source.read(pos, m, inBuf)
            for (f in 0 until m) {
                val l = inBuf[f * ch]
                val r = if (ch == 2) inBuf[f * 2 + 1] else l
                fx.process(l, r)
                out[2 * f] = if (fx.outL.isFinite()) fx.outL else 0f
                out[2 * f + 1] = if (fx.outR.isFinite()) fx.outR else 0f
            }
            sink.write(out, m)
            pos += m
            onProgress(minOf(1.0, pos.toDouble() / total).toFloat())
        }
        return total
    }
}
