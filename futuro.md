# Futuro — features planejadas (FUSAO M54)

Registro do dono (2026-10-18). Nada aqui está implementado. Doutrina da casa vale para as duas:
referência de terceiros é **arquitetura**, não código copiado (ver `handoff.md` §1; checar licença);
nada de spoof de device (kill-list do `tools/fusao-sweep.py`); efeito só conta se **medido** no teste
certo (kernlab, `tools/kernlab/README.md`).

## 1. Frame generation (base: Stellar)

- **Objetivo**: gerar frames intermediários em jogos para subir o fps percebido.
- **Base**: Stellar. O pacote em `_referencia/Stellar-v22.5-testing1.1-101026/` NÃO tem frame gen (o
  changelog só tem "spoofing de device p/ destravar FPS" — proibido aqui — e "Frame Aware Scheduling",
  que é escalonamento). Precisa da versão nova do Stellar que trouxer o recurso.
- **Perguntas antes de codar** (responder lendo a versão nova, não chutando):
  1. Onde o frame é gerado: camada Vulkan/GLES injetada no jogo, SurfaceFlinger, ou recurso do driver?
  2. Custo na GPU: no M54 os dois jogos medidos já estão com a GPU cravada no máximo (NTE 12-18 fps,
     Roblox ~28 fps, `status.md` CONSOLIDADO). Interpolar também gasta GPU — num jogo GPU-bound pode
     roubar frame real do jogo. Com base de 13 fps a interpolação tem pouca informação e soma latência.
  3. Latência de toque: frame gerado não responde a input; medir toque -> pixel, não só fps.
- **Como validar**: kernlab `game` (fps, intervalo entre frames, potência, temperatura) + latência de
  toque, com e sem o recurso, no NTE e no Roblox, cena parada e cena em movimento.
- **Risco**: userspace (sem kernel) = baixo risco de panic. Risco real = não compensar no Mali-G68.

## 2. "Smart Pixels" — quando o Rairin lançar a feature nova

Descrição do dono (do anúncio do Rairin, recurso que existe no Google Pixel e em ROMs AOSP):
- controla **quantos pixels ficam acesos**, para a tela gastar menos;
- serve também de **tela de privacidade**: dificulta ler mensagens à distância, com cobertura ajustável;
- tem **movimento dinâmico**: os pixels apagados se revezam (não fica o mesmo padrão parado).

- **Por que faz sentido no M54**: a tela é Super AMOLED — pixel preto é pixel desligado, o gasto cai com
  a fração de pixels acesos (efeito proporcional ao brilho médio do conteúdo).
- **Caminho provável**: overlay de tela cheia no APK FUSAO (máscara de pontos pretos, sem receber toque,
  padrão que desloca com o tempo p/ não marcar a tela). Estudar como o Rairin faz quando lançar
  (`_referencia/Rairin/` é a referência B) e reimplementar.
- **Pontos a medir/checar**:
  1. Composição: uma camada extra de tela cheia precisa ficar no hardware composer (overlay plane). Se cair
     em composição por GPU, custa GPU — péssimo em jogo GPU-bound. Ver no `dumpsys SurfaceFlinger`
     (composição DEVICE vs CLIENT) com o overlay ligado.
  2. Ganho real de potência: kernlab `idle`/`scroll` com conteúdo claro e escuro, overlay on/off (pbat).
  3. Limites do Android: janelas `FLAG_SECURE` e tela de bloqueio; overlay de app comum não cobre tudo
     (acessibilidade cobre mais, com outro custo de permissão).
- **Risco**: sem kernel, sem sysfs = baixo. Custo possível = composição e legibilidade.
- **Status 2026-10-20: implementado no app 1.1.5** (aba Pixels + bloco "Smart Pixels" nas Configurações
  rápidas; `app/app/src/main/java/com/orkgabb/fusao/pixels/`). Janela TYPE_ACCESSIBILITY_OVERLAY (preto 100%),
  bloco 16x16 células (Bayer / xadrez 2x2) repetido por BitmapShader — sem bitmap de tela cheia; movimento =
  deslocamento do shader, 1 redesenho por passo, parado com a tela apagada. Boot: o sistema religa o serviço
  de acessibilidade. **Falta no aparelho**: pontos 1–3 acima (composição DEVICE/CLIENT, pbat on/off com
  conteúdo claro/escuro, tela de bloqueio/FLAG_SECURE) e a cobertura real (barras, recorte, rotação).

## 3. Pontas abertas do FAS (fas-rs, 2.11.x) — registradas em 2026-10-19

O FAS está adotado e provado no Bleach (gráficos mínimos, limite 45): bateria −10,4%, fps −1,3%, frames
≥33 ms de 0,13% para 0,79% (`status.md`, seção fas-rs). Falta medir onde ele ainda não foi visto.

1. **Trava de GPU saturada do fork** — 2026-10-20: a versão 0003 (só `gpu_busy`) estava ERRADA (ativava com a
   GPU a 221 MHz; Roblox: ~120 alternâncias). Corrigida no 0005 (só no teto da GPU, histerese, nunca na
   travada): 0 alternâncias no NTE. Falta: jogo preso de verdade na GPU (teto + 99%) mostrar a CPU baixa com o
   mesmo fps. Texto original: com `gpu_busy` ≥ 95% o
   fas-rs não sobe a CPU. Implementada, NÃO medida. Teste: um jogo em gráficos altos (Bleach no máximo
   prendia a GPU a 949 MHz/99%); conferir no `fas.log` a linha `GPU guard: saturated` e que a CPU fica baixa
   com o mesmo fps. Sem a trava, o fas-rs jogava o A78 a 2,4 GHz por 0 fps.
2. **FAS em outros jogos**: `tools/kernlab/experiments/cpu-controle-nte.json` e `cpu-controle-roblox.json`
   (FUSAO × teto solto × fas-rs, regra de decisão declarada no JSON). Rodar nos gráficos que o dono usa;
   bateria ≥ 30%, sem carregador, ~16-20 min cada. Se um jogo piorar, tirar só ele: **feito no 2.12.0**,
   `fusao fas target <pkg> off` (app: FAS · OFF); o Roblox já sai fora do FAS uma vez no boot. O
   `cpu-controle-roblox.json` continua sendo a prova (FUSAO × p4_2400 × fasrs) que falta.
3. **Separar a taxa de tela do `framework`**: hoje ligar o framework traz junto a trava de 60 Hz do
   battery (o "agarrado") e 120 Hz fixo em jogo. Opção: o framework aplicar só as tríades (jobs/standby)
   e deixar a tela no stock 120/60. Medir antes no kernlab (scroll e jogo, pbat) — as tríades nunca foram
   medidas.
4. **Pico de ~3 s no Roblox (queixa do dono, 2026-10-20)**: inferência = trava de GPU alternando (corrigida no
   2.11.5). Confirmar numa sessão do dono no Roblox com o binário novo: sem pico periódico e sem "FAS stopped".
5. **FAS também para GPU e memória (MIF)** — pergunta do dono, 2026-10-20. O fas-rs só controla CPU. A ideia
   faz sentido (frame acima do alvo = GPU/MIF podem descer), mas o aparelho já mostrou três limites:
   - o Hyper/EPIC da Samsung é DONO de `gpu_min_clock`/`gpu_max_clock` e reescreve nas trocas de app e nos
     hints (`status.md` 2.11.3); o térmico desce o teto da GPU em jogo. Escrever por cima a cada frame = briga
     com a ROM (o dono já decidiu: proibido);
   - o governor da GPU (Mali, tick de 5 ms) já segue a carga; o ganho possível é só TETO (não deixar a GPU
     subir além do que o alvo pede), não piso;
   - MIF/INT são devfreq com QoS da Samsung (mesma família de dono externo).
   Caminho de menor briga: o fas-rs escrever só o TETO da GPU em jogo (`gpu_max_clock`), descendo um degrau da
   `gpu_freq_table` quando o frame sobra e subindo na hora em que falta (mesmo laço da CPU), e soltando no fim.
   Antes de codar: medir no kernlab se capar a GPU num jogo que bate o limite (Bleach 45, Roblox no médio)
   economiza (pbat) sem perder fps, e se o Hyper reescreve o teto DURANTE o jogo (sem troca de app).
