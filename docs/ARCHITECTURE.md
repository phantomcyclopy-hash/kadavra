# Kadavra architecture (target for v1.0.0, and what exists)

## Delivered in Stage A (v0.11.0) — pure Kotlin, covered by unit tests, not yet wired to the UI
- `session/Session.kt`: `SessionModel` (up to 7 independent `TrackModel`s, master gain, loop, selection),
  `EngineCaps` (what each engine can do *today*: requires input, can generate, offline, live, record, dynamic
  parameters, can be a source, can be an effect, implemented) and `EngineSettings` (every engine's parameters kept per
  track). Audibility rule: if any track is soloed exactly the soloed tracks sound (even if muted), else all non-muted.
- `session/Mixer.kt`: the central, block-based mixer. One transport position drives every track, so tracks are
  sample-aligned by construction; per-track gain, pan (mono: constant power; stereo: balance), mute/solo, start offset,
  other sample rates (linear resampling), loop with wrap counting, seek, per-track and master peak meters, master gain
  and a soft limiter (identity below 0.8, saturating at exactly 1). Output is independent of the block size (tested).
  Muted/non-audible tracks are never read.
- `audio/MixPlayer.kt`: the single playback thread and `AudioTrack` (float stereo) for the whole session;
  `soundingPosition()` subtracts the frames still buffered so the playhead follows what is heard.
  **Needs a device to verify** (latency, underruns, thermal).
- `session/TrackCache.kt`: per-track PCM cache files on disk (16-bit LE interleaved at the track's own rate).
- `util/Gates.kt` (`canPlayNow`) and the fix for generator-only playback.

## Stages still to do (each must build green and keep every test before the next starts)
- **B — Workstation UI:** replace the terminal screen: header with the unchanged logo/title, track navigation, timeline
  with waveforms (peak pyramid built from the PCM cache, seconds on the x axis, amplitude on the y axis, zoom, selection,
  playhead from `MixPlayer.soundingPosition`), mixer strips with meters, transport, engine panels, status panel;
  Frutiger-dark-aero look and a cheap background-grid shader that honours "remove animations".
  The ViewModel moves from "one audio" to `SessionModel` + `Mixer` + `MixPlayer`.
- **C — Instruments and recording:** Guglielmo as an independent track source (no input needed; already works as a
  generator after the 0.11.0 fix), Safo live as a track voice, Safo live recording with `AudioRecord` (RECORD_AUDIO
  permission, ring buffer, monitoring, over/underrun protection).
- **D — Editing:** standalone slicer/shuffler on any track or result, two-source slice merge, per-track and master
  export (WAV only: Android has no MP3 encoder), export ranges.
- **E — Remaining engines and release:** Velazquez (documented image-to-sound mappings), performance work, docs, 1.0.0.

## Engine contract
Each engine declares an `EngineCaps` row. Engines that generate need no input and open their parameters on an empty
track; engines that need input are offered only on tracks with audio. Offline engines run `PcmSource -> SampleSink`
through `OfflineRenderer`; streaming engines (`SafoSynth`, `HeisenbergFx`, `WagnerFx`) are stateful per-sample/per-block
classes that can later run inside the mixer.

## What the tests cover and what needs a device
- Automatically: parameter validation, DSP behaviour of every engine, slicer, WAV writing, session model rules,
  engine-capability consistency with the catalog, mixer (sync across tracks/offsets/rates, pan law, gain, solo/mute, loop
  and seek, block-size independence, limiter, meters), cache handling, the playback gate for generator-only results.
- Only on a device: `MixPlayer` and `SafoLive` audio output (latency, glitches, LOAD), microphone recording, the
  shader's cost, the Compose layout on the Galaxy A55.
