package com.kadrava.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatClock
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/** Mouton parameters, Picotador/Embaralhador and actions, drawn as terminal lines. */
@Composable
fun MoutonPanel(ui: UiState, wf: WaveformData?, vm: KadavraViewModel) {
    val free = LocalContext.current.cacheDir.usableSpace
    val params = PaulstretchParams(ui.stretch, ui.windowSeconds, ui.seed)
    val estimate = remember(ui.stretch, ui.windowSeconds, wf, free) {
        if (wf == null) null else runCatching {
            PaulstretchPlanner.plan(params, wf.frames, wf.channels, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    val busy = ui.process is ProcessState.Running || ui.process is ProcessState.Exporting

    val sFrac = (ln(ui.stretch) / ln(PaulstretchParams.MAX_STRETCH)).toFloat().coerceIn(0f, 1f)
    TermSlider("  stretch", "%.1fx".format(ui.stretch), sFrac) { f ->
        vm.setStretch(round(exp(f * ln(PaulstretchParams.MAX_STRETCH)) * 10.0) / 10.0)
    }
    val ratio = PaulstretchParams.MAX_WINDOW_S / PaulstretchParams.MIN_WINDOW_S
    val wFrac = (ln(ui.windowSeconds / PaulstretchParams.MIN_WINDOW_S) / ln(ratio)).toFloat().coerceIn(0f, 1f)
    val fft = wf?.let { params.fftSize(it.sampleRate) }
    TermSlider("  janela", "%.2f s".format(ui.windowSeconds) + (fft?.let { " (FFT $it)" } ?: ""), wFrac) { f ->
        vm.setWindow(round(PaulstretchParams.MIN_WINDOW_S * exp(f * ln(ratio)) * 100.0) / 100.0)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TermItem(listOf(Span("  semente ${ui.seed}")), separator = false)
        TermItem(listOf(Span("[-]", Palette.Prompt)), enabled = !busy && ui.seed > 0, separator = false, onClick = vm::prevSeed)
        TermItem(listOf(Span("[+]", Palette.Prompt)), enabled = !busy, separator = false, onClick = vm::nextSeed)
    }
    TermText(">", color = Palette.Dim)

    // Picotador + Embaralhador
    TermItem(
        listOf(Span("  slices "), Span(if (ui.slicesEnabled) "[on]" else "[off]", Palette.Prompt)),
        enabled = !busy, onClick = { vm.setSlices(!ui.slicesEnabled) },
        description = "Picotador e embaralhador: " + if (ui.slicesEnabled) "ligado" else "desligado",
    )
    if (ui.slicesEnabled) {
        val maxF = SliceParams.MAX_FACTOR.toDouble()
        val minF = SliceParams.MIN_FACTOR.toDouble()
        val fFrac = (ln(ui.sliceFactor / minF) / ln(maxF / minF)).toFloat().coerceIn(0f, 1f)
        TermSlider("  factor", "${ui.sliceFactor} slices", fFrac) { f ->
            vm.setSliceFactor(round(minF * exp(f * ln(maxF / minF))).toInt().coerceIn(SliceParams.MIN_FACTOR, SliceParams.MAX_FACTOR))
        }
        TermSlider("  tamanhos", "variação %.2f".format(ui.sliceVariation), ui.sliceVariation.toFloat()) { f ->
            vm.setSliceVariation(round(f * 100.0) / 100.0)
        }
        TermText("  corta, embaralha (semente ${ui.seed}) e só então aplica o Mouton.", color = Palette.Dim)
        TermText(">", color = Palette.Dim)
    }

    // estimate
    when {
        wf == null -> TermText("  importe um áudio para processar.", color = Palette.Dim)
        estimate?.isFailure == true -> TermText("  indisponível: ${estimate?.exceptionOrNull()?.message}", color = Palette.Error)
        estimate?.isSuccess == true -> {
            val p = estimate!!.getOrThrow()
            val secs = p.outputFrames.toDouble() / wf.sampleRate
            TermText("  saída: ${formatClock((secs * 1000).toLong())} · ~${p.wavBytes / 1_000_000} MB · WAV 16 bits ${wf.sampleRate} Hz ${wf.channels} ch")
        }
    }
    TermText(">", color = Palette.Dim)

    // actions
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (ui.process) {
            is ProcessState.Running -> TermItem(listOf(Span("[Cancel]", Palette.Prompt)), separator = false, onClick = vm::cancelProcessing)
            is ProcessState.Exporting -> TermItem(listOf(Span("[Cancel export]", Palette.Prompt)), separator = false, onClick = vm::cancelExport)
            else -> TermItem(listOf(Span("[Process]", Palette.Prompt)), enabled = estimate?.isSuccess == true, separator = false, onClick = vm::startMouton)
        }
        val hasResult = ui.process is ProcessState.Done
        if (ui.previewTrack == null) {
            TermItem(listOf(Span("[Listen]", Palette.Prompt)), enabled = hasResult && !ui.previewLoading, separator = false, onClick = vm::listenResult)
        } else {
            TermItem(listOf(Span("[Original]", Palette.Prompt)), separator = false, onClick = vm::listenOriginal)
        }
    }
    TermText(">", color = Palette.Dim)

    when (val st = ui.process) {
        ProcessState.Idle -> {}
        is ProcessState.Running -> {
            TermText("  ${st.stage} ${asciiBar(st.progress, 12)} ${(st.progress * 100).toInt()}%")
            Elapsed(st.startedAtMs)
        }
        is ProcessState.Exporting -> TermText("  exportando ${asciiBar(st.progress, 12)} ${(st.progress * 100).toInt()}%")
        is ProcessState.Done -> TermText("  pronto: ${st.summary}", color = Palette.Prompt)
        is ProcessState.Failed -> TermText("  erro: ${st.message}", color = Palette.Error)
    }
    if (ui.previewLoading) TermText("  preparando resultado para ouvir...", color = Palette.Dim)
    TermText("  o original não é alterado; a saída é um arquivo novo.", color = Palette.Dim)
    TermText(">", color = Palette.Dim)
}

@Composable
private fun Elapsed(startedAt: Long) {
    val now by produceState(android.os.SystemClock.elapsedRealtime()) {
        while (true) { value = android.os.SystemClock.elapsedRealtime(); delay(500) }
    }
    TermText("  decorrido ${formatClock(now - startedAt)}", color = Palette.Dim)
}
