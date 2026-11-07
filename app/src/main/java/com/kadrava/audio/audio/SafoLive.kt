package com.kadrava.audio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.kadrava.audio.dsp.PcmSource
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.SafoSynth
import kotlin.math.abs
import kotlin.math.max

/**
 * Real-time Safo: a worker thread renders [SafoSynth] blocks and streams them to an [AudioTrack]
 * (float, stereo). Parameters can change while it plays ([setParams]). [load] is the share of each
 * block's time budget spent rendering. Owns [source] and closes nothing: the caller closes it after [stop].
 */
class SafoLive(
    private val source: PcmSource,
    initial: SafoParams,
    private val peaks: FloatArray?,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var pending: SafoParams = initial
    @Volatile private var synth: SafoSynth? = null
    @Volatile var frozen = false
    @Volatile var load = 0f
        private set
    private var thread: Thread? = null

    val peakL: Float get() = synth?.peakL ?: 0f
    val peakR: Float get() = synth?.peakR ?: 0f
    val readFraction: Double get() = synth?.readFraction ?: pending.loopStart

    fun setParams(p: SafoParams) {
        pending = p
        synth?.params = p
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "kadavra-safo-live").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(1500) } catch (_: InterruptedException) {} }
        thread = null
        synth = null
    }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = source.sampleRate
            val energy = peaks?.let { p -> DoubleArray(p.size) { p[it].toDouble() } }
            val s = SafoSynth(source, pending, Long.MAX_VALUE, BLOCK, energy) { !running }
            s.params = pending
            synth = s
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(max(minBuf, BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            t.play()
            val out = FloatArray(BLOCK * 2)
            val budgetNs = BLOCK * 1e9 / sr
            while (running) {
                val t0 = System.nanoTime()
                s.render(out, BLOCK, frozen)
                for (i in out.indices) { val v = out[i]; if (v > 1f) out[i] = 1f else if (v < -1f) out[i] = -1f }
                val spent = System.nanoTime() - t0
                load = load * 0.9f + 0.1f * (spent / budgetNs).toFloat()
                val w = t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) { onError("audio output error ($w)"); break }
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    private companion object { const val BLOCK = 1024 }
}
