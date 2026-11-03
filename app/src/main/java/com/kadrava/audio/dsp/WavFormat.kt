package com.kadrava.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFormat {
    const val HEADER_SIZE = 44

    /** Canonical 44-byte RIFF/WAVE header for 16-bit integer PCM with a known number of frames. */
    fun pcm16Header(sampleRate: Int, channels: Int, frames: Long): ByteArray {
        require(sampleRate > 0 && channels in 1..2 && frames >= 0)
        val dataBytes = frames * channels * 2
        require(dataBytes < Int.MAX_VALUE - 36) { "WAV too large for a 32-bit RIFF header" }
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                                   // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)             // byte rate
            putShort((channels * 2).toShort())            // block align
            putShort(16)                                  // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }
}
