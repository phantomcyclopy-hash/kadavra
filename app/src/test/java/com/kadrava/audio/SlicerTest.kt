package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.MemorySink
import com.kadrava.audio.dsp.PaulstretchEngine
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.dsp.SlicedPcmSource
import com.kadrava.audio.dsp.Slicer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlicerTest {
    private val sr = 1000

    @Test fun rejectsBadParams() {
        for (p in listOf(SliceParams(factor = 1), SliceParams(factor = 513), SliceParams(sizeVariation = Double.NaN),
            SliceParams(sizeVariation = 1.5), SliceParams(fadeMs = -1.0))) {
            try { p.validate(); throw AssertionError("should reject $p") } catch (_: InvalidParamException) {}
        }
        SliceParams().validate()
    }

    @Test fun slicesCoverTheWholeSourceExactlyOnce() {
        val plan = Slicer.plan(10_000, sr, SliceParams(factor = 16, sizeVariation = 0.8, seed = 5))
        assertEquals(16, plan.slices)
        val idx = (0 until plan.slices).sortedBy { plan.srcStart[it] }
        assertEquals(0L, plan.srcStart[idx.first()])
        for (k in 0 until idx.size - 1) assertEquals(plan.srcStart[idx[k + 1]], plan.srcStart[idx[k]] + plan.length[idx[k]])
        assertEquals(10_000L, plan.srcStart[idx.last()] + plan.length[idx.last()])
        assertEquals(10_000L, plan.length.sum())
        assertTrue(plan.length.all { it >= 1 })
    }

    @Test fun zeroVariationGivesEqualSizes() {
        val plan = Slicer.plan(1000, sr, SliceParams(factor = 8, sizeVariation = 0.0))
        assertTrue(plan.length.all { it in 124L..126L })
    }

    @Test fun shuffleAlwaysChangesOrderAndIsRepeatable() {
        for (seed in 1L..20L) {
            val a = Slicer.plan(5000, sr, SliceParams(factor = 2, seed = seed))
            assertFalse("seed $seed", a.srcStart.toList() == a.srcStart.sorted())
            val b = Slicer.plan(5000, sr, SliceParams(factor = 2, seed = seed))
            assertEquals(a.srcStart.toList(), b.srcStart.toList())
        }
        val x = Slicer.plan(5000, sr, SliceParams(factor = 32, seed = 1))
        val y = Slicer.plan(5000, sr, SliceParams(factor = 32, seed = 2))
        assertFalse(x.srcStart.toList() == y.srcStart.toList())
    }

    @Test fun factorIsClampedToLength() {
        val plan = Slicer.plan(5, sr, SliceParams(factor = 8))
        assertEquals(5, plan.slices)
        assertTrue(plan.length.all { it == 1L })
    }

    @Test fun slicedSourceIsAPermutationOfTheBase() {
        val data = FloatArray(2000) { it.toFloat() }
        val base = ArrayPcmSource(sr, 1, data)
        val plan = Slicer.plan(base.frames, sr, SliceParams(factor = 10, sizeVariation = 0.5, seed = 3, fadeMs = 0.0))
        val src = SlicedPcmSource(base, plan)
        val out = FloatArray(2000)
        src.read(0, 2000, out)
        assertEquals(data.toList(), out.sorted())
        for (j in 0 until plan.slices) {
            val o = plan.outStart[j].toInt()
            assertEquals(plan.srcStart[j].toFloat(), out[o], 0f)
            assertEquals((plan.srcStart[j] + plan.length[j] - 1).toFloat(), out[o + plan.length[j].toInt() - 1], 0f)
        }
    }

    @Test fun chunkedReadsEqualOneBigRead() {
        val data = FloatArray(3000) { (it % 97).toFloat() + it * 0.001f }
        val src = SlicedPcmSource(ArrayPcmSource(sr, 1, data), Slicer.plan(3000, sr, SliceParams(factor = 12, seed = 9)))
        val whole = FloatArray(3000); src.read(0, 3000, whole)
        val pieced = FloatArray(3000)
        var pos = 0
        val sizes = intArrayOf(1, 7, 64, 333, 1000)
        var k = 0
        val tmp = FloatArray(1000)
        while (pos < 3000) {
            val n = minOf(sizes[k++ % sizes.size], 3000 - pos)
            src.read(pos.toLong(), n, tmp)
            System.arraycopy(tmp, 0, pieced, pos, n)
            pos += n
        }
        assertTrue(whole.contentEquals(pieced))
    }

    @Test fun readsOutsideTheRangeAreZero() {
        val src = SlicedPcmSource(ArrayPcmSource(sr, 1, FloatArray(100) { 1f }), Slicer.plan(100, sr, SliceParams(factor = 4, fadeMs = 0.0)))
        val out = FloatArray(10) { 9f }
        src.read(-5, 10, out)
        for (i in 0 until 5) assertEquals(0f, out[i], 0f)
        for (i in 5 until 10) assertEquals(1f, out[i], 0f)
        src.read(98, 10, out)
        assertEquals(1f, out[0], 0f); assertEquals(1f, out[1], 0f); assertEquals(0f, out[2], 0f)
    }

    @Test fun declickFadesOnlyDiscontinuousJoins() {
        val total = 4000
        val base = ArrayPcmSource(sr, 1, FloatArray(total) { 1f })
        val plan = Slicer.plan(total.toLong(), sr, SliceParams(factor = 4, sizeVariation = 0.0, seed = 2, fadeMs = 5.0))
        val out = FloatArray(total); SlicedPcmSource(base, plan).read(0, total, out)
        assertTrue(out.all { it in 0f..1f })
        for (j in 0 until plan.slices) {
            val o = plan.outStart[j].toInt(); val len = plan.length[j].toInt()
            assertEquals(1f, out[o + len / 2], 0f)
            if (plan.fadeIn[j]) assertTrue(out[o] < 0.1f) else assertEquals(1f, out[o], 0f)
            if (plan.fadeOut[j]) assertTrue(out[o + len - 1] < 0.1f) else assertEquals(1f, out[o + len - 1], 0f)
        }
    }

    @Test fun moutonOnSlicedSourceKeepsLengthAndFinite() {
        val data = FloatArray(44100) { kotlin.math.sin(it * 0.05).toFloat() * 0.5f }
        val base = ArrayPcmSource(44100, 1, data)
        val src = SlicedPcmSource(base, Slicer.plan(base.frames, 44100, SliceParams(factor = 6, seed = 4)))
        val p = PaulstretchParams(stretch = 2.0)
        val sink = MemorySink(1, p.outputFrames(src.frames).toInt())
        assertEquals(88200L, PaulstretchEngine(p).run(src, sink))
        assertTrue(sink.data.all { it.isFinite() })
    }
}
