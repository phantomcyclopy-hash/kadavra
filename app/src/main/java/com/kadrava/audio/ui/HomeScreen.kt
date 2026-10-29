package com.kadrava.audio.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kadrava.audio.engine.EngineCatalog
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.designVersion
import com.kadrava.audio.util.ellipsize
import com.kadrava.audio.util.formatClock
import com.kadrava.audio.viz.AsciiWaveformRenderer
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: KadavraViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val pos = vm.positionMs.collectAsState()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-wav")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    val load = ui.load
    val original = (load as? LoadState.Ready)?.track
    val active = ui.previewTrack ?: original
    val reduced = rememberReducedMotion()
    val busy = load is LoadState.Loading || ui.process is ProcessState.Running ||
        ui.process is ProcessState.Exporting || ui.previewLoading
    val logoState = when {
        load is LoadState.Loading -> load.progress?.let { LogoState.Progress(it) } ?: LogoState.Indeterminate
        load is LoadState.Error -> LogoState.Error
        ui.process is ProcessState.Running -> LogoState.Progress((ui.process as ProcessState.Running).progress)
        ui.process is ProcessState.Exporting -> LogoState.Progress((ui.process as ProcessState.Exporting).progress)
        ui.previewLoading -> LogoState.Indeterminate
        ui.process is ProcessState.Failed -> LogoState.Error
        else -> LogoState.Idle
    }
    val done = (ui.process as? ProcessState.Done) ?: (ui.process as? ProcessState.Exporting)?.done
    val versionName = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } }

    val measurer = rememberTextMeasurer()
    val cellPx = remember(measurer) {
        measurer.measure("M", TextStyle(fontFamily = Term.font, fontSize = Term.base)).size.width.coerceAtLeast(1)
    }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val cols = ((constraints.maxWidth - 2 * padPx) / cellPx).toInt().coerceIn(20, 90)
        CompositionLocalProvider(LocalTermCols provides cols) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Header()
                BusyGlyphs(busy, reduced)
                TermItem(listOf(Span("# ${designVersion(versionName)}", Palette.Version)))

                // engines
                EngineCatalog.all.forEach { e ->
                    TermItem(
                        buildList {
                            add(Span("~ ", Palette.Prompt))
                            add(Span(e.name, if (e.implemented) Palette.Fg else Palette.Dim))
                            if (!e.implemented) add(Span("  (indisponível)", Palette.Dim))
                        },
                        onClick = { vm.selectEngine(e) },
                        description = e.name + if (e.implemented) "" else ", indisponível",
                    )
                    if (ui.selectedEngine == e.id) when (e.id) {
                        "mouton" -> MoutonPanel(ui, original?.waveform, vm)
                        "mose" -> MosePanel(ui, original?.waveform, vm)
                        "cindy" -> CindyPanel(ui, active?.waveform?.sampleRate, active != null && !ui.previewLoading, vm)
                    }
                }

                // import
                TermItem(
                    listOf(Span("$ "), Span("[Import]", Palette.Prompt)),
                    onClick = { picker.launch(arrayOf("audio/*", "application/ogg")) },
                    description = "Importar áudio",
                )

                // loaded audio line
                when {
                    load is LoadState.Loading -> {
                        val p = load.progress
                        TermItem(listOf(Span("= "), Span("[${load.stage}] "), Span(if (p != null) "${asciiBar(p, 10)} ${(p * 100).toInt()}%" else "(progresso não mensurável)", Palette.Dim)))
                        TermItem(listOf(Span("[Cancel]", Palette.Prompt)), onClick = vm::cancelLoad)
                    }
                    load is LoadState.Error -> {
                        TermItem(listOf(Span("= "), Span("[erro] ", Palette.Error), Span(load.message, Palette.Error)))
                        TermItem(listOf(Span("use [Import] para tentar outro arquivo.", Palette.Dim)))
                    }
                    active != null -> AudioLine(active, pos, cols)
                    else -> TermItem(listOf(Span("= "), Span("[nenhum áudio carregado]", Palette.Dim)))
                }

                // seek line
                SeekLine(active?.waveform?.durationMs ?: 0L, pos, cols, enabled = active != null && !ui.previewLoading, onSeek = vm::seekFraction)

                // transport
                val canPlay = active != null && !ui.previewLoading
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermItem(listOf(Span("[Play]", Palette.Prompt)), enabled = canPlay, separator = false, onClick = vm::play)
                    TermItem(listOf(Span("[Pause]", Palette.Prompt)), enabled = canPlay, separator = false, onClick = vm::pause)
                    TermItem(listOf(Span("[Rewind]", Palette.Prompt)), enabled = canPlay, separator = false, onClick = vm::rewind)
                }
                TermText(">", color = Palette.Dim)

                // export
                TermItem(
                    listOf(Span("! "), Span("[Export]", Palette.Prompt)),
                    enabled = ui.process is ProcessState.Done,
                    onClick = { (ui.process as? ProcessState.Done)?.let { exporter.launch(it.suggestedName) } },
                    separator = false,
                    description = "Exportar WAV",
                )
                TermItem(listOf(Span(".:", Palette.Dim)), separator = false)
                TermItem(listOf(Span("[${done?.suggestedName ?: "Audio_(Output).wav"}]:.", if (done != null) Palette.Fg else Palette.Dim)))
                ui.message?.let { TermItem(listOf(Span("> $it", Palette.Fg)), separator = false) }

                // logo block
                TermItem(listOf(Span("+")), separator = false)
                TermItem(listOf(Span("+")), separator = false)
                Row(Modifier.padding(vertical = 4.dp)) {
                    TermText("  ")
                    KadavraLogo(logoState, Modifier.size(56.dp), reduced)
                }
                TermItem(listOf(Span("+")), separator = false)
                TermItem(listOf(Span("+")), separator = false)

                // end session
                TermItem(
                    listOf(Span("[Logout]", Palette.Prompt), Span(";.", Palette.Dim)),
                    separator = false,
                    onClick = { vm.endSession(); (ctx as? Activity)?.finishAffinity() },
                    description = "Encerrar sessão e sair",
                )
            }
        }
    }
}

@Composable
private fun Header() {
    val letters = "Kadrava"
    val text = buildAnnotatedString {
        letters.forEachIndexed { i, c ->
            if (i > 0) append(" ")
            withStyle(SpanStyle(color = Palette.Rainbow[i % Palette.Rainbow.size])) { append(c) }
        }
        withStyle(SpanStyle(color = Palette.Fg)) { append(" *") }
    }
    BasicText(
        text,
        modifier = Modifier.semantics { contentDescription = "Kadavra" },
        style = TextStyle(fontFamily = Term.font, fontSize = 18.sp),
    )
}

/** Four yellow-green glyph lines: the drawn pattern when idle, rotating line characters while work runs. */
@Composable
private fun BusyGlyphs(busy: Boolean, reduced: Boolean) {
    val idle = listOf("\\", "/", "\\", "\\")
    val frames = listOf("\\", "|", "/", "-")
    val animate = busy && !reduced
    val tick by produceState(0, animate) {
        if (animate) while (true) { delay(120); value += 1 }
    }
    val rows = if (!animate) idle else List(4) { frames[(tick + it) % 4] }
    Column(Modifier.semantics { contentDescription = if (busy) "Processando" else "Ocioso" }) {
        rows.forEach { BasicText(it, style = TextStyle(fontFamily = Term.font, fontSize = Term.base, color = Palette.Glyph)) }
    }
}

@Composable
private fun AudioLine(track: Track, pos: State<Long>, cols: Int) {
    val dur = track.waveform.durationMs
    val clockLen = formatClock(dur).length * 2 + 4          // "(m:ss/m:ss) " reserved width
    val prefix = "= ["
    val suffixLen = 2 + clockLen                              // "] " + clock
    val rem = cols - prefix.length - suffixLen
    val waveCols = (rem / 3).coerceIn(8, 17)
    val nameMax = (rem - waveCols).coerceAtLeast(6)
    val wave = remember(track, waveCols) { AsciiWaveformRenderer.renderLine(track.waveform.peaks, waveCols) }
    TermItem(
        buildList {
            add(Span(prefix))
            add(Span(ellipsize(track.name, nameMax)))
            add(Span("] (${formatClock(pos.value)}/${formatClock(dur)}) "))
            wave.forEachIndexed { i, c -> add(Span(c.toString(), Iridescence.cell(i.toFloat() / waveCols, 0.7f))) }
        },
        description = "${track.name}, ${formatClock(pos.value)} de ${formatClock(dur)}",
    )
}

/** `------#----------`: the playhead; tap or drag to seek. */
@Composable
private fun SeekLine(durationMs: Long, pos: State<Long>, cols: Int, enabled: Boolean, onSeek: (Float) -> Unit) {
    var widthPx by remember { mutableIntStateOf(1) }
    val frac = if (durationMs > 0) (pos.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val len = cols.coerceAtLeast(8)
    val hash = if (enabled) Math.round(frac * (len - 1)) else -1
    val text = buildAnnotatedString {
        for (i in 0 until len) {
            if (i == hash) withStyle(SpanStyle(color = Palette.Prompt)) { append("#") }
            else withStyle(SpanStyle(color = Palette.Dim)) { append("-") }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onSeek((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / widthPx).coerceIn(0f, 1f)) }
            }
            .semantics { contentDescription = "Posição de reprodução, toque ou arraste para buscar" }
            .padding(vertical = 8.dp)
    ) {
        BasicText(text, style = TextStyle(fontFamily = Term.font, fontSize = Term.base))
        BasicText(">", style = TextStyle(fontFamily = Term.font, fontSize = Term.base, color = Palette.Dim))
    }
}
