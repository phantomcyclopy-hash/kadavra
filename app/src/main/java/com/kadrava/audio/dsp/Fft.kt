package com.kadrava.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT (double precision). Forward uses e^{-i...};
 * inverse uses e^{+i...} and scales by 1/n, so inverse(forward(x)) == x.
 * Not thread-safe (stateless apart from precomputed tables, so one instance per thread).
 */
class Fft(val n: Int) {
    init { require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two >= 2, got $n" } }

    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 1 until n) r[i] = (r[i shr 1] shr 1) or ((i and 1) shl (bits - 1))
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        require(re.size >= n && im.size >= n)
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half]; val xi = im[j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1.0 / n
            for (i in 0 until n) { re[i] *= s; im[i] *= s }
        }
    }
}
