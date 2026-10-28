package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchRenderer
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.RawPcmFileSource
import com.kadrava.audio.dsp.WavFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavRendererTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sr = 22050

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun headerFieldsAreCorrect() {
        val h = le(WavFormat.pcm16Header(48000, 2, 1000))
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals(36 + 4000, h.getInt(4))
        assertEquals("WAVE", String(h.array(), 8, 4))
        assertEquals("fmt ", String(h.array(), 12, 4))
        assertEquals(16, h.getInt(16)); assertEquals(1, h.getShort(20).toInt()); assertEquals(2, h.getShort(22).toInt())
        assertEquals(48000, h.getInt(24)); assertEquals(192000, h.getInt(28))
        assertEquals(4, h.getShort(32).toInt()); assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4)); assertEquals(4000, h.getInt(40))
    }

    @Test fun rendersValidWavWithExpectedSizeAndCleansScratch() {
        val data = FloatArray(sr) { (0.5 * sin(2 * PI * 330 * it / sr)).toFloat() }
        val out = File(tmp.root, "out.wav"); val work = File(tmp.root, "work")
        val r = PaulstretchRenderer.render(ArrayPcmSource(sr, 1, data), PaulstretchParams(stretch = 2.0), out, work)
        assertEquals(sr * 2L, r.outputFrames)
        assertEquals(44L + sr * 2L * 2, out.length())
        val b = le(out.readBytes())
        assertEquals(sr, b.getInt(24)); assertEquals(1, b.getShort(22).toInt())
        assertEquals(sr * 2 * 2, b.getInt(40))
        assertFalse(File(work, "stretched.f32").exists())
    }

    @Test fun loudInputIsScaledBelowCeilingWithoutClipping() {
        val data = FloatArray(sr) { if ((it / 50) % 2 == 0) 1f else -1f }
        val out = File(tmp.root, "loud.wav")
        val r = PaulstretchRenderer.render(ArrayPcmSource(sr, 1, data), PaulstretchParams(stretch = 2.0), out, File(tmp.root, "w2"))
        val b = le(out.readBytes())
        var peak = 0
        for (i in 44 until b.capacity() step 2) peak = maxOf(peak, abs(b.getShort(i).toInt()))
        assertTrue("peak $peak", peak <= (PaulstretchRenderer.PEAK_CEILING * 32767).toInt() + 1)
        if (r.peak > PaulstretchRenderer.PEAK_CEILING) assertTrue(r.gain < 1f) else assertEquals(1f, r.gain, 0f)
    }

    @Test fun cancelledRenderLeavesNoFiles() {
        val out = File(tmp.root, "c.wav"); val work = File(tmp.root, "w3")
        try {
            PaulstretchRenderer.render(ArrayPcmSource(sr, 1, FloatArray(sr)), PaulstretchParams(stretch = 2.0), out, work, {}, { true })
            throw AssertionError("expected cancellation")
        } catch (_: ProcessingCancelled) {}
        assertFalse(out.exists())
        assertFalse(File(work, "stretched.f32").exists())
    }

    @Test fun rawPcmFileSourceReadsLittleEndianAndZeroPads() {
        val f = tmp.newFile("in.pcm")
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        shortArrayOf(16384, -16384, 8192, 0).forEach { bb.putShort(it) }
        f.writeBytes(bb.array())
        RawPcmFileSource(f, 8000, 2).use { src ->
            assertEquals(2L, src.frames)
            val out = FloatArray(8)
            src.read(-1, 4, out)
            assertEquals(0f, out[0], 0f); assertEquals(0f, out[1], 0f)
            assertEquals(0.5f, out[2], 1e-6f); assertEquals(-0.5f, out[3], 1e-6f)
            assertEquals(0.25f, out[4], 1e-6f); assertEquals(0f, out[6], 0f)
        }
    }
}
