package com.kadrava.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kadrava.audio.dsp.AnalysisResult
import com.kadrava.audio.dsp.AnalyzerParams
import com.kadrava.audio.dsp.SpectrumAscii
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatClock
import kotlin.math.round

private const val CHART_ROWS = 10

private fun db(v: Float) = if (v <= -119f) "-inf" else "%.1f".format(v)

/** Cindy: offline FFT analysis of the active audio (original or result). */
@Composable
fun CindyPanel(ui: UiState, sampleRate: Int?, hasAudio: Boolean, vm: KadavraViewModel) {
    val sizes = AnalyzerParams.SIZES
    val idx = sizes.indexOf(ui.anFft).coerceAtLeast(0)
    val binHz = sampleRate?.let { " · %.1f Hz/bin".format(it.toDouble() / ui.anFft) } ?: ""
    val running = ui.analysis is AnalysisState.Running

    TermSlider("  fft", "${ui.anFft}$binHz", idx / (sizes.size - 1f)) { f ->
        vm.setAnFft(sizes[round(f * (sizes.size - 1)).toInt().coerceIn(0, sizes.size - 1)])
    }
    TermItem(listOf(Span("  janela "), Span("[${ui.anWindow.label}]", Palette.Prompt)), enabled = !running, onClick = vm::cycleAnWindow, description = "Janela: ${ui.anWindow.label}")
    TermItem(listOf(Span("  eixo "), Span(if (ui.anLog) "[log]" else "[linear]", Palette.Prompt)), onClick = vm::toggleAnLog, description = "Eixo de frequência")
    TermItem(listOf(Span("  peak-hold "), Span(if (ui.anHold) "[on]" else "[off]", Palette.Prompt)), onClick = vm::toggleAnHold, description = "Peak-hold")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) TermItem(listOf(Span("[Cancel]", Palette.Prompt)), separator = false, onClick = vm::cancelAnalysis)
        else TermItem(listOf(Span("[Analyze]", Palette.Prompt)), enabled = hasAudio, separator = false, onClick = vm::startAnalysis)
    }
    TermText(">", color = Palette.Dim)

    when (val a = ui.analysis) {
        AnalysisState.Idle -> TermText(if (hasAudio) "  analisa o áudio ativo (original ou resultado)." else "  importe um áudio para analisar.", color = Palette.Dim)
        is AnalysisState.Running -> TermText("  ${a.stage} ${asciiBar(a.progress, 12)} ${(a.progress * 100).toInt()}%")
        is AnalysisState.Failed -> TermText("  erro: ${a.message}", color = Palette.Error)
        is AnalysisState.Done -> DoneView(ui, a, vm)
    }
    TermText(">", color = Palette.Dim)
}

@Composable
private fun DoneView(ui: UiState, a: AnalysisState.Done, vm: KadavraViewModel) {
    val r = a.result
    TermText("  origem: ${a.source} · ${formatClock((r.durationSec * 1000).toLong())} · ${r.sampleRate} Hz")
    TermText("  pico ${db(r.peakDb)} dBFS · rms ${db(r.rmsDb)} dBFS · crest ${"%.1f".format(r.crestDb)} dB")
    TermText("  dominante %.1f Hz · %d janela(s) · FFT %d".format(r.dominantHz, r.windowsUsed, r.fftSize))
    TermItem(
        listOf(Span("  ver "), Span(if (ui.anShowPlayhead) "[playhead ${formatClock(a.playheadMs)}]" else "[média]", Palette.Prompt)),
        onClick = vm::toggleAnView, description = "Alternar entre média e posição de reprodução",
    )
    Chart(r, ui.anLog, ui.anHold && !ui.anShowPlayhead, ui.anShowPlayhead)
}

@Composable
private fun Chart(r: AnalysisResult, log: Boolean, hold: Boolean, playhead: Boolean) {
    val cols = (LocalTermCols.current - 2).coerceAtLeast(16)
    val lines: List<AnnotatedString> = remember(r, cols, log, hold, playhead) {
        val levels = SpectrumAscii.rebin(if (playhead) r.playheadDb else r.avgDb, r.sampleRate, r.fftSize, cols, log)
        val holdLv = if (hold) SpectrumAscii.rebin(r.holdDb, r.sampleRate, r.fftSize, cols, log) else null
        val chart = SpectrumAscii.chart(levels, holdLv, CHART_ROWS)
        List(chart.rows) { row ->
            buildAnnotatedString {
                append("  ")
                for (c in 0 until cols) {
                    val ch = chart.charAt(row, c)
                    if (ch == ' ') append(' ')
                    else withStyle(SpanStyle(color = if (ch == '-') Palette.Dim else Iridescence.cell(c.toFloat() / cols, chart.levelAt(row, c)))) { append(ch) }
                }
            }
        }
    }
    val style = TextStyle(fontFamily = Term.font, fontSize = Term.base, color = Palette.Fg)
    TermText("  0 dBFS", color = Palette.Dim)
    lines.forEach { BasicText(it, style = style) }
    TermText("  " + SpectrumAscii.axisLine(cols, r.sampleRate, log), color = Palette.Dim)
    TermText("  -90 dBFS${if (log) " · eixo log" else " · eixo linear"}", color = Palette.Dim)
}
