package com.kadrava.audio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Thin MediaPlayer wrapper. Main-thread only. Always call [release] when done. */
class PlaybackController(private val context: Context) {
    private var mp: MediaPlayer? = null

    fun prepare(uri: Uri, onReady: () -> Unit, onCompleted: () -> Unit, onError: (String) -> Unit) {
        release()
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context, uri)
            p.setOnPreparedListener { onReady() }
            p.setOnCompletionListener { onCompleted() }
            p.setOnErrorListener { _, what, extra ->
                onError("Erro de reprodução (código $what/$extra). Tente importar o arquivo novamente.")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            release()
            onError("Não foi possível iniciar o player: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun play() { try { mp?.start() } catch (_: IllegalStateException) {} }
    fun pause() { try { mp?.let { if (it.isPlaying) it.pause() } } catch (_: IllegalStateException) {} }
    fun seekTo(ms: Long) { try { mp?.seekTo(ms, MediaPlayer.SEEK_CLOSEST) } catch (_: IllegalStateException) {} }
    fun positionMs(): Long = try { mp?.currentPosition?.toLong() ?: 0L } catch (_: IllegalStateException) { 0L }

    fun release() {
        mp?.let { try { it.release() } catch (_: Exception) {} }
        mp = null
    }
}
