package com.kadrava.audio.session

import com.kadrava.audio.dsp.GuglielmoParams
import com.kadrava.audio.dsp.HeisenbergParams
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.dsp.PaulstretchParams
import com.kadrava.audio.dsp.SafoParams
import com.kadrava.audio.dsp.SliceParams
import com.kadrava.audio.dsp.WagnerParams

/**
 * What an engine implementation can do today (not what is planned). The session and the UI decide what to offer
 * from this table; a test checks it against the engine catalog.
 */
data class EngineCaps(
    val id: String,
    /** Needs audio to work on (a track with audio, or a source to read grains from). */
    val requiresInput: Boolean,
    /** Can create audio with no input at all. */
    val canGenerate: Boolean,
    val offline: Boolean,
    /** Real-time playback exists in the code. */
    val live: Boolean,
    /** Live recording exists in the code. */
    val record: Boolean,
    /** Parameters may change while it runs. */
    val dynamicParams: Boolean,
    /** Can be the source of a track (generated, or a live voice). */
    val asSource: Boolean,
    /** Can be applied to a track that already has audio. */
    val asEffect: Boolean,
    val implemented: Boolean,
) {
    companion object {
        val all: List<EngineCaps> = listOf(
            EngineCaps("mouton", true, false, true, false, false, false, false, true, true),
            EngineCaps("safo", true, false, true, true, false, true, true, true, true),
            EngineCaps("mose", true, false, true, false, false, false, false, true, true),
            EngineCaps("wagner", true, false, true, false, false, false, false, true, true),
            EngineCaps("heisenberg", true, false, true, false, false, false, false, true, true),
            EngineCaps("guglielmo", false, true, true, false, false, false, true, false, true),
            EngineCaps("velazquez", false, true, true, false, false, false, true, false, false),
        )

        fun find(id: String): EngineCaps? = all.firstOrNull { it.id == id }
    }
}

/** Every engine's own parameters, kept per track so switching tracks never loses them. */
data class EngineSettings(
    val mouton: PaulstretchParams = PaulstretchParams(),
    val safo: SafoParams = SafoParams(),
    val heisenberg: HeisenbergParams = HeisenbergParams(),
    val wagner: WagnerParams = WagnerParams(),
    val guglielmo: GuglielmoParams = GuglielmoParams(),
    val eqGains: List<Double> = List(6) { 0.0 },
    val slices: SliceParams = SliceParams(),
    val slicesEnabled: Boolean = false,
)

enum class SourceKind { EMPTY, IMPORTED, RECORDED, GENERATED, PROCESSED }

/**
 * One track. It never depends on another track. Audio lives in a PCM cache file (16-bit little-endian interleaved,
 * at the track's own [sampleRate]); [offsetFrames] is in session frames, the selection in track frames.
 */
data class TrackModel(
    val id: Int,
    val name: String,
    val kind: SourceKind = SourceKind.EMPTY,
    val sourceLabel: String? = null,
    val cacheFile: String? = null,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val frames: Long = 0L,
    /** Where the track starts on the session timeline, in session frames. */
    val offsetFrames: Long = 0L,
    val engineId: String? = null,
    val settings: EngineSettings = EngineSettings(),
    val gain: Float = 1f,
    /** -1 (left) .. +1 (right). */
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** Selected region in track frames; -1 = none. */
    val selStart: Long = -1L,
    val selEnd: Long = -1L,
) {
    val hasAudio: Boolean get() = kind != SourceKind.EMPTY && cacheFile != null && frames > 0 && sampleRate > 0
    val durationSeconds: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
    val hasSelection: Boolean get() = selStart >= 0 && selEnd > selStart

    fun validate() {
        if (id !in 1..SessionModel.MAX_TRACKS) throw InvalidParamException("track id must be 1..${SessionModel.MAX_TRACKS} (got $id).")
        if (!gain.isFinite() || gain < 0f || gain > 4f) throw InvalidParamException("track gain must be between 0 and 4 (got $gain).")
        if (!pan.isFinite() || pan < -1f || pan > 1f) throw InvalidParamException("track pan must be between -1 and 1 (got $pan).")
        if (offsetFrames < 0) throw InvalidParamException("track offset cannot be negative.")
        if (frames < 0) throw InvalidParamException("track length cannot be negative.")
        if (selStart != -1L || selEnd != -1L) {
            if (selStart < 0 || selEnd <= selStart || selEnd > frames) throw InvalidParamException("invalid selection $selStart..$selEnd for a track of $frames frames.")
        }
        if (engineId != null && EngineCaps.find(engineId) == null) throw InvalidParamException("unknown engine '$engineId'.")
    }
}

/** The whole session: up to [MAX_TRACKS] independent tracks, the master bus and the transport state. */
data class SessionModel(
    val tracks: List<TrackModel> = emptyList(),
    val sampleRate: Int = DEFAULT_RATE,
    val masterGain: Float = 1f,
    val loop: Boolean = false,
    val loopStart: Long = 0L,
    val loopEnd: Long = 0L,
    val selectedTrack: Int? = null,
) {
    fun track(id: Int): TrackModel? = tracks.firstOrNull { it.id == id }

    /** Adds an empty track using the smallest free id (1..7). */
    fun addTrack(name: String? = null): SessionModel {
        if (tracks.size >= MAX_TRACKS) throw LimitException("a session holds at most $MAX_TRACKS tracks.")
        val id = (1..MAX_TRACKS).first { free -> tracks.none { it.id == free } }
        val t = TrackModel(id = id, name = name ?: "Track $id")
        return copy(tracks = (tracks + t).sortedBy { it.id }, selectedTrack = selectedTrack ?: id)
    }

    fun removeTrack(id: Int): SessionModel {
        if (track(id) == null) return this
        val rest = tracks.filter { it.id != id }
        return copy(tracks = rest, selectedTrack = if (selectedTrack == id) rest.firstOrNull()?.id else selectedTrack)
    }

    fun withTrack(id: Int, f: (TrackModel) -> TrackModel): SessionModel {
        val old = track(id) ?: throw InvalidParamException("no track $id.")
        val new = f(old)
        new.validate()
        if (new.id != id) throw InvalidParamException("a track's id cannot change.")
        return copy(tracks = tracks.map { if (it.id == id) new else it })
    }

    private val anySolo: Boolean get() = tracks.any { it.solo }

    /**
     * Audibility rule: if any track is soloed, exactly the soloed tracks sound (even if also muted);
     * otherwise every track that is not muted sounds.
     */
    val audibleIds: Set<Int>
        get() = tracks.filter { if (anySolo) it.solo else !it.mute }.map { it.id }.toSet()

    /** End of the last track on the timeline, in session frames. */
    val durationFrames: Long
        get() = tracks.filter { it.hasAudio }.maxOfOrNull {
            it.offsetFrames + Math.ceil(it.frames.toDouble() * sampleRate / it.sampleRate).toLong()
        } ?: 0L

    val durationSeconds: Double get() = durationFrames.toDouble() / sampleRate

    companion object {
        const val MAX_TRACKS = 7
        const val DEFAULT_RATE = 44100
    }
}
