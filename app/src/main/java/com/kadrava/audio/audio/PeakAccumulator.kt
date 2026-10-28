package com.kadrava.audio.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Streaming reduction of decoded PCM into a fixed number of peak buckets (bounded memory). */
object PeakAccumulator {
    const val ENC_PCM_16 = 2   // android.media.AudioFormat.ENCODING_PCM_16BIT
    const val ENC_PCM_8 = 3    // ENCODING_PCM_8BIT
    const val ENC_PCM_FLOAT = 4 // ENCODING_PCM_FLOAT

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENC_PCM_16 -> 2
        ENC_PCM_8 -> 1
        ENC_PCM_FLOAT -> 4
        else -> throw DecodeException("Formato PCM do decodificador não suportado (código $encoding).")
    }

    /**
     * Reads [size] bytes starting at [buf]'s current position (not modified).
     * Returns the number of whole frames consumed.
     */
    fun accumulate(
        buf: ByteBuffer, size: Int, encoding: Int, channels: Int,
        startFrame: Long, totalFrames: Long, peaks: FloatArray,
    ): Int {
        require(channels >= 1 && totalFrames >= 1 && peaks.isNotEmpty())
        val bps = bytesPerSample(encoding)
        val frames = size / (bps * channels)
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        var pos = b.position()
        for (f in 0 until frames) {
            var m = 0f
            for (ch in 0 until channels) {
                val v = when (encoding) {
                    ENC_PCM_16 -> abs(b.getShort(pos).toInt()) / 32768f
                    ENC_PCM_8 -> abs((b.get(pos).toInt() and 0xFF) - 128) / 128f
                    else -> { val x = b.getFloat(pos); if (x.isFinite()) abs(x) else 0f }
                }
                if (v > m) m = v
                pos += bps
            }
            val idx = (((startFrame + f) * peaks.size) / totalFrames).toInt().coerceIn(0, peaks.size - 1)
            if (m > peaks[idx]) peaks[idx] = m
        }
        return frames
    }
}
