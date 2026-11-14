package com.kadrava.audio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kadrava.audio.engine.EngineCatalog
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.designVersion
import com.kadrava.audio.util.formatClock

private val FONT = Term.font

@Composable
fun HomeScreen(vm: KadavraViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val pos = vm.positionMs.collectAsState()
    val liveStats = vm.liveStats.collectAsState()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-wav")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause(); vm.stopLive() }
    val light = Palette.light
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = light
            c.isAppearanceLightNavigationBars = light
        }
    }
    val scope = rememberCoroutineScope()

    val load = ui.load
    val original = (load as? LoadState.Ready)?.track
    val active = ui.previewTrack ?: original
    val reduced = rememberReducedMotion()
    val liveOn = ui.live == LiveState.On
    val running = ui.process as? ProcessState.Running
    val processing = running != null || ui.process is ProcessState.Exporting || load is LoadState.Loading ||
        ui.previewLoading || ui.live is LiveState.Preparing
    val logoProgress: Float? = running?.progress ?: (load as? LoadState.Loading)?.progress ?: (ui.process as? ProcessState.Exporting)?.progress
    val versionName = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } }
    val levelFn = remember(vm) { { vm.levelNow() } }

    val engine = EngineCatalog.all.firstOrNull { it.id == ui.selectedEngine } ?: EngineCatalog.all.first()
    val spec: EngineSpec = when (engine.id) {
        "mouton" -> moutonSpec(ui, original?.waveform, vm)
        "mose" -> moseSpec(ui, original?.waveform, vm)
        "safo" -> safoSpec(ui, original?.waveform, vm)
        "heisenberg" -> heisenbergSpec(ui, original?.waveform, vm)
        "wagner" -> wagnerSpec(ui, original?.waveform, vm)
        "guglielmo" -> guglielmoSpec(ui, vm)
        else -> EngineSpec(emptyList(), false, null, {}, {})
    }
    val done = (ui.process as? ProcessState.Done) ?: (ui.process as? ProcessState.Exporting)?.done
    val hasAudio = original != null
    val canPlay = active != null && !ui.previewLoading

    CompositionLocalProvider(LocalSelection provides remember { SelectionState(scope) }) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Header(
                hasAudio = hasAudio, playing = ui.playing || liveOn, processing = processing,
                error = load is LoadState.Error || ui.process is ProcessState.Failed,
                progress = logoProgress, level = levelFn, version = designVersion(versionName),
                light = light, onToggleLight = vm::toggleLight,
            )
            Sep()
            EngineHeader(ui, vm, glowing = running != null || liveOn, reduced = reduced)

            // the module: pages and sub-pages extend sideways, never downwards
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                ModuleView(spec.pages, ui.page, ui.sub, vm::setPage, vm::setSub)
            }

            Sep()
            ProcessRow(ui, vm, spec, hasAudio)
            StatusLine(ui, spec, liveStats)
            Sep()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TermItem(listOf(Span("$ "), Span("[Import]", kind = SpanKind.CONTROL)), separator = false,
                    onClick = { picker.launch(arrayOf("audio/*", "application/ogg")) }, description = "Import audio")
                if (load is LoadState.Loading) {
                    TermItem(listOf(Span("[Cancel]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::cancelLoad)
                } else {
                    TermItem(listOf(Span("[Eject]", kind = SpanKind.CONTROL)), enabled = load !is LoadState.Idle || ui.process is ProcessState.Done || ui.previewTrack != null, separator = false,
                        onClick = vm::eject, description = "Eject: remove all audio")
                }
            }
            AudioRow(load, active?.name)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (ui.previewTrack == null) {
                    TermItem(listOf(Span("[Listen]", kind = SpanKind.CONTROL)), enabled = ui.process is ProcessState.Done && !ui.previewLoading,
                        separator = false, onClick = { vm.listenResult() })
                } else {
                    TermItem(listOf(Span("[Original]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::listenOriginal)
                }
                TermItem(listOf(Span("[Reintegrate]", kind = SpanKind.CONTROL)), enabled = ui.process is ProcessState.Done && !ui.previewLoading,
                    separator = false, onClick = vm::reintegrate)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TermItem(listOf(Span("[Play]", kind = SpanKind.CONTROL)), enabled = canPlay, separator = false, onClick = vm::play)
                TermItem(listOf(Span("[Pause]", kind = SpanKind.CONTROL)), enabled = canPlay, separator = false, onClick = vm::pause)
                TermItem(listOf(Span("[Rewind]", kind = SpanKind.CONTROL)), enabled = canPlay, separator = false, onClick = vm::rewind)
            }
            SeekRow(active?.waveform?.durationMs ?: 0L, pos, canPlay, vm::seekFraction)
            Sep()
            ExportBar(ui.process, done != null, onExport = { done?.let { exporter.launch(it.suggestedName) } }, onCancel = vm::cancelExport)
        }
    }
}

@Composable
private fun Sep() = BasicText(">", style = TextStyle(fontFamily = FONT, fontSize = 12.sp, color = Palette.Fg))

/** Logo on the left, title nudged to its right, version on the far right. */
@Composable
private fun Header(
    hasAudio: Boolean, playing: Boolean, processing: Boolean, error: Boolean,
    progress: Float?, level: () -> Float, version: String, light: Boolean, onToggleLight: () -> Unit,
) {
    val letters = "Kadavra"
    val title = buildAnnotatedString {
        letters.forEachIndexed { i, c ->
            if (i > 0) append(" ")
            withStyle(SpanStyle(color = Palette.Rainbow[i % Palette.Rainbow.size])) { append(c) }
        }
        withStyle(SpanStyle(color = Palette.Fg)) { append(" 𝆯") }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        KadavraLogo(hasAudio, playing, processing, error, progress, level, Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        BasicText(title, style = TextStyle(fontFamily = FONT, fontSize = 18.sp))
        Spacer(Modifier.weight(1f))
        TermItem(listOf(Span(if (light) "[Dark]" else "[Light]", kind = SpanKind.CONTROL)), separator = false, key = "theme",
            onClick = onToggleLight, description = "Switch light and dark theme")
        Spacer(Modifier.width(6.dp))
        BasicText(version, style = TextStyle(fontFamily = FONT, fontSize = 12.sp, color = Palette.Fg))
    }
}

/** `[<]  Safo  2/7  [>]`: one engine at a time; the name glows while it works. */
@Composable
private fun EngineHeader(ui: UiState, vm: KadavraViewModel, glowing: Boolean, reduced: Boolean) {
    val list = EngineCatalog.all
    val idx = list.indexOfFirst { it.id == ui.selectedEngine }.coerceAtLeast(0)
    val e = list[idx]
    val color = Palette.Rainbow[idx % Palette.Rainbow.size]
    CarouselRow("eng-prev", "eng-next", { vm.moveEngine(-1) }, { vm.moveEngine(1) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EngineName(e.name, color, glowing, reduced)
            if (!e.implemented) BasicText(" (n/a)", style = TextStyle(fontFamily = FONT, fontSize = Term.base, color = Palette.Disabled))
            BasicText("  ${idx + 1}/${list.size}", style = TextStyle(fontFamily = FONT, fontSize = 12.sp, color = Palette.Fg))
        }
    }
}

@Composable
private fun EngineName(name: String, color: Color, glowing: Boolean, reduced: Boolean) {
    val base = TextStyle(fontFamily = FONT, fontSize = 16.sp, color = color)
    if (!glowing) {
        BasicText(name, style = base)
    } else {
        val pulse = if (reduced) 1f else {
            val t = rememberInfiniteTransition(label = "glow")
            val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "g")
            v
        }
        BasicText(name, style = base.copy(shadow = Shadow(color.copy(alpha = pulse), Offset.Zero, 8f + 16f * pulse)))
    }
}

@Composable
private fun ProcessRow(ui: UiState, vm: KadavraViewModel, spec: EngineSpec, hasAudio: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        when {
            ui.process is ProcessState.Running ->
                TermItem(listOf(Span("[Cancel]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::cancelProcessing)
            ui.process is ProcessState.Exporting ->
                TermItem(listOf(Span("[Cancel]", kind = SpanKind.CONTROL)), separator = false, onClick = vm::cancelExport)
            else -> {
                TermItem(listOf(Span("[Process]", kind = SpanKind.CONTROL)), enabled = spec.canProcess, separator = false, onClick = spec.onProcess)
                TermItem(listOf(Span("[Preview]", kind = SpanKind.CONTROL)), enabled = spec.canProcess, separator = false, onClick = spec.onPreview)
                if (spec.showLive) {
                    TermItem(listOf(Span(if (ui.live == LiveState.Off) "[Live]" else "[Stop]", kind = SpanKind.CONTROL)),
                        enabled = hasAudio, key = "live", separator = false, onClick = vm::toggleLive, description = "Real-time playback")
                }
            }
        }
    }
}

/** One line: progress, live readouts, last message or result, in that order of priority. */
@Composable
private fun StatusLine(ui: UiState, spec: EngineSpec, stats: State<LiveStats>) {
    val style = TextStyle(fontFamily = FONT, fontSize = 12.sp, color = Palette.Fg)
    val st = ui.process
    val live = ui.live
    when {
        st is ProcessState.Running -> StatusText("${st.stage} ${asciiBar(st.progress, 10)} ${(st.progress * 100).toInt()}%")
        st is ProcessState.Exporting -> StatusText("export ${asciiBar(st.progress, 10)} ${(st.progress * 100).toInt()}%")
        live is LiveState.Preparing -> StatusText("live ${asciiBar(live.progress, 10)} ${(live.progress * 100).toInt()}%")
        live == LiveState.On -> {
            val s = stats.value
            StatusText("LOAD ${(s.load * 100).toInt()}% L ${asciiBar(meter(s.peakL), 6)} R ${asciiBar(meter(s.peakR), 6)}")
        }
        ui.message != null -> StatusText(ui.message)
        st is ProcessState.Done -> StatusText(st.summary)
        st is ProcessState.Failed -> StatusText("error: ${st.message}")
        spec.problem != null && ui.load is LoadState.Ready -> StatusText(spec.problem)
        else -> BasicText(" ", style = style, maxLines = 1)
    }
}

private fun meter(peak: Float): Float = if (peak <= 1e-4f) 0f else ((20f * kotlin.math.log10(peak) + 48f) / 48f).coerceIn(0f, 1f)

@Composable
private fun StatusText(text: String) = TermText(text, size = 12.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp))

@Composable
private fun AudioRow(load: LoadState, name: String?) {
    val text = when (load) {
        is LoadState.Loading -> "= [${load.stage}]" + (load.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
        is LoadState.Error -> "= [error] ${load.message}"
        else -> if (name != null) "= [$name]" else "= [Empty]"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TermText(text, maxLines = 1)
    }
}

/** `----------------  1:18/3:45`: a 16-dash playhead (tap or drag) and a typeable current time. */
@Composable
private fun SeekRow(durationMs: Long, pos: State<Long>, enabled: Boolean, onSeek: (Float) -> Unit) {
    val frac = if (durationMs > 0) (pos.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        SliderBar("seek", frac, 16, formatClock(pos.value), enabled, onSeek)
        Spacer(Modifier.width(8.dp))
        TermNumberField(
            valueText = formatClock(pos.value), key = "seek",
            onType = if (enabled && durationMs > 0) ({ sec: Double -> onSeek((sec * 1000.0 / durationMs).toFloat().coerceIn(0f, 1f)) }) else null,
            modifier = Modifier.width(52.dp), time = true,
        )
        BasicText("/${formatClock(durationMs)}", style = TextStyle(fontFamily = FONT, fontSize = Term.base, color = Palette.Fg))
    }
}

/** The fixed bottom tab: `.:[Export].:` and nothing else below it. */
@Composable
private fun ExportBar(process: ProcessState, ready: Boolean, onExport: () -> Unit, onCancel: () -> Unit) {
    val exporting = process is ProcessState.Exporting
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TermItem(
            listOf(Span(".:", Palette.Fg), Span(if (exporting) "[Cancel]" else "[Export]", kind = SpanKind.CONTROL), Span(".:", Palette.Fg)),
            enabled = ready, separator = false, key = "export",
            onClick = if (exporting) onCancel else onExport, description = if (exporting) "Cancel export" else "Export WAV",
        )
    }
}
