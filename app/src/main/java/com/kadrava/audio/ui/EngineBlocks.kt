package com.kadrava.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatClock
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/** Picotador + Embaralhador controls (shared by the engines marked with a superscript 1). */
@Composable
fun SlicerBlock(ui: UiState, vm: KadavraViewModel) {
    val busy = ui.process is ProcessState.Running || ui.process is ProcessState.Exporting
    TermItem(
        listOf(Span("  slices "), Span(if (ui.slicesEnabled) "[on]" else "[off]", kind = SpanKind.CONTROL)),
        enabled = !busy, key = "slices", onClick = { vm.setSlices(!ui.slicesEnabled) },
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
        TermText("  corta, embaralha (semente ${ui.seed}) e só então aplica o motor.", color = Palette.Dim)
        TermText(">", color = Palette.Dim)
    }
}

/** Estimate line, [Process]/[Cancel], [Listen]/[Original], progress and result lines. */
@Composable
fun ProcessBlock(
    ui: UiState, vm: KadavraViewModel,
    info: String?, error: String?, canProcess: Boolean, onProcess: () -> Unit,
) {
    when {
        error != null -> TermText("  indisponível: $error", color = Palette.Error)
        info != null -> TermText("  $info")
    }
    TermText(">", color = Palette.Dim)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (ui.process) {
            is ProcessState.Running -> TermItem(listOf(Span("[Cancel]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::cancelProcessing)
            is ProcessState.Exporting -> TermItem(listOf(Span("[Cancel export]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::cancelExport)
            else -> TermItem(listOf(Span("[Process]", kind = SpanKind.CONTROL)), enabled = canProcess, separator = false, onClick = onProcess)
        }
        val hasResult = ui.process is ProcessState.Done
        if (ui.previewTrack == null) {
            TermItem(listOf(Span("[Listen]", kind = SpanKind.CONTROL)), enabled = hasResult && !ui.previewLoading, separator = false, onClick = vm::listenResult)
        } else {
            TermItem(listOf(Span("[Original]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::listenOriginal)
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
