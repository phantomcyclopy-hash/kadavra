# Kadavra — fases 1 a 3 (v0.2.0)

App Android nativo (Kotlin + Jetpack Compose), fundo preto OLED, visual de terminal.
Application ID: `com.kadrava.audio` (conforme o prompt). minSdk 26, target/compile 34.

## STATUS HONESTO
**Este código NÃO foi compilado nem testado por quem o escreveu** (o ambiente de geração não tinha
Android SDK nem Gradle, e estava sem rede). Nenhum APK é fornecido. Pode haver erros de compilação
a corrigir. Verifique com os comandos abaixo e me envie a saída de qualquer erro.

## Implementado
- Tela inicial no esquema do conceito: "Bem-vindo ao Kadavra!", lista de modelos/efeitos clicáveis,
  linha `~` verde com o áudio carregado (ou "importar áudio") e a logo de 12 pontos ao lado.
- Logo animada com estados reais: parada, progresso medido (pontos acendem), indeterminado
  (só quando o progresso não é mensurável), erro. Respeita "remover animações" do sistema.
- Importação via Storage Access Framework; decodificação com MediaCodec (formatos = o que o
  aparelho decodifica; não testado em WAV/MP3/AAC/FLAC/OGG). Memória constante (2000 buckets de pico),
  cancelável, com progresso real.
- Visualizador ASCII (rampa ` .,:;irsXA253hMHGS#9B&@`) de **picos de forma de onda** (não é espectro),
  cor iridescente por posição, playhead sincronizado com `MediaPlayer`, toque/arraste para buscar.
- Reprodução: tocar, pausar, parar, busca, posição/duração, erros visíveis, pausa ao ir para segundo plano,
  liberação do player no `onCleared`.
- Testes unitários JVM: renderizador ASCII, acumulador de picos PCM, formatadores.

## Fase 3 — Paulstretch (novo)
- Motor STFT real (`dsp/PaulstretchEngine.kt`): janela Hann na análise, módulo espectral mantido,
  **fase 100% aleatória** (simétrica, saída real), janela seno na síntese com overlap de 50%, ganho
  que preserva o RMS. Não é redução de velocidade de reprodução.
- Parâmetros validados: fator 1–100x, janela 0,05–1,0 s (FFT potência de 2, 1024–131072), semente.
  Determinístico para a mesma entrada/parâmetros/semente. Estéreo com fases independentes por canal.
- Pipeline em disco, memória limitada: decodifica para PCM 16 bits temporário -> motor -> arquivo
  float32 temporário (mede o pico) -> WAV PCM 16 bits. Se o pico passar de 0,98 aplica um único ganho
  de redução (nunca amplifica). Sem dither. Temporários apagados em sucesso, erro e cancelamento.
- Limites verificados antes de começar: entrada <= 2 h, saída <= 1 h e <= 1,5 GB, mono/estéreo,
  espaço livre em disco. Rejeição com mensagem clara.
- Execução em segundo plano, progresso real (decodificação 20% / STFT 65% / gravação 15%),
  cancelamento, exportação pelo seletor do Android (nunca sobrescreve em silêncio; cancelar a
  exportação remove só o arquivo parcial que o app acabou de criar).
- Limitações: transientes viram textura de ruído (perda de fase, por projeto); início/fim têm fade de
  meia janela; saída na mesma taxa da entrada; 16 bits; não há pré-escuta do resultado ainda.
- Verificação: o algoritmo foi prototipado em Python/numpy (nível RMS preservado, tom de 440 Hz mantido,
  duração exata). O código Kotlin e os testes JVM (FFT, Paulstretch, planner, WAV, conversão PCM)
  **ainda não foram executados**.

## NÃO implementado (a lista mostra "[não implementado]")
Analisador espectral, EQ, reverb, delay, distorção, granular, ruído/drone, imagem→som,
pré-escuta do resultado, biblioteca/presets, configurações, navegação por abas, tela de inicialização,
foco de áudio, testes instrumentados. Nada disso é anunciado como pronto.

## Build
Requisitos: JDK 17, Android SDK 34 (platform + build-tools), Gradle 8.7.
O `gradlew`/`gradle-wrapper.jar` não estão incluídos (sem rede para gerá-los).

- Android Studio (recomendado, em PC): *Open* na pasta; ele sincroniza sozinho.
- Linha de comando: `gradle wrapper --gradle-version 8.7` uma vez, depois
  `./gradlew testDebugUnitTest assembleDebug`
  APK: `app/build/outputs/apk/debug/app-debug.apk`
- Sem PC: suba a pasta num repositório GitHub; `.github/workflows/build.yml` roda testes e
  gera o APK debug como artefato (aba Actions).

Versões: AGP 8.5.2, Kotlin 2.0.20 (plugin Compose), Compose BOM 2024.09.00, activity-compose 1.9.2,
lifecycle 2.8.6, coroutines 1.8.1, JUnit 4.13.2.

## Release
Crie um keystore (`keytool -genkeypair ...`), configure `signingConfigs` em `app/build.gradle.kts`
via propriedades locais (nunca commite o keystore) e rode `./gradlew assembleRelease`.

## Arquitetura
`audio/` decodificação + player · `viz/` renderizador ASCII (puro, testável) · `ui/` Compose + ViewModel ·
`dsp/` FFT, Paulstretch, planner, WAV (JVM puro, testável) · `engine/` catálogo (só Paulstretch `implemented=true`) · `util/` formatadores.
A posição de reprodução vive num `StateFlow` separado e só é lida na fase de desenho do playhead,
evitando recomposição a cada tick.

## Limitações conhecidas
Arquivos sem duração no cabeçalho são rejeitados. PCM suportado na decodificação: 8 bits, 16 bits, float.
Sem foco de áudio (outro app pode tocar junto). A fonte é a monoespaçada do sistema.
