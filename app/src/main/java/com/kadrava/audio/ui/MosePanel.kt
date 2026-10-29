package com.kadrava.audio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.BandParam
import com.kadrava.audio.dsp.BandType
import com.kadrava.audio.dsp.EqEngine
import com.kadrava.audio.dsp.EqParams
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.util.formatClock
import kotlin.math.round

private fun hz(f: Double) = if (f >= 1000) "%.1f kHz".format(f / 1000) else "${f.toInt()} Hz"

/** Mose: six fixed bands (low shelf, four peaks, high shelf); gain is the control (+-18 dB). */
@Composable
fun MosePanel(ui: UiState, wf: WaveformData?, vm: KadavraViewModel) {
    val free = LocalContext.current.cacheDir.usableSpace
    val eq = EqParams.default.withGains(ui.eqGains)
    val estimate = remember(wf, free) {
        if (wf == null) null else runCatching {
            PaulstretchPlanner.plan(PaulstretchParams(stretch = 1.0), wf.frames, wf.channels, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }

    eq.bands.forEachIndexed { i, b ->
        val kind = when (b.type) { BandType.LOW_SHELF -> "shelf grave"; BandType.HIGH_SHELF -> "shelf agudo"; BandType.PEAK -> "pico Q ${b.q}" }
        val g = ui.eqGains[i]
        TermSlider("  ${hz(b.freqHz)} ($kind)", "%+.1f dB".format(g), ((g + BandParam.MAX_GAIN_DB) / (2 * BandParam.MAX_GAIN_DB)).toFloat()) { f ->
            vm.setEqGain(i, round((f * 2 * BandParam.MAX_GAIN_DB - BandParam.MAX_GAIN_DB) * 2.0) / 2.0)
        }
    }
    TermItem(listOf(Span("  "), Span("[Flat]", kind = SpanKind.CONTROL)), enabled = !eq.isFlat, onClick = vm::resetEq, description = "Zerar todas as bandas")

    SlicerBlock(ui, vm)

    val skipped = if (wf == null) 0 else EqEngine.skipped(eq, wf.sampleRate).size
    val info = when {
        wf == null -> "importe um áudio para processar."
        eq.isFlat -> "todas as bandas em 0 dB: nada a processar."
        estimate?.isSuccess == true -> {
            val p = estimate!!.getOrNull()!!
            val secs = p.outputFrames.toDouble() / wf.sampleRate
            "saída: ${formatClock((secs * 1000).toLong())} · ~${p.wavBytes / 1_000_000} MB · WAV 16 bits" +
                if (skipped > 0) " · $skipped banda(s) acima de 0,45×taxa serão ignoradas" else ""
        }
        else -> null
    }
    ProcessBlock(ui, vm, info, estimate?.exceptionOrNull()?.message, estimate?.isSuccess == true && !eq.isFlat, vm::startMose)
}
