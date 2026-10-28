package com.kadrava.audio.dsp

import java.io.Closeable
import java.io.RandomAccessFile
import java.io.File

/** Random-access, interleaved float PCM. Reads outside [0, frames) yield zeros. */
interface PcmSource {
    val frames: Long
    val channels: Int
    val sampleRate: Int
    /** Fills out[0 until count*channels]. */
    fun read(startFrame: Long, count: Int, out: FloatArray)
}

/** Receives finished interleaved output frames in order. */
interface SampleSink {
    fun write(interleaved: FloatArray, frames: Int)
}

class ArrayPcmSource(
    override val sampleRate: Int,
    override val channels: Int,
    private val data: FloatArray,
) : PcmSource {
    override val frames: Long = (data.size / channels).toLong()

    override fun read(startFrame: Long, count: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, count * channels, 0f)
        val a = maxOf(startFrame, 0L)
        val b = minOf(startFrame + count, frames)
        if (b > a) {
            System.arraycopy(data, (a * channels).toInt(), out, ((a - startFrame) * channels).toInt(), ((b - a) * channels).toInt())
        }
    }
}

class MemorySink(private val channels: Int, capacityFrames: Int) : SampleSink {
    val data = FloatArray(capacityFrames * channels)
    var framesWritten = 0
        private set

    override fun write(interleaved: FloatArray, frames: Int) {
        System.arraycopy(interleaved, 0, data, framesWritten * channels, frames * channels)
        framesWritten += frames
    }
}

/** 16-bit little-endian interleaved PCM file (no header), read through a RandomAccessFile. */
class RawPcmFileSource(file: File, override val sampleRate: Int, override val channels: Int) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val frames: Long = raf.length() / (2L * channels)
    private var scratch = ByteArray(0)

    override fun read(startFrame: Long, count: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, count * channels, 0f)
        val a = maxOf(startFrame, 0L)
        val b = minOf(startFrame + count, frames)
        if (b <= a) return
        val bytes = ((b - a) * channels * 2).toInt()
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        raf.seek(a * channels * 2)
        raf.readFully(scratch, 0, bytes)
        var o = ((a - startFrame) * channels).toInt()
        var i = 0
        while (i < bytes) {
            val v = (scratch[i + 1].toInt() shl 8) or (scratch[i].toInt() and 0xFF)
            out[o++] = v.toShort() / 32768f
            i += 2
        }
    }

    override fun close() = raf.close()
}
