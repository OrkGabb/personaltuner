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
