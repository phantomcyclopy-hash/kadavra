package com.kadrava.audio.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatTime
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.round

@Composable
fun PaulstretchPanel(
    ui: UiState,
    wf: WaveformData?,
    onStretch: (Double) -> Unit,
    onWindow: (Double) -> Unit,
    onSeedMinus: () -> Unit,
    onSeedPlus: () -> Unit,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onExport: (String) -> Unit,
    onCancelExport: () -> Unit,
) {
    val free = LocalContext.current.cacheDir.usableSpace
    val params = PaulstretchParams(ui.stretch, ui.windowSeconds, ui.seed)
    val estimate = remember(ui.stretch, ui.windowSeconds, wf, free) {
        if (wf == null) null else runCatching {
            PaulstretchPlanner.plan(params, wf.frames, wf.channels, wf.sampleRate, free, wf.frames * wf.channels * 2L)
        }
    }
    val busy = ui.process is ProcessState.Running || ui.process is ProcessState.Exporting

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        TermText("-- paulstretch · STFT com fase aleatória --", color = Palette.Dim, size = 12.sp)
        Spacer(Modifier.height(4.dp))

        val sFrac = (ln(ui.stretch) / ln(PaulstretchParams.MAX_STRETCH)).toFloat().coerceIn(0f, 1f)
        TermSlider("fator de extensão", "%.1fx".format(ui.stretch), sFrac) { f ->
            onStretch(round(exp(f * ln(PaulstretchParams.MAX_STRETCH)) * 10.0) / 10.0)
        }
        val ratio = PaulstretchParams.MAX_WINDOW_S / PaulstretchParams.MIN_WINDOW_S
        val wFrac = (ln(ui.windowSeconds / PaulstretchParams.MIN_WINDOW_S) / ln(ratio)).toFloat().coerceIn(0f, 1f)
        val fft = wf?.let { params.fftSize(it.sampleRate) }
        TermSlider("janela", "%.2f s".format(ui.windowSeconds) + (fft?.let { " (FFT $it)" } ?: ""), wFrac) { f ->
            onWindow(round(PaulstretchParams.MIN_WINDOW_S * exp(f * ln(ratio)) * 100.0) / 100.0)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermText("semente ${ui.seed}", size = 13.sp)
            Action("[ - ]", enabled = !busy && ui.seed > 0, onClick = onSeedMinus)
            Action("[ + ]", enabled = !busy, onClick = onSeedPlus)
        }

        when {
            wf == null -> TermText("importe um áudio para processar.", color = Palette.Dim, size = 12.sp)
            estimate?.isFailure == true ->
                TermText("indisponível: ${estimate?.exceptionOrNull()?.message}", color = Palette.Error, size = 12.sp)
            estimate?.isSuccess == true -> {
                val p = estimate!!.getOrThrow()
                val secs = p.outputFrames.toDouble() / wf.sampleRate
                TermText(
                    "saída estimada: ${formatTime((secs * 1000).toLong())} · ~${p.wavBytes / 1_000_000} MB · WAV PCM 16 bits, ${wf.sampleRate} Hz, ${wf.channels} ch",
                    size = 12.sp,
                )
            }
        }

        when (val st = ui.process) {
            ProcessState.Idle -> {}
            is ProcessState.Running -> {
                TermText("${st.stage} ${asciiBar(st.progress)} ${(st.progress * 100).toInt()}%", size = 12.sp)
                Elapsed(st.startedAtMs)
            }
            is ProcessState.Exporting ->
                TermText("exportando ${asciiBar(st.progress)} ${(st.progress * 100).toInt()}%", size = 12.sp)
            is ProcessState.Done -> TermText("pronto: ${st.summary}", color = Palette.Prompt, size = 12.sp)
            is ProcessState.Failed -> TermText("erro: ${st.message}", color = Palette.Error, size = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val st = ui.process) {
                is ProcessState.Running -> Action("[ cancelar ]", onClick = onCancel)
                is ProcessState.Exporting -> Action("[ cancelar exportação ]", onClick = onCancelExport)
                else -> {
                    Action("[ processar ]", enabled = estimate?.isSuccess == true) { onProcess() }
                    if (st is ProcessState.Done) Action("[ exportar WAV ]") { onExport(st.suggestedName) }
                }
            }
        }
        TermText(
            "o original não é alterado; a saída é um arquivo novo. A entrada é decodificada em 16 bits; sem dither.",
            color = Palette.Dim, size = 11.sp,
        )
    }
}

@Composable
private fun Elapsed(startedAt: Long) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { value = SystemClock.elapsedRealtime(); delay(500) }
    }
    TermText("decorrido ${formatTime(now - startedAt)}", color = Palette.Dim, size = 12.sp)
}

@Composable
private fun Action(label: String, enabled: Boolean = true, color: Color = Palette.Fg, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) { TermText(label, color = if (enabled) color else Palette.Dim) }
}
