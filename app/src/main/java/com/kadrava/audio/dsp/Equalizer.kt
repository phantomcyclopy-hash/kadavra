package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class BandType { LOW_SHELF, PEAK, HIGH_SHELF }

data class BandParam(val type: BandType, val freqHz: Double, val gainDb: Double, val q: Double = 1.0) {
    fun validate() {
        if (!freqHz.isFinite() || freqHz < MIN_HZ || freqHz > MAX_HZ)
            throw InvalidParamException("frequência da banda deve estar entre $MIN_HZ e $MAX_HZ Hz (recebido $freqHz).")
        if (!gainDb.isFinite() || gainDb < -MAX_GAIN_DB || gainDb > MAX_GAIN_DB)
            throw InvalidParamException("ganho da banda deve estar entre -$MAX_GAIN_DB e +$MAX_GAIN_DB dB (recebido $gainDb).")
        if (!q.isFinite() || q < MIN_Q || q > MAX_Q)
            throw InvalidParamException("Q da banda deve estar entre $MIN_Q e $MAX_Q (recebido $q).")
    }

    companion object {
        const val MIN_HZ = 20.0
        const val MAX_HZ = 20000.0
        const val MAX_GAIN_DB = 18.0
        const val MIN_Q = 0.3
        const val MAX_Q = 8.0
    }
}

data class EqParams(val bands: List<BandParam>) {
    fun validate() {
        if (bands.isEmpty() || bands.size > 16) throw InvalidParamException("o equalizador aceita de 1 a 16 bandas.")
        bands.forEach { it.validate() }
    }

    val isFlat: Boolean get() = bands.all { it.gainDb == 0.0 }

    fun withGains(gains: List<Double>): EqParams {
        require(gains.size == bands.size)
        return EqParams(bands.mapIndexed { i, b -> b.copy(gainDb = gains[i]) })
    }

    companion object {
        /** Mose's six fixed bands (gain is the user control; frequency/Q are fixed in the UI). */
        val default = EqParams(
            listOf(
                BandParam(BandType.LOW_SHELF, 80.0, 0.0, 0.7071),
                BandParam(BandType.PEAK, 250.0, 0.0, 1.0),
                BandParam(BandType.PEAK, 800.0, 0.0, 1.0),
                BandParam(BandType.PEAK, 2500.0, 0.0, 1.0),
                BandParam(BandType.PEAK, 7000.0, 0.0, 1.0),
                BandParam(BandType.HIGH_SHELF, 12000.0, 0.0, 0.7071),
            )
        )
    }
}

/** Second-order IIR section (RBJ cookbook), transposed direct form II in double precision. */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    /** |H(e^jw)| in dB at [freqHz]. */
    fun magnitudeDb(freqHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 20.0 * log10(hypot(nr, ni) / hypot(dr, di))
    }

    /** Poles are inside the unit circle iff |a2| < 1 and |a1| < 1 + a2. */
    val isStable: Boolean get() = kotlin.math.abs(a2) < 1.0 && kotlin.math.abs(a1) < 1.0 + a2
}

object BiquadDesign {
    fun design(band: BandParam, sampleRate: Int): Biquad {
        band.validate()
        require(band.freqHz < sampleRate / 2.0) { "band frequency must be below Nyquist" }
        val a = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * band.freqHz / sampleRate
        val c = cos(w0)
        val al = sin(w0) / (2.0 * band.q)
        val sa = sqrt(a)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            BandType.PEAK -> {
                b0 = 1 + al * a; b1 = -2 * c; b2 = 1 - al * a
                a0 = 1 + al / a; a1 = -2 * c; a2 = 1 - al / a
            }
            BandType.LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * c + 2 * sa * al)
                b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - 2 * sa * al)
                a0 = (a + 1) + (a - 1) * c + 2 * sa * al
                a1 = -2 * ((a - 1) + (a + 1) * c)
                a2 = (a + 1) + (a - 1) * c - 2 * sa * al
            }
            BandType.HIGH_SHELF -> {
                b0 = a * ((a + 1) + (a - 1) * c + 2 * sa * al)
                b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - 2 * sa * al)
                a0 = (a + 1) - (a - 1) * c + 2 * sa * al
                a1 = 2 * ((a - 1) - (a + 1) * c)
                a2 = (a + 1) - (a - 1) * c - 2 * sa * al
            }
        }
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * Mose: cascade of biquad bands, offline, streaming (bounded memory). Bands at 0 dB are bypassed
 * exactly; bands at or above 0.45 x sample rate are skipped (reported by [skipped]). No latency.
 * Output level is not altered by the engine; the renderer lowers peaks above 0.98 with a single gain.
 */
class EqEngine(private val params: EqParams) {

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
        val sr = source.sampleRate
        val active = params.bands.filter { it.gainDb != 0.0 && it.freqHz < MAX_BAND_FRACTION * sr }
        val chains = Array(ch) { active.map { BiquadDesign.design(it, sr) } }

        val block = 4096
        val inBuf = FloatArray(block * ch)
        val outBuf = FloatArray(block * ch)
        var pos = 0L
        val total = source.frames
        while (pos < total) {
            if (cancelled()) throw ProcessingCancelled()
            val n = minOf(block.toLong(), total - pos).toInt()
            source.read(pos, n, inBuf)
            for (f in 0 until n) for (c in 0 until ch) {
                var v = inBuf[f * ch + c].toDouble()
                for (bq in chains[c]) v = bq.process(v)
                val o = v.toFloat()
                outBuf[f * ch + c] = if (o.isFinite()) o else 0f
            }
            sink.write(outBuf, n)
            pos += n
            onProgress(pos.toFloat() / total)
        }
        return total
    }

    companion object {
        const val MAX_BAND_FRACTION = 0.45

        fun skipped(params: EqParams, sampleRate: Int): List<BandParam> =
            params.bands.filter { it.gainDb != 0.0 && it.freqHz >= MAX_BAND_FRACTION * sampleRate }
    }
}
