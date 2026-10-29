package com.kadrava.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.util.formatClock
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/** Mouton: Paulstretch-style spectral time stretch. */
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
        TermItem(listOf(Span("[-]", kind = SpanKind.CONTROL)), enabled = !busy && ui.seed > 0, separator = false, onClick = vm::prevSeed)
        TermItem(listOf(Span("[+]", kind = SpanKind.CONTROL)), enabled = !busy, separator = false, onClick = vm::nextSeed)
    }
    TermText(">", color = Palette.Dim)

    SlicerBlock(ui, vm)

    val info = when {
        wf == null -> "importe um áudio para processar."
        estimate?.isSuccess == true -> {
            val p = estimate!!.getOrNull()!!
            val secs = p.outputFrames.toDouble() / wf.sampleRate
            "saída: ${formatClock((secs * 1000).toLong())} · ~${p.wavBytes / 1_000_000} MB · WAV 16 bits ${wf.sampleRate} Hz ${wf.channels} ch"
        }
        else -> null
    }
    ProcessBlock(ui, vm, info, estimate?.exceptionOrNull()?.message, estimate?.isSuccess == true, vm::startMouton)
}
