# Changelog
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
