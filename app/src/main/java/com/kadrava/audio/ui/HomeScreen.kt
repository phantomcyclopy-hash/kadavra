package com.kadrava.audio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kadrava.audio.engine.EngineCatalog
import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatTime

@Composable
fun HomeScreen(vm: KadavraViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val pos = vm.positionMs.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-wav")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    val load = ui.load
    val track = (load as? LoadState.Ready)?.track
    val logoState = when (load) {
        is LoadState.Loading -> load.progress?.let { LogoState.Progress(it) } ?: LogoState.Indeterminate
        is LoadState.Error -> LogoState.Error
        else -> when (val p = ui.process) {
            is ProcessState.Running -> LogoState.Progress(p.progress)
            is ProcessState.Exporting -> LogoState.Progress(p.progress)
            is ProcessState.Failed -> LogoState.Error
            else -> LogoState.Idle
        }
    }
    val duration = track?.waveform?.durationMs ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        TermText("Bem-vindo ao Kadavra!")
        Spacer(Modifier.height(16.dp))
        TermText("modelos e efeitos:", color = Palette.Dim)
        EngineCatalog.all.forEach { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Ver estado de ${e.name}") { vm.selectEngine(e) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TermText(if (ui.selectedEngine == e.id) "> ${e.name}" else "- ${e.name}", color = if (ui.selectedEngine == e.id) Palette.Prompt else Palette.Fg)
                if (!e.implemented) TermText("  [não implementado]", color = Palette.Dim, size = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // prompt line: "~ <audio loaded | importar áudio>" + logo
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermText("~", color = Palette.Prompt)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Importar áudio") {
                        picker.launch(arrayOf("audio/*", "application/ogg"))
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                TermText(track?.name ?: "importar áudio", color = Palette.Prompt, maxLines = 1)
            }
            KadavraLogo(logoState, Modifier.size(32.dp))
        }

        // state line (always reflects real state)
        when (load) {
            LoadState.Idle -> TermText("nenhum áudio carregado.", color = Palette.Dim, size = 12.sp)
            is LoadState.Loading -> {
                val p = load.progress
                TermText(
                    if (p != null) "${load.stage} ${asciiBar(p)} ${(p * 100).toInt()}%"
                    else "${load.stage} (progresso não mensurável)",
                    color = Palette.Fg, size = 12.sp,
                )
                Box(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Cancelar carregamento") { vm.cancelLoad() },
                    contentAlignment = Alignment.CenterStart,
                ) { TermText("[ cancelar ]", color = Palette.Dim) }
            }
            is LoadState.Ready -> {
                val w = load.track.waveform
                TermText("${w.mime} · ${w.sampleRate} Hz · ${w.channels} ch · ${formatTime(w.durationMs)}", color = Palette.Dim, size = 12.sp)
            }
            is LoadState.Error -> {
                TermText("erro: ${load.message}", color = Palette.Error, size = 12.sp)
                TermText("toque em \"importar áudio\" para tentar outro arquivo.", color = Palette.Dim, size = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        TermText("forma de onda · pico por coluna · normalizada", color = Palette.Dim, size = 11.sp)
        AsciiWaveform(
            peaks = track?.waveform?.peaks,
            progress = { if (duration > 0) pos.value.toFloat() / duration else 0f },
            onSeek = vm::seekFraction,
        )

        if (track != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionText(if (ui.playing) "[ pausar ]" else "[ tocar ]") { vm.togglePlay() }
                ActionText("[ parar ]") { vm.stop() }
                Spacer(Modifier.weight(1f))
                TimeReadout(pos, duration)
            }
        }

        if (ui.selectedEngine == "paulstretch") {
            PaulstretchPanel(
                ui = ui,
                wf = track?.waveform,
                onStretch = vm::setStretch,
                onWindow = vm::setWindow,
                onSeedMinus = vm::prevSeed,
                onSeedPlus = vm::nextSeed,
                onProcess = vm::startPaulstretch,
                onCancel = vm::cancelProcessing,
                onExport = { name -> exporter.launch(name) },
                onCancelExport = vm::cancelExport,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            TermText("selecione um motor implementado na lista para processar e exportar.", color = Palette.Dim, size = 12.sp)
        }
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            TermText("> $it", color = Palette.Fg, size = 12.sp)
        }
    }
}

@Composable
private fun TimeReadout(pos: State<Long>, duration: Long) {
    TermText("${formatTime(pos.value)} / ${formatTime(duration)}", size = 13.sp)
}

@Composable
private fun ActionText(label: String, color: Color = Palette.Fg, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) { TermText(label, color = color) }
}
