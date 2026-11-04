package com.kadrava.audio

import com.kadrava.audio.audio.Pcm16Converter
import com.kadrava.audio.audio.PeakAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16ConverterTest {
    private fun shorts(b: ByteArray): List<Int> =
        List(b.size / 2) { (b[it * 2].toInt() and 0xFF) or (b[it * 2 + 1].toInt() shl 8) }.map { it.toShort().toInt() }

    @Test fun floatIsClampedAndNonFiniteBecomesZero() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        floatArrayOf(0.5f, 2f, Float.NaN, -1f).forEach { buf.putFloat(it) }
        buf.flip()
        val out = ByteArrayOutputStream()
        assertEquals(4, Pcm16Converter.appendPcm16(buf, 16, PeakAccumulator.ENC_PCM_FLOAT, 1, out))
        assertEquals(listOf(16384, 32767, 0, -32767), shorts(out.toByteArray()))
    }

    @Test fun sixteenBitPassesThroughAndEightBitIsScaled() {
        val b16 = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()); b16.putShort(-1234); b16.putShort(321); b16.flip()
        val o1 = ByteArrayOutputStream()
        Pcm16Converter.appendPcm16(b16, 4, PeakAccumulator.ENC_PCM_16, 2, o1)
        assertEquals(listOf(-1234, 321), shorts(o1.toByteArray()))

        val b8 = ByteBuffer.wrap(byteArrayOf(0x80.toByte(), 0xFF.toByte()))
        val o2 = ByteArrayOutputStream()
        Pcm16Converter.appendPcm16(b8, 2, PeakAccumulator.ENC_PCM_8, 1, o2)
        assertEquals(listOf(0, 127 shl 8), shorts(o2.toByteArray()))
    }
}
