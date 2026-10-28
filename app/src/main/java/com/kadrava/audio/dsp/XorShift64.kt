package com.kadrava.audio.dsp

/** Tiny deterministic PRNG (xorshift64*), identical on every JVM/Android version. */
class XorShift64(seed: Long) {
    private var s: Long = seed * -7046029254386353131L + 0x1234567L

    init { if (s == 0L) s = -7046029254386353131L }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double {
        s = s xor (s ushr 12)
        s = s xor (s shl 25)
        s = s xor (s ushr 27)
        val r = s * 2685821657736338717L
        return (r ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
