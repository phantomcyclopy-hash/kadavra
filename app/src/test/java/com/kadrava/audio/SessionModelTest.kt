package com.kadrava.audio

import com.kadrava.audio.dsp.ArrayPcmSource
import com.kadrava.audio.dsp.InvalidParamException
import com.kadrava.audio.dsp.LimitException
import com.kadrava.audio.engine.EngineCatalog
import com.kadrava.audio.session.EngineCaps
import com.kadrava.audio.session.MixTrack
import com.kadrava.audio.session.MixerState
import com.kadrava.audio.session.SessionModel
import com.kadrava.audio.session.SourceKind
import com.kadrava.audio.session.TrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelTest {
    private fun full(id: Int, rate: Int = 44100, frames: Long = 44100, offset: Long = 0) =
        TrackModel(id, "T$id", SourceKind.IMPORTED, "f.wav", "/c/$id.pcm", rate, 2, frames, offsetFrames = offset)

    @Test fun tracksGetTheSmallestFreeIdAndTheSessionIsBoundedAtSeven() {
        var s = SessionModel()
        repeat(7) { s = s.addTrack() }
        assertEquals((1..7).toList(), s.tracks.map { it.id })
        assertEquals("Track 4", s.track(4)!!.name)
        try { s.addTrack(); throw AssertionError("an eighth track must be refused") } catch (_: LimitException) {}
        s = s.removeTrack(3).addTrack("noise")
        assertEquals(3, s.track(3)!!.id); assertEquals("noise", s.track(3)!!.name)
        assertEquals(1, SessionModel().addTrack().selectedTrack)
    }

    @Test fun removingTheSelectedTrackMovesTheSelection() {
        val s = SessionModel().addTrack().addTrack().removeTrack(1)
        assertEquals(2, s.selectedTrack)
        assertEquals(s, s.removeTrack(5))
    }

    @Test fun trackEditsAreValidated() {
        val s = SessionModel().addTrack()
        val bad = listOf<(TrackModel) -> TrackModel>(
            { it.copy(gain = 5f) }, { it.copy(gain = Float.NaN) }, { it.copy(pan = 2f) }, { it.copy(offsetFrames = -1) },
            { it.copy(engineId = "nope") }, { it.copy(id = 4) },
            { it.copy(frames = 100, selStart = 10, selEnd = 500) }, { it.copy(frames = 100, selStart = 50, selEnd = 50) },
        )
        for (f in bad) try { s.withTrack(1, f); throw AssertionError("should reject") } catch (_: InvalidParamException) {}
        val ok = s.withTrack(1) { it.copy(gain = 2f, pan = -1f, engineId = "guglielmo", frames = 100, selStart = 10, selEnd = 90) }
        assertTrue(ok.track(1)!!.hasSelection)
        try { s.withTrack(6) { it }; throw AssertionError() } catch (_: InvalidParamException) {}
    }

    @Test fun tracksAreIndependent() {
        val s = SessionModel().addTrack().addTrack()
        val t = s.withTrack(1) { it.copy(gain = 0.3f, solo = true, name = "x") }
        assertEquals(s.track(2), t.track(2))
        assertEquals(SessionModel().addTrack().track(1)!!.settings, t.track(1)!!.settings.copy())
    }

    @Test fun anEmptyTrackHasNoAudioAndACachedOneHas() {
        assertFalse(SessionModel().addTrack().track(1)!!.hasAudio)
        assertTrue(full(1).hasAudio)
        assertFalse(full(1).copy(frames = 0).hasAudio)
        assertEquals(1.0, full(1).durationSeconds, 1e-9)
    }

    @Test fun solosOverrideMutesInEveryCombinationAndMatchTheMixer() {
        val src = ArrayPcmSource(44100, 1, FloatArray(10))
        val flags = listOf(false to false, true to false, false to true, true to true)
        for (a in flags) for (b in flags) for (c in flags) {
            val tracks = listOf(a, b, c).mapIndexed { i, f -> full(i + 1).copy(mute = f.first, solo = f.second) }
            val session = SessionModel(tracks = tracks)
            val mixerState = MixerState(tracks.map { MixTrack(it.id, src, mute = it.mute, solo = it.solo) })
            val fromMixer = mixerState.tracks.filter { mixerState.isAudible(it) }.map { it.id }.toSet()
            assertEquals("$a $b $c", fromMixer, session.audibleIds)
            if (tracks.any { it.solo }) assertTrue(session.audibleIds.all { id -> tracks[id - 1].solo })
        }
    }

    @Test fun durationUsesOffsetsAndEachTracksOwnRate() {
        val s = SessionModel(tracks = listOf(full(1, 44100, 44100), full(2, 22050, 22050, offset = 1000)))
        assertEquals(45100L, s.durationFrames)
        assertEquals(45100.0 / 44100, s.durationSeconds, 1e-9)
        assertEquals(0L, SessionModel().addTrack().durationFrames)
    }

    @Test fun engineCapabilitiesMatchTheCatalogAndAreConsistent() {
        assertEquals(EngineCatalog.all.map { it.id }.toSet(), EngineCaps.all.map { it.id }.toSet())
        for (e in EngineCatalog.all) assertEquals("implemented: ${e.id}", e.implemented, EngineCaps.find(e.id)!!.implemented)
        for (c in EngineCaps.all) {
            if (c.canGenerate) assertFalse("${c.id}: a generator needs no input", c.requiresInput)
            if (c.live) assertTrue("${c.id}: live needs dynamic parameters", c.dynamicParams)
            if (c.implemented) assertTrue("${c.id}: implemented engines render offline", c.offline)
            assertTrue("${c.id}: it must be usable somehow", c.asSource || c.asEffect)
        }
        val g = EngineCaps.find("guglielmo")!!
        assertTrue(g.canGenerate && !g.requiresInput && g.asSource && !g.asEffect)
        assertNull(EngineCaps.find("cindy"))
    }
}
