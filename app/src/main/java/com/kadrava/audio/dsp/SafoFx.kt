package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/** One-pole low-pass (the "smoothing" filter): y += a (x - y). */
class OnePole(private var a: Float) {
    private var y = 0f
    fun process(x: Float): Float { y += a * (x - y); return y }
    fun setCutoff(cutoffHz: Double, sampleRate: Int) { a = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat() }

    companion object {
        fun forCutoff(cutoffHz: Double, sampleRate: Int) =
            OnePole((1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat())
    }
}

/** Circular delay with linear-interpolated read; call [write] first, then [read] (0 = the sample just written). */
class DelayLine(size: Int) {
    private val buf = FloatArray(max(4, size))
    private var pos = 0

    fun write(x: Float) { buf[pos] = x; pos = (pos + 1) % buf.size }

    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(0f, (buf.size - 2).toFloat())
        val rp = pos - 1 - d
        val fl = floor(rp)
        val frac = rp - fl
        val i0 = ((fl.toInt() % buf.size) + buf.size) % buf.size
        val i1 = (i0 + 1) % buf.size
        return buf[i0] * (1 - frac) + buf[i1] * frac
    }
}

/**
 * Freeverb-style stereo reverb: 4 damped combs + 2 allpasses per channel. [amount] sets decay (feedback
 * 0.70..0.98). When [frozen] the input is muted and the feedback is 0.9995 with no damping, so the tail
 * is held (decays over minutes). Output is the wet signal only, in [outL]/[outR].
 */
class Reverb(sampleRate: Int) {
    var outL = 0f
        private set
    var outR = 0f
        private set

    private val k = sampleRate / 44100.0
    private val combSizes = intArrayOf(1116, 1188, 1277, 1356)
    private val allSizes = intArrayOf(556, 441)
    private val combs = Array(2) { ch -> Array(4) { FloatArray(max(1, Math.round((combSizes[it] + ch * 23) * k).toInt())) } }
    private val combIdx = Array(2) { IntArray(4) }
    private val combFilt = Array(2) { FloatArray(4) }
    private val alls = Array(2) { ch -> Array(2) { FloatArray(max(1, Math.round((allSizes[it] + ch * 23) * k).toInt())) } }
    private val allIdx = Array(2) { IntArray(2) }

    fun process(inL: Float, inR: Float, amount: Float, frozen: Boolean) {
        val fb = if (frozen) 0.9995f else 0.7f + 0.28f * amount
        val damp = if (frozen) 0f else 0.35f
        val gin = if (frozen) 0f else 0.015f * (inL + inR)
        for (ch in 0 until 2) {
            var acc = 0f
            for (i in 0 until 4) {
                val buf = combs[ch][i]
                val idx = combIdx[ch][i]
                val y = buf[idx]
                combFilt[ch][i] = y * (1 - damp) + combFilt[ch][i] * damp
                buf[idx] = gin + combFilt[ch][i] * fb
                combIdx[ch][i] = (idx + 1) % buf.size
                acc += y
            }
            for (i in 0 until 2) {
                val buf = alls[ch][i]
                val idx = allIdx[ch][i]
                val b = buf[idx]
                val o = -acc + b
                buf[idx] = acc + b * 0.5f
                allIdx[ch][i] = (idx + 1) % buf.size
                acc = o
            }
            if (ch == 0) outL = acc else outR = acc
        }
    }
}

/** The "Magic" panel: one effect chosen, one amount control. amount 0 is an exact bypass. */
enum class MagicEffect(val label: String) {
    OFF("off"), CHORUS("chorus"), TREMOLO("tremolo"), HAAS("haas"), PHASED_DELAY("delay-fase"), STUTTER_VIBRATO("vibrato");

    fun next(): MagicEffect = entries[(ordinal + 1) % entries.size]
}

class MagicFx(private val effect: MagicEffect, private val amount: Float, private val sr: Int, seed: Long) {
    var outL = 0f
        private set
    var outR = 0f
        private set

    private val rng = XorShift64(seed * 104729L + 5)
    private val dl = DelayLine((sr * 0.06).toInt() + 8)
    private val dr = DelayLine((sr * 0.06).toInt() + 8)
    private var n = 0L
    private var held = 0f
    private var holdLeft = 0
    private var heldDelay = 0.003f

    fun process(inL: Float, inR: Float) {
        val t = n.toDouble() / sr
        n++
        val a = amount
        when {
            effect == MagicEffect.OFF || a <= 0f -> { outL = inL; outR = inR }
            effect == MagicEffect.CHORUS -> {
                dl.write(inL); dr.write(inR)
                val ms = sr / 1000f
                val l = dl.read((12f + 4f * sin(2 * PI * 0.35 * t).toFloat()) * ms)
                val r = dr.read((12f + 4f * sin(2 * PI * 0.43 * t + 1.3).toFloat()) * ms)
                outL = (1 - 0.5f * a) * inL + 0.5f * a * l
                outR = (1 - 0.5f * a) * inR + 0.5f * a * r
            }
            effect == MagicEffect.TREMOLO -> {
                val g = 1f - a * (0.5f + 0.5f * tanh(3.0 * sin(2 * PI * 7.0 * t)).toFloat())
                outL = inL * g; outR = inR * g
            }
            effect == MagicEffect.HAAS -> {
                dr.write(inR)
                outL = inL
                outR = dr.read(Math.round(a * 0.02f * sr).toFloat())
            }
            effect == MagicEffect.PHASED_DELAY -> {
                if (holdLeft <= 0) { heldDelay = (0.001f + 0.011f * rng.nextDouble().toFloat()); holdLeft = (0.09 * sr).toInt() }
                holdLeft--
                val d = heldDelay * sr
                val fl = dl.read(d); val fr = dr.read(d)
                outL = inL + a * 0.7f * fl; outR = inR + a * 0.7f * fr
                dl.write(inL + a * 0.5f * fl); dr.write(inR + a * 0.5f * fr)
            }
            else -> { // STUTTER_VIBRATO: stepped (sample & hold) modulation of a short delay
                if (holdLeft <= 0) { held = (rng.nextDouble() * 2 - 1).toFloat(); holdLeft = (sr / 12.0).toInt() }
                holdLeft--
                dl.write(inL); dr.write(inR)
                val d = (0.006f + a * 0.004f * held) * sr
                outL = inL * (1 - a) + dl.read(d) * a
                outR = inR * (1 - a) + dr.read(d) * a
            }
        }
    }
}
