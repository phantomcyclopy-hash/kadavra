package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

enum class DistShape(val label: String) {
    OFF("off"), SOFT("soft"), HARD("hard"), ASYM("asym"), FOLD("fold"), CRUSH("crush");

    fun next(): DistShape = entries[(ordinal + 1) % entries.size]
}

/**
 * Heisenberg: delay and distortion in one stream effect.
 * Delay: [delayMs], [feedback] (max 0.95, the loop is also clamped), [delayMix], optional [pingPong]; the
 * feedback path has a low-pass ([fbLowHz], 20000 = off), a high-pass ([fbHighHz], 20 = off) and a pitch
 * shifter ([shift] semitones, applied again on every repeat). [tailSeconds] extends the output so the repeats ring out.
 * Distortion: [shape] with [drive] (0..30 dB), [distMix], [levelDb]; CRUSH uses [bits] and [rate] (sample hold).
 * [distAfter] puts the distortion after the delay instead of before it. No oversampling: HARD, FOLD and CRUSH alias.
 */
data class HeisenbergParams(
    val delayMs: Double = 350.0,
    val feedback: Double = 0.4,
    val delayMix: Double = 0.4,
    val pingPong: Boolean = false,
    val fbLowHz: Double = 20000.0,
    val fbHighHz: Double = 20.0,
    val shift: Double = 0.0,
    val tailSeconds: Double = 2.0,
    val shape: DistShape = DistShape.OFF,
    val drive: Double = 0.3,
    val distMix: Double = 1.0,
    val levelDb: Double = 0.0,
    val bits: Int = 8,
    val rate: Int = 1,
    val distAfter: Boolean = false,
) {
    val isNoOp: Boolean get() = delayMix == 0.0 && shape == DistShape.OFF

    fun validate() {
        fun r(name: String, v: Double, lo: Double, hi: Double) {
            if (!v.isFinite() || v < lo || v > hi) throw InvalidParamException("$name must be between $lo and $hi (got $v).")
        }
        r("delay time (ms)", delayMs, 1.0, 2000.0)
        r("feedback", feedback, 0.0, 0.95)
        r("delay mix", delayMix, 0.0, 1.0)
        r("feedback low-pass (Hz)", fbLowHz, 200.0, 20000.0)
        r("feedback high-pass (Hz)", fbHighHz, 20.0, 2000.0)
        r("shift (st)", shift, -12.0, 12.0)
        r("tail (s)", tailSeconds, 0.0, 10.0)
        r("drive", drive, 0.0, 1.0)
        r("distortion mix", distMix, 0.0, 1.0)
        r("level (dB)", levelDb, -24.0, 12.0)
        if (bits < 1 || bits > 16) throw InvalidParamException("bits must be between 1 and 16 (got $bits).")
        if (rate < 1 || rate > 64) throw InvalidParamException("rate must be between 1 and 64 (got $rate).")
    }

    fun tailFrames(sampleRate: Int): Long = Math.round(tailSeconds * sampleRate)
}

/** Classic two-tap rotating-delay pitch shifter (used inside the delay's feedback path). */
class PitchShifter(sampleRate: Int) {
    private val window = (0.06 * sampleRate).toInt().coerceAtLeast(64)
    private val buf = FloatArray(window * 2 + 8)
    private var pos = 0
    private var phase = 0.0

    private fun read(d: Double): Float {
        val rp = pos - 1 - d
        val fl = floor(rp)
        val fr = (rp - fl).toFloat()
        val i0 = ((fl.toInt() % buf.size) + buf.size) % buf.size
        val i1 = (i0 + 1) % buf.size
        return buf[i0] * (1 - fr) + buf[i1] * fr
    }

    fun process(x: Float, ratio: Double): Float {
        buf[pos] = x
        pos = (pos + 1) % buf.size
        val p1 = phase
        val p2 = (phase + 0.5) % 1.0
        val g1 = sin(PI * p1).let { (it * it).toFloat() }
        val g2 = sin(PI * p2).let { (it * it).toFloat() }
        val y = read(p1 * window) * g1 + read(p2 * window) * g2
        phase = ((phase + (1.0 - ratio) / window) % 1.0 + 1.0) % 1.0
        return y
    }
}

/** The per-sample effect, stereo in/out through [outL]/[outR]. Safe to drive live: [setParams] may be called between samples. */
class HeisenbergFx(private val sr: Int, initial: HeisenbergParams) {
    var outL = 0f
        private set
    var outR = 0f
        private set

    private var p = initial
    private val lineL = DelayLine((2.0 * sr).toInt() + 8)
    private val lineR = DelayLine((2.0 * sr).toInt() + 8)
    private val lpL = OnePole.forCutoff(20000.0, sr)
    private val lpR = OnePole.forCutoff(20000.0, sr)
    private val hpL = OnePole.forCutoff(20.0, sr)
    private val hpR = OnePole.forCutoff(20.0, sr)
    private val psL = PitchShifter(sr)
    private val psR = PitchShifter(sr)
    private var gain = 1f
    private var outGain = 1f
    private var ratio = 1.0
    private var quantSteps = 128f
    private val held = FloatArray(2)
    private val holdLeft = IntArray(2)
    private val dcX = FloatArray(2)
    private val dcY = FloatArray(2)

    init { setParams(initial) }

    fun setParams(np: HeisenbergParams) {
        p = np
        lpL.setCutoff(np.fbLowHz, sr); lpR.setCutoff(np.fbLowHz, sr)
        hpL.setCutoff(np.fbHighHz, sr); hpR.setCutoff(np.fbHighHz, sr)
        gain = 10.0.pow(np.drive * 30.0 / 20.0).toFloat()
        outGain = 10.0.pow(np.levelDb / 20.0).toFloat()
        ratio = 2.0.pow(np.shift / 12.0)
        quantSteps = 2.0.pow(np.bits - 1.0).toFloat()
    }

    private fun fold(v: Float): Float {
        var t = (v + 1f) % 4f
        if (t < 0f) t += 4f
        return 1f - abs(t - 2f)
    }

    private fun dist(x: Float, ch: Int): Float {
        val c = p
        if (c.shape == DistShape.OFF) return x
        val v = x * gain
        var y = when (c.shape) {
            DistShape.SOFT -> tanh(v.toDouble()).toFloat()
            DistShape.HARD -> v.coerceIn(-1f, 1f)
            DistShape.ASYM -> if (v >= 0f) tanh(v.toDouble()).toFloat() else tanh(v * 0.4).toFloat()
            DistShape.FOLD -> fold(v)
            DistShape.CRUSH -> {
                if (holdLeft[ch] <= 0) {
                    held[ch] = (v.coerceIn(-1f, 1f) * quantSteps).roundToInt() / quantSteps
                    holdLeft[ch] = c.rate
                }
                holdLeft[ch]--
                held[ch]
            }
            DistShape.OFF -> x
        }
        if (c.shape == DistShape.ASYM) {   // asymmetric shaping adds DC: remove it
            val out = y - dcX[ch] + 0.995f * dcY[ch]
            dcX[ch] = y; dcY[ch] = out
            y = out
        }
        return x * (1f - c.distMix.toFloat()) + y * c.distMix.toFloat()
    }

    private fun feedbackPath(d: Float, lp: OnePole, hp: OnePole, ps: PitchShifter): Float {
        var f = d
        if (p.fbLowHz < 19999.0) f = lp.process(f)
        if (p.fbHighHz > 20.5) f -= hp.process(f)
        if (p.shift != 0.0) f = ps.process(f, ratio)
        return f.coerceIn(-4f, 4f) * p.feedback.toFloat()
    }

    fun process(inL: Float, inR: Float) {
        var l = inL
        var r = inR
        if (p.shape != DistShape.OFF && !p.distAfter) { l = dist(l, 0); r = dist(r, 1) }

        val d = (p.delayMs * sr / 1000.0).toFloat()
        val dl = lineL.read(d - 1f)
        val dr = lineR.read(d - 1f)
        val fl = feedbackPath(dl, lpL, hpL, psL)
        val fr = feedbackPath(dr, lpR, hpR, psR)
        if (p.pingPong) {
            lineL.write((l + r) * 0.5f + fr)
            lineR.write(fl)
        } else {
            lineL.write(l + fl)
            lineR.write(r + fr)
        }
        val mix = p.delayMix.toFloat()
        var oL = l + mix * dl
        var oR = r + mix * dr

        if (p.shape != DistShape.OFF && p.distAfter) { oL = dist(oL, 0); oR = dist(oR, 1) }
        outL = oL * outGain
        outR = oR * outGain
    }
}

/** Offline run: output is always stereo, length = input + tail. */
class HeisenbergEngine(private val p: HeisenbergParams) {

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
        val fx = HeisenbergFx(source.sampleRate, p)
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
