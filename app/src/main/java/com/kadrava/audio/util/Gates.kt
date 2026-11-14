package com.kadrava.audio.util

/**
 * Whether the transport may act. Audio can come from a loaded file or from a result/generated track, so a
 * generator such as Guglielmo plays even when nothing was imported.
 */
fun canPlayNow(hasLoadedAudio: Boolean, hasResultTrack: Boolean, resultLoading: Boolean): Boolean =
    (hasLoadedAudio || hasResultTrack) && !resultLoading
