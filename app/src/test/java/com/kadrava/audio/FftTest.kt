package com.kadrava.audio

import com.kadrava.audio.dsp.Fft
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class FftTest {
    @Test fun impulseGivesFlatSpectrum() {
        val re = DoubleArray(16).also { it[0] = 1.0 }
        val im = DoubleArray(16)
        Fft(16).transform(re, im)
        for (i in 0 until 16) { assertEquals(1.0, re[i], 1e-12); assertEquals(0.0, im[i], 1e-12) }
    }

    @Test fun sinePeaksAtItsBin() {
        val n = 64; val k = 5
        val re = DoubleArray(n) { sin(2 * PI * k * it / n) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        val mag = DoubleArray(n) { hypot(re[it], im[it]) }
        assertEquals(n / 2.0, mag[k], 1e-9)
        assertEquals(n / 2.0, mag[n - k], 1e-9)
        for (i in 0 until n) if (i != k && i != n - k) assertEquals(0.0, mag[i], 1e-9)
    }

    @Test fun inverseRoundTrips() {
        val n = 256
        val x = DoubleArray(n) { sin(it * 0.37) + 0.1 * it % 3 }
        val re = x.copyOf(); val im = DoubleArray(n)
        val f = Fft(n)
        f.transform(re, im); f.transform(re, im, inverse = true)
        for (i in 0 until n) { assertEquals(x[i], re[i], 1e-10); assertEquals(0.0, im[i], 1e-10) }
    }

    @Test fun parsevalHolds() {
        val n = 128
        val re = DoubleArray(n) { sin(it * 1.3) * 0.5 }; val im = DoubleArray(n)
        val e1 = re.sumOf { it * it }
        Fft(n).transform(re, im)
        val e2 = (0 until n).sumOf { re[it] * re[it] + im[it] * im[it] } / n
        assertEquals(e1, e2, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonPowerOfTwo() { Fft(100) }
}
