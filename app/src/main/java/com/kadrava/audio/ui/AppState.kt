package com.kadrava.audio.ui

import com.kadrava.audio.audio.WaveformData
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

data class UiState(
    val load: LoadState = LoadState.Idle,
    val playing: Boolean = false,
    val message: String? = null,
    val selectedEngine: String? = null,
    val stretch: Double = 8.0,
    val windowSeconds: Double = 0.25,
    val seed: Long = 1L,
    val process: ProcessState = ProcessState.Idle,
)
