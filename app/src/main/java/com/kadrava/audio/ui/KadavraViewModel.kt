package com.kadrava.audio.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadrava.audio.audio.DecodeException
import com.kadrava.audio.audio.PlaybackController
import com.kadrava.audio.audio.WaveformDecoder
import com.kadrava.audio.dsp.AnalyzerParams
import com.kadrava.audio.dsp.EqEngine
import com.kadrava.audio.dsp.EqParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.OfflineRenderer
import com.kadrava.audio.dsp.PaulstretchEngine
import com.kadrava.audio.dsp.SampleSink
import com.kadrava.audio.dsp.SpectrumAnalyzer
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.PaulstretchRenderer
import com.kadrava.audio.dsp.PcmSource
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.dsp.SlicedPcmSource
import com.kadrava.audio.dsp.Slicer
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.RawPcmFileSource
import com.kadrava.audio.engine.EngineInfo
import com.kadrava.audio.util.dbfsText
import com.kadrava.audio.util.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class KadavraViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _pos = MutableStateFlow(0L)
    /** Kept apart from [ui] so the 20 Hz position ticks don't recompose the whole screen. */
    val positionMs: StateFlow<Long> = _pos.asStateFlow()

    private val player = PlaybackController(app)
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var procJob: Job? = null
    private var exportJob: Job? = null
    private var previewJob: Job? = null
    private var analysisJob: Job? = null
    private var previewUri: Uri? = null
    private var currentUri: Uri? = null

    private val workDir: File get() = File(getApplication<Application>().cacheDir, "kadavra-work")
    private val analysisDir: File get() = File(getApplication<Application>().cacheDir, "kadavra-analysis")

    // ---------------------------------------------------------------- import / playback

    fun importAudio(uri: Uri) {
        loadJob?.cancel()
        procJob?.cancel()
        exportJob?.cancel()
        previewJob?.cancel()
        analysisJob?.cancel()
        previewUri = null
        stopTicker()
        player.release()
        currentUri = uri
        _pos.value = 0
        _ui.update {
            it.copy(load = LoadState.Loading(null, "decodificando"), playing = false, message = null, process = ProcessState.Idle, previewTrack = null, previewLoading = false, analysis = AnalysisState.Idle)
        }
        loadJob = viewModelScope.launch {
            try {
                val name = queryName(uri)
                val wf = WaveformDecoder.decode(getApplication(), uri) { p ->
                    _ui.update { s ->
                        if (s.load is LoadState.Loading) s.copy(load = LoadState.Loading(p, "decodificando")) else s
                    }
                }
                _ui.update { it.copy(load = LoadState.Loading(null, "iniciando player")) }
                preparePlayer(uri) { _ui.update { it.copy(load = LoadState.Ready(Track(name, wf))) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecodeException) {
                fail(e.message ?: "Erro ao carregar o áudio.")
            } catch (e: Exception) {
                fail("Erro inesperado: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelLoad() {
        if (_ui.value.load !is LoadState.Loading) return
        loadJob?.cancel()
        player.release()
        _ui.update { it.copy(load = LoadState.Idle, playing = false, message = "carregamento cancelado.") }
    }

    private fun canPlay(): Boolean = _ui.value.load is LoadState.Ready && !_ui.value.previewLoading

    fun play() {
        if (!canPlay() || _ui.value.playing) return
        player.play()
        _ui.update { it.copy(playing = true) }
        startTicker()
    }

    fun pause() {
        if (!_ui.value.playing) return
        player.pause()
        stopTicker()
        _pos.value = player.positionMs()
        _ui.update { it.copy(playing = false) }
    }

    fun rewind() {
        if (!canPlay()) return
        player.seekTo(0)
        _pos.value = 0
    }

    private fun activeDurationMs(): Long =
        (_ui.value.previewTrack ?: (_ui.value.load as? LoadState.Ready)?.track)?.waveform?.durationMs ?: 0L

    fun seekFraction(f: Float) {
        if (!canPlay()) return
        val ms = (f.coerceIn(0f, 1f) * activeDurationMs()).toLong()
        player.seekTo(ms)
        _pos.value = ms
    }

    private fun preparePlayer(uri: Uri, onReady: () -> Unit) {
        player.prepare(
            uri,
            onReady = onReady,
            onCompleted = {
                stopTicker()
                player.seekTo(0)
                _pos.value = 0
                _ui.update { it.copy(playing = false) }
            },
            onError = { msg ->
                if (_ui.value.previewTrack != null || _ui.value.previewLoading) {
                    _ui.update { it.copy(previewTrack = null, previewLoading = false, playing = false, message = "erro ao ouvir o resultado: $msg") }
                    currentUri?.let { u -> preparePlayer(u) {} }
                } else fail(msg)
            },
        )
    }

    // ---------------------------------------------------------------- preview of the result

    fun listenResult() {
        val st = _ui.value.process
        val done = (st as? ProcessState.Done) ?: (st as? ProcessState.Exporting)?.done ?: return
        if (_ui.value.previewLoading) return
        previewJob?.cancel()
        pause()
        stopTicker()
        player.release()
        _pos.value = 0
        _ui.update { it.copy(previewLoading = true, playing = false, message = null) }
        previewJob = viewModelScope.launch {
            try {
                val uri = Uri.fromFile(done.file)
                val wf = WaveformDecoder.decode(getApplication(), uri)
                preparePlayer(uri) { previewUri = uri; _ui.update { it.copy(previewTrack = Track("resultado", wf), previewLoading = false) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(previewLoading = false, message = "não foi possível ouvir o resultado: ${e.message ?: e.javaClass.simpleName}") }
                currentUri?.let { u -> preparePlayer(u) {} }
            }
        }
    }

    fun listenOriginal() {
        if (_ui.value.previewTrack == null && !_ui.value.previewLoading) return
        val uri = currentUri ?: return
        previewJob?.cancel()
        pause()
        stopTicker()
        player.release()
        _pos.value = 0
        previewUri = null
        _ui.update { it.copy(previewTrack = null, previewLoading = false, playing = false) }
        preparePlayer(uri) {}
    }

    /** Stops everything, deletes temporary files and clears the session (the original file is untouched). */
    fun endSession() {
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel(); previewJob?.cancel(); analysisJob?.cancel()
        stopTicker()
        player.release()
        currentUri = null
        previewUri = null
        try { analysisDir.deleteRecursively() } catch (_: Exception) {}
        _pos.value = 0
        try { workDir.deleteRecursively() } catch (_: Exception) {}
        _ui.value = UiState()
    }

    // ---------------------------------------------------------------- engines

    fun selectEngine(e: EngineInfo) {
        _ui.update {
            if (e.implemented) it.copy(selectedEngine = if (it.selectedEngine == e.id) null else e.id, message = null)
            else it.copy(message = "${e.id}: não implementado nesta versão (${e.description}). Previsto para fases futuras.")
        }
    }

    fun setStretch(v: Double) = _ui.update { it.copy(stretch = v, process = idleIfFinished(it.process)) }
    fun setWindow(v: Double) = _ui.update { it.copy(windowSeconds = v, process = idleIfFinished(it.process)) }
    fun setSlices(enabled: Boolean) = _ui.update { it.copy(slicesEnabled = enabled, process = idleIfFinished(it.process)) }
    fun setSliceFactor(v: Int) = _ui.update { it.copy(sliceFactor = v, process = idleIfFinished(it.process)) }
    fun setSliceVariation(v: Double) = _ui.update { it.copy(sliceVariation = v, process = idleIfFinished(it.process)) }
    fun nextSeed() = _ui.update { it.copy(seed = it.seed + 1, process = idleIfFinished(it.process)) }
    fun prevSeed() = _ui.update { it.copy(seed = maxOf(0L, it.seed - 1), process = idleIfFinished(it.process)) }

    private fun idleIfFinished(p: ProcessState): ProcessState =
        if (p is ProcessState.Done || p is ProcessState.Failed) ProcessState.Idle else p

    fun setEqGain(i: Int, db: Double) = _ui.update {
        it.copy(eqGains = it.eqGains.toMutableList().also { g -> g[i] = db }, process = idleIfFinished(it.process))
    }
    fun resetEq() = _ui.update { it.copy(eqGains = List(6) { 0.0 }, process = idleIfFinished(it.process)) }

    fun startMouton() {
        val s = _ui.value
        val params = PaulstretchParams(s.stretch, s.windowSeconds, s.seed)
        startOffline(
            tag = "mouton_${String.format(java.util.Locale.US, "%.1f", params.stretch)}x",
            stage = "mouton (STFT)",
            check = { fr, ch, sr, free -> PaulstretchPlanner.plan(params, fr, ch, sr, free) },
            produce = { src, sink, prog, canc -> PaulstretchEngine(params).run(src, sink, prog, canc) },
        )
    }

    fun startMose() {
        val s = _ui.value
        val eq = EqParams.default.withGains(s.eqGains)
        if (eq.isFlat) {
            _ui.update { it.copy(message = "mose: todas as bandas em 0 dB; não há o que processar.") }
            return
        }
        startOffline(
            tag = "mose",
            stage = "mose (equalizador)",
            check = { fr, ch, sr, free -> PaulstretchPlanner.plan(PaulstretchParams(stretch = 1.0), fr, ch, sr, free) },
            produce = { src, sink, prog, canc -> EqEngine(eq).run(src, sink, prog, canc) },
            note = { sr ->
                val sk = EqEngine.skipped(eq, sr).size
                if (sk == 0) "" else " · $sk banda(s) acima de 0,45×taxa ignorada(s)"
            },
        )
    }

    private fun startOffline(
        tag: String,
        stage: String,
        check: (frames: Long, channels: Int, sampleRate: Int, freeBytes: Long) -> Unit,
        produce: (PcmSource, SampleSink, (Float) -> Unit, () -> Boolean) -> Long,
        note: (sampleRate: Int) -> String = { "" },
    ) {
        val s = _ui.value
        val ready = s.load as? LoadState.Ready ?: return
        val uri = currentUri ?: return
        if (s.process is ProcessState.Running || s.process is ProcessState.Exporting) return
        val slices: SliceParams? = if (s.slicesEnabled) SliceParams(factor = s.sliceFactor, sizeVariation = s.sliceVariation, seed = s.seed) else null
        if (s.previewTrack != null || s.previewLoading) listenOriginal()
        val baseName = ready.track.name.substringBeforeLast('.', ready.track.name)
        val started = SystemClock.elapsedRealtime()
        var lastP = -1f
        fun report(st: String, p: Float) {
            if (p - lastP >= 0.005f || p >= 1f) {
                lastP = p
                _ui.update { if (it.process is ProcessState.Running) it.copy(process = ProcessState.Running(st, p, started)) else it }
            }
        }
        _ui.update { it.copy(process = ProcessState.Running("preparando", 0f, started), message = null) }
        procJob = viewModelScope.launch {
            try {
                val done = runOffline(uri, slices, baseName, tag, stage, check, produce, note, ::report)
                _ui.update { it.copy(process = done) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(process = ProcessState.Idle, message = "processamento cancelado.") }
                throw e
            } catch (e: ProcessingCancelled) {
                _ui.update { it.copy(process = ProcessState.Idle, message = "processamento cancelado.") }
            } catch (e: InvalidParamException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "Parâmetros inválidos.")) }
            } catch (e: LimitException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "Limite excedido.")) }
            } catch (e: DecodeException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "Falha ao decodificar.")) }
            } catch (e: Exception) {
                _ui.update { it.copy(process = ProcessState.Failed("Falha no processamento: ${e.message ?: e.javaClass.simpleName}. Verifique o espaço livre e tente novamente.")) }
            }
        }
    }

    fun cancelProcessing() {
        if (_ui.value.process !is ProcessState.Running) return
        procJob?.cancel()
        _ui.update { it.copy(process = ProcessState.Idle, message = "cancelando...") }
    }

    private suspend fun runOffline(
        uri: Uri, slices: SliceParams?, baseName: String, tag: String, stage: String,
        check: (Long, Int, Int, Long) -> Unit,
        produce: (PcmSource, SampleSink, (Float) -> Unit, () -> Boolean) -> Long,
        note: (Int) -> String,
        report: (String, Float) -> Unit,
    ): ProcessState.Done = withContext(Dispatchers.IO) {
        val job = this.coroutineContext[Job]!!
        val cancelled = { !job.isActive }
        val work = workDir
        work.deleteRecursively()
        work.mkdirs()
        val pcm = File(work, "input.pcm")
        val out = File(work, "output.wav")
        var ok = false
        try {
            val wf = FileOutputStream(pcm).buffered(1 shl 16).use { sink ->
                WaveformDecoder.decode(getApplication(), uri, pcmSink = sink) { p -> report("decodificando PCM", p * 0.2f) }
            }
            if (wf.frames <= 0L) throw InvalidParamException("O áudio decodificado está vazio.")
            check(wf.frames, wf.channels, wf.sampleRate, work.usableSpace)
            val r = RawPcmFileSource(pcm, wf.sampleRate, wf.channels).use { base ->
                slices?.validate()
                val src: PcmSource = if (slices != null) SlicedPcmSource(base, Slicer.plan(base.frames, base.sampleRate, slices)) else base
                OfflineRenderer.render(wf.channels, wf.sampleRate, out, work, { p ->
                    report(if (p < 0.85f) stage else "gravando WAV", 0.2f + 0.8f * p)
                }, cancelled) { sink, prog -> produce(src, sink, prog, cancelled) }
            }
            val secs = r.outputFrames.toDouble() / r.sampleRate
            val outPeak = minOf(r.peak, PaulstretchRenderer.PEAK_CEILING)
            val sliceNote = if (slices != null) " · picotado em ${minOf(slices.factor.toLong(), wf.frames)} slices e embaralhado (semente ${slices.seed})" else ""
            val summary = "${formatTime((secs * 1000).toLong())} · ${r.sampleRate} Hz · ${r.channels} ch · WAV PCM 16 bits · pico ${dbfsText(outPeak)} dBFS" + sliceNote + note(r.sampleRate) +
                if (r.gain < 1f) " (ganho reduzido de ${dbfsText(r.peak)} dBFS para evitar clipping)" else ""
            ok = true
            ProcessState.Done(out, summary, "${baseName}_$tag.wav")
        } finally {
            pcm.delete()
            if (!ok) out.delete()
        }
    }

    // ---------------------------------------------------------------- Cindy (analysis)

    fun setAnFft(v: Int) = _ui.update { it.copy(anFft = v) }
    fun cycleAnWindow() = _ui.update { it.copy(anWindow = it.anWindow.next()) }
    fun toggleAnLog() = _ui.update { it.copy(anLog = !it.anLog) }
    fun toggleAnHold() = _ui.update { it.copy(anHold = !it.anHold) }
    fun toggleAnView() = _ui.update { it.copy(anShowPlayhead = !it.anShowPlayhead) }

    fun startAnalysis() {
        val s = _ui.value
        if (s.load !is LoadState.Ready || s.previewLoading || s.analysis is AnalysisState.Running) return
        val uri = previewUri ?: currentUri ?: return
        val label = if (s.previewTrack != null) "resultado" else "original"
        val params = AnalyzerParams(s.anFft, s.anWindow)
        val playMs = _pos.value
        var lastP = -1f
        fun report(st: String, p: Float) {
            if (p - lastP >= 0.01f || p >= 1f) {
                lastP = p
                _ui.update { if (it.analysis is AnalysisState.Running) it.copy(analysis = AnalysisState.Running(st, p)) else it }
            }
        }
        _ui.update { it.copy(analysis = AnalysisState.Running("decodificando PCM", 0f)) }
        analysisJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val job = this.coroutineContext[Job]!!
                    val dir = analysisDir
                    dir.deleteRecursively(); dir.mkdirs()
                    val pcm = File(dir, "analysis.pcm")
                    try {
                        val wf = FileOutputStream(pcm).buffered(1 shl 16).use { sink ->
                            WaveformDecoder.decode(getApplication(), uri, pcmSink = sink) { p -> report("decodificando PCM", p * 0.3f) }
                        }
                        if (wf.frames <= 0L) throw InvalidParamException("O áudio decodificado está vazio.")
                        RawPcmFileSource(pcm, wf.sampleRate, wf.channels).use { src ->
                            SpectrumAnalyzer.analyze(src, params, playMs * wf.sampleRate / 1000, { p -> report("analisando", 0.3f + 0.7f * p) }, { !job.isActive })
                        }
                    } finally { pcm.delete() }
                }
                _ui.update { it.copy(analysis = AnalysisState.Done(res, playMs, label)) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(analysis = AnalysisState.Idle, message = "análise cancelada.") }
                throw e
            } catch (e: ProcessingCancelled) {
                _ui.update { it.copy(analysis = AnalysisState.Idle, message = "análise cancelada.") }
            } catch (e: DecodeException) {
                _ui.update { it.copy(analysis = AnalysisState.Failed(e.message ?: "Falha ao decodificar.")) }
            } catch (e: InvalidParamException) {
                _ui.update { it.copy(analysis = AnalysisState.Failed(e.message ?: "Parâmetros inválidos.")) }
            } catch (e: Exception) {
                _ui.update { it.copy(analysis = AnalysisState.Failed("Falha na análise: ${e.message ?: e.javaClass.simpleName}.")) }
            }
        }
    }

    fun cancelAnalysis() {
        if (_ui.value.analysis !is AnalysisState.Running) return
        analysisJob?.cancel()
    }

    // ---------------------------------------------------------------- export

    fun exportTo(uri: Uri) {
        val st = _ui.value.process as? ProcessState.Done ?: return
        exportJob?.cancel()
        _ui.update { it.copy(process = ProcessState.Exporting(0f, st)) }
        exportJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val total = st.file.length().coerceAtLeast(1L)
                    val os = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Não foi possível abrir o destino escolhido.")
                    os.use { dst ->
                        FileInputStream(st.file).use { src ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                ensureActive()
                                val n = src.read(buf)
                                if (n < 0) break
                                dst.write(buf, 0, n)
                                copied += n
                                _ui.update { s -> if (s.process is ProcessState.Exporting) s.copy(process = ProcessState.Exporting(copied.toFloat() / total, st)) else s }
                            }
                            dst.flush()
                        }
                    }
                }
                _ui.update { it.copy(process = st, message = "exportado com sucesso.") }
            } catch (e: CancellationException) {
                discardPartial(uri)
                _ui.update { it.copy(process = st, message = "exportação cancelada; arquivo parcial removido.") }
                throw e
            } catch (e: Exception) {
                discardPartial(uri)
                _ui.update { it.copy(process = st, message = "falha ao exportar: ${e.message ?: e.javaClass.simpleName}. Escolha outro local e tente de novo.") }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    /** Removes only the (incomplete) document this app just created at the user's chosen destination. */
    private fun discardPartial(uri: Uri) {
        try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- internals

    private fun fail(msg: String) {
        stopTicker()
        player.release()
        _ui.update { it.copy(load = LoadState.Error(msg), playing = false) }
    }

    private fun startTicker() {
        stopTicker()
        tickJob = viewModelScope.launch {
            while (isActive) {
                _pos.value = player.positionMs()
                delay(50)
            }
        }
    }

    private fun stopTicker() { tickJob?.cancel(); tickJob = null }

    private fun queryName(uri: Uri): String {
        val q: String? = try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        return q ?: uri.lastPathSegment ?: "áudio"
    }

    override fun onCleared() {
        stopTicker()
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel(); previewJob?.cancel(); analysisJob?.cancel()
        player.release()
        try { workDir.deleteRecursively(); analysisDir.deleteRecursively() } catch (_: Exception) {}
    }
}
