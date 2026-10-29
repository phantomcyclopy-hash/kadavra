package com.kadrava.audio.engine

/** What Kadavra plans to offer. [implemented] is the truth: the UI never advertises more. */
data class EngineInfo(val id: String, val name: String, val description: String, val implemented: Boolean)

object EngineCatalog {
    val all: List<EngineInfo> = listOf(
        EngineInfo("mouton", "Mouton", "extensão temporal espectral extrema (STFT, fase aleatória)", true),
        EngineInfo("cindy", "Cindy", "analisador espectral", false),
        EngineInfo("mose", "Mose", "equalizador multibanda", false),
        EngineInfo("wagner", "Wagner", "reverb por convolução e redes de delay", false),
        EngineInfo("heisenberg", "Heisenberg", "delay com filtro, pitch, granular e espectral", false),
        EngineInfo("vlad", "Vlad", "distorção: bit-crush, waveshaping, wavefolding", false),
        EngineInfo("safo", "Safo", "síntese granular avançada", false),
        EngineInfo("guglielmo", "Guglielmo", "ruído e drone", false),
        EngineInfo("velazquez", "Velazquez", "imagem para som", false),
    )
}
