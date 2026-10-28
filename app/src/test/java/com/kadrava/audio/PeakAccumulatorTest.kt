package com.kadrava.audio

import com.kadrava.audio.audio.DecodeException
import com.kadrava.audio.audio.PeakAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeakAccumulatorTest {
    @Test fun mono16BitPeaksLandInTheRightBuckets() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        shortArrayOf(0, 16384, -32768, 0).forEach { buf.putShort(it) }
        buf.flip()
        val peaks = FloatArray(2)
        val frames = PeakAccumulator.accumulate(buf, 8, PeakAccumulator.ENC_PCM_16, 1, 0, 4, peaks)
        assertEquals(4, frames)
        assertEquals(0.5f, peaks[0], 1e-6f)
        assertEquals(1.0f, peaks[1], 1e-6f)
    }

    @Test fun stereoUsesLouderChannelAndIgnoresPartialFrames() {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.nativeOrder())
        shortArrayOf(1000, -16384, 0, 0).forEach { buf.putShort(it) }
        buf.putShort(7) // trailing partial frame (2 bytes of a 4-byte frame)
        buf.flip()
        val peaks = FloatArray(1)
        assertEquals(2, PeakAccumulator.accumulate(buf, 10, PeakAccumulator.ENC_PCM_16, 2, 0, 2, peaks))
        assertEquals(0.5f, peaks[0], 1e-6f)
    }

    @Test fun nonFiniteFloatSamplesAreTreatedAsSilence() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        buf.putFloat(Float.NaN); buf.putFloat(0.25f); buf.flip()
        val peaks = FloatArray(1)
        PeakAccumulator.accumulate(buf, 8, PeakAccumulator.ENC_PCM_FLOAT, 1, 0, 2, peaks)
        assertEquals(0.25f, peaks[0], 1e-6f)
    }

    @Test(expected = DecodeException::class) fun unsupportedEncodingIsRejected() {
        PeakAccumulator.bytesPerSample(99)
    }
}
