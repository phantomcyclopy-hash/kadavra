package com.kadrava.audio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.BandParam
import com.kadrava.audio.dsp.BandType
import com.kadrava.audio.dsp.EnvShape
import com.kadrava.audio.dsp.EqEngine
import com.kadrava.audio.dsp.DistShape
import com.kadrava.audio.dsp.EqParams
import com.kadrava.audio.dsp.HeisenbergParams
import com.kadrava.audio.dsp.LoopMode
import com.kadrava.audio.dsp.MagicEffect
import com.kadrava.audio.dsp.OfflinePlanner
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.SafoMode
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.dsp.WagnerParams
import com.kadrava.audio.util.formatPrecise
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign

private fun logFrac(v: Double, lo: Double, hi: Double) = (ln(v / lo) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)
private fun logVal(f: Float, lo: Double, hi: Double) = lo * exp(f * ln(hi / lo))
private fun linFrac(v: Double, lo: Double, hi: Double) = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
private fun linVal(f: Float, lo: Double, hi: Double) = lo + f * (hi - lo)
private fun r1(v: Double) = round(v * 10) / 10
private fun r2(v: Double) = round(v * 100) / 100

private fun busy(ui: UiState) = ui.process is ProcessState.Running || ui.process is ProcessState.Exporting

private fun slicesPage(ui: UiState, vm: KadavraViewModel): ModulePage {
    val maxF = SliceParams.MAX_FACTOR.toDouble()
    val minF = SliceParams.MIN_FACTOR.toDouble()
    val b = busy(ui)
    return ModulePage("SLICES", listOf(listOf(
        ButtonCell("SLICES", if (ui.slicesEnabled) "on" else "off", !b, "slices") { vm.setSlices(!ui.slicesEnabled) },
        SliderCell("FACTOR", "${ui.sliceFactor}", logFrac(ui.sliceFactor.toDouble(), minF, maxF),
            { f -> vm.setSliceFactor(round(logVal(f, minF, maxF)).toInt().coerceIn(SliceParams.MIN_FACTOR, SliceParams.MAX_FACTOR)) },
            { vm.setSliceFactor(it.roundToInt().coerceIn(SliceParams.MIN_FACTOR, SliceParams.MAX_FACTOR)) }),
        SliderCell("VARIATION", "%.2f".format(ui.sliceVariation), ui.sliceVariation.toFloat(),
            { f -> vm.setSliceVariation(r2(f.toDouble())) }, { vm.setSliceVariation(it.coerceIn(0.0, 1.0)) }),
    )))
}

/** Mouton: Paulstretch-style spectral time stretch. */
@Composable
fun moutonSpec(ui: UiState, wf: WaveformData?, vm: KadavraViewModel): EngineSpec {
    val free = LocalContext.current.cacheDir.usableSpace
    val params = PaulstretchParams(ui.stretch, ui.windowSeconds, ui.seed)
    val estimate = remember(ui.stretch, ui.windowSeconds, wf, free) {
        if (wf == null) null else runCatching {
            PaulstretchPlanner.plan(params, wf.frames, wf.channels, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    val ratio = PaulstretchParams.MAX_WINDOW_S / PaulstretchParams.MIN_WINDOW_S
    val main = listOf<Cell>(
        SliderCell("STRETCH", "%.1fx".format(ui.stretch), (ln(ui.stretch) / ln(PaulstretchParams.MAX_STRETCH)).toFloat().coerceIn(0f, 1f),
            { f -> vm.setStretch(round(exp(f * ln(PaulstretchParams.MAX_STRETCH)) * 10.0) / 10.0) },
            { vm.setStretch(it.coerceIn(PaulstretchParams.MIN_STRETCH, PaulstretchParams.MAX_STRETCH)) }),
        SliderCell("WINDOW", "%.2f s".format(ui.windowSeconds), (ln(ui.windowSeconds / PaulstretchParams.MIN_WINDOW_S) / ln(ratio)).toFloat().coerceIn(0f, 1f),
            { f -> vm.setWindow(round(PaulstretchParams.MIN_WINDOW_S * exp(f * ln(ratio)) * 100.0) / 100.0) },
            { vm.setWindow(it.coerceIn(PaulstretchParams.MIN_WINDOW_S, PaulstretchParams.MAX_WINDOW_S)) }),
        NumberCell("SEED", "${ui.seed}", { vm.setSeed(it.toLong()) }, vm::prevSeed, vm::nextSeed, !busy(ui)),
    )
    return EngineSpec(
        pages = listOf(ModulePage("STRETCH", listOf(main)), slicesPage(ui, vm)),
        canProcess = estimate?.isSuccess == true,
        problem = estimate?.exceptionOrNull()?.message,
        onProcess = { vm.startMouton() },
        onPreview = { vm.startMouton(preview = true) },
    )
}

/** Mose: six fixed bands (low shelf, four peaks, high shelf); gain is the control (+-18 dB). */
@Composable
fun moseSpec(ui: UiState, wf: WaveformData?, vm: KadavraViewModel): EngineSpec {
    val free = LocalContext.current.cacheDir.usableSpace
    val eq = EqParams.default.withGains(ui.eqGains)
    val estimate = remember(wf, free) {
        if (wf == null) null else runCatching {
            PaulstretchPlanner.plan(PaulstretchParams(stretch = 1.0), wf.frames, wf.channels, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    val maxG = BandParam.MAX_GAIN_DB
    val cells = ArrayList<Cell>()
    eq.bands.forEachIndexed { i, b ->
        val hz = if (b.freqHz >= 1000) "%.1fk".format(b.freqHz / 1000) else "${b.freqHz.toInt()}"
        val kind = when (b.type) { BandType.LOW_SHELF -> "LO"; BandType.HIGH_SHELF -> "HI"; BandType.PEAK -> "PK" }
        val g = ui.eqGains[i]
        cells.add(SliderCell("$kind $hz", "%+.1f dB".format(g), ((g + maxG) / (2 * maxG)).toFloat(),
            { f -> vm.setEqGain(i, round((f * 2 * maxG - maxG) * 2.0) / 2.0) },
            { vm.setEqGain(i, it.coerceIn(-maxG, maxG)) }))
    }
    cells.add(ButtonCell("RESET", "flat", !eq.isFlat) { vm.resetEq() })
    val skipped = if (wf == null) 0 else EqEngine.skipped(eq, wf.sampleRate).size
    return EngineSpec(
        pages = listOf(ModulePage("BANDS", listOf(cells)), slicesPage(ui, vm)),
        canProcess = estimate?.isSuccess == true && !eq.isFlat,
        problem = estimate?.exceptionOrNull()?.message ?: if (skipped > 0) "$skipped band(s) above 0.45 x rate will be skipped" else null,
        onProcess = { vm.startMose() },
        onPreview = { vm.startMose(preview = true) },
    )
}

/**
 * Safo: granular synthesis modelled on The Secret Laboratory's Grainstorm. Pages: GRANULATION (nine main
 * controls, then reverse/grains/silence/s&h/spread/jitter), WAVEFORM (loop start/end/speed with precise sliders,
 * then loop mode, source mode, length, shape, seed), ENV, FX. Everything also works live ([Live]).
 */
@Composable
fun safoSpec(ui: UiState, wf: WaveformData?, vm: KadavraViewModel): EngineSpec {
    val free = LocalContext.current.cacheDir.usableSpace
    val sp = ui.safo
    val b = busy(ui)
    val estimate = remember(sp, wf, free) {
        if (wf == null) null else runCatching {
            sp.validate()
            OfflinePlanner.plan(wf.frames, sp.outputFrames(wf.sampleRate), 2, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    val durationSec = (wf?.durationMs ?: 0L) / 1000.0

    fun slider(label: String, text: String, frac: Float, set: (Float) -> Unit, type: (Double) -> Unit) = SliderCell(label, text, frac, set, type)
    fun unit(label: String, v: Double, f: (SafoParams, Double) -> SafoParams) =
        slider(label, "%.2f".format(v), v.toFloat(), { x -> vm.safo { f(it, r2(x.toDouble())) } }, { t -> vm.safo { f(it, t.coerceIn(0.0, 1.0)) } })

    val gran1 = listOf<Cell>(
        slider("GRAINSIZE", "%.0f ms".format(sp.grainMs), logFrac(sp.grainMs, SafoParams.MIN_GRAIN_MS, SafoParams.MAX_GRAIN_MS),
            { f -> vm.safo { it.copy(grainMs = round(logVal(f, SafoParams.MIN_GRAIN_MS, SafoParams.MAX_GRAIN_MS))) } },
            { v -> vm.safo { it.copy(grainMs = v.coerceIn(SafoParams.MIN_GRAIN_MS, SafoParams.MAX_GRAIN_MS)) } }),
        slider("DENSITY", "%.0f Hz".format(sp.densityHz), logFrac(sp.densityHz, SafoParams.MIN_DENSITY, SafoParams.MAX_DENSITY),
            { f -> vm.safo { it.copy(densityHz = round(logVal(f, SafoParams.MIN_DENSITY, SafoParams.MAX_DENSITY))) } },
            { v -> vm.safo { it.copy(densityHz = v.coerceIn(SafoParams.MIN_DENSITY, SafoParams.MAX_DENSITY)) } }),
        unit("DEVIATION", sp.deviation) { p, v -> p.copy(deviation = v) },
        unit("RND READ", sp.rndRead) { p, v -> p.copy(rndRead = v) },
        unit("PANNING", sp.panning) { p, v -> p.copy(panning = v) },
        slider("ENV CYCLES", "${sp.envCycles}", linFrac(sp.envCycles.toDouble(), 1.0, 16.0),
            { f -> vm.safo { it.copy(envCycles = linVal(f, 1.0, 16.0).roundToInt().coerceIn(1, 32)) } },
            { v -> vm.safo { it.copy(envCycles = v.roundToInt().coerceIn(1, 32)) } }),
        unit("ENV PHASE", sp.envPhase) { p, v -> p.copy(envPhase = v) },
        slider("PITCH", "%+.1f st".format(sp.pitch), linFrac(sp.pitch, -24.0, 24.0),
            { f -> vm.safo { it.copy(pitch = r1(linVal(f, -24.0, 24.0))) } },
            { v -> vm.safo { it.copy(pitch = v.coerceIn(-24.0, 24.0)) } }),
        slider("GLISS", "%+.1f st".format(sp.gliss), linFrac(sp.gliss, -12.0, 12.0),
            { f -> vm.safo { it.copy(gliss = r1(linVal(f, -12.0, 12.0))) } },
            { v -> vm.safo { it.copy(gliss = v.coerceIn(-12.0, 12.0)) } }),
    )
    val gran2 = listOf<Cell>(
        unit("REVERSE", sp.reverse) { p, v -> p.copy(reverse = v) },
        slider("GRAINS", "${sp.burstGrains}", linFrac(sp.burstGrains.toDouble(), 1.0, 16.0),
            { f -> vm.safo { it.copy(burstGrains = linVal(f, 1.0, 16.0).roundToInt().coerceIn(1, 64)) } },
            { v -> vm.safo { it.copy(burstGrains = v.roundToInt().coerceIn(1, 64)) } }),
        slider("SILENCE", "${sp.burstSilence}", linFrac(sp.burstSilence.toDouble(), 0.0, 16.0),
            { f -> vm.safo { it.copy(burstSilence = linVal(f, 0.0, 16.0).roundToInt().coerceIn(0, 64)) } },
            { v -> vm.safo { it.copy(burstSilence = v.roundToInt().coerceIn(0, 64)) } }),
        slider("S&H", "${sp.hold}", linFrac(sp.hold.toDouble(), 1.0, 16.0),
            { f -> vm.safo { it.copy(hold = linVal(f, 1.0, 16.0).roundToInt().coerceIn(1, 16)) } },
            { v -> vm.safo { it.copy(hold = v.roundToInt().coerceIn(1, 16)) } }),
        slider("SPREAD", "±%.1f st".format(sp.pitchSpread), linFrac(sp.pitchSpread, 0.0, 12.0),
            { f -> vm.safo { it.copy(pitchSpread = r1(linVal(f, 0.0, 12.0))) } },
            { v -> vm.safo { it.copy(pitchSpread = v.coerceIn(0.0, 12.0)) } }),
        slider("JITTER", "±%.1f st".format(sp.pitchJitter), linFrac(sp.pitchJitter, 0.0, 12.0),
            { f -> vm.safo { it.copy(pitchJitter = r1(linVal(f, 0.0, 12.0))) } },
            { v -> vm.safo { it.copy(pitchJitter = v.coerceIn(0.0, 12.0)) } }),
    )

    // loop region: precise times (m:ss.mmm), typed seconds, nudge buttons of 0.1 s
    fun timeText(frac: Double) = if (durationSec > 0) formatPrecise(frac * durationSec) else "%.1f%%".format(frac * 100)
    fun nudge(seconds: Double) = if (durationSec > 0) seconds / durationSec else 0.001
    fun setStart(v: Double) = vm.safo { it.copy(loopStart = v.coerceIn(0.0, it.loopEnd - SafoParams.MIN_LOOP)) }
    fun setEnd(v: Double) = vm.safo { it.copy(loopEnd = v.coerceIn(it.loopStart + SafoParams.MIN_LOOP, 1.0)) }
    val maxSp = SafoParams.MAX_SPEED
    val speedFrac = (0.5 + 0.5 * sign(sp.speed) * (ln(1 + abs(sp.speed) * 10) / ln(1 + maxSp * 10))).toFloat()
    fun setSpeed(v: Double) = vm.safo { it.copy(speed = v.coerceIn(-maxSp, maxSp)) }
    val wave1 = listOf<Cell>(
        WideSliderCell("START", timeText(sp.loopStart), sp.loopStart.toFloat(),
            { f -> setStart(f.toDouble()) },
            { t -> if (durationSec > 0) setStart(t / durationSec) },
            { setStart(sp.loopStart - nudge(0.1)) }, { setStart(sp.loopStart + nudge(0.1)) }),
        WideSliderCell("END", timeText(sp.loopEnd), sp.loopEnd.toFloat(),
            { f -> setEnd(f.toDouble()) },
            { t -> if (durationSec > 0) setEnd(t / durationSec) },
            { setEnd(sp.loopEnd - nudge(0.1)) }, { setEnd(sp.loopEnd + nudge(0.1)) }),
        WideSliderCell("SPEED", "%+.3fx".format(sp.speed), speedFrac,
            { f ->
                val s = (f - 0.5f) * 2f
                setSpeed(r2(sign(s.toDouble()) * (exp(abs(s.toDouble()) * ln(1 + maxSp * 10)) - 1) / 10))
            },
            { setSpeed(it) }, { setSpeed(round((sp.speed - 0.05) * 1000) / 1000) }, { setSpeed(round((sp.speed + 0.05) * 1000) / 1000) }),
    )
    val shapeLabel = when (sp.mode) { SafoMode.STOCHASTIC -> "SHAPE"; SafoMode.PROBABILITY -> "FOCUS"; SafoMode.VECTOR_FIELD -> "TURB" }
    val wave2 = listOf<Cell>(
        ButtonCell("LOOP", sp.loopMode.label, !b, "loop") { vm.safo { it.copy(loopMode = it.loopMode.next()) } },
        ButtonCell("SOURCE", sp.mode.label, !b, "source") { vm.safo { it.copy(mode = it.mode.next()) } },
        slider("LENGTH", "%.0f s".format(sp.durationSeconds), logFrac(sp.durationSeconds, SafoParams.MIN_DURATION_S, SafoParams.MAX_DURATION_S),
            { f -> vm.safo { it.copy(durationSeconds = round(logVal(f, SafoParams.MIN_DURATION_S, SafoParams.MAX_DURATION_S))) } },
            { v -> vm.safo { it.copy(durationSeconds = v.coerceIn(SafoParams.MIN_DURATION_S, SafoParams.MAX_DURATION_S)) } }),
        unit(shapeLabel, sp.shape) { p, v -> p.copy(shape = v) },
        NumberCell("SEED", "${sp.seed}", { v -> vm.safo { it.copy(seed = max(0L, v.toLong())) } },
            { vm.safo { it.copy(seed = max(0L, it.seed - 1)) } }, { vm.safo { it.copy(seed = it.seed + 1) } }, !b),
    )
    val env = listOf<Cell>(
        ButtonCell("ENV", sp.envShape.label, !b, "env") { vm.safo { it.copy(envShape = it.envShape.next()) } },
        unit("DECAY", sp.decay) { p, v -> p.copy(decay = v) },
        slider("RAMP", "%+.2f".format(sp.ramp), linFrac(sp.ramp, -1.0, 1.0),
            { f -> vm.safo { it.copy(ramp = r2(linVal(f, -1.0, 1.0))) } },
            { v -> vm.safo { it.copy(ramp = v.coerceIn(-1.0, 1.0)) } }),
    )
    val fx1 = listOf<Cell>(
        slider("SMOOTH", if (sp.smoothHz >= 19999.0) "off" else "%.0f Hz".format(sp.smoothHz),
            1f - logFrac(sp.smoothHz, SafoParams.MIN_SMOOTH_HZ, 20000.0),
            { f -> vm.safo { it.copy(smoothHz = if (f <= 0.01f) 20000.0 else round(logVal(1f - f, SafoParams.MIN_SMOOTH_HZ, 20000.0))) } },
            { v -> vm.safo { it.copy(smoothHz = v.coerceIn(SafoParams.MIN_SMOOTH_HZ, 20000.0)) } }),
        ButtonCell("MAGIC", sp.magic.label, !b, "magic") { vm.safo { it.copy(magic = it.magic.next()) } },
        unit("AMOUNT", sp.magicAmount) { p, v -> p.copy(magicAmount = v) },
    )
    val fx2 = listOf<Cell>(
        unit("REVERB", sp.reverbAmount) { p, v -> p.copy(reverbAmount = v) },
        slider("FREEZE AT", if (sp.freezeAt >= 1.0) "off" else "%.0f %%".format(sp.freezeAt * 100), sp.freezeAt.toFloat(),
            { f -> vm.safo { it.copy(freezeAt = r2(f.toDouble())) } },
            { v -> vm.safo { it.copy(freezeAt = (v / 100.0).coerceIn(0.0, 1.0)) } }),
        ButtonCell("FREEZE", if (ui.liveFrozen) "on" else "off", true, "freeze") { vm.setLiveFreeze(!ui.liveFrozen) },
    )
    return EngineSpec(
        pages = listOf(
            ModulePage("GRANULATION", listOf(gran1, gran2)),
            ModulePage("WAVEFORM", listOf(wave1, wave2)),
            ModulePage("ENV", listOf(env)),
            ModulePage("FX", listOf(fx1, fx2)),
        ),
        canProcess = estimate?.isSuccess == true,
        problem = estimate?.exceptionOrNull()?.message,
        onProcess = { vm.startSafo() },
        onPreview = { vm.startSafo(preview = true) },
        showLive = true,
    )
}

/** Heisenberg: delay (with filtered, pitch-shifting feedback and ping-pong) and distortion. */
@Composable
fun heisenbergSpec(ui: UiState, wf: WaveformData?, vm: KadavraViewModel): EngineSpec {
    val free = LocalContext.current.cacheDir.usableSpace
    val hp = ui.heis
    val b = busy(ui)
    val estimate = remember(hp, wf, free) {
        if (wf == null) null else runCatching {
            hp.validate()
            OfflinePlanner.plan(wf.frames, wf.frames + hp.tailFrames(wf.sampleRate), 2, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    fun slider(label: String, text: String, frac: Float, set: (Float) -> Unit, type: (Double) -> Unit) = SliderCell(label, text, frac, set, type)
    fun unit(label: String, v: Double, f: (HeisenbergParams, Double) -> HeisenbergParams) =
        slider(label, "%.2f".format(v), v.toFloat(), { x -> vm.heis { f(it, r2(x.toDouble())) } }, { t -> vm.heis { f(it, t.coerceIn(0.0, 1.0)) } })

    val delay1 = listOf<Cell>(
        slider("TIME", "%.0f ms".format(hp.delayMs), logFrac(hp.delayMs, 1.0, 2000.0),
            { f -> vm.heis { it.copy(delayMs = round(logVal(f, 1.0, 2000.0))) } },
            { v -> vm.heis { it.copy(delayMs = v.coerceIn(1.0, 2000.0)) } }),
        slider("FEEDBACK", "%.2f".format(hp.feedback), linFrac(hp.feedback, 0.0, 0.95),
            { f -> vm.heis { it.copy(feedback = r2(linVal(f, 0.0, 0.95))) } },
            { v -> vm.heis { it.copy(feedback = v.coerceIn(0.0, 0.95)) } }),
        unit("MIX", hp.delayMix) { p, v -> p.copy(delayMix = v) },
        ButtonCell("PING", if (hp.pingPong) "on" else "off", !b, "ping") { vm.heis { it.copy(pingPong = !it.pingPong) } },
        slider("TAIL", "%.1f s".format(hp.tailSeconds), linFrac(hp.tailSeconds, 0.0, 10.0),
            { f -> vm.heis { it.copy(tailSeconds = r1(linVal(f, 0.0, 10.0))) } },
            { v -> vm.heis { it.copy(tailSeconds = v.coerceIn(0.0, 10.0)) } }),
        slider("SHIFT", "%+.1f st".format(hp.shift), linFrac(hp.shift, -12.0, 12.0),
            { f -> vm.heis { it.copy(shift = r1(linVal(f, -12.0, 12.0))) } },
            { v -> vm.heis { it.copy(shift = v.coerceIn(-12.0, 12.0)) } }),
    )
    val delay2 = listOf<Cell>(
        slider("FB LOW", if (hp.fbLowHz >= 19999.0) "off" else "%.0f Hz".format(hp.fbLowHz), 1f - logFrac(hp.fbLowHz, 200.0, 20000.0),
            { f -> vm.heis { it.copy(fbLowHz = if (f <= 0.01f) 20000.0 else round(logVal(1f - f, 200.0, 20000.0))) } },
            { v -> vm.heis { it.copy(fbLowHz = v.coerceIn(200.0, 20000.0)) } }),
        slider("FB HIGH", if (hp.fbHighHz <= 20.5) "off" else "%.0f Hz".format(hp.fbHighHz), logFrac(hp.fbHighHz, 20.0, 2000.0),
            { f -> vm.heis { it.copy(fbHighHz = if (f <= 0.01f) 20.0 else round(logVal(f, 20.0, 2000.0))) } },
            { v -> vm.heis { it.copy(fbHighHz = v.coerceIn(20.0, 2000.0)) } }),
    )
    val dist = listOf<Cell>(
        ButtonCell("SHAPE", hp.shape.label, !b, "shape") { vm.heis { it.copy(shape = it.shape.next()) } },
        unit("DRIVE", hp.drive) { p, v -> p.copy(drive = v) },
        unit("MIX", hp.distMix) { p, v -> p.copy(distMix = v) },
        slider("LEVEL", "%+.1f dB".format(hp.levelDb), linFrac(hp.levelDb, -24.0, 12.0),
            { f -> vm.heis { it.copy(levelDb = r1(linVal(f, -24.0, 12.0))) } },
            { v -> vm.heis { it.copy(levelDb = v.coerceIn(-24.0, 12.0)) } }),
        slider("BITS", "${hp.bits}", linFrac(hp.bits.toDouble(), 1.0, 16.0),
            { f -> vm.heis { it.copy(bits = linVal(f, 1.0, 16.0).roundToInt().coerceIn(1, 16)) } },
            { v -> vm.heis { it.copy(bits = v.roundToInt().coerceIn(1, 16)) } }),
        slider("RATE", "${hp.rate}", linFrac(hp.rate.toDouble(), 1.0, 64.0),
            { f -> vm.heis { it.copy(rate = linVal(f, 1.0, 64.0).roundToInt().coerceIn(1, 64)) } },
            { v -> vm.heis { it.copy(rate = v.roundToInt().coerceIn(1, 64)) } }),
        ButtonCell("ORDER", if (hp.distAfter) "post" else "pre", !b, "order") { vm.heis { it.copy(distAfter = !it.distAfter) } },
    )
    return EngineSpec(
        pages = listOf(ModulePage("DELAY", listOf(delay1, delay2)), ModulePage("DIST", listOf(dist)), slicesPage(ui, vm)),
        canProcess = estimate?.isSuccess == true && !hp.isNoOp,
        problem = estimate?.exceptionOrNull()?.message ?: if (hp.isNoOp) "mix 0 and shape off: nothing to do" else null,
        onProcess = { vm.startHeisenberg() },
        onPreview = { vm.startHeisenberg(preview = true) },
    )
}

/** Wagner: FDN reverb. */
@Composable
fun wagnerSpec(ui: UiState, wf: WaveformData?, vm: KadavraViewModel): EngineSpec {
    val free = LocalContext.current.cacheDir.usableSpace
    val wp = ui.wag
    val estimate = remember(wp, wf, free) {
        if (wf == null) null else runCatching {
            wp.validate()
            OfflinePlanner.plan(wf.frames, wf.frames + wp.tailFrames(wf.sampleRate), 2, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    fun unit(label: String, v: Double, f: (WagnerParams, Double) -> WagnerParams) =
        SliderCell(label, "%.2f".format(v), v.toFloat(), { x -> vm.wag { f(it, r2(x.toDouble())) } }, { t -> vm.wag { f(it, t.coerceIn(0.0, 1.0)) } })
    val space1 = listOf<Cell>(
        SliderCell("RT60", "%.1f s".format(wp.rt60), logFrac(wp.rt60, 0.1, 30.0),
            { f -> vm.wag { it.copy(rt60 = r1(logVal(f, 0.1, 30.0)).coerceAtLeast(0.1)) } },
            { v -> vm.wag { it.copy(rt60 = v.coerceIn(0.1, 30.0)) } }),
        SliderCell("SIZE", "%.2fx".format(wp.size), logFrac(wp.size, 0.3, 3.0),
            { f -> vm.wag { it.copy(size = r2(logVal(f, 0.3, 3.0)).coerceIn(0.3, 3.0)) } },
            { v -> vm.wag { it.copy(size = v.coerceIn(0.3, 3.0)) } }),
        unit("DAMP", wp.damp) { p, v -> p.copy(damp = v.coerceIn(0.0, 0.95)) },
        SliderCell("PRE-DLY", "%.0f ms".format(wp.preDelayMs), linFrac(wp.preDelayMs, 0.0, 250.0),
            { f -> vm.wag { it.copy(preDelayMs = round(linVal(f, 0.0, 250.0))) } },
            { v -> vm.wag { it.copy(preDelayMs = v.coerceIn(0.0, 250.0)) } }),
        unit("DIFFUSE", wp.diffusion) { p, v -> p.copy(diffusion = v) },
        unit("MOD", wp.mod) { p, v -> p.copy(mod = v) },
    )
    val space2 = listOf<Cell>(
        unit("WIDTH", wp.width) { p, v -> p.copy(width = v) },
        unit("MIX", wp.mix) { p, v -> p.copy(mix = v) },
        SliderCell("TAIL", "%.1f s".format(wp.tailSeconds), linFrac(wp.tailSeconds, 0.0, 30.0),
            { f -> vm.wag { it.copy(tailSeconds = r1(linVal(f, 0.0, 30.0))) } },
            { v -> vm.wag { it.copy(tailSeconds = v.coerceIn(0.0, 30.0)) } }),
    )
    return EngineSpec(
        pages = listOf(ModulePage("SPACE", listOf(space1, space2)), slicesPage(ui, vm)),
        canProcess = estimate?.isSuccess == true && !wp.isNoOp,
        problem = estimate?.exceptionOrNull()?.message ?: if (wp.isNoOp) "mix 0: nothing to do" else null,
        onProcess = { vm.startWagner() },
        onPreview = { vm.startWagner(preview = true) },
    )
}
