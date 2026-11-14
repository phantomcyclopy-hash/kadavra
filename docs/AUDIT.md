# Audit of the repository (state at v0.10.1, before the v1.0.0 work)

Method: read from the source, not from the README. Numbers are from the repository files.

## Architecture today
- **One loaded audio.** `KadavraViewModel` owns a single `currentUri`, one decoded `Track`, one `ProcessState`, one
  result. Every engine takes that audio (or none, for Guglielmo) and produces a *result*; `[Reintegrate]` copies the
  result into the cache and loads it as the new single audio. There is no session, no second track.
- **Two unrelated playback paths.** `PlaybackController` (MediaPlayer, original and results) and `SafoLive`
  (AudioTrack, real-time granular). They are mutually exclusive (starting one stops the other).
- **Offline pipeline.** Decode to a 16-bit PCM cache file -> `PcmSource` -> engine -> float scratch file ->
  peak check -> 16-bit WAV (`OfflineRenderer`). Streaming, bounded memory, cancellable, with progress.
- **Waveform.** `WaveformDecoder` keeps 2000 peak buckets per file. They are used only to drive the logo's loudness;
  **there is no waveform view, timeline, zoom or selection in the UI.**
- **Export.** One WAV (the last result) through the Storage Access Framework. No per-track or master export.
- **UI.** A static, terminal-style single screen (modules in a 3-column grid, one status line, fixed export bar,
  light/dark theme, portrait only). No panels, mixer, meters or timeline.
- **Size.** Main code: dsp 18 files, ui 12, audio 6, engine 1, util 2 (plus the new session package); tests in 17 files.

## Engines (verified in code)
| Engine | Offline | Live | Needs input | Notes |
|---|---|---|---|---|
| Mouton (Paulstretch) | yes | no | yes | slicer/shuffler before the engine |
| Safo (granular) | yes | **yes** (AudioTrack, live parameter changes) | yes (grain source) | no recording, no slicer, stereo out |
| Mose (EQ, 6 bands) | yes | no | yes | slicer before the engine |
| Heisenberg (delay + distortion) | yes | no (streaming class exists) | yes | slicer before the engine |
| Wagner (FDN reverb) | yes | no (streaming class exists) | yes | no convolution |
| Guglielmo (noise/drone) | yes | no | **no (generator)** | fixed 44.1 kHz stereo |
| Velazquez (image to sound) | **not implemented** | no | image | listed as `(n/a)` |

## Defects and mismatches found
1. **Generator results could not be played without an imported audio (fixed in 0.11.0).** `canPlay()` required
   `load is LoadState.Ready`; with nothing imported, a Guglielmo result existed but `play()`, `rewind()`, seeking and the
   auto-play after `[Preview]` returned silently while the UI showed `[Play]` enabled. `[Original]` was also a no-op in
   that case and `[Eject]` was disabled while a result existed. Now `canPlayNow(hasLoaded, hasResult, resultLoading)`.
2. Slicer/shuffler exists only as a pre-processing stage of four engines; it is not a standalone tool, not usable on
   the result, and there is no two-source merge.
3. Engines produce different channel counts and rates (Mouton/Mose keep the input's, others force stereo, Guglielmo is
   fixed at 44.1 kHz); the old design never had to mix them. The new mixer resamples (linear) and handles mono/stereo.
4. `SafoLive` decodes the whole loaded audio again on every `[Live]` start (cached per URI in memory of the ViewModel only).
5. The README described the multitrack goal as design only; that is still true for everything except the new
   `session` package (model + mixer + cache), which is not wired to the UI yet.

## Risks
- Playback paths: two playback engines plus the future mixer must never run together.
- CPU/battery: Safo at high density, a 3-D background shader, and 7 tracks all compete with the audio thread.
- Memory: PCM stays on disk (good); peak pyramids and waveform bitmaps must be bounded.
- Hardware-only features (microphone, latency, thermal behaviour) cannot be proven in CI.

## Files that must change for the rest of v1.0.0
`ui/KadavraViewModel.kt` (single-audio state -> session), `ui/HomeScreen.kt` and the Compose layer (new workstation UI),
`ui/EngineSpecs.kt` (per-track settings), `audio/SafoLive.kt` (record + shared source), `dsp/OfflineRenderer` consumers
(per-track processing), new: waveform pyramid, timeline, recorder, merge tool, Velazquez engine, per-track and master export.
