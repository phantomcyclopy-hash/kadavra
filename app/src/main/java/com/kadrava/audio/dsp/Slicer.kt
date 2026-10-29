package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.cos

data class SliceParams(
    /** Number of Slices the audio is cut into. */
    val factor: Int = 8,
    /** 0 = equal-size Slices; 1 = sizes vary by up to +-90% around the mean. */
    val sizeVariation: Double = 0.3,
    /** The randomiser: reorders the Slices (seeded, repeatable). */
    val shuffle: Boolean = true,
    val seed: Long = 1L,
    /** Declick fade at discontinuous joins (a fade, not a crossfade). */
    val fadeMs: Double = 5.0,
) {
    fun validate() {
        if (factor < MIN_FACTOR || factor > MAX_FACTOR)
            throw InvalidParamException("factor deve estar entre $MIN_FACTOR e $MAX_FACTOR (recebido $factor).")
        if (!sizeVariation.isFinite() || sizeVariation < 0.0 || sizeVariation > 1.0)
            throw InvalidParamException("variação de tamanho deve estar entre 0 e 1 (recebido $sizeVariation).")
        if (!fadeMs.isFinite() || fadeMs < 0.0 || fadeMs > 50.0)
            throw InvalidParamException("fade deve estar entre 0 e 50 ms (recebido $fadeMs).")
    }

    companion object {
        const val MIN_FACTOR = 2
        const val MAX_FACTOR = 512
    }
}

/** Output slot j plays source frames [srcStart[j], srcStart[j]+length[j]) at output position outStart[j]. */
class SlicePlan(
    val srcStart: LongArray,
    val length: LongArray,
    val outStart: LongArray,
    val fadeIn: BooleanArray,
    val fadeOut: BooleanArray,
    val fadeFrames: Int,
    val totalFrames: Long,
) {
    val slices: Int get() = srcStart.size
}

object Slicer {
    fun plan(totalFrames: Long, sampleRate: Int, p: SliceParams): SlicePlan {
        p.validate()
        if (totalFrames <= 0L) throw InvalidParamException("O áudio está vazio.")
        val n = minOf(p.factor.toLong(), totalFrames).toInt()
        val rng = XorShift64(p.seed * 1315423911L + 7)

        val w = DoubleArray(n) { 1.0 + p.sizeVariation * 0.9 * (rng.nextDouble() * 2.0 - 1.0) }
        var sum = 0.0
        for (x in w) sum += x
        val bounds = LongArray(n + 1)
        var cum = 0.0
        for (i in 1 until n) {
            cum += w[i - 1]
            val b = Math.round(totalFrames * (cum / sum))
            bounds[i] = b.coerceIn(bounds[i - 1] + 1, totalFrames - (n - i))
        }
        bounds[n] = totalFrames

        val order = IntArray(n) { it }
        if (p.shuffle && n >= 2) {
            for (i in n - 1 downTo 1) {
                val j = (rng.nextDouble() * (i + 1)).toInt().coerceAtMost(i)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            var identity = true
            for (i in 0 until n) if (order[i] != i) { identity = false; break }
            if (identity) { val t = order[0]; order[0] = order[1]; order[1] = t }
        }

        val srcStart = LongArray(n) { bounds[order[it]] }
        val length = LongArray(n) { bounds[order[it] + 1] - bounds[order[it]] }
        val outStart = LongArray(n)
        for (j in 1 until n) outStart[j] = outStart[j - 1] + length[j - 1]
        val fadeIn = BooleanArray(n) { it > 0 && srcStart[it] != srcStart[it - 1] + length[it - 1] }
        val fadeOut = BooleanArray(n) { it < n - 1 && srcStart[it + 1] != srcStart[it] + length[it] }
        val fadeFrames = Math.round(p.fadeMs * sampleRate / 1000.0).toInt()
        return SlicePlan(srcStart, length, outStart, fadeIn, fadeOut, fadeFrames, totalFrames)
    }
}

/** Presents [base] re-ordered according to [plan]; same length and format, no big buffers. */
class SlicedPcmSource(private val base: PcmSource, private val plan: SlicePlan) : PcmSource {
    init { require(base.frames == plan.totalFrames) { "plan does not match source length" } }

    override val frames: Long = plan.totalFrames
    override val channels: Int = base.channels
    override val sampleRate: Int = base.sampleRate
    private var scratch = FloatArray(0)

    override fun read(startFrame: Long, count: Int, out: FloatArray) {
        val ch = channels
        java.util.Arrays.fill(out, 0, count * ch, 0f)
        val a = maxOf(startFrame, 0L)
        val b = minOf(startFrame + count, frames)
        if (b <= a) return
        var j = slotOf(a)
        var pos = a
        while (pos < b) {
            val len = plan.length[j]
            val within = pos - plan.outStart[j]
            val n = minOf(len - within, b - pos).toInt()
            if (scratch.size < n * ch) scratch = FloatArray(n * ch)
            base.read(plan.srcStart[j] + within, n, scratch)
            val fe = minOf(plan.fadeFrames.toLong(), len / 2).toInt()
            val dst = ((pos - startFrame) * ch).toInt()
            if (fe <= 0 || !(plan.fadeIn[j] || plan.fadeOut[j])) {
                System.arraycopy(scratch, 0, out, dst, n * ch)
            } else {
                for (k in 0 until n) {
                    val idx = within + k
                    var g = 1f
                    if (plan.fadeIn[j] && idx < fe) g *= ramp(idx, fe)
                    val fromEnd = len - 1 - idx
                    if (plan.fadeOut[j] && fromEnd < fe) g *= ramp(fromEnd, fe)
                    for (c in 0 until ch) out[dst + k * ch + c] = scratch[k * ch + c] * g
                }
            }
            pos += n
            j++
        }
    }

    private fun ramp(i: Long, fe: Int): Float = (0.5 - 0.5 * cos(PI * (i + 0.5) / fe)).toFloat()

    /** Largest slot index whose outStart <= p. */
    private fun slotOf(p: Long): Int {
        var lo = 0
        var hi = plan.slices - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (plan.outStart[mid] <= p) lo = mid else hi = mid - 1
        }
        return lo
    }
}
