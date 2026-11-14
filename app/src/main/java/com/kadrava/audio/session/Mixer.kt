package com.kadrava.audio.session

import com.kadrava.audio.dsp.PcmSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** One track as the mixer sees it. [source] is read only by the thread that calls [Mixer.render]. */
class MixTrack(
    val id: Int,
    val source: PcmSource,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** Where the track starts on the timeline, in session frames. */
    val offsetFrames: Long = 0L,
)

/** Immutable snapshot of everything the audio thread needs; replace it as a whole to change anything. */
class MixerState(
    val tracks: List<MixTrack> = emptyList(),
    val masterGain: Float = 1f,
    val loop: Boolean = false,
    val loopStart: Long = 0L,
    val loopEnd: Long = 0L,
) {
    private val anySolo: Boolean = tracks.any { it.solo }

    /** Same rule as [SessionModel.audibleIds]. */
    fun isAudible(t: MixTrack): Boolean = if (anySolo) t.solo else !t.mute

    val loopActive: Boolean get() = loop && loopEnd > loopStart
}

/**
 * The central mixer: one block-based render for every track, driven by a single transport position, so all tracks
 * are sample-aligned by construction (a track's frame is always derived from the same absolute timeline frame).
 * Tracks may have other sample rates (linear-interpolated resampling) and start offsets. Mono tracks use a
 * constant-power pan law (-3 dB at centre); stereo tracks use a balance (unity at centre). The master bus applies
 * the master gain and a soft limiter that leaves everything below 0.8 untouched and never exceeds +-1.
 * The output does not depend on how the caller chops time into blocks.
 */
class Mixer(val sessionRate: Int, initial: MixerState = MixerState()) {
    @Volatile var state: MixerState = initial

    /** Next timeline frame to be rendered. */
    @Volatile var position: Long = 0L
        private set

    /** Loop wraps since the last seek (tells what is sounding from what is merely buffered). */
    @Volatile var wrapCount: Long = 0L
        private set

    @Volatile private var pendingSeek: Long = -1L
    @Volatile private var peaks: FloatArray = FloatArray(PEAK_SLOTS)

    fun seek(frame: Long) { pendingSeek = max(0L, frame) }

    /** Peak of track [id] (1..7) in the last rendered block: channel 0 = left, 1 = right. */
    fun trackPeak(id: Int, channel: Int): Float = peaks.getOrElse(id * 2 + channel) { 0f }
    val masterPeakL: Float get() = peaks[0]
    val masterPeakR: Float get() = peaks[1]

    /**
     * Timeline position that is actually sounding, when [lagFrames] already rendered frames are still buffered in
     * the output device. With looping, a position that fell before the loop start wrapped around once.
     */
    fun soundingPosition(lagFrames: Long): Long {
        val st = state
        var p = position - max(0L, lagFrames)
        if (st.loopActive && wrapCount > 0 && p < st.loopStart) p += st.loopEnd - st.loopStart
        return max(0L, p)
    }

    private var tmp = FloatArray(0)

    /** Fills [out] (interleaved stereo) with [frames] frames and advances the transport. */
    fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && out.size >= frames * 2)
        val st = state
        val seek = pendingSeek
        if (seek >= 0) { position = seek; wrapCount = 0; pendingSeek = -1L }
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val tp = FloatArray(PEAK_SLOTS)
        var pos = position
        var done = 0
        var wraps = wrapCount
        while (done < frames) {
            var seg = frames - done
            if (st.loopActive) {
                if (pos >= st.loopEnd) { pos = st.loopStart; wraps++ }
                seg = min(seg.toLong(), st.loopEnd - pos).toInt()
            }
            for (t in st.tracks) if (st.isAudible(t)) mixSegment(t, pos, out, done, seg, tp)
            pos += seg
            done += seg
            if (st.loopActive && pos >= st.loopEnd) { pos = st.loopStart; wraps++ }
        }
        position = pos
        wrapCount = wraps
        var mL = 0f
        var mR = 0f
        for (i in 0 until frames) {
            val l = softClip(out[2 * i] * st.masterGain)
            val r = softClip(out[2 * i + 1] * st.masterGain)
            out[2 * i] = l
            out[2 * i + 1] = r
            if (abs(l) > mL) mL = abs(l)
            if (abs(r) > mR) mR = abs(r)
        }
        tp[0] = mL
        tp[1] = mR
        peaks = tp
    }

    private fun mixSegment(t: MixTrack, pos: Long, out: FloatArray, outFrame: Int, seg: Int, tp: FloatArray) {
        if (seg <= 0) return
        val ch = t.source.channels
        val ratio = t.source.sampleRate.toDouble() / sessionRate
        val local0 = pos - t.offsetFrames
        var gl: Float
        var gr: Float
        if (ch == 1) {
            val a = (t.pan + 1f) * (PI / 4).toFloat()
            gl = cos(a)
            gr = sin(a)
        } else {
            gl = min(1f, 1f - t.pan)
            gr = min(1f, 1f + t.pan)
        }
        gl *= t.gain
        gr *= t.gain
        var pl = 0f
        var pr = 0f

        if (ratio == 1.0) {
            if (tmp.size < seg * ch) tmp = FloatArray(seg * ch)
            t.source.read(local0, seg, tmp)
            for (i in 0 until seg) {
                val l0 = tmp[i * ch]
                val r0 = if (ch == 2) tmp[i * 2 + 1] else l0
                val l = l0 * gl
                val r = r0 * gr
                out[(outFrame + i) * 2] += l
                out[(outFrame + i) * 2 + 1] += r
                if (abs(l) > pl) pl = abs(l)
                if (abs(r) > pr) pr = abs(r)
            }
        } else {
            val first = floor(local0 * ratio).toLong()
            val count = ceil(seg * ratio).toInt() + 3
            if (tmp.size < count * ch) tmp = FloatArray(count * ch)
            t.source.read(first, count, tmp)
            for (i in 0 until seg) {
                val rel = (local0 + i) * ratio - first
                val i0 = floor(rel).toInt().coerceIn(0, count - 2)
                val f = (rel - i0).toFloat()
                val l0 = tmp[i0 * ch] * (1 - f) + tmp[(i0 + 1) * ch] * f
                val r0 = if (ch == 2) tmp[i0 * 2 + 1] * (1 - f) + tmp[(i0 + 1) * 2 + 1] * f else l0
                val l = l0 * gl
                val r = r0 * gr
                out[(outFrame + i) * 2] += l
                out[(outFrame + i) * 2 + 1] += r
                if (abs(l) > pl) pl = abs(l)
                if (abs(r) > pr) pr = abs(r)
            }
        }
        val s = t.id * 2
        if (s + 1 < tp.size) {
            if (pl > tp[s]) tp[s] = pl
            if (pr > tp[s + 1]) tp[s + 1] = pr
        }
    }

    companion object {
        const val PEAK_SLOTS = (SessionModel.MAX_TRACKS + 1) * 2
        private const val KNEE = 0.8f

        /** Identity up to 0.8, then a smooth saturation towards (but never reaching) +-1. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val y = KNEE + (1f - KNEE) * tanh(((a - KNEE) / (1f - KNEE)).toDouble()).toFloat()
            return if (x < 0f) -y else y
        }
    }
}
