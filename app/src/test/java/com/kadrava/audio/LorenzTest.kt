package com.kadrava.audio

import com.kadrava.audio.dsp.LorenzAttractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class LorenzTest {
    @Test fun sameSeedGivesTheSameTrajectory() {
        val a = LorenzAttractor(7); val b = LorenzAttractor(7)
        repeat(500) { a.advance(0.01); b.advance(0.01) }
        assertEquals(a.x, b.x, 0.0); assertEquals(a.y, b.y, 0.0); assertEquals(a.z, b.z, 0.0)
    }

    @Test fun differentSeedsStartAtDifferentPointsOnTheAttractor() {
        val a = LorenzAttractor(1); val b = LorenzAttractor(2)
        assertTrue(abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z) > 1.0)
        for (l in listOf(a, b)) assertTrue("${l.x} ${l.y} ${l.z}", abs(l.x) < 25 && abs(l.y) < 30 && l.z > 0 && l.z < 55)
    }

    @Test fun longRunStatisticsMatchTheKnownAttractor() {
        val l = LorenzAttractor(3)
        val xs = DoubleArray(20000) { l.advance(0.01); l.x }
        val mean = xs.average()
        val std = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        assertTrue("mean $mean", abs(mean) < 3.0)
        assertTrue("std $std", std in 6.8..9.2)           // about 7.9 for the true attractor
        assertTrue(xs.all { it.isFinite() && abs(it) < 25.0 })
    }

    @Test fun trajectoryDoesNotDependOnHowTimeIsChopped() {
        val a = LorenzAttractor(5); val b = LorenzAttractor(5)
        a.advance(1.0)
        repeat(100) { b.advance(0.01) }
        assertEquals(a.x, b.x, 1e-9); assertEquals(a.y, b.y, 1e-9); assertEquals(a.z, b.z, 1e-9)
    }

    @Test fun normalisedOutputsStayInsideTheUnitRangeAndSwingBothWays() {
        val l = LorenzAttractor(9)
        var minX = 1.0; var maxX = -1.0
        repeat(5000) {
            l.advance(0.02)
            assertTrue(abs(l.nx) < 1.0 && abs(l.ny) < 1.0 && abs(l.nz) < 1.0)
            minX = minOf(minX, l.nx); maxX = maxOf(maxX, l.nx)
        }
        assertTrue("nx range $minX..$maxX", minX < -0.6 && maxX > 0.6)
    }
}
