package com.kadrava.audio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

class WaveformData(
    val peaks: FloatArray,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val mime: String,
    /** Frames actually decoded (exact, unlike the duration-based estimate). */
    val frames: Long,
)

/**
 * Decodes with the platform MediaCodec (so supported formats = what this device's decoders support)
 * and keeps only [buckets] peak values: memory stays constant regardless of file length.
 * Cancellable; reports real progress (decoded frames / expected frames).
 */
object WaveformDecoder {
    const val DEFAULT_BUCKETS = 2000

    suspend fun decode(
        context: Context,
        uri: Uri,
        buckets: Int = DEFAULT_BUCKETS,
        /** Optional: also receives the decoded audio as 16-bit LE interleaved PCM (streamed, no big buffers). */
        pcmSink: OutputStream? = null,
        onProgress: (Float) -> Unit = {},
    ): WaveformData = withContext(Dispatchers.Default) {
        require(buckets > 0)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw DecodeException("Não foi possível abrir o arquivo. Verifique se ele ainda existe e tente importar de novo.", e)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("O arquivo não contém uma faixa de áudio reconhecida.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) throw DecodeException("Não foi possível determinar a duração do áudio (formato sem duração no cabeçalho).")

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = PeakAccumulator.ENC_PCM_16

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val peaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0L
            var lastReport = 0L

            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else PeakAccumulator.ENC_PCM_16
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            pcmSink?.let { Pcm16Converter.appendPcm16(outBuf, info.size, encoding, channels, it) }
                            val totalFrames = maxOf(1L, durationUs * sampleRate / 1_000_000L)
                            frames += PeakAccumulator.accumulate(outBuf, info.size, encoding, channels, frames, totalFrames, peaks)
                            if (frames - lastReport > sampleRate / 4) {
                                lastReport = frames
                                onProgress((frames.toFloat() / totalFrames).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
            WaveformData(peaks, durationUs / 1000, sampleRate, channels, mime, frames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Falha ao decodificar o áudio (${e.message ?: e.javaClass.simpleName}). O formato pode não ser suportado por este aparelho.", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
