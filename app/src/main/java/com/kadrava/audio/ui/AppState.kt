package com.kadrava.audio.ui

import com.kadrava.audio.audio.WaveformData
import com.kadrava.audio.dsp.HeisenbergParams
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.WagnerParams
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

sealed interface LiveState {
    data object Off : LiveState
    data class Preparing(val progress: Float) : LiveState
    data object On : LiveState
}

/** Polled readouts of the live Safo player (kept apart from [UiState] so they do not recompose everything). */
data class LiveStats(val load: Float = 0f, val peakL: Float = 0f, val peakR: Float = 0f, val readFraction: Double = 0.0)

data class UiState(
    val load: LoadState = LoadState.Idle,
    val playing: Boolean = false,
    val message: String? = null,
    val selectedEngine: String? = "mouton",
    val page: Int = 0,
    val sub: Int = 0,
    val stretch: Double = 8.0,
    val windowSeconds: Double = 0.25,
    val seed: Long = 1L,
    val slicesEnabled: Boolean = false,
    val sliceFactor: Int = 8,
    val sliceVariation: Double = 0.3,
    val eqGains: List<Double> = List(6) { 0.0 },
    val safo: SafoParams = SafoParams(),
    val heis: HeisenbergParams = HeisenbergParams(),
    val wag: WagnerParams = WagnerParams(),
    val live: LiveState = LiveState.Off,
    val liveFrozen: Boolean = false,
    val process: ProcessState = ProcessState.Idle,
    /** The processed result loaded into the player for listening (the loaded audio stays in [load]). */
    val previewTrack: Track? = null,
    val previewLoading: Boolean = false,
)
