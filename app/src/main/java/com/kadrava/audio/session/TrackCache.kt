package com.kadrava.audio.session

import java.io.File

/** Where each track's decoded PCM lives on disk (nothing is kept whole in RAM). */
class TrackCache(private val root: File) {
    fun fileFor(id: Int): File = File(root, "track$id.pcm")

    /** An empty path for the track's PCM; any previous file is removed. */
    fun prepare(id: Int): File {
        root.mkdirs()
        val f = fileFor(id)
        if (f.exists()) f.delete()
        return f
    }

    fun delete(id: Int) { fileFor(id).delete() }

    fun clear() { root.deleteRecursively() }

    fun usedBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L
}
