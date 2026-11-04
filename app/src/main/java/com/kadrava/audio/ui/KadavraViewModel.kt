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
import com.kadrava.audio.audio.SafoLive
import com.kadrava.audio.audio.WaveformDecoder
import com.kadrava.audio.dsp.EqEngine
import com.kadrava.audio.dsp.EqParams
import com.kadrava.audio.dsp.HeisenbergEngine
import com.kadrava.audio.dsp.HeisenbergParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.OfflinePlanner
import com.kadrava.audio.dsp.OfflineRenderer
import com.kadrava.audio.dsp.PaulstretchEngine
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.PaulstretchPlanner
import com.kadrava.audio.dsp.PaulstretchRenderer
import com.kadrava.audio.dsp.PcmSource
import com.kadrava.audio.dsp.ProcessingCancelled
import com.kadrava.audio.dsp.RawPcmFileSource
import com.kadrava.audio.dsp.SafoEngine
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.SampleSink
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.dsp.SlicedPcmSource
import com.kadrava.audio.dsp.Slicer
import com.kadrava.audio.dsp.TruncatedPcmSource
import com.kadrava.audio.engine.EngineCatalog
import com.kadrava.audio.util.dbfsText
import com.kadrava.audio.util.formatClock
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
import kotlin.math.log10
import kotlin.math.max

class KadavraViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _pos = MutableStateFlow(0L)
    /** Kept apart from [ui] so the 20 Hz position ticks don't recompose the whole screen. */
    val positionMs: StateFlow<Long> = _pos.asStateFlow()

    private val _liveStats = MutableStateFlow(LiveStats())
    val liveStats: StateFlow<LiveStats> = _liveStats.asStateFlow()

    private val player = PlaybackController(app)
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var procJob: Job? = null
    private var exportJob: Job? = null
    private var previewJob: Job? = null
    private var liveJob: Job? = null
    private var liveTick: Job? = null
    private var currentUri: Uri? = null
    private var autoPlayNext = false

    private var live: SafoLive? = null
    private var liveSrc: RawPcmFileSource? = null
    private var liveFile: File? = null
    private var liveKey: String? = null
    private var liveRate = 0
    private var liveCh = 0

    private val cache: File get() = getApplication<Application>().cacheDir
    private val workDir: File get() = File(cache, "kadavra-work")
    private val materialDir: File get() = File(cache, "kadavra-material")
    private val liveDir: File get() = File(cache, "kadavra-live")

    // ---------------------------------------------------------------- import / eject / playback

    fun importAudio(uri: Uri) {
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel(); previewJob?.cancel(); liveJob?.cancel()
        stopLive()
        stopTicker()
        player.release()
        currentUri = uri
        liveKey = null
        _pos.value = 0
        _ui.update {
            it.copy(
                load = LoadState.Loading(null, "decoding"), playing = false, message = null,
                process = ProcessState.Idle, previewTrack = null, previewLoading = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val name = queryName(uri)
                val wf = WaveformDecoder.decode(getApplication(), uri) { p ->
                    _ui.update { s -> if (s.load is LoadState.Loading) s.copy(load = LoadState.Loading(p, "decoding")) else s }
                }
                _ui.update { it.copy(load = LoadState.Loading(null, "starting player")) }
                preparePlayer(uri) { _ui.update { it.copy(load = LoadState.Ready(Track(name, wf))) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecodeException) {
                fail(e.message ?: "could not load the audio")
            } catch (e: Exception) {
                fail("unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelLoad() {
        if (_ui.value.load !is LoadState.Loading) return
        loadJob?.cancel()
        player.release()
        _ui.update { it.copy(load = LoadState.Idle, playing = false, message = "load cancelled") }
    }

    /** Removes all audio: loaded file, results, previews, live player and every temporary file. */
    fun eject() {
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel(); previewJob?.cancel(); liveJob?.cancel()
        stopLive()
        stopTicker()
        player.release()
        currentUri = null
        liveKey = null
        _pos.value = 0
        deleteTemporaryFiles()
        _ui.update {
            it.copy(
                load = LoadState.Idle, playing = false, message = null, process = ProcessState.Idle,
                previewTrack = null, previewLoading = false, live = LiveState.Off,
            )
        }
    }

    private fun canPlay(): Boolean = _ui.value.load is LoadState.Ready && !_ui.value.previewLoading

    fun play() {
        if (!canPlay() || _ui.value.playing) return
        stopLive()
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
                    _ui.update { it.copy(previewTrack = null, previewLoading = false, playing = false, message = "result playback error: $msg") }
                    currentUri?.let { u -> preparePlayer(u) {} }
                } else fail(msg)
            },
        )
    }

    /** 0..1 loudness of what is sounding now (peak envelope at the playhead, or the live output) for the logo. */
    fun levelNow(): Float {
        val s = _ui.value
        if (s.live == LiveState.On) return dbToLevel(max(_liveStats.value.peakL, _liveStats.value.peakR))
        if (!s.playing) return 0f
        val wf = (s.previewTrack ?: (s.load as? LoadState.Ready)?.track)?.waveform ?: return 0f
        if (wf.durationMs <= 0 || wf.peaks.isEmpty()) return 0f
        val idx = ((_pos.value.toDouble() / wf.durationMs) * wf.peaks.size).toInt().coerceIn(0, wf.peaks.size - 1)
        return dbToLevel(wf.peaks[idx])
    }

    private fun dbToLevel(peak: Float): Float = if (peak <= 1e-5f) 0f else ((20f * log10(peak) + 50f) / 50f).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- result preview / reintegrate

    fun listenResult(autoPlay: Boolean = false) {
        val st = _ui.value.process
        val done = (st as? ProcessState.Done) ?: (st as? ProcessState.Exporting)?.done ?: return
        if (_ui.value.previewLoading) return
        stopLive()
        autoPlayNext = autoPlay
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
                preparePlayer(uri) {
                    _ui.update { it.copy(previewTrack = Track("result", wf), previewLoading = false) }
                    if (autoPlayNext) { autoPlayNext = false; play() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(previewLoading = false, message = "cannot play the result: ${e.message ?: e.javaClass.simpleName}") }
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
        _ui.update { it.copy(previewTrack = null, previewLoading = false, playing = false) }
        preparePlayer(uri) {}
    }

    /**
     * Makes the processed result the new raw material, as many times as you like: it is copied to a lasting
     * file and loaded as the current audio. The previous material and the previous result are discarded;
     * the file you imported is never touched.
     */
    fun reintegrate() {
        val done = _ui.value.process as? ProcessState.Done ?: return
        viewModelScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val dir = File(materialDir, System.currentTimeMillis().toString())
                    dir.mkdirs()
                    File(dir, done.suggestedName).also { done.file.copyTo(it, overwrite = true) }
                }
                importAudio(Uri.fromFile(copy))
                withContext(Dispatchers.IO) {
                    materialDir.listFiles()?.filter { it != copy.parentFile }?.forEach { it.deleteRecursively() }
                }
                _ui.update { it.copy(message = "reintegrated") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = "reintegrate failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ---------------------------------------------------------------- engines and modules

    fun moveEngine(delta: Int) {
        val list = EngineCatalog.all
        val i = list.indexOfFirst { it.id == _ui.value.selectedEngine }.coerceAtLeast(0)
        val next = list[((i + delta) % list.size + list.size) % list.size]
        if (next.id != "safo") stopLive()
        _ui.update { it.copy(selectedEngine = next.id, page = 0, sub = 0, message = null) }
    }

    fun setPage(i: Int) = _ui.update { it.copy(page = i, sub = 0) }
    fun setSub(i: Int) = _ui.update { it.copy(sub = i) }

    private fun idleIfFinished(p: ProcessState): ProcessState =
        if (p is ProcessState.Done || p is ProcessState.Failed) ProcessState.Idle else p

    fun setStretch(v: Double) = _ui.update { it.copy(stretch = v, process = idleIfFinished(it.process)) }
    fun setWindow(v: Double) = _ui.update { it.copy(windowSeconds = v, process = idleIfFinished(it.process)) }
    fun setSeed(v: Long) = _ui.update { it.copy(seed = v.coerceAtLeast(0L), process = idleIfFinished(it.process)) }
    fun nextSeed() = _ui.update { it.copy(seed = it.seed + 1, process = idleIfFinished(it.process)) }
    fun prevSeed() = _ui.update { it.copy(seed = maxOf(0L, it.seed - 1), process = idleIfFinished(it.process)) }
    fun setSlices(enabled: Boolean) = _ui.update { it.copy(slicesEnabled = enabled, process = idleIfFinished(it.process)) }
    fun setSliceFactor(v: Int) = _ui.update { it.copy(sliceFactor = v, process = idleIfFinished(it.process)) }
    fun setSliceVariation(v: Double) = _ui.update { it.copy(sliceVariation = v, process = idleIfFinished(it.process)) }
    fun setEqGain(i: Int, db: Double) = _ui.update {
        it.copy(eqGains = it.eqGains.toMutableList().also { g -> g[i] = db }, process = idleIfFinished(it.process))
    }
    fun resetEq() = _ui.update { it.copy(eqGains = List(6) { 0.0 }, process = idleIfFinished(it.process)) }

    /** Changes Safo parameters; a running live player hears them immediately. */
    fun safo(f: (SafoParams) -> SafoParams) {
        _ui.update { it.copy(safo = f(it.safo), process = idleIfFinished(it.process)) }
        live?.setParams(_ui.value.safo)
    }

    fun heis(f: (HeisenbergParams) -> HeisenbergParams) =
        _ui.update { it.copy(heis = f(it.heis), process = idleIfFinished(it.process)) }

    fun setLiveFreeze(on: Boolean) {
        _ui.update { it.copy(liveFrozen = on) }
        live?.frozen = on
    }

    // ---------------------------------------------------------------- offline processing

    fun startMouton(preview: Boolean = false) {
        val s = _ui.value
        val params = PaulstretchParams(s.stretch, s.windowSeconds, s.seed)
        startOffline(
            tag = "mouton_${String.format(java.util.Locale.US, "%.1f", params.stretch)}x" + if (preview) "_preview" else "",
            stage = "mouton",
            check = { fr, ch, sr, free -> PaulstretchPlanner.plan(params, fr, ch, sr, free) },
            produce = { src, sink, prog, canc -> PaulstretchEngine(params).run(src, sink, prog, canc) },
            preview = preview,
            truncateInputSeconds = PREVIEW_IN_MOUTON_S,
        )
    }

    fun startMose(preview: Boolean = false) {
        val eq = EqParams.default.withGains(_ui.value.eqGains)
        if (eq.isFlat) {
            _ui.update { it.copy(message = "mose: all bands flat") }
            return
        }
        startOffline(
            tag = "mose" + if (preview) "_preview" else "",
            stage = "mose",
            check = { fr, ch, sr, free -> PaulstretchPlanner.plan(PaulstretchParams(stretch = 1.0), fr, ch, sr, free) },
            produce = { src, sink, prog, canc -> EqEngine(eq).run(src, sink, prog, canc) },
            note = { sr ->
                val sk = EqEngine.skipped(eq, sr).size
                if (sk == 0) "" else " $sk band(s) above 0.45 x rate skipped"
            },
            preview = preview,
            truncateInputSeconds = PREVIEW_IN_MOSE_S,
        )
    }

    fun startHeisenberg(preview: Boolean = false) {
        val hp = _ui.value.heis
        if (hp.isNoOp) {
            _ui.update { it.copy(message = "heisenberg: mix 0 and shape off, nothing to do") }
            return
        }
        startOffline(
            tag = "heisenberg" + if (preview) "_preview" else "",
            stage = "heisenberg",
            check = { fr, ch, sr, free -> OfflinePlanner.plan(fr, fr + hp.tailFrames(sr), 2, sr, free) },
            produce = { src, sink, prog, canc -> HeisenbergEngine(hp).run(src, sink, prog, canc) },
            outChannels = 2,
            preview = preview,
            truncateInputSeconds = PREVIEW_IN_MOSE_S,
        )
    }

    fun startSafo(preview: Boolean = false) {
        val full = _ui.value.safo
        val sp = if (preview) full.copy(durationSeconds = minOf(full.durationSeconds, PREVIEW_OUT_S)) else full
        startOffline(
            tag = "safo" + if (preview) "_preview" else "",
            stage = "safo",
            check = { fr, ch, sr, free -> OfflinePlanner.plan(fr, sp.outputFrames(sr), 2, sr, free) },
            produce = { src, sink, prog, canc -> SafoEngine(sp).run(src, sink, prog, canc) },
            useSlices = false,
            outChannels = 2,
            preview = preview,
        )
    }

    private fun startOffline(
        tag: String,
        stage: String,
        check: (frames: Long, channels: Int, sampleRate: Int, freeBytes: Long) -> Unit,
        produce: (PcmSource, SampleSink, (Float) -> Unit, () -> Boolean) -> Long,
        note: (sampleRate: Int) -> String = { "" },
        useSlices: Boolean = true,
        outChannels: Int? = null,
        preview: Boolean = false,
        truncateInputSeconds: Double? = null,
    ) {
        val s = _ui.value
        val ready = s.load as? LoadState.Ready ?: return
        val uri = currentUri ?: return
        if (s.process is ProcessState.Running || s.process is ProcessState.Exporting) return
        val slices: SliceParams? = if (useSlices && s.slicesEnabled) SliceParams(factor = s.sliceFactor, sizeVariation = s.sliceVariation, seed = s.seed) else null
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
        _ui.update { it.copy(process = ProcessState.Running("preparing", 0f, started), message = null) }
        procJob = viewModelScope.launch {
            try {
                val done = runOffline(uri, slices, baseName, tag, stage, check, produce, note, outChannels, if (preview) truncateInputSeconds else null, ::report)
                _ui.update { it.copy(process = done) }
                if (preview) listenResult(autoPlay = true)
            } catch (e: CancellationException) {
                _ui.update { it.copy(process = ProcessState.Idle, message = "processing cancelled") }
                throw e
            } catch (e: ProcessingCancelled) {
                _ui.update { it.copy(process = ProcessState.Idle, message = "processing cancelled") }
            } catch (e: InvalidParamException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "invalid parameters")) }
            } catch (e: LimitException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "limit exceeded")) }
            } catch (e: DecodeException) {
                _ui.update { it.copy(process = ProcessState.Failed(e.message ?: "decode failed")) }
            } catch (e: Exception) {
                _ui.update { it.copy(process = ProcessState.Failed("processing failed: ${e.message ?: e.javaClass.simpleName} (check free space)")) }
            }
        }
    }

    fun cancelProcessing() {
        if (_ui.value.process !is ProcessState.Running) return
        procJob?.cancel()
        _ui.update { it.copy(process = ProcessState.Idle, message = "cancelling") }
    }

    private suspend fun runOffline(
        uri: Uri, slices: SliceParams?, baseName: String, tag: String, stage: String,
        check: (Long, Int, Int, Long) -> Unit,
        produce: (PcmSource, SampleSink, (Float) -> Unit, () -> Boolean) -> Long,
        note: (Int) -> String,
        outChannels: Int?,
        truncateSeconds: Double?,
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
                WaveformDecoder.decode(getApplication(), uri, pcmSink = sink) { p -> report("decoding", p * 0.2f) }
            }
            if (wf.frames <= 0L) throw InvalidParamException("the decoded audio is empty")
            check(wf.frames, wf.channels, wf.sampleRate, work.usableSpace)
            val r = RawPcmFileSource(pcm, wf.sampleRate, wf.channels).use { raw ->
                val base: PcmSource = if (truncateSeconds != null) TruncatedPcmSource(raw, (truncateSeconds * raw.sampleRate).toLong()) else raw
                slices?.validate()
                val src: PcmSource = if (slices != null) SlicedPcmSource(base, Slicer.plan(base.frames, base.sampleRate, slices)) else base
                OfflineRenderer.render(outChannels ?: wf.channels, wf.sampleRate, out, work, { p ->
                    report(if (p < 0.85f) stage else "writing", 0.2f + 0.8f * p)
                }, cancelled) { sink, prog -> produce(src, sink, prog, cancelled) }
            }
            val secs = r.outputFrames.toDouble() / r.sampleRate
            val outPeak = minOf(r.peak, PaulstretchRenderer.PEAK_CEILING)
            val previewNote = if (truncateSeconds != null) " preview" else ""
            val sliceNote = if (slices != null) " ${minOf(slices.factor.toLong(), wf.frames)} slices" else ""
            val gainNote = if (r.gain < 1f) " (lowered from ${dbfsText(r.peak)} dB)" else ""
            val summary = "${formatClock((secs * 1000).toLong())} ${r.sampleRate}Hz ${r.channels}ch peak ${dbfsText(outPeak)}dB" +
                previewNote + sliceNote + note(r.sampleRate) + gainNote
            ok = true
            ProcessState.Done(out, summary, "${baseName}_$tag.wav")
        } finally {
            pcm.delete()
            if (!ok) out.delete()
        }
    }

    // ---------------------------------------------------------------- Safo live

    fun toggleLive() {
        if (_ui.value.live == LiveState.Off) startLive() else stopLive()
    }

    private fun startLive() {
        val s = _ui.value
        val ready = s.load as? LoadState.Ready ?: return
        val uri = currentUri ?: return
        if (s.live != LiveState.Off) return
        pause()
        _ui.update { it.copy(live = LiveState.Preparing(0f), message = null) }
        liveJob = viewModelScope.launch {
            try {
                val key = uri.toString()
                if (liveKey != key || liveFile?.exists() != true) {
                    withContext(Dispatchers.IO) {
                        val dir = liveDir
                        dir.deleteRecursively(); dir.mkdirs()
                        val f = File(dir, "live.pcm")
                        val wf = FileOutputStream(f).buffered(1 shl 16).use { sink ->
                            WaveformDecoder.decode(getApplication(), uri, pcmSink = sink) { p ->
                                _ui.update { u -> if (u.live is LiveState.Preparing) u.copy(live = LiveState.Preparing(p)) else u }
                            }
                        }
                        if (wf.channels !in 1..2) throw LimitException("only mono or stereo audio is supported")
                        liveFile = f; liveRate = wf.sampleRate; liveCh = wf.channels; liveKey = key
                    }
                }
                val src = RawPcmFileSource(liveFile!!, liveRate, liveCh)
                liveSrc = src
                val l = SafoLive(src, _ui.value.safo, ready.track.waveform.peaks) { msg ->
                    viewModelScope.launch { stopLive(); _ui.update { it.copy(message = "live error: $msg") } }
                }
                l.frozen = _ui.value.liveFrozen
                live = l
                l.start()
                _ui.update { it.copy(live = LiveState.On) }
                liveTick = viewModelScope.launch {
                    while (isActive) {
                        live?.let { _liveStats.value = LiveStats(it.load, it.peakL, it.peakR, it.readFraction) }
                        delay(50)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopLive()
                _ui.update { it.copy(message = "live: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveTick?.cancel(); liveTick = null
        live?.stop(); live = null
        try { liveSrc?.close() } catch (_: Exception) {}
        liveSrc = null
        _liveStats.value = LiveStats()
        if (_ui.value.live != LiveState.Off) _ui.update { it.copy(live = LiveState.Off) }
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
                        ?: throw IOException("cannot open the chosen destination")
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
                _ui.update { it.copy(process = st, message = "exported") }
            } catch (e: CancellationException) {
                discardPartial(uri)
                _ui.update { it.copy(process = st, message = "export cancelled") }
                throw e
            } catch (e: Exception) {
                discardPartial(uri)
                _ui.update { it.copy(process = st, message = "export failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    /** Removes only the (incomplete) document this app just created at the user's chosen destination. */
    private fun discardPartial(uri: Uri) {
        try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- internals

    private fun deleteTemporaryFiles() {
        try { workDir.deleteRecursively(); materialDir.deleteRecursively(); liveDir.deleteRecursively() } catch (_: Exception) {}
    }

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
        return q ?: uri.lastPathSegment ?: "audio"
    }

    override fun onCleared() {
        stopTicker()
        loadJob?.cancel(); procJob?.cancel(); exportJob?.cancel(); previewJob?.cancel(); liveJob?.cancel()
        stopLive()
        player.release()
        deleteTemporaryFiles()
    }

    private companion object {
        const val PREVIEW_OUT_S = 8.0
        const val PREVIEW_IN_MOUTON_S = 4.0
        const val PREVIEW_IN_MOSE_S = 10.0
    }
}
