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
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.PaulstretchRenderer
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
    private var currentUri: Uri? = null

    private val workDir: File get() = File(getApplication<Application>().cacheDir, "kadavra-work")

    // ---------------------------------------------------------------- import / playback

    fun importAudio(uri: Uri) {
        loadJob?.cancel()
        procJob?.cancel()
        exportJob?.cancel()
        stopTicker()
        player.release()
        currentUri = uri
        _pos.value = 0
        _ui.update {
            it.copy(load = LoadState.Loading(null, "decodificando"), playing = false, message = null, process = ProcessState.Idle)
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
                player.prepare(
                    uri,
                    onReady = { _ui.update { it.copy(load = LoadState.Ready(Track(name, wf))) } },
                    onCompleted = {
                        stopTicker()
                        player.seekTo(0)
                        _pos.value = 0
                        _ui.update { it.copy(playing = false) }
                    },
                    onError = { fail(it) },
                )
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

    fun togglePlay() {
        if (_ui.value.load !is LoadState.Ready) return
        if (_ui.value.playing) pause() else {
            player.play()
            _ui.update { it.copy(playing = true) }
            startTicker()
        }
    }

    fun pause() {
        if (!_ui.value.playing) return
        player.pause()
        stopTicker()
        _pos.value = player.positionMs()
        _ui.update { it.copy(playing = false) }
    }

    fun stop() {
        if (_ui.value.load !is LoadState.Ready) return
        player.pause()
        stopTicker()
        player.seekTo(0)
        _pos.value = 0
        _ui.update { it.copy(playing = false) }
    }

    fun seekFraction(f: Float) {
        val ready = _ui.value.load as? LoadState.Ready ?: return
        val ms = (f.coerceIn(0f, 1f) * ready.track.waveform.durationMs).toLong()
        player.seekTo(ms)
        _pos.value = ms
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
    fun nextSeed() = _ui.update { it.copy(seed = it.seed + 1, process = idleIfFinished(it.process)) }
    fun prevSeed() = _ui.update { it.copy(seed = maxOf(0L, it.seed - 1), process = idleIfFinished(it.process)) }

    private fun idleIfFinished(p: ProcessState): ProcessState =
        if (p is ProcessState.Done || p is ProcessState.Failed) ProcessState.Idle else p

    fun startPaulstretch() {
        val s = _ui.value
        val ready = s.load as? LoadState.Ready ?: return
        val uri = currentUri ?: return
        if (s.process is ProcessState.Running || s.process is ProcessState.Exporting) return
        val params = PaulstretchParams(s.stretch, s.windowSeconds, s.seed)
        val baseName = ready.track.name.substringBeforeLast('.', ready.track.name)
        val started = SystemClock.elapsedRealtime()
        var lastP = -1f
        fun report(stage: String, p: Float) {
            if (p - lastP >= 0.005f || p >= 1f) {
                lastP = p
                _ui.update { if (it.process is ProcessState.Running) it.copy(process = ProcessState.Running(stage, p, started)) else it }
            }
        }
        _ui.update { it.copy(process = ProcessState.Running("preparando", 0f, started), message = null) }
        procJob = viewModelScope.launch {
            try {
                val done = runPaulstretch(uri, params, baseName, ::report)
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

    private suspend fun runPaulstretch(
        uri: Uri, params: PaulstretchParams, baseName: String, report: (String, Float) -> Unit,
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
            params.validate()
            val wf = FileOutputStream(pcm).buffered(1 shl 16).use { sink ->
                WaveformDecoder.decode(getApplication(), uri, pcmSink = sink) { p -> report("decodificando PCM", p * 0.2f) }
            }
            if (wf.frames <= 0L) throw InvalidParamException("O áudio decodificado está vazio.")
            PaulstretchPlanner.plan(params, wf.frames, wf.channels, wf.sampleRate, work.usableSpace)
            val r = RawPcmFileSource(pcm, wf.sampleRate, wf.channels).use { src ->
                PaulstretchRenderer.render(src, params, out, work, { p ->
                    report(if (p < 0.85f) "paulstretch (STFT)" else "gravando WAV", 0.2f + 0.8f * p)
                }, cancelled)
            }
            val secs = r.outputFrames.toDouble() / r.sampleRate
            val outPeak = minOf(r.peak, PaulstretchRenderer.PEAK_CEILING)
            val summary = "${formatTime((secs * 1000).toLong())} · ${r.sampleRate} Hz · ${r.channels} ch · WAV PCM 16 bits · pico ${dbfsText(outPeak)} dBFS" +
                if (r.gain < 1f) " (ganho reduzido de ${dbfsText(r.peak)} dBFS para evitar clipping)" else ""
            val name = "${baseName}_paulstretch_${String.format(java.util.Locale.US, "%.1f", params.stretch)}x.wav"
            ok = true
            ProcessState.Done(out, summary, name)
        } finally {
            pcm.delete()
            if (!ok) out.delete()
        }
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
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel()
        player.release()
        try { workDir.deleteRecursively() } catch (_: Exception) {}
    }
}
