# Kadavra (v0.11.0)

Android audio workstation (Kotlin + Jetpack Compose) with a terminal-style, all-text interface.
Application ID `com.kadrava.audio` · minSdk 26 · target Galaxy A55.

## Status (honest)
- **v0.10.1 and earlier: built green on GitHub Actions** and tried on the device (0.8.x).
- **v0.11.0 = Stage A of the v1.0.0 plan.** Not v1.0.0. It adds the session model, engine capability table, the central
  mixer, a single-thread player and the track cache (all pure code with unit tests), and fixes a real bug: results of
  generators such as Guglielmo could not be played when no audio had been imported. None of the new session code is
  connected to the screen yet; the screen is still the single-audio terminal UI. **Not built yet.**
- See `docs/AUDIT.md` (what the code really does) and `docs/ARCHITECTURE.md` (stages B-E, what is tested, what needs a device).
- Not verified: sound quality of Safo/Mose/Heisenberg/Wagner/Guglielmo by ear, live-player glitches and LOAD on the A55,
  `MixPlayer` on hardware.

## Interface rules
- Single static screen (no scrolling), pure black, DejaVu Sans Mono (third_party/DejaVu-LICENSE.txt), all text in English.
- Layout, top to bottom: logo + `K a d a v r a 𝆯` + version · engine selector `[<] Name n/N [>]` · the module
  (pages and sub-pages with `[<]` `[>]`, controls in a 3-column grid, never extending downwards) ·
  `[Process] [Preview] [Live]` · one status line · `$ [Import] [Eject]` · `= [name]` / `= [Empty]` ·
  `[Listen] [Reintegrate]` · `[Play] [Pause] [Rewind]` · 16-dash seek bar + typeable time ·
  fixed bottom bar `.:[Export].:` (nothing below it).
- Colours: everything white except the logo, the 7 title letters and the buttons/sliders (red). Touching a button or
  slider only **flashes** it (about a quarter of a second, then it fades back to red). The engine name has its rainbow
  colour and glows while it works.
- Theme: `[Light]` / `[Dark]` in the header swaps white and black everywhere (white background, black text, black flash);
  buttons, title letters, engine colours and logo keep their colours; the choice is remembered; status/navigation bar
  icons follow. Portrait only (screen orientation locked).
- Sliders: 10 dashes (grid) or 18 (wide); every value is typeable (any number, clamped to the valid range).
  The Safo loop region uses wide sliders with `m:ss.mmm` times and `[-]` `[+]` nudges of 0.1 s.
- No explanatory prose anywhere: only controls, readouts and short status/error lines.

## Logo
12 dots. Rotation speed is eased (never cut): slow counter-clockwise while audio is loaded, faster clockwise
while processing (and it reverses smoothly), winding down to rest. While audio plays, colours intensify and each
dot's rise/fall and tremble grow with the loudness; the loudness is the file's peak envelope at the playhead
(or the live output level), with a fixed default sensitivity. While processing with measurable progress, dots light
up whole, one per twelfth. With "remove animations" on, it stays still.

## Engines
| Name | What | State |
|---|---|---|
| Mouton | Paulstretch-style spectral stretch (STFT, random phase), slicer/shuffler | done (compiled in 0.2.0) |
| Safo | Granular synth modelled on The Secret Laboratory's Grainstorm, **live and offline** | written, unverified |
| Mose | 6-band biquad EQ, slicer/shuffler | written, unverified |
| Heisenberg | Delay (filtered, pitch-shifting feedback, ping-pong, tail) + distortion (soft, hard, asym, fold, bit-crush/sample-hold, drive, mix, level, pre/post), slicer/shuffler | written, unverified |
| Wagner | Feedback-delay-network reverb: 8 delay lines + Householder matrix, per-line damping, input diffusers, modulation, pre-delay, width, mix, tail; slicer/shuffler | written, unverified |
| Guglielmo | Noise and drone **generator** (needs no input audio): resonant band-pass bank at the partials of a root (stiffness stretch, Q, noise colour), wall of up to 128 micro-oscillators with slow amplitude drift, Lorenz-attractor chaos bending the bank, cross-fed saturating feedback loop; fixed 44.1 kHz stereo, deterministic by seed | written, unverified |
| Velazquez | image to sound | not implemented (`(n/a)`) |

Cindy (analyzer) was abandoned; Vlad was merged into Heisenberg.

Wagner's decay time (RT60) was measured in a prototype within about 3% of the setting (damping 0, modulation 0); it is a
synthetic room. Convolution with an impulse response is planned together with multitrack (another track can be the IR).

Guglielmo works without a loaded audio: `[Process]`/`[Preview]` generate the sound; `[Reintegrate]` then makes it the loaded
audio so any other engine can process it. Not done yet: spectral freeze and phase-vocoder smearing, using a loaded audio as the
bank's input.

Heisenberg is a stateful per-sample effect (`HeisenbergFx`) driven by an offline engine, so the same code can run live
and inside a track later. Not done yet: frequency-shifting, granular and spectral delays; no oversampling (hard clip,
fold and crush alias). The pitch shifter sits in the feedback path, so every repeat shifts again (440 → 873 → 1740 Hz
for +12 st, checked in a prototype).

### Safo vs. the original Grainstorm
Reference: https://thesecretlaboratory.com/apps/grainstorm/instructions (read for v0.7.0). Implemented, following
its documented behaviour: GRAINSIZE 5 ms–1 s, DENSITY 1–500 Hz, DEVIATION (random delay of each grain within
`deviation × sr / density` samples), RND READ (random read offset), PANNING (random L/R gain), ENV CYCLES and
ENV PHASE, PITCH, GLISS, REVERSE (probability), GRAINS/SILENCE (play N grains, skip M), loop START/END, SPEED
(1.0 = original), three loop modes (wrap, ping-pong with reversed grains, ping-pong), ENV1 shapes, real-time
playback with live parameter changes. Extras from other tools: S&H, spread/jitter, decay/ramp, smoothing filter,
Magic effects (chorus, tremolo, Haas, phased delay, vibrato), reverb with freeze, probability and vector-field
position modes.
**Not implemented from the original:** 4 simultaneous tracks, phase vocoder / cross synthesis, LFOs, envelope
followers, the effect rack (SSB, vocoder, convolution reverb, compressor, delays…), tape mode, other grain
envelopes (ENV2), grain-by-grain effects, recording/loading presets. "Exactly like Grainstorm" is a multi-step goal.

Real-time: `[Live]` decodes the loaded audio once to a cache file, then a worker thread renders 1024-frame blocks
and streams them to an `AudioTrack` (float, stereo). The status line shows LOAD (render time / block time) and
L/R levels. `[Process]`/`[Preview]` render offline (preview = at most 8 s of output for Safo, the first 4 s of
input for Mouton, 10 s for Mose).

### Reintegrate
`[Reintegrate]` copies the result to a lasting cache file and loads it as the current audio, as many times as you
like; the previous material and result are discarded; your imported file is never touched. `[Eject]` removes
all audio and temporary files.

## Build
JDK 17, Android SDK 34, Gradle 8.7. Without a PC: push to GitHub; `.github/workflows/build.yml` runs the tests and
builds the debug APK (Actions > Artifacts). Versions: AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00,
activity-compose 1.9.2, lifecycle 2.8.6, coroutines 1.8.1, JUnit 4.13.2. Release: create a keystore, configure
`signingConfigs` through local properties (never commit it), `./gradlew assembleRelease`.

## Limits
Input ≤ 2 h, output ≤ 1 h and ≤ 1.5 GB, mono/stereo only, 16-bit WAV out (no MP3: Android has no MP3 encoder),
peaks above 0.98 are lowered by one gain (never boosted), no dither.

## Multitrack goal (design, not built)
Aim: work with several audios at once, up to 7 tracks, and treat the whole as one living audio.
- **Model:** a Session of up to 7 Tracks. A Track = decoded PCM cache file + peaks, the chosen engine with its own
  parameters, and a mixer strip (gain, pan, mute, solo). The screen gets a track selector (1–7) like Grainstorm's.
- **Modes:** *individual* (select a track and work on it, as today); *mix* (all tracks to a master bus, live or bounced
  to a file); *merge* (generative: shuffle track order, randomise effect assignment/parameters with a seed, scheduled
  transitions/crossfades between tracks).
- **Live:** one audio thread renders every track's synth block by block, sums them on the master bus, limits, and streams
  to a single `AudioTrack` (no thread per track). `SafoSynth` and `HeisenbergFx` are already streaming and
  instantiable several times; Mose (biquads) is trivially streamable; Mouton needs a real-time STFT variant.
- **Freeze and capture:** a ring buffer records the master (or a track) output; "freeze" loops a selected region and can
  feed Safo as its source, so a frozen moment becomes grain material while everything else keeps moving.
- **Live recording:** `AudioRecord` (needs the RECORD_AUDIO permission, asked at first use) writes into a track's
  source while Safo reads behind the write head, as in Grainstorm's live mode; input monitoring is optional.
- **Limits to respect:** CPU (7 granular voices × density: the LOAD meter must gate new grains), RAM (sources stay on
  disk, random access), battery/thermal, and the static one-screen layout (the track selector replaces a row, not scroll).
- **Order of work:** M1 track model + selector + per-track parameters; M2 live mix bus with several Safo voices and a master
  meter; M3 merge tools (shuffle, random FX, crossfade); M4 capture/freeze; M5 live recording.

## Roadmap
1. Make 0.8.0 compile and pass its tests; fix what the device shows.
2. Multitrack M1–M2 (the architecture above), then M3–M5.
3. Safo: ENV page (ENV2), LFOs, envelope followers, the FX rack, phase vocoder.
4. Wagner convolution (needs a second audio as IR), Velazquez (image to sound), Guglielmo extras (spectral freeze, loaded audio as bank input); Heisenberg extras
   (frequency shifter, granular and spectral delays).
