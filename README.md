# Kadavra — visão do projeto (v0.3.0)

Estação de áudio nativa para Android (Kotlin + Jetpack Compose), pensada como um **instrumento de
design sonoro experimental** com interface 100% em texto, idêntica a um terminal.
Application ID: `com.kadrava.audio` · minSdk 26 · alvo: Galaxy A55.

---

## 1. Princípios

1. **Interface = terminal.** Tudo é texto monoespaçado sobre preto absoluto (`#000000`). Sem cartões,
   ícones gráficos, sliders nativos ou botões arredondados. Os itens são clicáveis, mas visualmente
   indistinguíveis de linhas de terminal. O layout de referência é o desenho do autor (mantido fora
   deste documento).
2. **Fonte única: DejaVu Sans Mono** (arquivo fornecido pelo autor), embutida no app
   (`res/font`), junto com o texto da licença DejaVu. Nenhum outro tipo de letra.
3. **Cabeçalho.** O título aparece com as letras separadas por espaço (`K a d r a v a`), em corpo
   levemente maior que o resto (tamanho de subtítulo). São 7 letras, cada uma numa das 7 cores do
   arco-íris (vermelho, laranja, amarelo, verde, azul, anil, violeta), tiradas da paleta da logo.
   A grafia do cabeçalho segue exatamente o desenho do autor; o nome do app/pacote continua `Kadavra`.
4. **Cursor de toque.** O bloco verde do Termux **não** fica parado na tela: aparece apenas ao lado do
   item que está sendo tocado/selecionado, e some em seguida.
5. **Tudo em ASCII.** Menus, parâmetros (barras `------#-----`), progresso, miniforma de onda na
   linha do áudio carregado e exportação usam só caracteres da tabela ASCII.
6. **Honestidade técnica.** Um motor só aparece como disponível se faz o que anuncia. Nada de
   progresso falso, nada de "profissional/hiper-realista" sem medição. O que não existe aparece
   como `[não implementado]`.
7. **Áudio original nunca é alterado.** Todo processamento gera um arquivo novo; nada é apagado nem
   sobrescrito em silêncio.

## 2. Motores (rebranding)

Marcados com ¹ aceitam o **Picotador + Embaralhador** (seção 3).

| Nome | Função | Escopo planejado | Estado |
|---|---|---|---|
| **Mouton** ¹ | Time-stretch espectral extremo (antigo Paulstretch) | STFT, fase aleatória, fator 1–100x, janela 0,05–1 s, semente | **Implementado** (STFT + Picotador/Embaralhador + pré-escuta; código da v0.3.0 ainda não compilado) |
| **Cindy** | Analisador espectral | FFT configurável, janelas, pico/RMS, eixo logarítmico, peak-hold, a partir do sinal real | não implementado |
| **Mose** ¹ | Equalizador | multibanda com filtros biquad, faixas validadas, sem clipping inesperado | não implementado |
| **Wagner** ¹ | Reverb | convolução particionada por FFT com qualquer resposta ao impulso (IR) importada, mais reverb sintético por redes de linhas de atraso (FDN) com matriz de feedback, para espaços reais ou impossíveis | não implementado |
| **Heisenberg** ¹ | Delay | tempo/feedback/mix, ping-pong, filtragem no feedback, delay com deslocamento de frequência/pitch, delay granular, delay espectral | não implementado |
| **Vlad** ¹ | Distorção | bit-crushing, waveshaping, wavefolding, clipping assimétrico, controle de harmônicos, sobreamostragem contra aliasing | não implementado |
| **Safo** | Síntese granular | grãos estocásticos, nuvens de probabilidade, granulação guiada por campos vetoriais e por "espaços latentes" (ver nota) | não implementado |
| **Guglielmo** ¹ | Ruído e drone | feedback não linear (no-input mixing), congelamento espectral, smearing de phase vocoder, atratores caóticos, bancos de filtros ressonantes, paredes de micro-osciladores aditivos | não implementado |
| **Velazquez** | Imagem para som | ressíntese espectral e desenho no espectrograma, databending, processamento de imagem sobre áudio, sonificação por varredura de pixels | não implementado |

Notas honestas:
- **Mouton** é, de fato, o algoritmo Paulstretch (não é redução de velocidade). Limitações herdadas:
  transientes viram textura de ruído, fade de meia janela no início/fim, estéreo com fases
  independentes por canal.
- **Wagner**: "hiper-realista" só será afirmado depois de comparar contra IRs de referência. A
  convolução será offline primeiro (renderização em arquivo), tempo real só se medido viável.
- **Safo / "espaço latente"**: será implementado como um espaço de parâmetros de baixa dimensão
  (p.ex. PCA de características dos grãos). Redes neurais só entram se você decidir e se couberem no
  aparelho; hoje não estão no escopo.
- **Guglielmo / Velazquez** têm muitas técnicas; serão entregues em camadas, cada técnica só listada
  quando funcionar.

## 3. Picotador + Embaralhador (módulo compartilhado)

Disponível nos motores marcados com ¹.

- **Picotador:** corta o áudio em **Slices**, em quantidade **Factor** e tamanhos definíveis
  (uniformes, ou entre um mínimo e um máximo, sorteados com semente).
- **Embaralhador:** reordena os Slices aleatoriamente (semente fixa = resultado repetível).
- Crossfade curto nas emendas (padrão proposto: 5 ms) para evitar cliques.
- Desenho técnico previsto: um `PcmSource` decorador que mapeia quadros por uma tabela de
  permutação sobre o arquivo temporário, sem carregar áudio na memória.
- **Decisão assumida (confirmar):** o Picotador atua **antes** do motor (pré-processamento), de modo
  que o motor processe o áudio já picotado. A duração total da entrada não muda.

## 4. Estado atual

- **v0.2.0 (verificada no GitHub Actions):** compilou; 40 de 41 testes passaram (o erro era um valor
  esperado errado no teste de `fftSize`, já corrigido).
- **v0.3.0 (fase 4): escrita, mas ainda NÃO compilada nem testada.** Traz: interface de terminal com
  DejaVu Sans Mono embutida (`res/font`, licença em `third_party/DejaVu-LICENSE.txt`), cabeçalho
  `K a d r a v a *` com 7 cores, cursor verde só no item tocado, nomes novos dos motores, Picotador +
  Embaralhador no Mouton (testes em `SlicerTest`), pré-escuta do resultado (`[Listen]`/`[Original]`),
  miniforma de onda de uma linha e linha de busca `------#-----`.
- **Ainda não verificado:** execução no aparelho, qualidade sonora do Mouton ouvida, memória/tempo no A55.
- Interpretações minhas do desenho (corrija se estiverem erradas):
  - as 4 linhas `\ / \ \` amarelo-esverdeadas ficam como no desenho em repouso e giram
    (`\ | / -`) enquanto há trabalho em andamento;
  - `[Logout]` encerra a sessão (para o áudio, apaga temporários e fecha o app); não há contas;
  - a saída é **WAV** (`[nome].wav`): o Android não tem codificador MP3 nativo, então o `.mp3` do
    desenho não é possível sem biblioteca externa; M4A/AAC seria o caminho viável;
  - `[Rewind]` volta ao início sem parar nem iniciar a reprodução.

## 5. Roteiro

| Fase | Entrega |
|---|---|
| 4 | **Escrita (aguarda build):** interface terminal definitiva, nomes novos, pré-escuta do resultado, Picotador/Embaralhador no Mouton |
| 5 | Cindy (analisador) e Mose (EQ) |
| 6 | Vlad e Heisenberg |
| 7 | Wagner (convolução + FDN) |
| 8 | Safo e Guglielmo |
| 9 | Velazquez |
| 10 | Revisão de desempenho, memória, bateria, testes instrumentados, APK de release assinado |

Cada fase deve compilar e passar nos testes antes da seguinte.

## 6. Build

Requisitos: JDK 17, Android SDK 34, Gradle 8.7 (o `gradlew` não está no repositório).

- **Sem PC:** suba a pasta ao GitHub; `.github/workflows/build.yml` roda testes e gera o APK debug
  (aba *Actions* > *Artifacts*).
- **Android Studio:** abrir a pasta e sincronizar.
- **Linha de comando:** `gradle wrapper --gradle-version 8.7`, depois
  `./gradlew testDebugUnitTest assembleDebug`.
- **Release:** criar keystore, configurar `signingConfigs` por propriedades locais (nunca versionar o
  keystore), `./gradlew assembleRelease`.

Versões: AGP 8.5.2 · Kotlin 2.0.20 · Compose BOM 2024.09.00 · activity-compose 1.9.2 · lifecycle 2.8.6 ·
coroutines 1.8.1 · JUnit 4.13.2.

## 7. Arquitetura

`audio/` decodificação e player · `dsp/` FFT, Mouton, planner, WAV (JVM puro, testável) ·
`viz/` renderizador ASCII · `ui/` Compose + ViewModel · `engine/` catálogo (só o que está
implementado vira `implemented=true`) · `util/` formatadores.
Processamento sempre fora da thread principal, memória limitada (arquivos temporários), cancelável,
com progresso medido.

## 8. Limites atuais do Mouton

Entrada ≤ 2 h; saída ≤ 1 h e ≤ 1,5 GB; mono ou estéreo; saída WAV PCM 16 bits na taxa da entrada,
sem dither; picos acima de 0,98 recebem um único ganho de redução (nunca amplifica); a entrada é
decodificada em 16 bits.
