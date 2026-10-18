# kernlab — tuning de kernel que se prova medindo

Laboratório para os nós que o FKM expõe e ninguém tocou (Stune & Uclamp, CPU Sets,
Scheduler, Memory, I/O, GPU) **mais o EMS da Samsung**, que o FKM nem mostra. A ferramenta
aplica cada valor, prova que o nó segurou durante a medição, mede o efeito com carga
reproduzível e responde com estatística, sem depender de alguém testar na mão.

Alvo: Galaxy M54 (SM-M546B, Exynos 1380, kernel 5.15.189, OneUI 8.5), temp-root KernelSU.

## Por que existe

Tunar sem medir não tem valor. Medir na mão não escala: são centenas de combinações, e cada
uma precisaria de vários minutos para separar o efeito do ruído. Automação com `keyevent` não
mede nada. O kernlab mede por cinco instrumentos, todos provados no aparelho em 2026-10-18:

| Instrumento | O que mede | Prova |
|---|---|---|
| `kl_burst` (nativo) | frame sintético em 2 estágios (UI → render) no cgroup top-app: latência ponta a ponta, perda de frame, wakeup, núcleo usado, energia de CPU por thread | `uclamp.min` 1→30: perdas 88–95% → 0,28% em ABAB |
| `kl_touch` (uinput) | gesto nativo com tempo exato (sem a JVM do `input swipe` disputando CPU) | 4 swipes → 248 frames no gfxinfo |
| `gfxinfo` | frames HWUI: jank, p50–p99, deadline perdido | lista de apps do Configurações |
| TimeStats do SurfaceFlinger | por camada: `averageFPS`, histograma present→present, jank — **inclusive SurfaceView de jogo** | funciona no OneUI 8.5 com `-clear` + `-enable` (o `status.md` §3.8 dizia "vazio": faltava o `-enable`) |
| `kl_sample` + `/proc/<tid>/time_in_state` | potência da bateria (mA × µV, 4 Hz, sem fork), frequências, temperaturas; energia de CPU pelo modelo de potência do próprio EMS | `energy_table` do EMS |

Outros dois: `kl_io` (leitura aleatória O_DIRECT **só leitura** na LUN de dados, sequencial com
cache frio, mista com escritor) e `am start -W` (launch, revisita de apps para memória).

## Método

- **Blocos espelhados**: cada bloco roda uma permutação dos braços e a mesma ao contrário
  (A B | B A). A comparação é sempre DENTRO do bloco, então calor e bateria, comuns ao bloco,
  se cancelam.
- **Estatística pareada por bloco**: efeito %, IC 95% por bootstrap, p por permutação de
  sinal. O veredito exige IC fora de zero **e** |efeito| ≥ limiar declarado no experimento.
  Os vereditos são `MELHORA`, `PIORA`, `EQUIVALENTE (±x%)` (é uma resposta, não um vazio),
  `REAL MAS PEQUENO` e `INCONCLUSIVO` (falta bloco).
- **Métrica primária declarada antes** de rodar. As secundárias são exploratórias.
- **Um trial só conta se o nó segurou**. O nó é relido antes e depois da carga, e as duas
  leituras precisam bater com o pedido. No EMS a prova é o `cur_set`, e o conjunto ativo
  (modo/nível) também precisa ser o do nó: a Samsung troca de nível sozinha, por exemplo
  `level2` no launch.
- **Controles**: `controle-aa` (negativo, tem de dar "sem efeito") e `controle-positivo`
  (top-app sem os A78, tem de dar `PIORA`). Rodar os dois depois de qualquer mudança na ferramenta.

## Segurança (temp-root: reboot completo = root perdido)

- Só lê e escreve o que está em `kl/knobs.py`, com domínio de valores restrito por nó.
  **Lista negra**: `ems/multi_load` (a leitura dele derrubou o kernel em 2026-10-18 com
  UBSAN em `multi_load_read`), `/sys/kernel/ems`, `cpufreq_limit`, `xperf`, `exynos_pm_qos`.
- EMS: o `aio_tuner` aceita `cpu == NR_CPUS` (off-by-one) e não limita `group`. O registro só
  gera os coregrupos 0/4 e os grupos 0–9. Nós `medium` exigem `"allow_medium": true`.
- Estado de base explícito: o PC lê, grava no plano, e o agente recusa começar se o estado
  mudou nesse meio-tempo. A restauração roda no fim, no aborto, no sinal e na saída inesperada.
  Nada do que se escreve sobrevive a reboot (sysfs/procfs); o único `settings` tocado é o
  timeout de tela, que também é restaurado.
- Portão térmico antes de cada trial (espera esfriar). Bateria ≥ 42 °C ou < 15% aborta.
- Roda destacado no aparelho (`setsid nohup`): queda do ADB Wi-Fi não interrompe nem deixa
  estado sujo. `attach` retoma a coleta com a porta nova.

## Uso

```
python tools/kernlab/kernlab.py connect 192.168.1.75:PORTA
python tools/kernlab/kernlab.py inventory                 # estado atual de todos os nós registrados
python tools/kernlab/kernlab.py list                      # experimentos disponíveis
python tools/kernlab/kernlab.py run tools/kernlab/experiments/controle-aa.json
python tools/kernlab/kernlab.py queue --wait-screen a.json b.json ...   # fila; mantém a tela acesa ENTRE runs
python tools/kernlab/kernlab.py posse --preset amplo [--set nó=valor]   # quem reescreve o quê (ver abaixo)
python tools/kernlab/kernlab.py summary                   # uma linha por (run, braço) em runs/SUMMARY.md
python tools/kernlab/kernlab.py attach <run_id>           # depois de uma queda do Wi-Fi
python tools/kernlab/kernlab.py stop [--clear]            # aborta e restaura; --clear libera novos runs
python tools/kernlab/kernlab.py report tools/kernlab/runs/<run_id>
python -m unittest discover -s tools/kernlab/tests        # suíte offline
NDK=~/android-ndk-r28 bash tools/kernlab/native/build.sh  # (WSL) recompila os nativos
```

Um experimento é um JSON em `experiments/`: workload, braços (`{nó: valor}`; o primeiro
braço é a referência e `@base` significa "como o aparelho estava"), número de blocos,
métrica primária e limiar. Resultados ficam em `runs/<run_id>/` (fora do git), com
`report.md`, os artefatos brutos por trial, o plano exato que rodou e o manifesto.

## Posse do nó

Escrever um nó não é controlá-lo: a Samsung reafirma a política dela em eventos. `posse` grava
os valores, provoca espera / launch / scroll / home / outro app e relê tudo depois de cada
evento → `SEGURA` ou `REVERTIDO em <evento>`. Um nó revertido não pode ser tunado por escrita
direta (o experimento mediria o escritor da Samsung); a saída é usar uma alavanca equivalente
que ninguém reescreve (ex.: `freqboost` do grupo `ta` dentro do emstune) ou configurar o
escritor na fonte. Trava por `chmod 444`/bind-mount continua proibida (nodes-m54.md §10).

## Achados medidos (2026-10-18, M54, tela acesa)

| Achado | Prova |
|---|---|
| O ego (`energy_aware`) escolhe ~1,25 × ciclos/s **por thread**; frame encadeado em 2 estágios pode ficar na freq. mínima | burst: 83% de perda no equilíbrio A55 |
| O burst padrão é **biestável** pela histerese do ontime (sobe >90, desce <61): estágio 1 preso nos A55 (util ~63, ~83% de perda) ou estável nos A78 (util ~123, ~1,5%). Números absolutos entre runs não se comparam; o relatório mostra o regime da base | `regime_warnings` nos relatórios |
| No equilíbrio A55, `top-app uclamp.min` 25 tira a perda de 83% para 0,5% (+48% energia/frame); 10 não paga; 40 só gasta mais | `uclamp-topapp-min` (base mista: ver aviso) |
| Mesmo run, equilíbrio A78, pisos quietos: `uclamp.min` 25 → −19% lat_p95 (+12% energia/frame); **`freqboost ta` 30 → −47% (+34%)**; 60 → −69% (+123%, desperdício) | `alavancas-frame-burst`, 48/48, p=0,031 |
| Posse: **18/20 nós seguram** espera/launch/scroll/home/troca de app. `top-app uclamp.min` é **revertido pelo Hyper/EPIC** (recurso `TABoost` em `/vendor/etc/hyper/config_vendor.json`); `gpu_poweroff_delay` não aceita escrita; **todo parâmetro do emstune segura** | `posse --preset amplo` |
| Hints do Hyper sobem `scaling_min_freq` (ex.: `LISTVIEW_SCROLL` 40% por 10 s, `AMS_RESUME` até liberar) → o portão de cada trial espera o piso voltar ao mínimo físico | portão `gate_floor` |
| No toque o emstune passa ~metade do tempo no **nível 1**; tuning do EMS para UI precisa dos níveis 0 e 1 | amostragem do `cur_set` durante scroll |
| Scroll real a 120 Hz: `freqboost ta` 30 sem efeito; 60 → −20% saltos de 1 frame por **+25% de potência**. Na UI o gargalo restante não é clock (o Hyper já dá piso no toque) | `ems-freqboost-ta-scroll120`, 36/36 |
| `swappiness`/`vfs_cache_pressure` do perfil battery (160/150 vs 130/100): sem efeito mensurável no scroll | `stock-vs-modulo-scroll`, 48/48 |
| A Samsung **reescreve** `top-app uclamp.min`: 1.00 com tela acesa, 0.00 em Dozing, volta a 1.00 a cada launch | 6/6 trials invalidados por deriva 25.00→1.00 no `scroll-uclamp` |
| emstune troca de conjunto sozinho: nível 2 no launch (lá `support_uclamp=1`), `power-scenario` com tela apagada | `cur_set` capturado após `am start`; env por trial |
| O perfil `battery` do FUSAO trava o refresh em 60/60 | `framework.sh` + `settings` |
| 120 Hz no scroll: **2× frames, intervalo 16→8 ms, ~6× menos saltos ≥33 ms**; sobram saltos de 1 frame (16 ms); **+34% de potência no scroll**; 120/60 = 120/120 durante o toque | `display-refresh`, 36/36 válidos |
| TimeStats funciona no OneUI 8.5 com `-clear` + `-enable` (fps e present→present por camada, inclusive jogo) | fixtures em `tests/fixtures` |

## Jogo (in-situ, única parte que precisa do dono)

1. Abrir o jogo e deixar numa cena **estável**: personagem parado, câmera fixa, sem menu,
   sem chat abrindo. Pode ser AFK num lobby.
2. Sonda de 5 s do TimeStats antes (a camada do jogo aparece? quantos fps?), depois
   `python tools/kernlab/kernlab.py run tools/kernlab/experiments/game-freqboost.json`
   (Roblox; `game-tetos.json` = NTE; troque `package` no JSON para outro jogo).
3. Não tocar na tela até o fim (~15-25 min). O trial que perde o foco é invalidado sozinho.
   Roblox expulsa quem fica parado ~20 min: o run precisa caber antes.

O kernlab só alterna o nó e mede a SurfaceView do jogo pelo TimeStats. Os blocos espelhados
cancelam a deriva térmica do jogo; o portão térmico do JSON é mais alto (SoC 70 °C).
Jogo segura piso de CPU o run inteiro (NTE/Roblox: P0 min 1152000), então os experimentos de
jogo usam `"gate": {..., "floor": "start"}`: a referência do portão de piso é o piso lido no
início do run (só hint ACIMA dele segura o trial). Com o padrão `"min"` todo trial de jogo
esperava 30 s e saía inválido. Em jogo o térmico da Samsung assume os tetos de GPU/CPU depois
de ~8 min: trial cujo teto derivou é excluído (2026-10-18: 12/36 e 9/24 válidos).

## Estender

- **Nó novo**: uma linha em `kl/knobs.py` com caminho, tipo e domínio seguro. Leia o nó uma
  vez à mão antes, sozinho.
- **Workload novo**: uma classe em `kl/workloads.py` (linha do plano, parser, direção das
  métricas) e uma função `wl_<nome>` em `device/agent.sh`. O compilador e a análise não mudam.
