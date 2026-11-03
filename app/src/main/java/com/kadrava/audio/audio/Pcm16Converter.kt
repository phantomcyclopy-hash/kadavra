package com.kadrava.audio.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Converts decoder output (8-bit / 16-bit / float PCM) to 16-bit little-endian PCM bytes. */
object Pcm16Converter {
    /** Reads [size] bytes from [buf]'s current position (unchanged). Returns frames written. */
    fun appendPcm16(buf: ByteBuffer, size: Int, encoding: Int, channels: Int, out: OutputStream): Int {
        val bps = PeakAccumulator.bytesPerSample(encoding)
        val frames = size / (bps * channels)
        val samples = frames * channels
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        var pos = b.position()
        val scratch = ByteArray(8192)
        var sp = 0
        for (i in 0 until samples) {
            val s: Int = when (encoding) {
                PeakAccumulator.ENC_PCM_16 -> b.getShort(pos).toInt()
                PeakAccumulator.ENC_PCM_8 -> ((b.get(pos).toInt() and 0xFF) - 128) shl 8
                else -> {
                    val x = b.getFloat(pos)
                    (if (x.isFinite()) x.coerceIn(-1f, 1f) * 32767f else 0f).roundToInt()
                }
            }
            pos += bps
            scratch[sp++] = s.toByte()
            scratch[sp++] = (s shr 8).toByte()
            if (sp == scratch.size) { out.write(scratch, 0, sp); sp = 0 }
        }
        if (sp > 0) out.write(scratch, 0, sp)
        return frames
    }
}
