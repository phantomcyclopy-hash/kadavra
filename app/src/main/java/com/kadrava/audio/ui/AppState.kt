package com.kadrava.audio.ui

import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.AnalysisResult
import com.kadrava.audio.dsp.WindowType
import java.io.File

class Track(val name: String, val waveform: WaveformData)

sealed interface LoadState {
    data object Idle : LoadState
    /** [progress] null = not measurable (truthful indeterminate). */
    data class Loading(val progress: Float?, val stage: String) : LoadState
    data class Ready(val track: Track) : LoadState
    data class Error(val message: String) : LoadState
}

sealed interface ProcessState {
    data object Idle : ProcessState
    data class Running(val stage: String, val progress: Float, val startedAtMs: Long) : ProcessState
    data class Done(val file: File, val summary: String, val suggestedName: String) : ProcessState
    data class Exporting(val progress: Float, val done: Done) : ProcessState
    data class Failed(val message: String) : ProcessState
}

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data class Running(val stage: String, val progress: Float) : AnalysisState
    /** [playheadMs] is where the playhead was when the analysis started; [source] names what was analysed. */
    class Done(val result: AnalysisResult, val playheadMs: Long, val source: String) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

data class UiState(
    val load: LoadState = LoadState.Idle,
    val playing: Boolean = false,
    val message: String? = null,
    val selectedEngine: String? = null,
    val stretch: Double = 8.0,
    val windowSeconds: Double = 0.25,
    val seed: Long = 1L,
    val slicesEnabled: Boolean = false,
    val sliceFactor: Int = 8,
    val sliceVariation: Double = 0.3,
    val eqGains: List<Double> = List(6) { 0.0 },
    val anFft: Int = 4096,
    val anWindow: WindowType = WindowType.HANN,
    val anLog: Boolean = true,
    val anHold: Boolean = true,
    val anShowPlayhead: Boolean = false,
    val analysis: AnalysisState = AnalysisState.Idle,
    val process: ProcessState = ProcessState.Idle,
    /** The processed result loaded into the player for listening (original stays in [load]). */
    val previewTrack: Track? = null,
    val previewLoading: Boolean = false,
)
