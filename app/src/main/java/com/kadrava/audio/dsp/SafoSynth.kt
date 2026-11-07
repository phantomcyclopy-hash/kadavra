package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming granular synthesizer, used both for offline rendering and for the live player.
 * [render] produces [blockFrames] (or fewer, at the end) interleaved stereo frames. Grain times are absolute,
 * so the output does not depend on the block size. [params] may be replaced at any time from another
 * thread: it is picked up at the start of the next block.
 *
 * [energy]: per-slice level of the source (any length) used by PROBABILITY mode; scanned from [source]
 * when null. Only one thread may call [render].
 */
class SafoSynth(
    private val source: PcmSource,
    initial: SafoParams,
    private val totalFrames: Long,
    private val blockFrames: Int,
    energy: DoubleArray? = null,
    private val cancelled: () -> Boolean = { false },
) {
    @Volatile var params: SafoParams = initial
    /** Where the read offset was for the last grain, as a fraction of the whole source. */
    @Volatile var readFraction: Double = initial.loopStart
        private set
    @Volatile var peakL: Float = 0f
        private set
    @Volatile var peakR: Float = 0f
        private set
    /** Frames rendered so far. */
    var position: Long = 0L
        private set

    private val inCh = source.channels
    private val sr = source.sampleRate
    private val n = source.frames
    private val srcSeconds = n.toDouble() / sr

    private var energyData: DoubleArray? = energy
    private var cur: SafoParams = initial
    private val rng = XorShift64(initial.seed * 7919L + 13)

    // derived from params (see rebuild)
    private var meanGrain = 0.0
    private var meanGap = 1.0
    private var norm = 1f
    private var rs = 0.0
    private var re = 1.0
    private var regionSeconds = 1.0
    private var cdf: DoubleArray? = null
    private val envN = 2048
    private val shapeTab = FloatArray(envN)
    private val decayTab = FloatArray(envN)
    private val warpTab = FloatArray(envN)
    private var smoothL: OnePole? = null
    private var smoothR: OnePole? = null
    private var magic: MagicFx? = null
    private var magicKey = ""
    private var reverb: Reverb? = null
    private var burstCycle = 1

    // streaming state
    private val accFrames = blockFrames + 2 * sr + 8
    private val acc = FloatArray(accFrames * 2)
    private var tmp = FloatArray(0)
    private var blockStart = 0L
    private var nextBase = 0.0
    private var slot = 0L
    private var grainCount = 0L
    private val held = DoubleArray(6)
    private var jitter = 0.0
    private var fx = 0.0
    private var fy = 0.0
    private var fphase = 0.0

    init {
        if (source.channels !in 1..2) throw LimitException("Only mono or stereo audio is supported.")
        if (n <= 0L) throw InvalidParamException("The audio is empty.")
        initial.validate()
        fx = (rng.nextDouble() * 2 - 1) * 0.8
        fy = (rng.nextDouble() * 2 - 1) * 0.8
        rebuild(initial)
        nextBase = rng.nextDouble() * meanGap
    }

    private fun scanEnergy(): DoubleArray {
        val buckets = 1024
        val ms = DoubleArray(buckets)
        val cnt = LongArray(buckets)
        val blk = 32768
        val buf = FloatArray(blk * inCh)
        var pos = 0L
        while (pos < n) {
            if (cancelled()) throw ProcessingCancelled()
            val m = min(blk.toLong(), n - pos).toInt()
            source.read(pos, m, buf)
            for (f in 0 until m) {
                val b = (((pos + f) * buckets) / n).toInt().coerceIn(0, buckets - 1)
                var e = 0.0
                for (c in 0 until inCh) { val v = buf[f * inCh + c].toDouble(); if (v.isFinite()) e += v * v }
                ms[b] += e / inCh; cnt[b]++
            }
            pos += m
        }
        return DoubleArray(buckets) { if (cnt[it] > 0) sqrt(ms[it] / cnt[it]) else 0.0 }
    }

    private fun rebuild(p: SafoParams) {
        cur = p
        meanGrain = p.grainMs * sr / 1000.0
        meanGap = sr / p.densityHz
        burstCycle = p.burstGrains + p.burstSilence
        rs = p.loopStart; re = p.loopEnd
        regionSeconds = max(1e-3, (re - rs) * srcSeconds)

        // grain envelope tables: inner shape, exponential decay and the ramp time-warp, all over x in [0,1]
        val skew = 2.0.pow(-2.0 * p.ramp)
        for (i in 0 until envN) {
            val x = i.toDouble() / (envN - 1)
            shapeTab[i] = when (p.envShape) {
                EnvShape.HANN -> sin(PI * x).let { (it * it).toFloat() }
                EnvShape.TRIANGLE -> (1.0 - abs(2 * x - 1)).toFloat()
                EnvShape.GAUSS -> max(0.0, exp(-0.5 * ((x - 0.5) / 0.16).pow(2)) - 0.045).toFloat()
                EnvShape.RECT -> min(1.0, min(x / 0.08, (1 - x) / 0.08)).toFloat()
            }
            decayTab[i] = exp(-6.0 * p.decay * x).toFloat()
            warpTab[i] = x.pow(skew).toFloat()
        }
        var ms = 0.0
        for (i in 0 until envN) { val e = envValue(i, p.envCycles, p.envPhase); ms += e.toDouble() * e }
        ms /= envN
        norm = (1.0 / sqrt(max(0.3, p.densityHz * p.grainMs / 1000.0 * ms))).toFloat()

        if (p.smoothHz < 19999.0) {
            val l = smoothL ?: OnePole.forCutoff(p.smoothHz, sr).also { smoothL = it }
            val r = smoothR ?: OnePole.forCutoff(p.smoothHz, sr).also { smoothR = it }
            l.setCutoff(p.smoothHz, sr); r.setCutoff(p.smoothHz, sr)
        } else { smoothL = null; smoothR = null }

        val key = "${p.magic}:${p.magicAmount}"
        if (key != magicKey) {
            magicKey = key
            magic = if (p.magic != MagicEffect.OFF && p.magicAmount > 0.0) MagicFx(p.magic, p.magicAmount.toFloat(), sr, p.seed) else null
        }
        if (p.reverbAmount > 0.0 && reverb == null) reverb = Reverb(sr)

        cdf = null
        if (p.mode == SafoMode.PROBABILITY) {
            val e = energyData ?: scanEnergy().also { energyData = it }
            val gamma = 3.0 * p.shape
            val c = DoubleArray(e.size)
            var accum = 0.0
            for (b in e.indices) {
                val lo = b.toDouble() / e.size; val hi = (b + 1).toDouble() / e.size
                val inside = hi > rs && lo < re
                accum += if (inside) (e[b] + 1e-9).pow(gamma) else 0.0
                c[b] = accum
            }
            if (accum > 0.0) for (b in c.indices) c[b] /= accum
            cdf = c
        }
    }

    private fun envValue(xi: Int, cycles: Int, phase: Double): Float {
        var u = warpTab[xi] * cycles + phase.toFloat()
        u -= floor(u)
        return shapeTab[(u * (envN - 1)).toInt()] * decayTab[xi]
    }

    private fun refresh(p: SafoParams) {
        for (i in held.indices) held[i] = rng.nextDouble()
        if (p.pitchJitter > 0.0) jitter = (jitter + p.pitchJitter * 0.3 * (rng.nextDouble() * 2 - 1)).coerceIn(-p.pitchJitter, p.pitchJitter)
        else jitter = 0.0
        if (p.mode == SafoMode.VECTOR_FIELD) {
            val dt = 0.03 + 0.3 * p.shape
            val k = 1.5 * p.shape
            val r2 = fx * fx + fy * fy
            val dx = fx * (1 - r2) - fy + k * sin(3 * fy + fphase)
            val dy = fy * (1 - r2) + fx + k * sin(3 * fx - fphase)
            fx = (fx + dt * dx).coerceIn(-1.5, 1.5)
            fy = (fy + dt * dy).coerceIn(-1.5, 1.5)
            fphase += 0.1
        }
    }

    private fun addGrain(base: Double) {
        val p = cur
        if (grainCount % p.hold == 0L) refresh(p)
        grainCount++
        val onset = base + p.deviation * meanGap * rng.nextDouble()
        val gLen = max(8, Math.round(meanGrain).toInt())
        var semis = p.pitch + p.pitchSpread * (2 * held[2] - 1) + jitter

        // read position inside the loop, as a fraction u of the loop length
        var u: Double
        var backwards = false
        when (p.mode) {
            SafoMode.STOCHASTIC -> {
                val d = p.speed * (base / sr) / regionSeconds
                if (p.loopMode == LoopMode.WRAP) {
                    u = d - floor(d)
                } else {
                    var m = d % 2.0
                    if (m < 0) m += 2.0
                    if (m < 1.0) u = m else { u = 2.0 - m; backwards = true }
                    if (p.speed < 0) backwards = !backwards
                }
            }
            SafoMode.PROBABILITY -> {
                val c = cdf!!
                var lo = 0; var hi = c.size - 1
                while (lo < hi) { val mid = (lo + hi) ushr 1; if (c[mid] < held[0]) lo = mid + 1 else hi = mid }
                val f = ((lo + held[1]) / c.size).coerceIn(rs, re)
                u = (f - rs) / (re - rs)
            }
            SafoMode.VECTOR_FIELD -> {
                u = ((fx + 1) / 2).coerceIn(0.0, 1.0)
                semis += fy * 7.0
            }
        }
        if (p.rndRead > 0.0) {
            u += p.rndRead * (2 * held[4] - 1) * 0.5
            u -= floor(u)
        }
        u = u.coerceIn(0.0, 0.9999999)
        val frac = rs + u * (re - rs)
        readFraction = frac
        val center = frac * n

        val reverseRead = held[5] < p.reverse || (p.loopMode == LoopMode.PING_PONG_REV && p.mode == SafoMode.STOCHASTIC && backwards)
        val r0 = 2.0.pow(semis.coerceIn(-48.0, 48.0) / 12.0)
        val gl = p.gliss / 12.0
        val step = if (gLen > 1) 2.0.pow(gl / (gLen - 1)) else 1.0
        val span = if (abs(step - 1.0) < 1e-9) gLen * r0 else r0 * (step.pow(gLen.toDouble()) - 1) / (step - 1)
        val startSrc = center - span / 2
        val readStart = floor(startSrc).toLong() - 1
        val readCount = ceil(span).toInt() + 5
        if (tmp.size < readCount * inCh) tmp = FloatArray(readCount * inCh)
        source.read(readStart, readCount, tmp)

        val pan = p.panning * (2 * held[3] - 1)
        val ang = (pan + 1) * PI / 4
        val gainL = (cos(ang) * 1.4142135).toFloat()
        val gainR = (sin(ang) * 1.4142135).toFloat()
        val o = (floor(onset).toLong() - blockStart).toInt()
        val phase = p.envPhase
        val cycles = p.envCycles
        var cum = 0.0
        var ratio = r0
        for (k in 0 until gLen) {
            val rel = (if (reverseRead) startSrc + span - cum else startSrc + cum) - readStart
            cum += ratio
            ratio *= step
            val i0 = floor(rel).toInt()
            if (i0 < 0 || i0 + 1 >= readCount) continue
            val fr = (rel - i0).toFloat()
            val xi = if (gLen > 1) (k.toLong() * (envN - 1) / (gLen - 1)).toInt() else 0
            val w = envValue(xi, cycles, phase) * norm
            val dst = (o + k) * 2
            if (dst + 2 > acc.size) break
            val vl = tmp[i0 * inCh] * (1 - fr) + tmp[(i0 + 1) * inCh] * fr
            val vr = if (inCh == 2) tmp[i0 * 2 + 1] * (1 - fr) + tmp[(i0 + 1) * 2 + 1] * fr else vl
            acc[dst] += vl * w * gainL
            acc[dst + 1] += vr * w * gainR
        }
    }

    /** Renders [frames] (<= block size) interleaved stereo frames into [out]. */
    fun render(out: FloatArray, frames: Int, frozen: Boolean) {
        require(frames in 0..blockFrames)
        val p = params
        if (p !== cur) rebuild(p)
        val limit = blockStart + blockFrames
        while (nextBase < limit && nextBase < totalFrames) {
            if (slot % burstCycle < cur.burstGrains) addGrain(nextBase)
            slot++
            nextBase += meanGap
        }
        val c = cur
        val revAmt = c.reverbAmount.toFloat()
        var pl = 0f; var pr = 0f
        for (i in 0 until frames) {
            var l = acc[2 * i]; var r = acc[2 * i + 1]
            if (!l.isFinite()) l = 0f
            if (!r.isFinite()) r = 0f
            val sl = smoothL; val sr2 = smoothR
            if (sl != null && sr2 != null) { l = sl.process(l); r = sr2.process(r) }
            val mg = magic
            if (mg != null) { mg.process(l, r); l = mg.outL; r = mg.outR }
            val rv = reverb
            if (rv != null && revAmt > 0f) {
                rv.process(l, r, revAmt, frozen)
                l += 3f * revAmt * rv.outL
                r += 3f * revAmt * rv.outR
            }
            out[2 * i] = l; out[2 * i + 1] = r
            if (abs(l) > pl) pl = abs(l)
            if (abs(r) > pr) pr = abs(r)
        }
        peakL = pl; peakR = pr
        System.arraycopy(acc, blockFrames * 2, acc, 0, (accFrames - blockFrames) * 2)
        java.util.Arrays.fill(acc, (accFrames - blockFrames) * 2, acc.size, 0f)
        blockStart += blockFrames
        position += frames
    }
}
