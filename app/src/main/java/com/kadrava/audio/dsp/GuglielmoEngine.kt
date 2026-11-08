package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Guglielmo: a generator of noise and drones (it needs no input audio).
 * - BANK: noise through a bank of [modes] resonant band-pass filters at the partials of [rootHz]
 *   (stiff-string stretching with [stretch], resonance [q]); [noiseColor] goes from white to brown.
 * - WALL: [wallCount] micro-oscillators detuned by up to [wallSpread] cents around the harmonics, each with its own
 *   slow amplitude drift.
 * - CHAOS: a Lorenz attractor (speed [chaosRate]) bends the bank frequencies by up to +-3 semitones x [chaos] and
 *   swells the wall.
 * - FEEDBACK: a cross-fed saturating delay loop (no-input-mixing style) blended in by [feedback].
 * Deterministic for a [seed]; always 44.1 kHz stereo; the output is bounded by a tanh limiter.
 */
data class GuglielmoParams(
    val durationSeconds: Double = 30.0,
    val rootHz: Double = 55.0,
    val modes: Int = 8,
    val stretch: Double = 0.1,
    val q: Double = 200.0,
    val noiseColor: Double = 0.3,
    val noiseLevel: Double = 0.8,
    val wallCount: Int = 32,
    val wallLevel: Double = 0.3,
    val wallSpread: Double = 15.0,
    val chaos: Double = 0.3,
    val chaosRate: Double = 0.2,
    val feedback: Double = 0.0,
    val width: Double = 0.7,
    val levelDb: Double = 0.0,
    val fadeSeconds: Double = 2.0,
    val seed: Long = 1L,
) {
    fun validate() {
        fun r(name: String, v: Double, lo: Double, hi: Double) {
            if (!v.isFinite() || v < lo || v > hi) throw InvalidParamException("$name must be between $lo and $hi (got $v).")
        }
        r("length (s)", durationSeconds, 1.0, 600.0)
        r("root (Hz)", rootHz, 20.0, 2000.0)
        if (modes < 1 || modes > 16) throw InvalidParamException("modes must be between 1 and 16 (got $modes).")
        r("stretch", stretch, 0.0, 1.0)
        r("q", q, 5.0, 1000.0)
        r("noise color", noiseColor, 0.0, 1.0)
        r("noise level", noiseLevel, 0.0, 1.0)
        if (wallCount < 0 || wallCount > 128) throw InvalidParamException("wall count must be between 0 and 128 (got $wallCount).")
        r("wall level", wallLevel, 0.0, 1.0)
        r("wall spread (cents)", wallSpread, 0.0, 100.0)
        r("chaos", chaos, 0.0, 1.0)
        r("chaos rate", chaosRate, 0.01, 10.0)
        r("feedback", feedback, 0.0, 1.0)
        r("width", width, 0.0, 1.0)
        r("level (dB)", levelDb, -24.0, 6.0)
        r("fade (s)", fadeSeconds, 0.0, 10.0)
    }

    fun outputFrames(): Long = Math.round(durationSeconds * GuglielmoEngine.SAMPLE_RATE)
}

class GuglielmoEngine(private val p: GuglielmoParams) {

    fun generate(
        sink: SampleSink,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Long {
        p.validate()
        val sr = SAMPLE_RATE
        val total = p.outputFrames()
        val rng = XorShift64(p.seed * 6007L + 11)

        // ---- bank
        val nm = p.modes
        val baseF = DoubleArray(nm) { val k = it + 1; p.rootHz * k * sqrt(1.0 + p.stretch * 0.01 * k * k) }
        val active = BooleanArray(nm) { baseF[it] < 0.45 * sr }
        val modeAmp = DoubleArray(nm) { k ->
            val comp = 1.0 / sqrt(PI * baseF[k] / (p.q * sr))
            (k + 1.0).pow(-0.7) * comp * BANK_SCALE * p.noiseLevel
        }
        val modeGainL = FloatArray(nm)
        val modeGainR = FloatArray(nm)
        for (k in 0 until nm) {
            val side = if (k % 2 == 0) -1.0 else 1.0
            val pan = (side * p.width * (0.3 + 0.7 * rng.nextDouble())).coerceIn(-1.0, 1.0)
            val ang = (pan + 1) * PI / 4
            modeGainL[k] = (cos(ang) * 1.4142135).toFloat()
            modeGainR[k] = (sin(ang) * 1.4142135).toFloat()
        }
        val b0 = DoubleArray(nm); val a1 = DoubleArray(nm); val a2 = DoubleArray(nm)
        val z1 = DoubleArray(nm); val z2 = DoubleArray(nm)

        // ---- wall of micro-oscillators (complex rotation phasors)
        val wc = p.wallCount
        val wRe = DoubleArray(wc); val wIm = DoubleArray(wc)
        val wCos = DoubleArray(wc); val wSin = DoubleArray(wc)
        val wPanL = FloatArray(wc); val wPanR = FloatArray(wc)
        val wLfoPhase = DoubleArray(wc); val wLfoRate = DoubleArray(wc)
        val wAmp = DoubleArray(wc); val wAmpStep = DoubleArray(wc)
        val wOn = BooleanArray(wc)
        for (i in 0 until wc) {
            val h = 1 + (rng.nextDouble() * rng.nextDouble() * 15).toInt()
            val detune = (rng.nextDouble() * 2 - 1) * p.wallSpread
            val f = p.rootHz * h * 2.0.pow(detune / 1200.0)
            wOn[i] = f < 0.45 * sr
            val ph = rng.nextDouble() * 2 * PI
            wRe[i] = cos(ph); wIm[i] = sin(ph)
            wCos[i] = cos(2 * PI * f / sr); wSin[i] = sin(2 * PI * f / sr)
            val pan = (rng.nextDouble() * 2 - 1) * p.width
            val ang = (pan + 1) * PI / 4
            wPanL[i] = (cos(ang) * 1.4142135).toFloat(); wPanR[i] = (sin(ang) * 1.4142135).toFloat()
            wLfoPhase[i] = rng.nextDouble() * 2 * PI
            wLfoRate[i] = 0.02 + 0.3 * rng.nextDouble()
        }
        val wallScale = if (wc > 0) WALL_SCALE * p.wallLevel / sqrt(wc.toDouble()) else 0.0

        // ---- chaos (Lorenz), stepped at control rate
        var lx = 1.0 + 0.1 * rng.nextDouble(); var ly = 1.0; var lz = 1.0
        val dt = p.chaosRate * CONTROL / sr
        fun stepLorenz() {
            val dx = 10.0 * (ly - lx)
            val dy = lx * (28.0 - lz) - ly
            val dz = lx * ly - (8.0 / 3.0) * lz
            lx += dt * dx; ly += dt * dy; lz += dt * dz
        }
        repeat(3000) { stepLorenz() }

        // ---- noise colour and feedback loop
        var brown = 0.0
        val lineL = DelayLine((0.06 * sr).toInt() + 8)
        val lineR = DelayLine((0.06 * sr).toInt() + 8)
        val fbOn = p.feedback > 0.0
        val fbGain = (0.5 + 0.495 * p.feedback).toFloat()
        val fb = p.feedback.toFloat()
        val dL = (0.037 * sr).toFloat()
        val dR = (0.053 * sr).toFloat()

        val master = 10.0.pow(p.levelDb / 20.0)
        val fadeFrames = (p.fadeSeconds * sr).toLong()
        val color = p.noiseColor
        val noiseOn = p.noiseLevel > 0.0

        val block = 4096
        val out = FloatArray(block * 2)
        var pos = 0L
        var modNx = 0.0; var modNy = 0.0; var wallSwell = 1.0

        while (pos < total) {
            if (cancelled()) throw ProcessingCancelled()
            val m = min(block.toLong(), total - pos).toInt()
            for (f in 0 until m) {
                val t = pos + f
                if ((t % CONTROL) == 0L) {
                    stepLorenz()
                    modNx = (lx / 20.0).coerceIn(-1.5, 1.5)
                    modNy = (ly / 25.0).coerceIn(-1.5, 1.5)
                    wallSwell = max(0.0, 1.0 + p.chaos * 0.8 * ((lz - 25.0) / 25.0).coerceIn(-1.5, 1.5))
                    for (k in 0 until nm) {
                        if (!active[k]) continue
                        val mod = 2.0.pow(p.chaos * 3.0 / 12.0 * (if (k % 2 == 0) modNx else modNy))
                        val fr = min(baseF[k] * mod, 0.45 * sr)
                        val w = 2 * PI * fr / sr
                        val al = sin(w) / (2 * p.q)
                        val a0 = 1 + al
                        b0[k] = al / a0
                        a1[k] = -2 * cos(w) / a0
                        a2[k] = (1 - al) / a0
                    }
                    for (i in 0 until wc) {
                        wLfoPhase[i] += 2 * PI * wLfoRate[i] * CONTROL / sr
                        val target = 0.5 + 0.5 * sin(wLfoPhase[i])
                        wAmpStep[i] = (target - wAmp[i]) / CONTROL
                        val mag = sqrt(wRe[i] * wRe[i] + wIm[i] * wIm[i])
                        if (mag > 0.0) { wRe[i] /= mag; wIm[i] /= mag }
                    }
                }
                var l = 0f
                var r = 0f
                if (noiseOn) {
                    val white = (rng.nextDouble() * 2 - 1) * 1.7320508
                    brown = brown * 0.995 + 0.08 * white
                    val v = (1 - color) * white + color * (brown / 0.801)
                    for (k in 0 until nm) {
                        if (!active[k]) continue
                        val o = b0[k] * v + z1[k]
                        z1[k] = -a1[k] * o + z2[k]
                        z2[k] = -b0[k] * v - a2[k] * o
                        val s = (modeAmp[k] * o).toFloat()
                        l += s * modeGainL[k]
                        r += s * modeGainR[k]
                    }
                }
                if (wc > 0 && wallScale > 0.0) {
                    var wl = 0.0
                    var wr = 0.0
                    for (i in 0 until wc) {
                        if (!wOn[i]) continue
                        val re = wRe[i] * wCos[i] - wIm[i] * wSin[i]
                        val im = wRe[i] * wSin[i] + wIm[i] * wCos[i]
                        wRe[i] = re; wIm[i] = im
                        wAmp[i] += wAmpStep[i]
                        val s = im * wAmp[i]
                        wl += s * wPanL[i]
                        wr += s * wPanR[i]
                    }
                    l += (wl * wallScale * wallSwell).toFloat()
                    r += (wr * wallScale * wallSwell).toFloat()
                }
                if (fbOn) {
                    val dl = lineL.read(dL - 1f)
                    val dr = lineR.read(dR - 1f)
                    val yl = tanh(1.6 * (0.5 * l + fbGain * dr)).toFloat()
                    val yr = tanh(1.6 * (0.5 * r + fbGain * dl)).toFloat()
                    lineL.write(yl); lineR.write(yr)
                    l = (1f - fb) * l + fb * yl * 0.8f
                    r = (1f - fb) * r + fb * yr * 0.8f
                }
                var env = 1.0
                if (fadeFrames > 0) {
                    val fin = min(1.0, t.toDouble() / fadeFrames)
                    val fout = min(1.0, (total - 1 - t).toDouble() / fadeFrames)
                    env = 0.5 - 0.5 * cos(PI * min(fin, fout))
                }
                val g = master * env
                out[2 * f] = tanh(l * g).toFloat()
                out[2 * f + 1] = tanh(r * g).toFloat()
            }
            sink.write(out, m)
            pos += m
            onProgress(min(1.0, pos.toDouble() / total).toFloat())
        }
        return total
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val CONTROL = 64
        private const val BANK_SCALE = 0.25
        private const val WALL_SCALE = 0.5
    }
}
