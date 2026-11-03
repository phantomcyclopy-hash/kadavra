# Changelog
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
