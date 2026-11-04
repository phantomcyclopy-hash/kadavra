package com.kadrava.audio.dsp

import kotlin.math.max
import kotlin.math.min

enum class SafoMode(val label: String) {
    /** Read offset moves through the loop at SPEED (like the original Grainstorm). */
    STOCHASTIC("cloud"),
    /** Positions drawn from a probability distribution proportional to local level^gamma. */
    PROBABILITY("prob"),
    /** Positions/pitch driven by a point flowing through a 2-D vector field. */
    VECTOR_FIELD("field");

    fun next(): SafoMode = entries[(ordinal + 1) % entries.size]
}

/** What happens when the read offset reaches a loop boundary (the original has three modes). */
enum class LoopMode(val label: String) {
    WRAP("wrap"),              // jump back to the other end
    PING_PONG_REV("ping-rev"), // bounce; grains read backwards while moving backwards
    PING_PONG("ping");         // bounce; grains keep reading forwards

    fun next(): LoopMode = entries[(ordinal + 1) % entries.size]
}

/** Inner grain envelope waveform (ENV1). */
enum class EnvShape(val label: String) {
    HANN("hann"), TRIANGLE("triangle"), GAUSS("gauss"), RECT("rect");

    fun next(): EnvShape = entries[(ordinal + 1) % entries.size]
}

/**
 * Parameters modelled on The Secret Laboratory's Grainstorm (see its public instructions):
 * - [grainMs] GRAINSIZE (5 ms - 1 s), [densityHz] DENSITY (1 - 500 Hz);
 * - [deviation] DEVIATION: each grain is delayed by a uniform random amount within [0, deviation x sr / density];
 * - [rndRead] RND READ: random offset of the read position, as a fraction of the loop;
 * - [panning] PANNING: random left/right gain per grain; [envCycles]/[envPhase]: how often ENV1 repeats within
 *   a grain and its starting offset; [pitch] PITCH by resampling; [gliss] GLISS: pitch glide across the grain;
 *   [reverse] probability of a grain being read backwards;
 * - [burstGrains]/[burstSilence] GRAINS/SILENCE: play N grains, skip M;
 * - [loopStart]/[loopEnd]/[speed]/[loopMode]: the loop and the read offset's velocity (1.0 = original speed).
 * Extras from other granular tools: [hold] (S&H), [pitchSpread]/[pitchJitter], [decay]/[ramp], [smoothHz]
 * (one-pole low-pass), Magic effects and a reverb with freeze. [mode] PROBABILITY/VECTOR_FIELD choose positions
 * differently ([shape]: focus, or turbulence).
 */
data class SafoParams(
    val mode: SafoMode = SafoMode.STOCHASTIC,
    val durationSeconds: Double = 20.0,
    val loopStart: Double = 0.0,
    val loopEnd: Double = 1.0,
    val speed: Double = 1.0,
    val loopMode: LoopMode = LoopMode.WRAP,
    val grainMs: Double = 80.0,
    val densityHz: Double = 20.0,
    val deviation: Double = 0.3,
    val rndRead: Double = 0.0,
    val panning: Double = 0.3,
    val envCycles: Int = 1,
    val envPhase: Double = 0.0,
    val pitch: Double = 0.0,
    val gliss: Double = 0.0,
    val reverse: Double = 0.0,
    val burstGrains: Int = 1,
    val burstSilence: Int = 0,
    val hold: Int = 1,
    val pitchSpread: Double = 0.0,
    val pitchJitter: Double = 0.0,
    val envShape: EnvShape = EnvShape.HANN,
    val decay: Double = 0.0,
    val ramp: Double = 0.0,
    val shape: Double = 0.3,
    val smoothHz: Double = 20000.0,
    val reverbAmount: Double = 0.0,
    val freezeAt: Double = 1.0,
    val magic: MagicEffect = MagicEffect.OFF,
    val magicAmount: Double = 0.0,
    val seed: Long = 1L,
) {
    fun validate() {
        fun r(name: String, v: Double, lo: Double, hi: Double) {
            if (!v.isFinite() || v < lo || v > hi) throw InvalidParamException("$name must be between $lo and $hi (got $v).")
        }
        r("length (s)", durationSeconds, MIN_DURATION_S, MAX_DURATION_S)
        r("loop start", loopStart, 0.0, 1.0)
        r("loop end", loopEnd, 0.0, 1.0)
        if (loopEnd - loopStart < MIN_LOOP) throw InvalidParamException("loop end must be after loop start.")
        r("speed", speed, -MAX_SPEED, MAX_SPEED)
        r("grainsize (ms)", grainMs, MIN_GRAIN_MS, MAX_GRAIN_MS)
        r("density (Hz)", densityHz, MIN_DENSITY, MAX_DENSITY)
        r("deviation", deviation, 0.0, 1.0)
        r("rnd read", rndRead, 0.0, 1.0)
        r("panning", panning, 0.0, 1.0)
        if (envCycles < 1 || envCycles > 32) throw InvalidParamException("env cycles must be between 1 and 32 (got $envCycles).")
        r("env phase", envPhase, 0.0, 1.0)
        r("pitch (st)", pitch, -24.0, 24.0)
        r("gliss (st)", gliss, -12.0, 12.0)
        r("reverse", reverse, 0.0, 1.0)
        if (burstGrains < 1 || burstGrains > 64) throw InvalidParamException("grains must be between 1 and 64 (got $burstGrains).")
        if (burstSilence < 0 || burstSilence > 64) throw InvalidParamException("silence must be between 0 and 64 (got $burstSilence).")
        if (hold < 1 || hold > 16) throw InvalidParamException("s&h must be between 1 and 16 (got $hold).")
        r("spread (st)", pitchSpread, 0.0, 12.0)
        r("jitter (st)", pitchJitter, 0.0, 12.0)
        r("decay", decay, 0.0, 1.0)
        r("ramp", ramp, -1.0, 1.0)
        r("shape", shape, 0.0, 1.0)
        r("smooth (Hz)", smoothHz, MIN_SMOOTH_HZ, 20000.0)
        r("reverb", reverbAmount, 0.0, 1.0)
        r("freeze at", freezeAt, 0.0, 1.0)
        r("magic amount", magicAmount, 0.0, 1.0)
    }

    fun outputFrames(sampleRate: Int): Long = max(1L, Math.round(durationSeconds * sampleRate))

    companion object {
        const val MIN_DURATION_S = 1.0
        const val MAX_DURATION_S = 600.0
        const val MIN_GRAIN_MS = 5.0
        const val MAX_GRAIN_MS = 1000.0
        const val MIN_DENSITY = 1.0
        const val MAX_DENSITY = 500.0
        const val MAX_SPEED = 16.0
        const val MIN_SMOOTH_HZ = 200.0
        const val MIN_LOOP = 0.001
    }
}

/** Offline render (output is always stereo): the same [SafoSynth] the live player uses, run to a fixed length. */
class SafoEngine(private val p: SafoParams) {

    fun run(
        source: PcmSource,
        sink: SampleSink,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Long {
        p.validate()
        val total = p.outputFrames(source.sampleRate)
        val block = 16384
        val synth = SafoSynth(source, p, total, block, null, cancelled)
        val freezeFrame = (p.freezeAt * total).toLong()
        val buf = FloatArray(block * 2)
        var pos = 0L
        while (pos < total) {
            if (cancelled()) throw ProcessingCancelled()
            val m = min(block.toLong(), total - pos).toInt()
            synth.render(buf, m, pos >= freezeFrame)
            sink.write(buf, m)
            pos += m
            onProgress(min(1.0, pos.toDouble() / total).toFloat())
        }
        return total
    }
}
