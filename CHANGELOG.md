# Changelog
## 0.11.0 — v1.0.0 Stage A (foundation)
- Fix: results of generators (Guglielmo) can be played, rewound and seeked with no imported audio; `[Original]` clears a generator-only result; `[Eject]` is enabled whenever there is a result.
- New `session` package: `SessionModel`/`TrackModel` (up to 7 independent tracks), `EngineCaps`, per-track `EngineSettings`, `TrackCache`.
- New `Mixer` (central block-based mix: sample-aligned tracks, gain/pan/mute/solo, offsets, resampling, loop, seek, meters, master gain, soft limiter) and `MixPlayer` (one thread, one AudioTrack). Not wired to the UI yet.
- Docs: `docs/AUDIT.md`, `docs/ARCHITECTURE.md`.
- Not verified in a build.

## 0.10.1
- Guglielmo chaos fixed (found by `chaosBendsTheBankOverTime`): the Lorenz source started near (1,1,1) with a warm-up measured in control steps (about 4 system time units at rate 1, almost none at low rates), so the trajectory was the same for every seed and moved little; `x/20` gave a standard deviation of about 0.4, i.e. +-1.2 semitones instead of the documented +-3. Now: `LorenzAttractor` (RK4, sub-steps of at most 0.005, seed-dependent start, 25 time units of warm-up so it is on the attractor, tanh-normalised outputs), control interval 32 samples. Measured in a prototype: spread 21-27 Hz -> 118-138 Hz, seeds decorrelated, x std 7.92 (theory 7.9).
- Tests: `LorenzTest` (determinism, seed variety, long-run statistics, step invariance, range); the chaos test now uses a spectral centroid over 17 windows and reports the measured values; added a seed-trajectory test.

## 0.10.0
- Guglielmo: noise/drone generator (resonant bank with stiffness stretch, micro-oscillator wall, Lorenz chaos, saturating feedback loop), no input audio needed; generative processing path in the ViewModel.
- `TermUi.kt`: coroutine imports added (the 0.9.0 compile fix, now in the source).
- Not verified in a build.

## 0.9.0
- Wagner: FDN reverb (RT60, size, damp, pre-delay, diffusion, mod, width, mix, tail) with the slicer/shuffler; `WagnerFx` is a streaming per-sample effect.
- Light theme (`[Light]`/`[Dark]`), remembered; system bar icons follow.
- Touch feedback only flashes (about 0.26 s) instead of staying highlighted.
- Portrait-only orientation.
- Not verified in a build.

## 0.8.1
- Heisenberg: `asym` distortion output now stays within +-1 after DC removal (0.9 headroom). 0.8.0 compiled; 98/99 tests passed.

## 0.8.0
- Heisenberg: delay (time, feedback, mix, ping-pong, tail, feedback low/high-pass, feedback pitch shift) and distortion (soft, hard, asym, fold, crush with bits/rate, drive, mix, level, pre/post), with the slicer/shuffler; `HeisenbergFx` is a streaming per-sample effect.
- Multitrack goal documented in the README (design only).
- Not verified in a build.

## 0.7.0
- UI: single static screen, modules extend sideways (pages/sub-pages, 3-column grid), fixed `.:[Export].:` bar, all text in English, no prose, `[Empty]`, `[Eject]`, logo beside the title; removed Logout, the bars above the version and the mini waveform.
- Logo: eased rotation (CCW slow with audio, CW while processing), loudness-reactive colours/tremor, whole dots as progress.
- Safo rebuilt as a streaming synth with Grainstorm-accurate parameters (deviation, rnd read, env cycles/phase, gliss, reverse, loop start/end/speed/modes, ENV shapes); real-time player (AudioTrack) with live parameter changes; precise loop sliders.
- `[Listen]`/`[Reintegrate]` above the player; reintegrate can be repeated forever, discarding the previous material.
- Not verified in a build.

## 0.6.1
- Safo: página GRANULATION com os controles do Grainstorm da The Secret Laboratory (GRAINSIZE, DENSITY, DEVIATION, RNDREAD, PANNING, ENV CYCLES, GRAINS, SILENCE, PITCH); módulos em páginas numeradas com `[<]` `[>]`.
- Novos parâmetros do motor: sizeDeviation, envCycles, burstGrains, burstSilence; RNDREAD vale em todos os modos.
- Referência corrigida no README (era Ijo Audio). Não verificado em build.

## 0.6.0
- Safo no estilo Grain Storm: módulos GRAIN/PITCH/SHAPE/TONE/MAGIC/VERB, move/scrub, spray, spread/jitter, decay/ramp/s&h, filtro, largura, Magic (5 efeitos), reverb com freeze; saída estéreo.
- Motores trocados com `[<]`/`[>]` ou deslize; sem contorno; brilho no nome ao processar.
- Sliders de 10 traços com valor digitável; linha de busca com tempo digitável; miniforma `.:i`.
- `[Preview]` e `[Reintegrate]`; logo com bolinhas inteiras e movimento individual.
- Não verificado em build.

## 0.5.0
- Safo: síntese granular (nuvem, probabilidade, campo vetorial), offline, com testes (SafoTest) e OfflinePlanner.
- Cindy removida; Vlad fundido ao Heisenberg. Nomes dos motores com cores do arco-íris e contorno branco.
- Logo menor e girando de verdade durante processamento; espaço vazio no lugar do nome de saída.
- Não verificado em build.

## 0.4.1
- Design: cabeçalho `K a d a v r a 𝆯`; tudo branco exceto logo, nome (7 cores), botões e sliders (vermelhos).
- Seleção: só o último botão/slider muda de cor (fade); cursor verde removido; waveform e gráfico da Cindy em branco.

## 0.4.0
- Cindy: analisador espectral FFT (janelas, pico/RMS/crest, eixo log/linear, peak-hold, instantâneo no playhead) com gráfico ASCII.
- Mose: equalizador de 6 bandas biquad, offline, com Picotador/Embaralhador opcional.
- OfflineRenderer genérico (Mouton e Mose); runner de processamento unificado no ViewModel.
- Testes: EqualizerTest, SpectrumAnalyzerTest. Não verificado em build.

## 0.3.0
- Interface de terminal (DejaVu Sans Mono), cabeçalho arco-íris, cursor de toque, nomes novos (Mouton etc.).
- Picotador + Embaralhador (Slicer, SlicedPcmSource) integrado ao Mouton; pré-escuta do resultado; miniforma de onda; linha de busca.
- Removido o visualizador ASCII multilinha e o painel antigo. Não verificado em build.

## docs
- README reescrito: rebranding dos motores (Mouton, Cindy, Mose, Wagner, Heisenberg, Vlad, Safo, Guglielmo, Velazquez), módulo Picotador/Embaralhador, princípios da interface terminal, roteiro por fases.

## 0.2.0
- Paulstretch end-to-end: STFT, planner de limites, renderer em 2 passes, WAV 16 bits, exportação SAF, cancelamento, painel com sliders ASCII, testes JVM.
- Não verificado em build (ver README).
## 0.1.0
- Fundação: tema OLED, logo de 12 pontos animada, tela inicial, importação SAF, decodificação em
  streaming para picos, visualizador ASCII iridescente, player, testes unitários da lógica pura.
- Não verificado em build (ver README).
