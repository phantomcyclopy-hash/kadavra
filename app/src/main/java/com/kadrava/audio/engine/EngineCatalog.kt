package com.kadrava.audio.engine

/** What Kadavra plans to offer. [implemented] is the truth: the UI never advertises more. */
data class EngineInfo(val id: String, val name: String, val description: String, val implemented: Boolean)

object EngineCatalog {
    val all: List<EngineInfo> = listOf(
        EngineInfo("mouton", "Mouton", "extreme spectral time stretch (STFT, random phase)", true),
        EngineInfo("safo", "Safo", "granular synthesis modelled on Grainstorm, live and offline", true),
        EngineInfo("mose", "Mose", "6-band equalizer (biquad)", true),
        EngineInfo("wagner", "Wagner", "feedback-delay-network reverb (8 lines, damping, diffusion, modulation)", true),
        EngineInfo("heisenberg", "Heisenberg", "delay with filtered, pitch-shifting feedback, plus distortion", true),
        EngineInfo("guglielmo", "Guglielmo", "noise and drone: resonant bank, micro-oscillator wall, chaos, feedback", true),
        EngineInfo("velazquez", "Velazquez", "image to sound", false),
    )
}
