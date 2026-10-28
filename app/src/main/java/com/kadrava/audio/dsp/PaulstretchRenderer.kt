package com.kadrava.audio.dsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Writes finished float frames to a scratch file (float32 LE) and tracks the true peak. */
class FloatFileSink(file: File, private val channels: Int) : SampleSink, Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var scratch: ByteBuffer = ByteBuffer.allocate(0)
    var frames = 0L
        private set
    var peak = 0f
        private set

    override fun write(interleaved: FloatArray, frames: Int) {
        val count = frames * channels
        if (scratch.capacity() < count * 4) scratch = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        for (i in 0 until count) {
            var v = interleaved[i]
            if (!v.isFinite()) v = 0f
            val a = abs(v)
            if (a > peak) peak = a
            scratch.putFloat(v)
        }
        out.write(scratch.array(), 0, count * 4)
        this.frames += frames
    }

    override fun close() = out.close()
}

class RenderResult(
    val outputFrames: Long, val sampleRate: Int, val channels: Int,
    val peak: Float, val gain: Float,
)

/**
 * Two passes, bounded memory: (1) engine -> float32 scratch file while measuring the peak;
 * (2) scratch -> 16-bit PCM WAV. If the peak exceeds [PEAK_CEILING] the whole file is scaled down by one
 * constant gain (never boosted). No dither is applied. Partial outputs are deleted on failure/cancel.
 */
object PaulstretchRenderer {
    const val PEAK_CEILING = 0.98f
    private const val STRETCH_SHARE = 0.85f

    fun render(
        source: PcmSource,
        params: PaulstretchParams,
        outWav: File,
        workDir: File,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): RenderResult {
        workDir.mkdirs()
        val scratchFile = File(workDir, "stretched.f32")
        var ok = false
        try {
            val ch = source.channels
            val sink = FloatFileSink(scratchFile, ch)
            try {
                PaulstretchEngine(params).run(source, sink, { onProgress(it * STRETCH_SHARE) }, cancelled)
            } finally {
                sink.close()
            }
            val frames = sink.frames
            val peak = sink.peak
            val gain = if (peak > PEAK_CEILING) PEAK_CEILING / peak else 1f

            val totalBytes = frames * ch * 4
            var remaining = totalBytes
            val chunk = ByteArray(1 shl 16)
            val inBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            val outChunk = ByteArray((1 shl 16) / 2)
            DataInputStream(BufferedInputStream(FileInputStream(scratchFile), 1 shl 16)).use { input ->
                BufferedOutputStream(FileOutputStream(outWav), 1 shl 16).use { out ->
                    out.write(WavFormat.pcm16Header(source.sampleRate, ch, frames))
                    while (remaining > 0) {
                        if (cancelled()) throw ProcessingCancelled()
                        val n = minOf(chunk.size.toLong(), remaining).toInt()
                        input.readFully(chunk, 0, n)
                        val samples = n / 4
                        for (i in 0 until samples) {
                            val v = (inBuf.getFloat(i * 4) * gain).coerceIn(-1f, 1f)
                            val s = Math.round(v * 32767f)
                            outChunk[i * 2] = s.toByte()
                            outChunk[i * 2 + 1] = (s shr 8).toByte()
                        }
                        out.write(outChunk, 0, samples * 2)
                        remaining -= n
                        onProgress(STRETCH_SHARE + (1f - STRETCH_SHARE) * (1f - remaining.toFloat() / totalBytes))
                    }
                }
            }
            ok = true
            return RenderResult(frames, source.sampleRate, ch, peak, gain)
        } finally {
            scratchFile.delete()
            if (!ok) outWav.delete()
        }
    }
}
