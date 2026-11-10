package com.kadrava.audio.dsp

import kotlin.math.ceil
import kotlin.math.tanh

/**
 * Lorenz system (sigma 10, rho 28, beta 8/3) integrated with classical RK4.
 *
 * The state starts at a seed-dependent random point and is advanced [WARMUP] time units (in system time, not
 * audio time) so it is already on the attractor, and different seeds are decorrelated by the chaotic divergence.
 * [advance] splits any time step into RK4 sub-steps of at most [MAX_STEP], so the trajectory does not depend on how
 * the caller chops time. The normalised outputs [nx], [ny], [nz] are tanh-compressed to (-1, 1): x and y have
 * a standard deviation of about 7.9 and 9, z is centred near 23.5.
 */
class LorenzAttractor(seed: Long) {
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set

    init {
        val rng = XorShift64(seed * 31L + 17L)
        x = (rng.nextDouble() * 2 - 1) * 15.0
        y = (rng.nextDouble() * 2 - 1) * 20.0
        z = 5.0 + 40.0 * rng.nextDouble()
        advance(WARMUP)
    }

    val nx: Double get() = tanh(x / 9.0)
    val ny: Double get() = tanh(y / 11.0)
    val nz: Double get() = tanh((z - 23.5) / 10.0)

    /** Advances the system by [timeUnits] (one unit is about one lobe switch of x). */
    fun advance(timeUnits: Double) {
        if (timeUnits <= 0.0) return
        val n = ceil(timeUnits / MAX_STEP).toInt().coerceAtLeast(1)
        val h = timeUnits / n
        repeat(n) { rk4(h) }
    }

    private fun rk4(h: Double) {
        val x0 = x; val y0 = y; val z0 = z
        val k1x = SIGMA * (y0 - x0); val k1y = x0 * (RHO - z0) - y0; val k1z = x0 * y0 - BETA * z0
        val x1 = x0 + h / 2 * k1x; val y1 = y0 + h / 2 * k1y; val z1 = z0 + h / 2 * k1z
        val k2x = SIGMA * (y1 - x1); val k2y = x1 * (RHO - z1) - y1; val k2z = x1 * y1 - BETA * z1
        val x2 = x0 + h / 2 * k2x; val y2 = y0 + h / 2 * k2y; val z2 = z0 + h / 2 * k2z
        val k3x = SIGMA * (y2 - x2); val k3y = x2 * (RHO - z2) - y2; val k3z = x2 * y2 - BETA * z2
        val x3 = x0 + h * k3x; val y3 = y0 + h * k3y; val z3 = z0 + h * k3z
        val k4x = SIGMA * (y3 - x3); val k4y = x3 * (RHO - z3) - y3; val k4z = x3 * y3 - BETA * z3
        x = x0 + h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
        y = y0 + h / 6 * (k1y + 2 * k2y + 2 * k3y + k4y)
        z = z0 + h / 6 * (k1z + 2 * k2z + 2 * k3z + k4z)
    }

    companion object {
        const val SIGMA = 10.0
        const val RHO = 28.0
        const val BETA = 8.0 / 3.0
        const val WARMUP = 25.0
        const val MAX_STEP = 0.005
    }
}
