package com.kadrava.audio.dsp

class InvalidParamException(message: String) : IllegalArgumentException(message)
class LimitException(message: String) : Exception(message)
class ProcessingCancelled : Exception("processamento cancelado")

data class ParamSpec(
    val id: String, val label: String, val min: Double, val max: Double,
    val default: Double, val unit: String, val logScale: Boolean = false,
)

data class PaulstretchParams(
    val stretch: Double = 8.0,
    val windowSeconds: Double = 0.25,
    val seed: Long = 1L,
) {
    fun validate() {
        if (!stretch.isFinite() || stretch < MIN_STRETCH || stretch > MAX_STRETCH)
            throw InvalidParamException("stretch deve estar entre $MIN_STRETCH e $MAX_STRETCH (recebido $stretch).")
        if (!windowSeconds.isFinite() || windowSeconds < MIN_WINDOW_S || windowSeconds > MAX_WINDOW_S)
            throw InvalidParamException("janela deve estar entre $MIN_WINDOW_S e $MAX_WINDOW_S s (recebido $windowSeconds).")
    }

    /** Power-of-two FFT size covering the window, clamped to [MIN_FFT, MAX_FFT]. */
    fun fftSize(sampleRate: Int): Int {
        val target = sampleRate * windowSeconds
        var n = MIN_FFT
        while (n < target && n < MAX_FFT) n = n shl 1
        return n
    }

    fun outputFrames(inputFrames: Long): Long = maxOf(1L, Math.round(inputFrames * stretch))

    companion object {
        const val MIN_STRETCH = 1.0
        const val MAX_STRETCH = 100.0
        const val MIN_WINDOW_S = 0.05
        const val MAX_WINDOW_S = 1.0
        const val MIN_FFT = 1024
        const val MAX_FFT = 131072

        val specs = listOf(
            ParamSpec("stretch", "fator de extensão", MIN_STRETCH, MAX_STRETCH, 8.0, "x", logScale = true),
            ParamSpec("window", "janela", MIN_WINDOW_S, MAX_WINDOW_S, 0.25, "s", logScale = true),
        )
    }
}

class PaulstretchPlan(
    val outputFrames: Long,
    val fftSize: Int,
    val wavBytes: Long,
    val diskBytesNeeded: Long,
)

/** Pre-flight checks: refuses jobs that would exceed duration, WAV size or disk limits. */
object PaulstretchPlanner {
    const val MAX_INPUT_SECONDS = 7200.0
    const val MAX_OUTPUT_SECONDS = 3600.0
    const val MAX_WAV_BYTES = 1_500_000_000L
    const val DISK_MARGIN_BYTES = 64L * 1024 * 1024

    /**
     * Disk need = float32 scratch (4 B/sample) + final 16-bit WAV (2 B/sample) + [inputPcmBytesStillNeeded]
     * (the temporary 16-bit copy of the input, when it has not been written yet).
     */
    fun plan(
        params: PaulstretchParams, inputFrames: Long, channels: Int, sampleRate: Int,
        freeBytes: Long, inputPcmBytesStillNeeded: Long = 0L,
    ): PaulstretchPlan {
        params.validate()
        if (inputFrames <= 0L) throw InvalidParamException("O áudio está vazio.")
        if (channels !in 1..2) throw LimitException("Somente áudio mono ou estéreo é suportado (recebido $channels canais).")
        if (sampleRate !in 8000..192000) throw LimitException("Taxa de amostragem fora do suportado: $sampleRate Hz.")
        if (inputFrames.toDouble() / sampleRate > MAX_INPUT_SECONDS)
            throw LimitException("Áudio longo demais (máximo ${MAX_INPUT_SECONDS.toInt() / 60} min).")
        val outSeconds = inputFrames.toDouble() * params.stretch / sampleRate
        if (outSeconds > MAX_OUTPUT_SECONDS)
            throw LimitException("A saída teria %.0f min; o máximo é %.0f min. Reduza o fator de extensão.".format(outSeconds / 60, MAX_OUTPUT_SECONDS / 60))
        val out = params.outputFrames(inputFrames)
        val wav = out * channels * 2 + 44
        if (wav > MAX_WAV_BYTES)
            throw LimitException("O WAV de saída passaria de %d MB. Reduza o fator de extensão.".format(MAX_WAV_BYTES / 1_000_000))
        val need = out * channels * 4 + wav + inputPcmBytesStillNeeded
        if (freeBytes < need + DISK_MARGIN_BYTES)
            throw LimitException("Espaço livre insuficiente: são necessários ~%d MB temporários.".format((need + DISK_MARGIN_BYTES) / 1_000_000))
        return PaulstretchPlan(out, params.fftSize(sampleRate), wav, need)
    }
}
