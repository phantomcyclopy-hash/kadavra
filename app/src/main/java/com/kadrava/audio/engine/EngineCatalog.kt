package com.kadrava.audio.engine

/** What Kadavra plans to offer. [implemented] is the truth: the UI never advertises more. */
data class EngineInfo(val id: String, val name: String, val description: String, val implemented: Boolean)

object EngineCatalog {
    val all: List<EngineInfo> = listOf(
        EngineInfo("paulstretch", "Paulstretch", "extensão temporal espectral extrema (STFT, fase aleatória)", true),
        EngineInfo("analyzer", "Analisador espectral", "FFT com picos e RMS", false),
        EngineInfo("eq", "Equalizador", "multibanda com filtros biquad", false),
        EngineInfo("reverb", "Reverb", "sala, decaimento e amortecimento", false),
        EngineInfo("delay", "Delay", "tempo, feedback e ping-pong", false),
        EngineInfo("distortion", "Distorção / saturação", "waveshaping com drive", false),
        EngineInfo("granular", "Granular", "grãos com densidade e posição", false),
        EngineInfo("noise_drone", "Ruído e drone", "síntese de texturas sustentadas", false),
        EngineInfo("image_to_sound", "Imagem para som", "sonificação determinística de imagem", false),
    )
}
