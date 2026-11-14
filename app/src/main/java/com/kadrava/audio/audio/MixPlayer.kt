package com.kadrava.audio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.kadrava.audio.session.Mixer
import kotlin.math.max

/**
 * The single playback engine for the whole session: one worker thread renders [Mixer] blocks and streams them to
 * one [AudioTrack] (float stereo), whatever the number of tracks. [pause] keeps the transport position;
 * [stop] rewinds to 0. [soundingPosition] accounts for the frames still buffered in the device, so a playhead
 * drawn from it follows what is actually heard.
 * Not unit-testable (needs the audio hardware): docs/ARCHITECTURE.md lists what tests cover and what needs a device.
 */
class MixPlayer(private val mixer: Mixer, private val onError: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var written = 0L
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile var load = 0f
        private set

    val isPlaying: Boolean get() = running

    fun play() {
        if (running) return
        running = true
        written = 0L
        thread = Thread({ loop() }, "kadavra-mix").also { it.start() }
    }

    fun pause() {
        running = false
        thread?.let { try { it.join(1500) } catch (_: InterruptedException) {} }
        thread = null
    }

    fun stop() {
        pause()
        mixer.seek(0)
    }

    /** Timeline frame currently sounding (what the playhead should show). */
    fun soundingPosition(): Long {
        val t = track
        if (!running || t == null) return mixer.position
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return mixer.soundingPosition(max(0L, written - head))
    }

    private fun loop() {
        var t: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = mixer.sessionRate
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
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
                mixer.render(out, BLOCK)
                load = load * 0.9f + 0.1f * ((System.nanoTime() - t0) / budgetNs).toFloat()
                val w = t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) { onError("audio output error ($w)"); break }
                written += BLOCK
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
            track = null
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }
    }

    private companion object { const val BLOCK = 1024 }
}
