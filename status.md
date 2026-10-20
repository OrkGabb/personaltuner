# STATUS CONSOLIDADO — FUSAO-M54 single-module (M54) — 2026-10-12 pós-reboot (v2.6 LIVE + verificada)
> Para novo chat: colar este arquivo + `handoff.md` + `nodes-m54.md`
> (`_historico/ataque.md` = plano original das 8 fases, cumprido; só colar se for
> retomar trabalho no módulo. APK: usar `fusao-apk/LEIA-ME.md`).
> Dono: PT-BR, direto, técnico, prova no aparelho (comando+saída), nunca teoria.
>
> ## MAPA PÓS-FAXINA (2026-10-13, PC reorganizado, aparelho intocado)
> Raiz agora tem SÓ: `fusao-v2.0/` (stage ativo 2.9.4/1596) + `fusao-apk/` +
> `tools/` + 4 docs. Resto movido sem perda:
> - `_releases/` = 22 zips `fusao-v*-M54.zip` (atual `fusao-v2.9.4-M54.zip`).
>   `tools/fusao-build.py` agora gera em `_releases/` por default; use
>   `--out _releases/fusao-vX-M54.zip`.
> - `_referencia/` = Encore/Stellar/Rairin (zips + extraídos) + `rboost.apk` +
>   baselines + `revert-stock-manual.sh` + `unspoof-thermal.sh`.
>   `handoff.md` atualizado p/ os novos paths.
> - `_historico/` = stages velhos (`fusao-v0.1/`, `fusao-v1.0/`) + fontes Rust
>   (`fusao-brain/`, `fusao-guard/`, `fusao-ebpf/`, `fusao-observer/`) +
>   `.observer-build/`, `.fake-test/`.
> - `fusao-apk/` = `FusaoApp-v1-debug.apk` (cópia do WSL `~/FusaoApp/.../app-debug.apk`)
>   + `LEIA-ME.md` (contrato p/ outra AI: design/responsividade).
> Menções antigas a `Rairin/`, `fusao-v0.1/`, `fusao-v1.0/` etc. neste arquivo
> referem-se aos paths PRÉ-faxina (= `_referencia/` / `_historico/` hoje).

## HANDOFF — estado verificado 2026-10-12 ~09:45 (v2.6 pós-soft-reboot, NTE aberto)
**Tudo o que pediu prova passou ao vivo:**
1. Módulo live `2.6.0-fusao/1587 OrkGabb`, `modules_update` consumido ✓
2. 4 daemons vivos com PIDs NOVOS (kill_stale ok): observer 16750, watch 16757,
   killer 16769, `fusaod serve` 16774 ✓
3. Observer: `registrado (tentativa 2)` — retry de 15s cobriu o AM atrasado ✓
4. NTE detectado instantâneo (`game-active.txt` = com.hottagames.nte) ✓
5. **TESTE CENTRAL v2.6 PROVADO**: `serve` vivo após 55+ ticks.
   `FALHOU: verify-fail p4 1056000→1248000` vira marcação + `recuado`, SEM
   morte; `CONTENDA p0` recua o nó. GPU 949000/949000 segurando teto ✓
   Reconcile: `externo p4 (1248000) reassumindo` — Samsung ADPF escreve
   floors de CPU em jogo; daemon re-afirma 2×, recua na 3ª. Sem briga, sem morte.
- Flags: `fusaod.enabled` + `dnd.enabled` ON; profile=game. sysfs: gpu
  949/949MHz fixo, mif 1014000, int/disp 333000, p0 1728000 (Samsung, recuado).
- Fios abertos p/ próximo chat: (a) matriz agora: `fusao framework on` →
  `perf on` → `fusao preload record` com NTE aberto 20-30s + `preload on` →
  DND/downscale; GPUWatch a cada etapa; bateria 45°C+ aborta; (b) p4 também
  trava (não só p0) — latch de descida/RW Samsung, ver 3.3; (c) DND bounce no
  loading (exit na perda de foco); (d) Rairin desativado (vigias mortas,
  kill-rairin.sh se voltar); (e) snapshot 02:44 contaminado; (f) swappiness 150
  = stock Samsung; (g) fusaod.log 12 linhas vinhos = 24h de daemon real.

## 0. Como operar (regras da casa)
- Aparelho: Galaxy M54 SM-M546B, Exynos 1380, Android 16/OneUI 8.5, temp-root KernelSU
  (uid 0, `ksu:s0`). **Soft-reboot preserva root E boot_id** (`b7177b93-...` intacto em
  todos); full reboot = proibido (perde temp-root). Uptime atual ~2.5 dias.
- ADB: cabo `RQCW506Z0CX` + WiFi `192.168.1.68` com porta ROTATIVA (40087→43097→
  38409→38721→42653…; wireless debugging dorme sozinho). Sem cabo: pedir porta nova.
- Sessão opencode `ses_ed9e95c95ffei9Rrz2rW9hZHVQ` MORREU (250k tokens + 400
  `Duplicate function_call_output bash:0` + respostas `@@@`). Nunca continuar nela
  sem `--fork`; recomeçar destes docs.
- Shell: PowerShell 5.1 (sem `head`, sem `&&`; `;` ok). `$` dentro de string dupla é
  interpolado — comandos adb com `$` (ex. `$f`, `$?`) QUEBRAM ou mentem (`rc=True`).
  Regra: comandos com `$`/pipes complexos vão em **script pushed** (`/data/local/tmp/`,
  depois `rm`), nunca inline. `pkill -f X` mata a própria shell se o alvo aparece na
  mesma linha — usar script pushed com filtro de PID (`grep -v $ME`), nunca pkill inline.
- Flash: `adb push zip /sdcard/Download/` + `ksud module install` (mostra self-check
  `[OK]/[MISSING]`; aborta em `[MISSING]`). Instalação stageia em
  `/data/adb/modules_update/` — ativa no próximo soft-reboot. Zips SÓ via Python
  `zipfile` (forward slashes; Compress-Archive gera backslashes que o instalador não lê).

## 1. Estado atual (2026-10-12, aparelho VERIFICADO pós-reboot v2.2)
- Boot: `boot_id b7177b93-...` PRESERVADO (soft). Módulo `2.2.0/1583 OrkGabb`
  ativo (`modules_update` consumido). SSOT semeado, `stock.snapshot` novo,
  `profile=balanced`, `game-active` vazio. Sysfs stock (533000/533000/221000,
  energy_aware).
- Vivos: `watch.sh` + `gos_killer.sh` (killer matou GOS em 3 game-enter Roblox
  pré-reboot: pids 13883/19821/27768) + **observer gerenciado (pid 15208,
  `observer: registrado`)**. `fusaod` parado (sem flag, esperado). Sem `encored`.
- Legado: `gos_killer.paused` (2026-10-11) órfão no disco — killer v2.x não lê
  esse marker (roda sempre); limpar na próxima build ou documentar.
- eBPF: `BPF_PROG_LOAD fd=3` ao vivo (ver §3.17).

## 1b. Sessão offline v2.2 (não instalada na época — histórico)
- Build pronta e NÃO instalada: **fusao-v2.1→v2.2-M54.zip** (735KB, 62 entries,
  sweep OK, 62/62 shell + 23/23 Rust). Instalação via adb WiFi pendente
  (porta da sessão passada `33969`, rotativa — confirmar antes).
- Aparelho: estado reportado pelo dono em 2026-10-11 (v0.5 desativada pós
  soft-reboot limpo) segue NÃO verificado. Nada foi escrito no device aqui.
- Autor no module.prop: **OrkGabb**. id mantido `encore` (continuidade de
  upgrade; evita módulo órfão no aparelho).

## 1b. Estado anterior do aparelho (último verificado, 2026-10-11)
- Módulo: **encore v0.5 v1579** (`fusao-v0.5-M54.zip`, 2.5MB). RaiRin-AI 8.51-NOVO
  enabled. zygisksu. `/data/adb/fusao/` sem `fusaod.log` (serve nunca subiu).
- Vivos: `encored daemon` + `gos_killer.sh` **PAUSADO** (marker `gos_killer.paused`;
  voltar com reboot ou start manual) + vigia Rairin (3× `service.sh`) + **GOS VIVO**
  + Rboost SEM APK (tudo via WebUI). `fusaod` estacionado (gate `fusaod.enabled`).
- Sysfs: stock (energy_aware, min 533000/533000, gpu 221/650, IO ssg, zones enabled).
- Marcas manuais pendentes de revert: `fixed-performance-mode=true` (ligado por mim,
  nunca revertido), governor `performance` travado nas 2 policies (segurou o jogo
  inteiro; `encored` não reescreveu). Se for mexer: devolver `false` + energy_aware.
- Baselines PC: `baseline-NOVO/STOCK-2026-10-10.txt`, `revert-stock-manual.sh`,
  `unspoof-thermal.sh`, `Rairin/` (NOVO zip, `rairin_novo.py`, gamelist SSOT),
  `encore-FUSAO-5.2.2/` (chassi original), stage em `fusao-v0.1/` (nome histórico;
  contém v0.5), zips `fusao-v0.1…v0.5-M54.zip`, `fusao-brain/` (Rust 0.4.0),
  `fusao-guard/` (guard byte-intacto + lib mínimo).

## 2. O que foi construído (v0.1 → v0.5 no aparelho; v1.0 → v2.2 offline)
- **v0.1**: chassi Encore + `fusaod` debug 5.3MB + guard. `stock.snapshot` real no boot,
  self-heal, self-check, uninstall com revert. WebUI = controle (decisão Fase 6).
- **v0.2**: + `gos_killer.sh` (schedule do dono: boot/30s/5min/10min/1h/horário,
  pid-record, log `fusao.log`, sem pm-disable).
- **v0.3**: cérebro com `prime()` (semeia cache do sysfs vivo) + `step_down` por tabela
  (descida sempre em degraus; subida direta) + testes 15/15 + release 539KB (NDK r28c).
  Killer + game-guard (poll 5s em game-active.txt, kill GOS em ≤5s do launch).
- **v0.4**: PARTIÇÃO — `decide()` emite SÓ `gpu_min_clock` (CPU virou telemetria);
  15/15 reescritos, release 537KB. Deploy ao vivo em jogo: 1 write GPU OK + silêncio.
- **v0.5 "Encore+"**: serve SÓ com `fusaod.enabled` (default: zero writer novo) +
  `pkill -f encored` no uninstall (fim do zumbi) + killer/game-guard + guard.
  Superfície de briga zero por construção. **Gate do serve PROVADO pós-boot.**
- **v1.0 (offline, 22 Rust + 30 shell)**: cérebro GPU/MIF/INT/DISP por perfil,
  watch próprio, triads Stellar fiéis (strings do daemon), perf, framework,
  CLI `fusao`, WebUI fusão. Ainda com `encored` (partição CPU=encored).
- **v2.0 single-module (offline)**: `encored`/profiler/binder/SPA REMOVIDOS
  (zip 2.5MB→728KB). `fusaod` writer único (assume CPU floors P0/P4 com
  degraus). Watch sole detector, zero fallback/Rairin. CLI+WebUI controle único.
- **v2.1 (offline, 23 Rust + 43 shell)**: observer `IProcessObserver` via
  app_process (dex 6.8KB, stub fora do dex — verificado por parser) como
  detector primário + watch em standby; `tune.sh` (swappiness/vfs_pressure/
  readahead/TCP por perfil, snapshot+revert, atrás do flag do framework).
- **v2.2 (offline, 23 Rust + 62 shell, ESTA)**: DND em jogo (flag `dnd.enabled`,
  off forçado no uninstall) + downscale `wm size` (720p/810p/900p/reset,
  escolha explícita, revert no uninstall) + gamelist add/del/list no CLI e na
  WebUI + `fusao-bpf-probe` (ELF ARM64 6.7KB, BPF_PROG_LOAD via syscall crua)
  + revert de `device_config game_overlay` no idle (bug achado na revisão
  processo×nó). WebUI verificada offline (HTML balanceado, 11 cmds com
  handler, zero URLs externas).

## 3. Descobertas provadas (achado → evidência)
1. **Detecção funciona nos 2 sentidos**: `game-active.txt` popula (NTE/Roblox) e zera
   em ≤5s na perda de foco; `current_profile` 1=jogo (encored auto-cura 1→0 sozinho).
2. **Single-writer era necessário**: `encored` SEGURA `policy0/min=1152000` cravado
   (watch 60s); janelas sem ele = estável. Rajada de entrada escreve 2002000 (max).
3. **Latch de descida do driver**: subidas colam sempre; descidas policy0 abaixo de
   1152000 recusadas por estado (fronteira exata, reproduzível); degraus de tabela
   passam (staircase de 9 writes verificados em jogo). Latch ≠ writer (10s sem
   flutuação com tudo morto). `policy4` aceita pulos; `policy0` exige degrau.
4. **Boot-race**: tick 1 pós-boot lê transiente do `encored` aplicando perfil
   (533000→1824000). Fail-loud, nunca thrash. v0.3+ resolve com prime+degraus.
5. **userspace reboot NÃO mata loops nohup** (killer sobreviveu com código velho;
   service.sh sobrescreveu o .sh embaixo = confusão). Trade com PID, nunca pkill inline.
6. **GOS**: nasce sozinho no boot E no game-launch (pids 18973/4078/1446/13883/19821/
   27768); killer mata em ≤5s (marks `boot/game-enter` no log); force-stop segura.
   GOS vivo = 12-13fps, morto = 19fps: aqui CUSTA, não paga. Killer pausado p/ teste.
7. **Inocentes com prova**: fixed-performance-mode (off→mesmo fail), governor
   (performance segurou o jogo → mesmo 15fps), mitigação (upstream 1571 tem EXATOS
   mesmos items, `device_rules={}` — FUSAO idêntica p/ M54), cpuset (grupos 0-7,
   sem restrição), Rairin shell (124 linhas lidas: único loop é o vigia),
   ADPF/foreground-hint (foco fora não solta o latch), thermal (frio com latch ativo).
8. **Instrumentos de fps MORTOS no OneUI 8.5**: `gfxinfo` só conta UI (4-25 frames em
   engine); `timestats -dump` vazio (mata junto o fps-counter do Rboost); `--latency`
   anel vazio. Válidos: **GPUWatch** (engine) — e Rboost SEM APK em todos os testes.
9. **throttle térmico real e legítimo**: `gpu_max` 650000→552000 a 57-65°C, liberação
   SOZINHA ao esfriar. Reboot não evita; só esfriar resolve. Cabo aquece (carga).
10. **Uninstall reverte tudo**: stock textbook pós-remoção + `/data/adb/fusao` limpo.
    Falha achada e corrigida na v0.5: `encored` zumbi (pid 30132 sem config).
11. **Rairin perf1.sh** trava `min=max_f` + `chmod 444` (one-shot via app); `perf1.sh`
    travou nós em 0444 no passado; adulteração de sensores térmicos (`extreme2/lite2`, 50+ bind-mounts)
    banido + removido (53 umounts); GPU ELFs no-op no Exynos; `$MOD` bugado.
12. **Exit-detection**: vigia limpa na saída; teardown morre se os agentes morrem
    (DND travou zen=1 → `dnd.sh --disable` do próprio Rairin resolveu; floors via
    degrau; profile auto-curou). `default_cpu_gov` tinha resíduo `schedutil` (stale).
13. **Profiler do Encore trava min=max+0444 (bytes dele, não teoria)**:
    `exynos_performance` escreve `gpu_min=gpu_max=max`, MIF `max_perf`,
    governor `performance` + `chmod 444` nos `scaling_*`. Bate com baseline-NOVO
    (gpu 949000/949000, mif 2093000/2093000) e nós 0444. `encored` em si NUNCA
    escreve sysfs (só detecta via binder + delega ao profiler). Ressalva: Rairin
    `perf1.sh` faz o mesmo `min=max`+0444 — autoria do cadeado é compartilhada.
14. **rboost NÃO reformado (medição, não preguiça)**: 76 paths hardcoded p/
    `RaiRin-AI` no dex, zero p/ fusao; nada de coleta de serial ou rede no app (sem
    verificações remotas no APK). Redirecionar = rebuild dex + resign que
    quebra a cada update. Fica fora do módulo, intocado; contrato do zip é só
    `/data/adb/fusao/` (leitura p/ overlay futuro, nunca escrita).
15. **Bateria manda no thermal (regra do dono, guard já cumpre)**: CPU 80°C+ OK;
    bateria 45°C+ prolongado = risco. `thermal_guard` dispara em compute
    ≥78°C OU bateria ≥45°C + histerese + timeout — alinhado por construção.
17. **eBPF FUNCIONAL ao vivo (2026-10-12, teste real, não config.gz)**:
    `fusao-bpf-probe` (BPF_PROG_LOAD via syscall crua, socket filter `ret -1`):
    1ª rodada `errno=14 EFAULT` = BUG NO MEU STRUCT (`license` é `__u64`, eu
    pus `char[16]` e desalinhei tudo) — corrigido, 2ª rodada `fd=3, rc=0`
    como root. Kernel 5.15.189 + JIT=y + BTF 5.7M + CGROUP_BPF=y.
    Veredito: observer/tuning eBPF de baixo overhead é VIÁVEL aqui (fas-rs
    do dono já sugeria). Entra no backlog pós-matriz, não neste flash.
18. **Observer PROVADO ao vivo (2026-10-12, pós-reboot v2.2)**:
    instância do boot morreu com `RuntimeException: ActivityManager
    indisponivel` (logcat, pid 11861, 02:44:37 — AM ainda não publicado);
    a mesma dex, minutos depois, `observer: registrado (pid 14111, 15208)`.
    Handoff observer↔watch PROVADO nos 2 sentidos ao vivo (`standby` com ele
    vivo, `assumindo poll 5s` com ele morto, de volta a `standby`).
    Correção: retry 20×15s dentro do `register()` (dex 7040b rebuildado e
    verificado por parser — **ainda NÃO no aparelho**, vai na próxima build).
    Instância gerenciada atual é a 15208 (manual 14111 morta, tmp limpo).
19. **Detecção instantânea PROVADA em jogo real (NTE, 2026-10-12)**:
    `observer: enter com.hottagames.nte` no `fusao.log` com foco confirmado
    (`GameActivity`, dumpsys) — sem os 5s do poll. Killer matou o GOS no
    game-enter (pid 17745). `fusaod` parado (sem flag, esperado).
    Achados p/ matriz: (a) `policy0/min=672000` com jogo aberto e NENHUM nosso
    writer ligado — autor externo (Samsung GameDriver/GOS antes do kill ou SDK
    do jogo); observar se persiste; (b) DND on/off alternou 6× em ~1min
    (bounce de foco no loading + `dnd.enabled` ativo) — exit apaga DND ao
    perder foco (shade no meio do jogo desliga o DND); comportamento anotado,
    sem mudança agora.
20. **Jank no NTE explicado por caps EXTERNOS (monitor 10s×60, 2026-10-12)**:
    com jogo em foco e TUDO nosso desligado: GPU 90-96% presa em 650MHz
    (`gpu_max=650000` com device FRIO — não é throttle térmico), `p4/max`
    1536000-1632000 (< stock 1824000), `p0/min` 1152000. Autor: perfil de jogo
    da Samsung (GameDriver/GameService — GOS estava morto o run inteiro).
    MIF/INT/DISP e bateria (26-27°C) inocentes. Consequência p/ o módulo:
    `fusaod` hoje só escreve MIN — contra teto externo não adianta; próxima
    build precisa possuir `gpu_max_clock` também (game=949000=stock, idle=
    restore via snapshot). Sem isso, matriz do `fusaod on` mede pouco.
    Sessão completa: 60/60 amostras com `gpu_max=650000`, GPU ≥85% em 58/60,
    bateria máx 35.7°C (longe dos 45), G3D máx 57°C (perto do corte legítimo
    57-65°C→552000: aliviar p/ 949000 esquenta — guard observa), GOS morto 60/60.
21. **Soft-reboot NÃO relança `service.sh` (2026-10-12, pós-v2.3)**:
    update aplicado (post-fs-data rodou, 2.3.0 ativa), mas `service.sh`
    (late_start) NÃO rodou neste boot: dex segue 05:19 (sem self-heal novo),
    observer morto desde 05:20:25 sem relaunch, watch+killer com os MESMOS
    PIDs desde 02:44 (sh sobrevive, `app_process` morre com o runtime).
    Subido manual gerenciado (pid 17824, `tentativa 1` — retry dex já live).
    Doutrina: reboot ≠ daemons frescos; sempre `pgrep`, nunca presumir.
    Fix definitivo (próxima build): `watch.sh` relança o observer ao detectar
    morte, em vez de só fazer poll.
- **v2.4 INSTALADA via WiFi `34553` (2026-10-12)**: **23/23 `[OK]`**. Primeiro
  tick do `fusaod` FALHOU ALTO ao vivo (`mif 1240280 → lido 1240000`: devfreq
  exige degrau de tabela) + `gpu_min=351000` verificado e depois resetado
  sozinho p/ 221000 (writer externo). Tabelas MIF/INT/DISP lidas ao vivo.
- **v2.5 INSTALADA via WiFi `40125` (2026-10-12)**: **23/23 `[OK]`**. Primeiro
  `fusaod on` ao vivo: `gpu_max` 650000→949000 OK, MIF/INT/DISP com snap OK,
  morte dupla no latch `policy0` (boot + manual). Tabelas devfreq lidas.
- **v2.6 INSTALADA via WiFi (2026-10-12)**: **23/23 `[OK]`**, stageada; NTE
  fechado p/ reboot. Ver "HANDOFF v2.6" no topo (checklist pós-boot).
  fail-LOUD sem fail-DEAD: verify-fail recua o nó (`mark_contended` com `want`
  p/ detectar recuperação; sucesso limpa a contenda) e o tick SEGUE nos
  outros 6 — latch do `policy0` nunca mais mata GPU/MIF. Correção de registro:
  o `service.sh` SIM subiu o serve no boot v2.5 (flag já existia); as duas
  mortes foram a do boot + a manual, ambas no latch.
  + MemPreload portado de verdade: `fusaod preload <pkg>` (`posix_fadvise`
  WILLNEED nos 3 dirs de dados, cap 2000 arqs/512MB, sem symlink, sanitizado;
  e2e offline com binário linux prova walk+advise) + hook no game-enter atrás
  de `preload.enabled` (default off) + `fusao preload on|off|now|record`.
  + **record→replay aprendido** (veredito web: primitivo WILLNEED é o certo —
  Chromium/preload-ng/kernel o usam; `mlock` trava RAM e `read()` bloqueia;
  o que manda é a SELEÇÃO): `preload-record` observa FAN_OPEN (fanotify,
  syscall crua — Bionic não exporta wrapper) e grava pack por jogo
  (`mtime size path`); `preload` usa o pack fresco e só faz walk na 1ª vez
  ou >50% obsoleto. Record exige root (só ao vivo); replay 100% testado
  offline (roundtrip + fresca/morta/inválida + e2e). Sem stub.
  + `gpu_max_clock` possuído (game=949000) + snap MIF/INT/DISP nas tabelas reais
  lidas ao vivo + `reconcile()` no cache (diverge 1-2× = re-afirma, 3+× =
  contenda declarada e recuo — sem briga com o driver do jogo). NÃO instalada
  — flash+reboot com o dono. Pós-flash a matriz recomeça do `fusaod on`.
16. **Revisão processo×nó v2.2 achou 1 bug real antes do flash**: `framework.sh`
    revertia `settings` no idle mas NÃO o `device_config game_overlay`
    (corrigido + teste `dc-revert`; `device_config delete` no stock ausente).

21. **Soft-reboot NÃO relança `service.sh` (2026-10-12, pós-v2.3)**:
    update aplicado (post-fs-data rodou, 2.3.0 ativa), mas `service.sh`
    (late_start) NÃO rodou neste boot: dex segue 05:19 (sem self-heal novo),
    observer morto desde 05:20:25 sem relaunch, watch+killer com os MESMOS
    PIDs desde 02:44 (sh sobrevive, `app_process` morre com o runtime).
    Subido manual gerenciado (pid 17824, `tentativa 1` — retry dex já live).
    Doutrina: reboot ≠ daemons frescos; sempre `pgrep`, nunca presumir.
    Fix definitivo (próxima build): `watch.sh` relança o observer ao detectar
    morte, em vez de só fazer poll.

23. **Rairin morto DE VERDADE ao vivo (2026-10-12)**: 3× `service.sh`
    sobreviventes mortas via script pushed (`kill -9` por PID, self fora de
    alcance) + `pgrep` vazio pós-kill. Módulo desativado não as ressuscita.
    Higiene de órfãos no NOSSO `service.sh` (mata os 5 daemons por PID com
    `$$` excluído ANTES de subir — código novo sempre, nunca zumbi velho) +
    `uninstall.sh` (`observer.sh`, `*_pid`) + `cleanup.sh` (guard/killer/dnd
    no disable). Harness 73/73. **v2.4 PRONTA p/ flash** (`fusao-v2.4-M54.zip`,
    sem flash sem aval do dono).
22. **Rairin morto mas vigia viva; ZRAM Samsung (2026-10-12)**:
    `RaiRin-AI/disable` presente, porém 3× `service.sh` sobreviventes (mesma
    classe dos nossos — loop sh não morre). `swappiness=150/pressure=120`
    provavelmente stock OneUI (tune nunca aplicou; Rairin sem writer ativo).
    ZRAM: 4GB, `[lzo-rle]` ativo (`lz4`/`zstd` disponíveis), 908MB swapped,
    `page-cluster 0`. Trocar p/ `lz4` exige `swapoff+reset+swapon` (joga 908MB
    de volta na RAM — engasga e é arriscado com jogo aberto; e não persiste
    sem hook de boot). Item de matriz FUTURA, em idle e com RAM livre — NÃO agora.
## 4. Falhas honestas (o que deu errado)
- **Regressão Roblox: 8-19fps vs 60fps+ dos solos, device frio.** Sem resíduo em
  NENHUM nó lido (governor/bus/refresh/device_config/perms/daemons — tudo stock).
  Mecanismos candidatos: (a) oscilação/pacing destruído por brigas+mortes a cada tick;
  (b) dip `policy4/min=533000` sem autor (big-cores famintos); (c) GPU 100% a 949MHz
  fria = workload full-res sem alívio (GOS "otimização" não paga aqui).
  Teorias MINHAS mortas: térmica (dono enterrou), GOS-load-bearing (12 < 19),
  fixed-mode, governador, mitigação. Ranking do dono p/ perf bruta: 1.Encore 2.Stellar/Rairin.
- **Erros meus de processo**: zip com backslashes (refeito); `pkill -f` suicida (2×);
  seção status apagada num edit (reparada); foco excessivo em térmico; assumir Game
  Booster FPS meter inexistente; sessão opencode morta por contexto.
- **Em aberto**: dip policy4 sem autor; latch 1152000 (regra exata do driver TBD);
  run Roblox instrumentado com GPUWatch (números engine) nunca fechado;
  observer registra ao vivo?; valores tune/profile_caps sem número.

## 5. Doutrina operacional (vale para o próximo chat)
1. Stock = energy_aware + ssg; snapshot em `/data/adb/fusao/` (NÃO apagar;
   legado em `/data/adb/.config/encore/` só existe se v0.5 já passou por aqui).
2. `chmod 644` antes de escrever; `policy0/min` SÓ em degraus de tabela; tetos
   policy4/max 1824000, mif/max 2093000; subidas colam, descidas podem recusar.
3. UM writer por nó, UM módulo (v2.x): CPU/GPU/MIF/INT/DISP=`fusaod` (cache);
   governor=intocado (só restore no boot/uninstall); IO-notify=`perf.sh`;
   readahead/VM/TCP=`tune.sh`; zones=`guard`; settings/device_config=
   `framework.sh`; zen=`dnd.sh`; `wm size`=`display.sh` (manual);
   `game-active.txt`=observer (watch só se ele morrer). Fail-loud > fight.
4. GOS: killer com game-guard (pausado agora); votar com número antes de matar.
5. Bateria = termômetro tenso (dono); CPU 80-90°C ok, bateria 45°C+ prolongado
   NÃO (guard dispara). Cabo fora ao jogar.
6. Medição: GPUWatch p/ fps engine; sysfs/zonas DURANTE o run; feel sem número não decide.
7. Builds: `cargo test` + release NDK (WSL); stages `fusao-v2.0/` (+`fusao-v1.0/`
   histórico); zip Python (forward slashes); sweep kill-list + harness shell +
   check-dex + check-webui; `ksud module install`; soft-reboot PELO DONO;
   WiFi rotaciona (confirmar porta). Stock agora em `/data/adb/fusao/`.

## 6. Próximo (v2.2 ATIVO — matriz, com o dono)
1. ~~adb/eBPF/flash~~ FEITOS em 2026-10-12 (probe `fd=3`, install 23/23 OK).
2. **Baseline limpo + GPUWatch** (frio, sem cabo) e matriz UM diferencial por vez:
   fusaod → framework(+tune) → perf → DND/downscale (features, sem número de
   fps — feel + bateria).
3. Observer em jogo real: abrir jogo da lista → `game-active.txt` preenche na
   hora (sem os 5s do poll) → overlay/perf engatam; matar jogo → limpa.
4. **A/B v1.0 vs v2.2** se detecção por evento fizer falta.
5. Próxima build (quando a matriz pedir): observer com retry (dex pronto no PC),
   cosméticos do self-check, limpeza do `gos_killer.paused` legado.

## Juízo 0-100 (2026-10-11)
0 baseline 100 | 1 chassi 95 (flash+uninstall provados, falta pkill-test do v0.5) |
2 detecção 100 | 3 cérebro 70 (lógica provada, estacionado por decisão) |
4 executores 100 | 5 framework 0 | 6 UX 60 (WebUI no ar, sem app) |
7 kill-list 10 | 8 validação 40 (matriz gigante, sem número engine fechado).
**Global v2.2: single-module completo offline (Rust 23/23, shell 62/62, sweep OK);
perf no aparelho PENDENTE (nada provado em fps/temp ainda — matriz do §6).**
Dívidas que viraram código: triads ✓, kill-list ✓ (offline), uninstall total ✓,
WebUI própria ✓ (gamelist editor + kill-switch + bugreport), observer ✓ (runtime
pendente), tune/DND/downscale ✓ (valores conservadores, matriz afina).

## Pós-reboot limpo + v0.5 DESATIVADA (2026-10-11, report do dono, NÃO verificado)
- Dono fez soft-reboot (limpo) e desabilitou o módulo encore v0.5. Aparelho
  inalcançável nesta hora (WiFi 42653 morta pós-boot, sem cabo) — estado
  "limpo + desativado" é REPORTADO, não provado. Verificar no reencontro:
  `disable` em `/data/adb/modules/encore/`, nenhum `encored/fusaod/killer`,
  sysfs stock, boot_id (soft preserva?).
- Marcas manuais provavelmente LIMPAS pelo reboot (fixed-mode? governor?
  Rairin service.sh NÃO re-roda em soft-reboot — vigia é sobrevivente do boot
  14:17; killer NÃO sobe com módulo desativado).

## O QUE FALTA / FALTANTES v2.2 (nada atrás sem veredito escrito)
- **Feito offline**: single-module ✓, kill-list ✓, triads ✓, uninstall total ✓,
  WebUI própria ✓ (gamelist add/del/list + kill-switch + bugreport), observer ✓
  (código; runtime pendente), tune/DND/downscale ✓ (conservadores; matriz afina).
- **Diferidos com motivo (não esquecidos)**:
  1. `touch.sh` levels (resetprop `ro.surface_flinger.*`): props `ro.*` lidas no
     boot do SF — sem prova de efeito em runtime; barato de adicionar se a matriz
     pedir feel de touch.
  2. `render/*` (setprop `debug.hwui.renderer` vulkan/skia/hybrid): exige restart
     do app + prova de ganho; mesmo custo baixo, mesma condição.
  3. Falsificação de identidade do aparelho: FORA por doutrina — exige Zygisk no processo-alvo, não cabe num
     módulo shell+daemon; seria módulo separado default-off, nunca neste.
  4. `drop_caches` / `panic_on_oops=0` (Encore faz): RECUSADOS — stutter e
     máscara de crash, respectivamente. Não entram nem com número.
  5. LMK/zram/PELT/`/dev/stune` (ausente no M54): sistema gerencia ou nó não
     existe aqui; mexer é risco sem tese.
  6. ` fps-aware` (`ITaskFpsCallback` no observer): futuro, pós-detecção provada.
  7. eBPF tuning/observer: VIÁVEL (probe `fd=3` ao vivo); entra pós-matriz.
- **Cosméticos p/ próxima build**: self-check acusa `[MISSING] webroot/index.html`
  (extraído logo depois — reordenar); mensagem "v2.0 single-module pronto" diz
  versão velha.
- **Dívidas técnicas abertas**: dip policy4 sem autor; latch 1152000 (regra exata
  TBD); pkill-test do uninstall v2.x (nunca executado ao vivo); valores do
  `tune.sh`/`profile_caps` são conservadores sem número (matriz afina);
  `gos_killer.paused` legado pode sobreviver no disco; observer registra?
  (`fusao.log` dirá pós-reboot).
- **Regra de retomada**: nada volta ao boot sem (1) número GPUWatch do estado atual
  limpo como baseline, (2) UM diferencial por vez atrás de flag, (3) uninstall
  testado a cada versão. Sem flash no escuro.

## v2.7 PENDENTE DE BOOT (2026-10-13, stageado, NÃO ativo)
- Build `fusao-v2.7-M54.zip` (761KB, 66 entries, sweep OK, Rust 30/30, shell 77/77)
  instalada via WiFi `192.168.1.70:45907`, **23/23 `[OK]`**, stageada em
  `modules_update` — ativa no próximo **soft-reboot (pelo dono; full reboot proibido)**.
- Corrige os 5 estranhos provados em 2026-10-13 (ver conversa): (1) idle restaura
  tetos ao stock (gpu_max 949000 + p0/p4 max) em vez de prender 754000 — fim da
  briga idle; (2) `uninstall.sh` lia snapshot DEPOIS do `rm -rf` (restore morto) —
  ordem corrigida + `cleanup.sh` restaura snapshot no disable; (3) `service.sh`
  reseed canônico se snapshot contaminado + rotação do `fusaod.log` >1MB;
  (4) killer respeita `gos_killer.paused` (`fusao gos on|off`); (5) watch relança
  o observer ao detectar morte; tick loga 1×/60s (log de 3.3MB/dia contido).
- Limpeza de identidade: zero menções funcionais a terceiros/verificações de terceiros no
  stage (só restam headers/atribuição Apache-2.0 — exigência legal, NOTICE manda
  incluir). `Rairin/rairin_novo.py` → `Rairin/rairin_tool.py` (conteúdo intacto).
- Pós-boot verificar: `module.prop 2.7.0/1588`, `modules_update` consumido,
  `stock.snapshot` reseeded (log `service: stock.snapshot reseeded`), PIDs novos,
  `gpu_max=949000` + `policy4_max=1824000` em idle, ticks silenciosos, `fusao gos`
  e `fusao reseed-stock` no CLI. Matriz recomeça do `framework on`.

## v2.8.2 STAGEADO + HOOKS PROVADOS AO VIVO (2026-10-13 ~10:50, NTE aberto)
- BUG REAL achado pelo dono ("foi ou não"): com observer vivo, o watch em standby
  NUNCA consumia `game-active.txt` — perf/dnd/preload/framework jamais dispararam
  (2 sessões com tudo ON e zero hooks; o loading bom anterior foi 100% fusaod).
- Fix: standby consome o estado a cada 5s e dispara `fire_enter/fire_exit`
  (com guarda `in_gamelist`); `fusao status`/`preload status` leem `.1` tb.
  Shell 82/82, sweep OK. Zip 764KB instalado 23/23 `[OK]`, stageado.
- Prova ao vivo no restart do watch (jogo aberto): `enter NTE` → perf io+proc,
  `preload ok files=677` (pack hit; 52 obsoletos pulados = freshness funcionando),
  `framework: apply game` + tune — tudo no mesmo segundo.
- Dívida restante da matriz: GPUWatch com número + uninstall v2.8.x ao vivo.

## PRELOAD: STELLAR × NOSSO (2026-10-13, verificado no pacote `Stellar-v22.5`)
- Stellar (strings do `stellar-daemon` + `en.json` + `changelog.md`): Asset Preloader
  por jogo, 2 intensidades (`preload.level` 0=Standard/1=Maximum). Mesmo primitivo
  que o nosso (thread async + `posix_fadvise`; sem `mlock`/`mincore` nos dois).
  Diferença entre os modos é ORÇAMENTO: Default balanceado p/ RAM baixa/média com
  "sufficiency budget" medido da RAM livre no momento (V15); Maximum p/ RAM grande,
  sem gate de RAM livre. Sem seleção aprendida (sem conceito de pack) e sem
  evidência de tratamento de obsoleto.
- Nosso (v2.8.x, provado ao vivo): modo único, mesmos 3 dirs de dados do jogo,
  caps 2000 arqs/512MB/sem symlink. Diferenças: (1) SELEÇÃO aprendida
  record→replay (pack `mtime size path` por jogo; usa o pack fresco, walk só na
  1ª vez ou >50% obsoleto); (2) freshness por entrada (NTE: 729 gravados, 677
  replayados, 52 obsoletos pulados); (3) fallback walk com `modo=` + errno no log
  (fanotify `ENOSYS` neste kernel); (4) visibilidade (`files= bytes= ms=`,
  `watch: hooks`, `fusao preload status`). Números: NTE 8.7GB/886ms, replay 1.3s.
- Veredito honesto: mesmo motor (WILLNEED async), política diferente. Nosso é
  superior em SELEÇÃO (só o que o jogo abriu), FRESHNESS e OBSERVABILIDADE.
  Stellar é superior em TUNAGEM por jogo (nível por app; o nosso é on/off global
  + packs por jogo) e em ORÇAMENTO adaptativo à RAM livre (o nosso tem teto fixo —
  com 144MB livres + swap pressionado, um WILLNEED de 677 arqs é hint, não lock,
  mas sem gate de RAM livre). Sem mlock dos dois lados (ninguém trava RAM).

## ESTADO ATUAL + PENDÊNCIAS DO DONO (2026-10-13 ~11:00)
- Ao vivo AGORA: v2.8.2 (watch+CLI live-patched; zip re-stageado 23/23 `[OK]`),
  NTE com hooks disparando, preload on + pack 729, profile game,
  flags framework+perf+fusaod on (dnd off, killer pausado).
- WebUI temporária ganhou preload ON/OFF (12 cmds, check-webui + harness 82/82
  + sweep OK); botão vale na hora (live-push), resto do zip no próximo reboot.
- FALTA O DONO TESTAR (nova versão): (1) próximo boot do NTE com replay do pack
  (loading + `preload ok files=~677` no log); (2) ciclo exit→idle (restore +
  `watch: exit`); (3) GPUWatch com número p/ fechar a matriz; (4) decidir killer
  (`fusao gos on` ou segue pausado); (5) uninstall v2.8.x ao vivo (nunca feito).
- Full reboot sem toque: temp-root refeito pelo dono; WiFi nova `192.168.1.70:43287`.
  Boot fresco = sysfs TODO stock (p0 533/2002000, p4 533/1824000, gpu 221/949):
  o clamp dos maxes era votante acumulado da sessão de 4 dias, NÃO perfil de boot.
- Bug real do full reboot: `app_process` falhou 2× com CANNOT LINK (janela pós-root
  com namespace instável; 1min depois o mesmo comando registrou tentativa 1).
  Prova: launch manual 10:12:36 → observer 21064 registrado, watch standby.
- Fix: `relaunch_observer()` com backoff (1×/min) + verificação + log de
  sucesso/falha; tenta a cada tick sem observer (antes: 1 tentativa só).
  Shell 81/81, sweep OK. `fusao-v2.8.1-M54.zip` (764KB) instalado 23/23 `[OK]`,
  stageado; `watch.sh` novo tb aplicado AO VIVO (21211, standby) p/ valer já —
  reboot futuro só consolida (sem pressa, quando quiser).
- Pós-reboot (quando houver): 2.8.1/1590, PIDs novos.

## NTE QUENTE + PRELOAD ON (2026-10-13 ~10:25, game profile, flags framework+perf)
- Relato do dono: -1~2fps, fps estável, texturas sem flicker/pop/low-res.
- Números com jogo quente: GPU 92% @ 949/949 (teto Samsung 650 vencido), p4
  960000/1824000, p0min 1152000 externo (recuo correto), MIF 1014000.
  Temps: BIG 63 / G3D 64 / batt 36.4°C — dentro da regra (CPU<80, batt<45).
- Record NTE: `errno=38 ENOSYS` (fanotify fora do build do kernel — definitivo,
  não é SELinux), fallback walk gravou pack de 729 entradas; `preload=on`.
  Próximo enter do NTE faz replay do pack.
- Detalhe: `fusao status` não mostrou hooks do enter (log rotacionou p/ .1 —
  dívida cosmética: status deveria ler `.1` tb).
- Exit NTE limpo (~10:3x): `observer: exit` instantâneo, game-active vazio,
  p0min 533000 + gpu 221/949 de volta. O floor 1152000 da Samsung é game-scoped
  (emprestado no jogo, devolvido no exit) — nada preso, nada a vencer.
- Build `fusao-v2.8-M54.zip` (764KB, 66 entries, sweep OK, Rust 33/33, shell 81/81)
  instalada via WiFi `192.168.1.70:35163`, **23/23 `[OK]`** — ativa no próximo
  **soft-reboot (pelo dono)**.
- Preload provado ao vivo antes do build: `preload NTE = 729 arqs/8.7GB em 886ms`,
  `preload Roblox = 2000 arqs/457MB em 755ms` (WILLNEED funciona; o "não funciona"
  era flag off + zero visibilidade). `preload-record` com fanotify NEGADO mesmo
  como root (caps cheias, Enforcing — SELinux do domínio ksu barra; `/proc/config.gz`
  ausente p/ confirmar no kernel).
- Correções: (1) contenda em vivo CONGELADO retenta 1×/60s (latch stale do boot
  não estaciona mais o nó; writer ativo segue recuado); provado: 1 tick manual
  desceu p0 1728000→533000 e p4 1440000→533000 por degraus; maxes seguem capados
  pelo driver com tela em uso (verify-fail = externo ativo, correto recuar).
  (2) `preload-record` com fallback walk (pack gravado + `modo=walk` no log) e
  errno visível (`errno=EACCES/...`); (3) observabilidade: `watch: hooks ...`
  no game-enter, `fusao preload status` (flag/packs/último run) e `fusao status`
  com última atividade dos ganchos.
- Pós-boot verificar: 2.8.0/1589, PIDs novos, retry de latch em ~60s sem briga,
  `fusao preload status`, e 1 ciclo `preload on` + game-enter p/ ver `watch: hooks`.

## v2.8 LIVE VERIFICADO (2026-10-13 ~09:13, idle, WiFi `192.168.1.70:45715`)
- 2.8.0/1589 ativo, `modules_update` consumido, PIDs novos (20721/20731/20738/20767),
  observer registrado tentativa 2, killer pausado, `fusao status` + ganchos OK.
- Idle stock onde o driver deixa: p0/p4 min 533000, gpu 221/949, mif 421.
- Maxes seguem capados (p0 1824000 vs 2002000, p4 1536000 vs 1824000) com mtime
  igual nos 2 (09:12:34 = nosso retry clampado OU writer Samsung periódico).
  `restricted_device_performance=1, 1` + `adaptive_power_saving_setting=1` presentes;
  sem power-save ativo (ps:false, 46%/33°C). Veredito: recuo correto, retry 1×/min
  recupera sozinho asim que a Samsung soltar. Pergunta ao dono: perfil de
  performance (Light?) ou economia adaptativa ligada em Ajustes → Bateria?
  (Resposta ~09:30: Light DESCARTADO — em Padrão os maxes seguem clampados e
  escrita manual root tb falha silenciosa. `thermalservice` limpo, sem knob boost,
  sem `cpufreq_limit` legacy, cdev3 com voto stale zerado e segurou 0, flag
  `restricted` é indicador (flip não solta; restaurada). Autor segue desconhecido
  (votante Samsung não-térmico); floors/GPU/MIF stock e segurados, maxes em recuo
  + retry com auto-recuperação. Impacto: -9%/-16% só em teto de burst.)

## v2.9 STAGEADO + LIVE-PATCHED (2026-10-13 ~12:53, NTE aberto, WiFi 192.168.1.70:42015)
- Pedido do dono: RAM clear ANTES do preload no game-enter (simples estilo
  Encore, nao idle complexo). Sem arquivo novo: bloco em fusao/watch.sh:fire_enter
  (sync + echo 3>drop_caches + echo 1>compact_memory, atras de
  ramclean.enabled, sincrono, loga free=antes->depois avail= rc=) + CLI
  fusao ramclean on|off|now|status + fusao status mostra ramclean= e o
  grep de ganchos inclui ramclean ok. module.prop 2.9.0/1592.
- Prova: harness 82/82 + teste fake (CLI now/status + watch hooks ramclean) +
  build 773KB/66 entries + sweep OK + ksud install com todos [OK], stageado
  em modules_update (pos-reboot vira 2.9.0). Live-patch aplicado ja:
  watch.sh + CLI copiados p/ /data/adb/fusao + /data/adb/modules/encore,
  watch velho 10611 morto, novo 8084 em standby, re-consumiu o NTE aberto.
- fusao ramclean now PROVADO como root: free=229736->981580 avail=1646552
  rc=0/0 (+751MB). Flag ramclean=on desde 12:53.
- PRELOAD (duvida do dono "nao vi diferenca"): ATIVO com prova - flag on desde
  10:30, pack /data/adb/fusao/preload/com_hottagames_nte.pack 729 entradas,
  replays 677 arqs/8.7GB/1311ms (10:50) + 674/8.5GB/1109ms (12:41) +
  675/8.7GB/1050ms (12:53, no restart do watch). Feel ~zero esperado:
  WILLNEED e hint (nao lock), 8.5GB aconselhados vs ~1.1-1.6GB Available, dispara
  no enter em corrida com o loading, swap pressionado (~2.2GB usados). Com o
  ramclean liberando 751MB antes, o proximo replay tem mais sala - A/B real e
  o proximo enter do NTE.
- PENDENTE (dono): 1 ciclo exit->enter do NTE p/ provar a ordem
  ramclean ok ANTES de preload ok + hooks ... ramclean ... preload ...;
  GPUWatch com numero; reboot futuro so consolida (sem pressa).

## v2.9 CICLO PROVADO (2026-10-13 ~13:01, NTE, WiFi 192.168.1.70:42015)
- Exit limpo 13:00:52 (observer exit instantaneo, watch exit, framework+tune
  restore stock). Enter 13:00:59, perf 13:01:00.
- Ordem provada: ramclean 13:01:01 (free=141196->362368 avail=1436848 rc=0/0)
  ANTES do preload (672 arqs/8.7GB/2129ms). Hooks: perf ramclean preload
  framework. Sem bounce, sem morte.
- Nota honesta: preload pos-drop levou 2129ms (vs 1050-1311ms com cache quente)
  - esperado no primeiro replay com pagecache frio. Feel A/B fica com o dono.

## FEATURE FREEZE v2.9 + PROXIMA ETAPA: APP (2026-10-13, decisao do dono)
- Chega de feature no modulo. v2.9 fecha a fase: writer unico fusaod, observer
  + watch com hooks (perf/dnd/ramclean/preload/framework), tune, DND, downscale,
  preload record/replay, ramclean antes do preload, CLI completa, WebUI 12 cmds.
- Achado no WSL (~/M54Tuner/M54Tuner): o app M54 Tuner (Kotlin/Compose, 5 tabs)
  + modulo m54tuner JA tem game_ram_clear (lib.sh: sync + drop 3 + cmd activity
  kill-all em cached/background, reporta MemAvailable) no Game-activation e no
  botao manual clear_ram.sh. Delta p/ FUSAO v2.9: nos fazemos drop+compact SEM
  kill-all. Opcao futura (nao implementada): kill-all pos-drop como v2.10.
- Gap conhecido: WebUI sem botao ramclean/preload (só CLI). Se o caminho for o
  app, WebUI vira fallback.
- Proxima etapa: dar ao FUSAO uma cara propria - aba FUSAO no M54 Tuner
  (FusaoBridge: flags /data/adb/fusao, CLI fusao, tail dos hooks, packs,
  gamelist) OU manter so WebUI OU apk separado. Dono decide.

## APK FUSAO v1 (2026-10-13 ~13:26, caminho "APK separado", scaffold pronto)
- Projeto novo ~/FusaoApp (WSL): Kotlin/Compose + libsu 6.0.0 (JitPack), AGP 8.7.3,
  minSdk 29, targetSdk/compileSdk 36, ZERO permissoes (sem INTERNET; picker de
  apps via <queries> LAUNCHER). Build OK: app-debug.apk 24MB.
- v1 e controle remoto: o app nunca escreve sysfs. Abas Status (perfil, jogo,
  daemons, ganchos) / Flags (7 toggles via CLI fusao) / Preload (status, now,
  record 20s + card ramclean now) / Jogos (lista + picker de instalados) /
  Log (fusao.log/fusaod.log + bugreport). Root re-checado a cada abertura.
- PENDENTE: adb WiFi caiu (porta rotativa, lista vazia) - instalar com a porta
  nova: adb install fusao-debug.apk, abrir, conceder root no prompt do KSU,
  conferir Status com NTE aberto.

## v2.9.1 DND-ARM + WEBUI EXTINTA (2026-10-13, cabo, dono dirigindo)
- Bug do dono (real): fusao dnd on ligava o DND na hora. Agora arma: on so cria
  o flag (aplica de imediato SO se o jogo ja estiver ativo); off desarma +
  desliga na hora. Prova ao vivo em idle: "armado: liga no proximo game-enter",
  zen_mode seguiu 0. App herdou o fix sem mudar (chama o CLI).
- GOS off = killer pausado (decisao antiga do dono, sem bug; toggle liga).
- WebUI extinta por decisao do dono (duas fontes da verdade): removidos
  fusao-v2.0/webroot/ + bloco de extracao + self-check + tools/check-webui.py;
  action.sh agora abre o app FUSAO (unica interface); module.prop sem webuiIcon,
  descricao CLI/APK. Harness 81/81 (check webui removido por desenho), sweep OK,
  zip 715KB/60 entries.
- 2.9.1/1593 stageado em modules_update (webroot ausente no stage, provado com
  ls). Live-patch: CLI + action atualizados, webroot vivo removido, watch 8084
  mantido (sem mudanca). APK rebuildado (subtitle dnd) + reinstalado + aberto.
- PENDENTE: reboot (pelo dono, sem pressa) consolida 2.9.1; teste DND real =
  armar no app + abrir NTE (liga) + sair (desliga).

## v2.9.2 DND VERIFY+RETRY (2026-10-13, cabo, bug do dono "nao armou")
- Causa raiz: hook disparava (hooks com dnd, flag ok), mas `settings`/`cmd`
  falhavam com "Failed transaction" no pico do game-enter; o dnd.sh logava
  "dnd: on" MENTINDO (sem verify). Prova que nao e contexto: probe detached
  funcionou (rc=0, mesmo SELinux ksu:s0) - falha e transiente de carga.
- Mapa zen real no M54 (OneUI 8.5): settings put SOZINHO = no-op; cmd set_dnd
  priority => zen=1 (desejado); cmd on => zen=2 (fallback valido); cmd off
  => zen=0.
- Fix: dnd.sh reescrito com verify (settings get) + retry 3x + log FALHOU em
  vez de mentir; state so gravado no sucesso verificado. Harness 84/84 (inclui
  on/off verified + fail-loud; achado extra: `| grep -q` em script multi-echo
  = SIGPIPE mata antes do atomic_write - testes via arquivo agora).
- 2.9.2/1594 stageado + dnd.sh live-patched; flag dnd armada. Falta o dono
  retestar: abrir Roblox/NTE => DND liga (zen=1) + log "dnd: on (zen=1)".

## v2.9.4 DND STDIO-FIX (2026-10-13, cabo, causa raiz PROVADA)
- Evidencia direta (E1 x E4): `cmd notification set_dnd priority >/data/adb/fusao/x`
  => rc=2 Failed transaction; o MESMO cmd com stdout=pipe => rc=0. framework.sh
  sempre descartou os 2 fds (aplica ok); dnd.sh herdava stdout=>log (quebrava).
  Reads em $() (pipe) sempre foram seguros.
- Encore TEM DND nativo sim (encored: set_dnd/enable_dnd/INotificationManager via
  Binder - nao precisa do modulo, so do mecanismo). Rairin tem dnd.sh mas com o
  MESMO `cmd` shell (sem vantagem). Stellar tambem e Binder-nativo.
- Fix: run() captura em pipe ($()) + loga falha com texto; verify+retry+guarda
  mantidos. Harness 85/85, sweep OK, zip 716KB/60, 2.9.4/1596 stageado.
- Prova ao vivo (spawn daemon, NTE aberto): `dnd: on (zen=1)` de primeira, sem
  retry; off => zen=0. Falta o ciclo real exit->enter do dono p/ fechar.

## REDESIGN APP APLICADO (2026-10-13 ~noite, outra AI via patch, cabo)- Patch `fusao-m54-redesign.patch` (26 arqs, +3884/-325, codigo + WebP + icons)
  aplicado em branch `feat/fusao-m54-premium-redesign` (PR pendente p/ main).
- Build QUEBROU 1x: 4 strings com aspas aninhadas sem escape no MainActivity.kt
  (1365/1747/1753/1984); corrigido com `\"`, `assembleDebug` OK (25MB).
- Instalado por cima via cabo (`install -r`, Success), MainActivity em foco,
  PID vivo sem FATAL no logcat. Falta o dono: abrir, conceder root no prompt
  do KSU e conferir Status com jogo aberto + visual do redesign.
- Relatório do designer verificado no código aplicado: race do Preload real
  (`loadPreload()`+`loadRamclean()` paralelos sobrescrevendo Busy) → trocado por
  `loadPreloadAndRamcleanIfEmpty()` em 1 `LaunchedEffect` (só restam chamadas em
  click manual, sequenciais). Revalidação de root no ON_START + `isAlive/isRoot`
  + `collectAsStateWithLifecycle` + `@Immutable` + `AppIconCache` LruCache(128):
  tudo presente. Designer não compilou (sandbox offline) — build feito aqui.

## FECHAMENTO 2026-10-13 (dono: "certinho", fim do dia)
- DND fechado: hook + verify + stdio-fix provados ao vivo. v2.9.4/1596 stageado
  em modules_update (consolida no proximo soft-reboot, sem pressa).
- Estado ao fechar: NTE jogavel, profile game, flags fusaod+framework+perf+
  preload+ramclean+dnd on, gos pausado, APK FUSAO v1 instalado (unica interface;
  WebUI extinta), cabo on.
- Fica p/ proxima sessao: (1) reboot consolida 2.9.4 (PIDs novos); (2) GPUWatch
  com numero p/ fechar a matriz; (3) feel prolongado ramclean+preload; (4) DND
  em uso real; (5) evoluir APK (aba termica/zram? dono decide).

## PROIBIDO: RELEASES DEBUG DO APK (2026-10-18, regra do dono)
- **Proibido instalar `assembleDebug` no aparelho.** Debug Compose compila sem R8,
  com checks de runtime e JIT cru = app laga/trava de fábrica. O dono recusa de cara.
- **Instalar SEMPRE `assembleRelease`** (R8 minify + shrinkResources, assinatura debug
  no build.gradle.kts — mesma chave, `install -r` substitui sem conflito). APK fica
  ~10x menor (25MB → ~2.6MB) e fluido.
- Debug só existe pra sessão ativa de desenvolvimento, nunca como entrega.
- Prova: v1-release (R8, 2.6MB) era a build fluida anterior; debug 24-25MB.

## FECHAMENTO 2026-10-15 (auditoria externa sincronizada + flash, cabo)
- Outra AI auditou modulo+app (4 commits via `git am fusao-m54-audit-fixes.patch`:
  `d84b9b6` ciclo de vida/gamelist/descida-degraus, `57e2fcd` perf/watch,
  `749ee39` app UI/Log/estado, `6fd0991` suite 89 checks + hashes). Sincronizado
  no PC: rebuild `_releases/fusao-v2.9.5-M54.zip` **byte-identico** ao dela
  (60 entries, 0 diffs; exigiu LF puro — `core.autocrlf=false` local, working
  tree normalizada sem tocar nos commits). Offline: **89/89 PASS**, sweep OK,
  dex OK. Bump p/ 2.9.5/1597 feito depois a pedido do dono (`5b6d91c`).
- App da auditoria **nao compilava** (`center` em `CacheDrawScope`,
  `FusaoDesignSystem.kt:802-803`). Fix commit `6f3dab4` (`size.width/2f`,
  `size.height/2f`) + build WSL `assembleDebug` OK (25.3MB, so warning
  pre-existente `FLAG_REDIRECT_STDERR`). Instalado por cima (`Success`,
  mesma assinatura debug).
- **Falha minha (registrada p/ nao repetir):** probe `svc power reboot` sem
  argumento REBOOTA de verdade → full reboot, temp-root perdido, boot_id novo
  (`e1ea3dee-…`, uptime zerado). Nunca mais probe com `reboot` no nome; metodo
  de soft reboot segue com o dono. Dono refez temp-root.
- Reboot consumiu `modules_update` (vazio pós-boot) = modulo auditado LIVE:
  PIDs novos (fusaod 5887, watch 5840, killer 5881, observer 5745), flags
  preservadas, gamelist intacta (531 linhas — fix do boot provado), `fusao
  status` sem `thermal=thermal=`, hooks NTE 15:06 (dnd zen=1, ramclean +974MB,
  preload replay 661 arqs/8.5GB/1192ms) + exit limpo 15:12.
- Fica: (1) GPUWatch com numero; (2) feel ramclean+preload + stamina Blackbird
  em sessao real; (3) DND em uso real; (4) decidir bump 2.9.5/1597 no module.prop.

## v2.9.6 IMPLEMENTADA (2026-10-18, patch externo aplicado + flash WiFi 192.168.1.75:39041)
- Patch `fusao-m54-backend-fixes.patch` da outra AI aplicado sem conflitos (26 arqs,
  +1661/-398): perfis com readback (tune.sh), preload gamelist sem jogo em foco
  (CLI+UI), observer.dex patched (processName/:sub/onFg), fim do cgroup.procs no
  perf.sh, fusaod .rodata corrigida, game_overlay float -> inteiro "0".
- Validado offline ANTES do build: 122/122 PASS, sweep OK, dex OK, zip 716KB
  (byte-identico ao da AI, comprovado por hash entrada-a-entrada). Bump
  2.9.5/1597 -> 2.9.6/1598 feito aqui (zip da AI esqueceu o version).
- ksud install 23/23 [OK], stageado em modules_update (ativa no soft-reboot do
  dono; full reboot proibido). APK entregue: RELEASE R8 (ver regra acima).
- Fica p/ pos-boot: (1) module.prop = 2.9.6/1598 e modules_update vazio; (2) PIDs
  novos; (3) pill CONFIRMADO no perfil (readback); (4) preload sem jogo em foco
  (seletor de alvos da gamelist); (5) stamina Blackbird no NTE (ciclo loading +
  voo) = prova real do fim de cgroup.procs + observer.dex + game_overlay.

## FULL REBOOT INESPERADO (2026-10-18, perda de temp-root, dono reportou)
- Algo rebootou de VERDADE o aparelho (dono nao pediu). Consequencias por doutrina:
  temp-root KernelSU PERDIDO, boot_id novo, uptime zerado, ADB WiFi porta
  192.168.1.75:39041 MORTA (rotativa; pedir porta nova ao dono).
- Modules_update era consumido pelo boot: modulo 2.9.6/1598 deveria estar
  stageado->ativo, MAS sem KernelSU ativo os modulos ficam DORMANTES
  (/data/adb/modules/* volta a rodar quando o temp-root for refeito + boot).
- Causa do reboot: DESCONHECIDA p/ agora. Candidatos a nao descartar
  (1) full reboot disparado por instalacao de APK/'update' do sistema;
  (2) remedio OneUI (auto-restart de manutencao); (3) toque involuntario.
  Registrar a proxima janela de boot_id para timeline comparada.
- CONFIRMACAO DO DONO (positivo): app FUSAO release R8 melhorou o feel
  (sem o lag/trava do debug). Debug banido em definitivo.
- Quando o dono re-fizer temp-root: verificar (a) KernelSU vivo;
  (b) /data/adb/modules/encore/module.prop = 2.9.6/1598; (c) PIDs novos
  dos daemons; (d) flags preservadas (fusaod+framework+perf+preload+
  ramclean+dnd on, gos pausado); (e) gamelist intacta; (f) fusao status
  intros sem inicializar nada velho; (g) perfil lido com readback; (h) NTE: stamina
  Blackbird no ciclo loading+voo = prova central do fim de cgroup.procs.

## CORREÇÃO 2026-10-18 (causa real do reboot, daemons pós-boot, tune.sh 2.9.7/1599)
- Substitui o "Causa do reboot: DESCONHECIDA" da seção acima. O full reboot (~03:3x) foi
  KERNEL PANIC, não `svc power reboot` nem remédio OneUI: `/proc/reset_reason=KPON`,
  `/proc/auto_comment` = `UBSAN Trap: Out-of-bounds`, `PC is at multi_load_read+0x410/0x418 [ems]`,
  processo `head`. Gatilho: varredura de leitura de OUTRA sessão em `/sys/devices/platform/ems/`
  — o nó `multi_load` derruba o kernel ao ser lido. Regra: nunca ler esse diretório (nem `cat`/
  `head`/varredura recursiva); nós só pela allowlist do `nodes-m54.md`.
- Estado real pós-boot dos daemons (leitura ao vivo): só `watch.sh` e `gos_killer.sh` vivos;
  `fusaod serve` e o observer MORTOS. O observer morre com `F linker: CANNOT LINK EXECUTABLE
  "app_process": library "libnativeloader.so" not found` (logcat 03:38:12-14, 3 tentativas).
  Causa AINDA NÃO investigada (não assumir que é o `observer.dex`/`observer.sh`; o erro é do
  linker ao subir o `app_process`).
- Bug das tabelas/clamp (tune.sh 2.9.6): os degraus hardcoded em `set_policy0`/`set_policy4`
  (1512000, 1320000, 1104000, 999000, 889000, 400000...) são de OUTRO SoC e não existem no
  Exynos 1380. Tabelas reais (ao vivo): P0 533000..2002000 (16 degraus), P4 533000..2400000
  (20), GPU 221000 351000 455000 552000 650000 754000 845000 949000; `cpuinfo_min_freq`=533000.
  O driver CLAMPA em silêncio (400000 em `policy0/scaling_min_freq` fica 533000, sem errno,
  `echo` rc 0) e o `w()` devolvia 0 mentindo: o `profile.state` ao vivo mostrava
  `profile_verify=9/10` + `profile_mismatch=p0_min:533000!=400000`. No fake sysfs o mesmo
  alvo passava 10/10 (o arquivo falso engole qualquer número).
- O que a 2.9.7 corrige (`fusao-v2.0/fusao/tune.sh`): tabela lida de `scaling_available_frequencies`
  / `gpu_freq_table` (fallback = tabelas reais acima); todo alvo de min/max passa por snap
  (min: menor degrau >= alvo; max: maior degrau <= alvo; limitado a cpuinfo_min/max; fora da
  faixa vira o limite físico + `WARN tune: snap ...` no log, sem matar o fluxo); `w()` relê e,
  se o nó não guardou o pedido, loga `WARN tune: clamp <nó> pedido=<x> lido=<y>` e devolve 1
  (sem `chmod 444`); alvos dos perfis viraram degraus reais e ficam num SSOT (`profile_targets`)
  que o apply escreve e o `verify_profile` confere. Mudanças: battery P0 piso 400000 -> 533000
  (mínimo físico/stock; NÃO 672000), balanced GPU max 858000 -> 845000, game P0 piso 999000
  -> 1056000 / P4 889000 -> 960000 (com jogo: 1104000 -> 1152000 / 999000 -> 1056000).
  Harness 173/173 (inclui driver falso que clampa + controles negativos); no PC sem
  `python3`/`strings` reais o dex e o elf-sym falham por ambiente (3 checks), não por código.
- Tetos do battery DECAEM no Dozing, por ação da Samsung (ela reescreve os nós): P0 max 1728000
  / P4 max 1440000 / GPU max 650000 presentes até uptime ~3194s e ausentes em ~3709s. NÃO é
  bug do tune.sh e NÃO se resolve com loop de reescrita (nada disso foi adicionado). Se for
  preciso reaplicar: por evento de tela-acesa, uma vez, com releitura (`w`/`verify_profile`).
  Documentado em comentário no `apply_battery`; não implementado.
- Pendente (aparelho NÃO foi tocado nesta sessão): flash do zip 2.9.7/1599 e prova ao vivo de
  `fusao profile battery` com `profile_verify=10/10` e p0_min 533000.

## KERNLAB + FECHAMENTO 2026-10-18 (~07:15, WiFi `192.168.1.75:40747`) — tuning MEDIDO dos nós do FKM/EMS
Pedido do dono: tunar os nós que o FKM expõe e ninguém tocou (Stune & Uclamp, CPU Sets, Scheduler,
Memory, I/O) com efeito MEDIDO, sem depender de teste manual. Resultado: ferramenta `tools/kernlab/`
(manual completo em `tools/kernlab/README.md`), validada por controles, + achados abaixo.
**Nada do kernlab está commitado** (`tools/kernlab/` untracked).

### O que é
- `tools/kernlab/kernlab.py` (CLI) + `kl/` (registro SSOT de nós `knobs.py` com domínio seguro e lista
  negra, compilador de plano, análise, estatística pareada, relatório) + `device/agent.sh` (runtime no
  aparelho: aplica, relê, mede, restaura; portão térmico e de piso; aborta e restaura sozinho) +
  4 nativos NDK em `device/bin` (fontes em `native/`, build no WSL: `NDK=~/android-ndk-r28 bash
  tools/kernlab/native/build.sh`): `kl_burst` (frame sintético UI->render: latência, perda, núcleo,
  energia por `time_in_state`), `kl_touch` (gesto nativo por uinput, sem a JVM do `input swipe`),
  `kl_io` (leitura O_DIRECT SÓ LEITURA na LUN de dados, sequencial, mista), `kl_sample` (telemetria
  4 Hz sem fork + potência mA x uV + vigia do `cur_set` do emstune).
- Experimentos = JSON em `tools/kernlab/experiments/` (20). Runs em `tools/kernlab/runs/<id>/` com
  `report.md`; `runs/SUMMARY.md` = uma linha por run/braço (`kernlab.py summary`).
- Comandos: `connect IP:PORTA` | `inventory` | `list` | `run exp.json` | `queue --wait-screen a.json b.json`
  | `posse --preset amplo` | `attach <run>` | `stop [--clear]` | `report runs/<id>` | `summary`.
  Suíte offline: `python -m unittest discover -s tools/kernlab/tests` (31 testes, fixtures reais do M54).
- Método: blocos espelhados (A B | B A), estatística pareada por bloco (IC t + p por permutação de
  sinal exata); veredito exige p<0,05 E IC fora de zero E |efeito| >= limiar; >=6 blocos (com 4 o p
  mínimo possível é 0,125). Trial só conta se o nó segurou do início ao fim (relido antes e depois),
  tela e modo do emstune iguais ao início, pisos de CPU no mínimo físico no início, portão térmico ok.

### Validação (antes de qualquer resultado)
- `controle-aa` (braços idênticos): EQUIVALENTE ±5%, 24/24. `controle-positivo` (top-app cpuset 0-3):
  PIORA +24,0% lat_p95, IC [+21,6, +26,4], p=0,031. Restauração OK em todos os runs.
- Bugs da PRÓPRIA ferramenta achados pelos controles/dados e corrigidos: bootstrap com 4 blocos deu
  "MELHORA -8% potência" num A/A -> IC t + p + >=6 blocos + trial de aquecimento; tela apagou no meio
  da fila (restaurar timeout de 30 min com último toque >30 min apaga na hora) -> `queue` mantém
  "nunca" e trial com tela/emstune mudados é excluído; CRLF do `adb shell su` sumia com o cabeçalho
  do `cur_set`; STOP era apagado pelo run seguinte da fila.

### Achados (6 blocos, p=0,031 salvo indicação; tela acesa)
| Achado | Run |
|---|---|
| **Fluidez = refresh.** 60->120 Hz no scroll: 2x frames (53->105 fps), p95 entre frames 16->8 ms, saltos >=33 ms 52->8 (~6x menos); custo **+34% de potência no scroll**. 120/60 = 120/120 durante o toque | `display-refresh` 36/36 |
| O "agarrado / não é 120 fluido" do dono = perfil **battery do FUSAO trava peak=min=60.0** (`framework.sh`). Stock 120/60 tira 96% dos saltos >=33 ms; `swappiness 160 / vfs 150` do battery: sem efeito mensurável no scroll | `stock-vs-modulo-scroll` 48/48 |
| `top-app cpu.uclamp.min` é **reescrito pela Samsung**: PowerHAL Hyper/EPIC, recurso `TABoost` em `/vendor/etc/hyper/config_vendor.json` (`CAMERA_UCLAMP`=1 permanente; hints de launch warm). 1.00 tela acesa, 0.00 em Dozing, volta a 1.00 após launch/toque. Valor posto no FKM some | `posse`; `scroll-uclamp` (6/6 trials u25 derivaram 25->1) |
| **Posse: 18/20 nós seguram** espera/launch/scroll/home/troca de app (latency_sensitive, uclamp de foreground/background, shares, cpusets, rt_default, vm, read_ahead, nr_requests, display, **todo parâmetro do emstune**). Não seguram: top-app uclamp.min (revertido), `gpu_poweroff_delay` (não aceita escrita) | `posse --preset amplo` |
| ego (`energy_aware`) escolhe f ~ 1,25 x ciclos/s POR THREAD: frame em 2 estágios pode ficar em 533 MHz. O burst padrão é **biestável** (ontime sobe >90, desce <61): estágio 1 preso nos A55 (~83% de perda) ou estável nos A78 (~1,5%). Só comparar DENTRO do run (o relatório mostra o regime) | todos os burst |
| Mesmo run, equilíbrio A78, pisos quietos: `uclamp.min` 25 -> -19% lat_p95 (+12% energia/frame); **`freqboost ta` 30 -> -47% (+34%)**; 60 -> -69% (+123%, desperdício). No equilíbrio A55, uclamp 25 tira a perda de 83% para 0,5% | `alavancas-frame-burst` 48/48; `uclamp-topapp-min` |
| **UI real a 120 Hz: clock quase não compra nada.** `freqboost ta` (níveis 0+1) 30: sem efeito; 60: -20% saltos de 1 frame por **+25% de potência**. O Hyper já dá piso no toque (`POWER_HINT_USER_INTERACTION` 1,1 GHz/500 ms; `LISTVIEW_SCROLL` 40%/10 s) | `ems-freqboost-ta-scroll120` 36/36 |
| emstune troca de conjunto sozinho: **nível 1 ~metade do tempo durante toque**, nível 2 no launch (lá `support_uclamp=1`), `power-scenario` (modo 1) com tela apagada. Nó do EMS é por modo/nível; o kernlab prova o nível 1 pela cópia do `cur_set` que o `kl_sample` grava quando ele ativa | amostragem + `curset_seen_m0l1` |
| **TimeStats FUNCIONA no OneUI 8.5** com `--timestats -clear` + `-enable` (corrige o §3.8 deste arquivo): por camada `averageFPS`, histograma present->present, jank, inclusive SurfaceView de jogo. gfxinfo também ok p/ HWUI | fixtures em `tools/kernlab/tests/fixtures` |
| Fonte do kernel do M54 no WSL (`~/SM-M546B_src`, EMS em `kernel/sched/ems/`). `aio_tuner` (tune.c) aceita cpu==NR_CPUS, não limita group e IGNORA em silêncio valor fora do domínio: escrita no EMS só se prova pelo `cur_set` | leitura de código |

### Estado do aparelho ao parar (~07:15, verificado)
- Módulo ATIVO = 2.9.6/1598. `main` = `e0a6bc5` (2.9.7/1599, ver "CORREÇÃO 2026-10-18" acima; feito por
  agente Sonnet em worktree e revisado aqui: harness 170 pass + 3 falhas de ambiente pré-existentes;
  teste ao vivo SÓ LEITURA no mksh do aparelho: tabelas e snap corretos). Zip
  `_releases/fusao-v2.9.7-M54.zip` (727828 bytes, 60 entries) **NÃO flashado**.
- **Framework do FUSAO DESLIGADO** (`fusao framework off`, 06:27): refresh 120.0/60.0, swappiness 130,
  vfs 100 (snapshots do próprio módulo). Volta com `fusao framework on`. `profile` no disco = battery
  (sem efeito com framework off).
- P4 `scaling_max_freq` escrito para 2400000 (= cpuinfo_max; o `tune.stock` tinha 1824000, um teto
  Samsung capturado). P0 533000-2002000, GPU 221000-949000, MIF min 421000.
- Daemons: `watch.sh` + `gos_killer.sh` vivos (desde 03:38); `fusaod` e observer MORTOS (CANNOT LINK).
- `screen_off_timeout` = 1800000 (restaurado). `/data/local/tmp/kernlab/` = home do kernlab (bin,
  agent.sh, runs); temporários `fkl_*` removidos; sem lock/STOP. Bateria ~59% às 06:28.

### Próximos passos (ordem sugerida)
1. **Teste in-situ do jogo (dono, novo chat):** jogo aberto numa cena ESTÁVEL (parado, câmera fixa,
   sem menu), sem tocar, sem cabo. `python tools/kernlab/kernlab.py connect IP:PORTA` e
   `python tools/kernlab/kernlab.py run tools/kernlab/experiments/game-freqboost.json` (~25 min).
   Trocar `package` no JSON (com.roblox.client / com.hottagames.nte). Se o preflight recusar com
   "conjunto ativo é modo X", o jogo pôs o emstune em outro modo: trocar `m0` -> `mX` nos ids.
   `game-uclamp.json` também existe, mas o top-app uclamp.min é reescrito pela Samsung (provável deriva).
2. Flash do 2.9.7 (`ksud module install` + soft-reboot pelo dono) e prova: `fusao profile battery`
   -> `profile_verify=10/10` com p0_min 533000. Depois decidir se volta `fusao framework on`.
3. Dívidas do módulo achadas hoje: mesma lista de degraus de OUTRO SoC em `system/bin/fusao:48`,
   `cleanup.sh:73`, `uninstall.sh:77` (sem snap/readback); game/balanced ainda capam P4 em 1824000
   (2400000 é alcançável hoje: medido p4_cur=2400000); `framework.stock` CONTAMINADO (triads
   idênticas às do balanced); observer CANNOT LINK `libnativeloader.so`; perfil battery trava refresh
   60/60 (a maior causa medida do "agarrado").
4. kernlab, experimentos prontos e NÃO rodados (fila interrompida p/ o display): `ems-pelt-margin`,
   `ems-support-uclamp`, `ems-rate-limit`, `uclamp-latency-sensitive`, `cpuset-background`,
   `io-scheduler`, `io-readahead`; `vm-swappiness` precisa dos componentes dos apps
   (`cmd package resolve-activity --brief <pkg>`). Rodar com `queue --wait-screen`.

### Doutrinas novas (não repetir os erros de hoje)
- NUNCA varrer sysfs/procfs às cegas: `ems/multi_load` dá kernel panic ao ser lido (o reboot de hoje).
  Ler só nós já provados; nó novo = um por vez, com log antes. Lista negra em `kl/knobs.py`.
- Escrever != controlar: antes de tunar um nó, `posse`. Nó revertido pela Samsung não se tuna por
  escrita direta (mede-se o escritor dela); usar a alavanca que segura (emstune) ou configurar a fonte.
  `chmod 444` / bind-mount seguem proibidos.
- Medir só com tela acesa e destravada (tela apagada = outro regime). Fila longa = `queue`.
- Hints do Hyper sobem `scaling_min_freq`: trial só começa com piso no mínimo físico (`gate_floor`).
- Travadinha visível = intervalo >= 33 ms (`ts_p2p_ge33ms_pct`); `ts_hitch_pct` (>1,5 vsync) também
  conta os saltos de 1 frame a 120 Hz, bem mais suaves.
- Git Bash: o `adb` vazio em `C:\platform-tools` sombreia o `adb.exe` (chamar `adb.exe`), e
  `MSYS_NO_PATHCONV=1` p/ caminhos `/data/...`. Saída do `adb shell su` vem com CRLF.

## JOGO IN-SITU + MANUTENÇÃO 2026-10-18 (~13:35-14:35, WiFi `192.168.1.75:44869`)
### Achados (aparelho sem reboot desde ~03:34; módulo ativo 2.9.6 durante os runs)
- **Hook de jogo do FUSAO reaplica o battery em TODO jogo da gamelist**: `watch: enter <pkg>` -> `tune: apply
  battery` em ~6 s (NTE 13:30:17, Roblox 14:20:02) -> GPU max 650000, P4 max 1440000, P0 max 1728000. Desfez
  o P4 2400000 escrito de manhã. Com `profile=battery` no disco, jogar = jogar capado.
- **NTE é limitado pela GPU em qualquer teto**: 12-13 fps a 650 MHz, 17,7 fps a 949 MHz (GPU ainda cravada no
  máximo), nenhuma thread > 40%. Esquenta BIG 54->81 °C em 30 s. Lever real = gráfico do jogo, não kernel.
- **O térmico da Samsung é dono dos tetos durante o jogo** (`game-tetos`, 12/36 válidos): com o calor
  acumulado a GPU max desceu em degraus 949000->845000->754000->650000->552000 e o P4 2400000->1440000 nos
  braços stock; no braço battery o teto 650000 da GPU foi SOBRESCRITO PARA CIMA (845000/949000/754000).
  CPU max só é clampado para baixo (P4 do battery ficou 1440000 em 36/36). Inferência pelo padrão: CPU via
  QoS (mínimo vence), GPU por sobrescrita (último escritor vence). Consequência p/ o módulo: o cap de GPU do
  battery não segura em jogo; e o stock só vale nos ~8 primeiros minutos de NTE.
- Trials válidos (frio): stock vs battery +36% fps (13,0 -> 17,7), p95 entre frames 86 -> 60 ms, potência
  3,0 -> 4,6-5,1 W (+54..69%). Veredito oficial INCONCLUSIVO (2 blocos). `runs/20261018-134639-game-tetos`.
- ~~Roblox é limitado pela CPU~~ **ERRADO, corrigido pelo run das 16:05 (ver CONSOLIDADO abaixo)**: a
  thread `RBX Worker C` ~1 núcleo enganou; com tetos stock a GPU fica cravada em 949 MHz e o fps segue a
  GPU, não a CPU. ~28-32 fps, p50 33 ms / p95 42 ms. Expulsa jogador parado em ~20 min -> run <= 15 min.
- **AstralOpt** (módulo "Game Threads Optimization", instalado 03:09, não documentado) estava INERTE: o
  monitor `app_process` dele morre com o mesmo `CANNOT LINK libnativeloader.so` do observer, `gameinfo`
  nunca criado, 147/147 threads do NTE com afinidade 0-7.

### kernlab (não commitado)
- Bug corrigido: o portão de piso exigia `scaling_min` no mínimo físico; jogo segura P0 min 1152000 o run
  inteiro -> TODO trial de jogo esperava 30 s e saía inválido (o dry-run não pega). Novo `gate.floor`:
  `"min"` (padrão) | `"start"` (referência = piso lido no `kl_begin`; só hint acima dele segura).
  Provado no aparelho no regime do jogo: start passa em 0 s; min espera 30 s + `gate_floor_timeout=1`.
  Os 3 experimentos de jogo usam `start`. Suíte 32/32.
- `experiments/game-tetos.json` (novo, NTE) e `game-freqboost.json` reescrito p/ Roblox (fb5 vs fb30 com
  tetos stock fixos nos dois braços, 24 x 25 s ~ 15 min, `gate.floor=start`).

### Manutenção feita (~14:30)
- AstralOpt: daemon morto (pid 24697), `ksud module uninstall` (remove=true), `service.d/.astral_cleanup.sh`
  órfão removido (só se apagava com flag `disable`).
- **FUSAO 2.9.7/1599 instalado** via `ksud module install` (md5 `467d6cdf...` = zip local), `update=true`,
  aguardando o soft-reboot do dono. Depois: `pgrep` dos daemons (soft-reboot não relança `service.sh`, §21).
- **Soft-reboot 15:29 (WiFi `:39715`, mesmo boot_id, root mantido) — verificado:** 2.9.7 ativo; AstralOpt
  sumiu (módulo, `ksu/bin`, `.config`). Desta vez o `service.sh` RODOU: watch/gos_killer novos (15:29:23)
  e o **observer vivo** (`registrado pid 24792, tentativa 1`) — o laço `CANNOT LINK libnativeloader.so`
  ia até 15:28:19 e acabou com o soft-reboot (estado do runtime, não do módulo). `fusaod` parado = esperado
  (só sobe com `game-active`).
- Apply do boot saiu `8/10 partial`: às 15:29:20 o boost de boot da Samsung segurava `scaling_min` no
  máximo e o snap percorreu a tabela inteira (36 WARN `clamp ... lido=2002000/2208000+`). **Dívida do
  módulo**: tune no boot corre contra o boost; reverificar uma vez depois que o piso solta, em vez de
  marcar partial. Em 15:47 `fusao profile battery` -> **`profile_verify=10/10` applied**, P0 533000-1728000,
  P4 533000-1440000, GPU 221000-650000 (prova do 2.9.7 feita). Entre 15:29 e 15:47, sem jogo, a GPU max
  650000 já tinha virado 949000 (Samsung sobrescreve).

### PENDENTE
1. ~~Teste do Roblox~~ FEITO 16:05 (ver CONSOLIDADO abaixo).
2. ~~Prova do 2.9.7~~ FEITA 15:47 (10/10). A dívida do apply no boot virou o fix do 2.9.8.
3. Movido p/ CONSOLIDADO (decisões do dono).

## CONSOLIDADO 2026-10-18 (~16:40) — conclusões das duas AIs + release 2.9.8/1600
Junta o que a outra AI deixou (v2.9.6 backend-fixes, CORREÇÃO 2.9.7) com o que o kernlab mediu hoje.
Fonte de cada linha entre parênteses. Fato medido != inferência (marcada).

### O que está provado
- **Reboot das 03:3x = kernel panic ao LER `ems/multi_load`** (`KPON`, UBSAN `multi_load_read [ems]`). Nós só
  pela allowlist do `nodes-m54.md` (CORREÇÃO 2.9.7 + kernlab).
- **Driver do Exynos 1380 clampa em silêncio** e as tabelas antigas eram de outro SoC; 2.9.7 lê a tabela do
  nó, faz snap e relê (`profile_verify=10/10` ao vivo 15:47) (outra AI + prova ao vivo hoje).
- **scaling_min/max leem o efetivo do QoS**: boost de boot (15:29) e hint de launch (16:03, entrada do Roblox)
  seguram o piso acima do pedido; o nosso pedido fica gravado e vale quando soltam (15:47 = 533000 sem
  reescrita). O 2.9.7 marcava isso como `partial` e logava 15-17 WARN por apply (kernlab, log do módulo).
- **Fluidez de UI = refresh**: 60->120 Hz = 2x frames, ~6x menos travadas >=33 ms, +34% potência no scroll. O
  "agarrado" era o battery do framework travando 60/60 (kernlab `display-refresh`, `stock-vs-modulo-scroll`).
- **Clock de CPU não compra frames no scroll leve nem nos dois jogos (GPU-bound) medidos** — NÃO testado
  onde a CPU manda (launch, troca de app, loading): scroll 120 Hz (`freqboost ta` 30 sem
  efeito; 60 = -20% saltos de 1 frame por +25% potência); Roblox (`fb30`: P4 médio 1,40 -> 1,86 GHz, fps
  27,6 = 27,6, p95 42 = 42 ms, +5..10% potência). Só o sintético (`kl_burst`) melhora com clock (kernlab).
- **Os dois jogos medidos são limitados pela GPU**: NTE 12-13 fps a 650 MHz / 17,7 a 949 (cravada); Roblox
  ~28 fps com a GPU cravada em 949 e o fps caindo junto com ela quando o térmico baixa (773-872 MHz -> 23-25
  fps). Lever de jogo = GPU e configuração gráfica, não CPU (kernlab `game-tetos`, `game-freqboost`).
- **Em jogo o térmico da Samsung é dono dos tetos depois de ~8 min**: GPU max sobrescrita (desce em degraus e
  SOBE por cima do cap 650000 do battery), P4/P0 max só caem. 24/36 (NTE) e 15/24 (Roblox) trials excluídos
  por isso — o kernlab exclui trial cujo nó derivou (kernlab).
- **Hook de jogo aplica o perfil ESCOLHIDO** (`tune.sh auto` = respeita `/data/adb/fusao/profile`): com
  battery, todo jogo roda com GPU 650000 / P4 1440000. É o desenho do watch.sh, não bug (código + log).
- `top-app uclamp.min` é reescrito pelo Hyper/EPIC (TABoost); 18/20 nós seguram; emstune segura (kernlab posse).
- **observer `CANNOT LINK libnativeloader.so`** sumiu com o soft-reboot das 15:29 (observer vivo, tentativa
  1). Causa raiz desconhecida (outra AI: "não assumir que é o dex"); pode voltar.
- AstralOpt (removido 14:30) estava inerte pelo mesmo `CANNOT LINK`.

### Release 2.9.8/1600 (módulo + APK, instalados 16:36; ativo após o soft-reboot do dono)
- `tune.sh`: (1) a descida do piso para no primeiro degrau que não pega (piso externo); (2) `verify_profile`
  classifica piso de CPU lido ACIMA do alvo / teto de CPU lido ABAIXO como externo durante `EXT_WINDOW_S`
  (180 s) após o apply -> estado novo **`pending`** + `profile_external=`; fora da janela volta a ser
  `partial` (driver que clampa de verdade continua visível); GPU/MIF/VM nunca são "externos"; (3) apply
  `pending` agenda reverificações SÓ DE LEITURA em +30/+90/+185 s (job obsoleto sai pelo `applied_at`);
  nada reescreve. `applied_at=` no `profile.state`.
- `system/bin/fusao`, `cleanup.sh`, `uninstall.sh`: restauração do P0 min desce pelos degraus REAIS do nó, só
  abaixo do piso atual (a lista de outro SoC começava SUBINDO o piso p/ 1728000). CLI repassa `profile_external`.
- APK (`FusaoApp-v2.9.8-release.apk`, R8, sha256 `dd92c61a...` = instalado): pílula/botão **AGUARDANDO** p/
  `pending` (antes caía no ramo genérico e o botão dizia "APLICADO").
- Validação: harness 192/192 no WSL (189 + 3 de ambiente no Git Bash); controle negativo = os testes novos
  contra o código 2.9.7 dão 14 FAIL (inclui "15 escritas" = os 15 WARN do aparelho); sweep OK; self-check do
  customize OK; zip `feb090fc...` (730292 bytes, 60 entradas) = o instalado.
- kernlab: `gate.floor: "start"` (jogo segura P0 min 1152000; o portão antigo invalidava todo trial de jogo).

### Pós-soft-reboot (check final, eu)
module.prop 2.9.8/1600 + modules_update vazio; PIDs novos de watch/gos_killer/observer; `fusao status` ->
estado do perfil (`applied` ou `pending` com reverify convergindo); log sem a enxurrada de WARN no boot.

### Decisões do dono (medido, não decidido aqui)
1. Perfil em jogo: com `profile=battery`, jogo = GPU 650000 / P4 1440000. No NTE frio isso custa ~27% de fps
   (13,0 vs 17,7) e poupa ~35% de potência; depois de ~8 min o térmico converge p/ perto disso de qualquer
   jeito. Opções: manter, ou hook aplicar `game` em jogo. O cap de GPU do battery não segura em jogo.
2. Framework do FUSAO segue OFF (refresh stock 120/60). Religar = battery trava 60/60 = o "agarrado".
3. `freqboost`/boost de CPU no módulo: os dados dizem que não compra frames; só gasta.

### Dívidas ainda abertas
- game/balanced capam P4 em 1824000 (2400000 é alcançável) — sem medida de benefício (os jogos medidos são
  GPU-bound), por isso NÃO mexido. `framework.stock` contaminado (triads = balanced). Causa do `CANNOT LINK`.
- Pós-boot da outra AI ainda sem prova: preload sem jogo em foco (seletor), stamina Blackbird no NTE
  (fim do cgroup.procs + observer.dex + game_overlay).
- kernlab: fila pronta e não rodada (`ems-pelt-margin`, `ems-support-uclamp`, `ems-rate-limit`,
  `uclamp-latency-sensitive`, `cpuset-background`, `io-scheduler`, `io-readahead`).

## 2.9.8 PROVADO AO VIVO + 2.9.9/1601 (regra de perfil do dono) — 2026-10-18 ~17:15, cabo `RQCW506Z0CX`
- **Soft-reboot do dono 16:50** (mesmo boot_id `324cbe74`, root ok): 2.9.8 ativo, watch/gos_killer/observer
  novos (16:50:43, observer vivo de novo). Apply do boot: a descida parou no 1º degrau = **2 WARN por policy
  (era 15)**; P0/P4 min classificados `profile_external`. Saiu `partial 7/10` por `gpu_min:455000!=221000`:
  o boost do boot também segura o PISO DA GPU; 17 min depois, sem reescrita, P0/P4 min 533000 e GPU min
  221000 (o pedido valeu) e GPU max 650000 -> 949000 (sobrescrito, não voltou).
- **2.9.9** (instalado 17:13, aguardando soft-reboot): (1) regra do dono, SSOT `effective_profile` no lib.sh:
  **battery + jogo da gamelist em foco = game**, fora do jogo volta a battery; **balanced fica balanced** em
  jogo (casuais); usada por tune/framework/perf/CLI (status confere o perfil em vigor e publica
  `profile_effective=`; `fusao profile battery` com jogo aberto aplica game). (2) piso da GPU entra como
  trava externa (medido acima); teto da GPU continua falha real. (3) app: botão escolhido mostra
  "EM JOGO: GAME". Achado lateral: `tune.sh verify` sem argumento verificava balanced (resolve_mode
  devolvia "verify"); corrigido.
- **fusaod fica fora do battery+jogo de propósito**: ele lê `/data/adb/fusao/profile` sozinho e com "battery"
  aplicaria os tetos de battery dele (GPU 552000, P0/P4 1056000) dentro do jogo. O gate dele segue no perfil
  ESCOLHIDO. O binário tem patch de `.rodata` da outra AI; o fonte em `_historico/fusao-brain` não é
  garantido igual — não recompilar sem conferir.
- Validação: harness 202/202 (WSL); controle negativo = 7 FAIL dos checks novos no código 2.9.8; sweep OK;
  zip `600dc44b...` (731177 b) e APK `e84f76b8...` = instalados.

## FKM: o que cada categoria deu (pedido inicial do dono)
| FKM | Medido | Resultado |
|---|---|---|
| Stune & Uclamp | `top-app uclamp.min` 10/25/40 (frame sintético) | -28/-46/-65% latência, mas o Hyper/EPIC da Samsung REESCREVE o nó em launch/toque: não dá p/ fixar. `latency_sensitive` não rodado |
| CPU Sets | top-app só nos A55 (controle positivo) | PIORA +24%: o cpuset stock está certo. `cpuset-background` não rodado |
| Scheduler (EMS `energy_aware`) | `freqboost ta` 30/60 | sintético -47/-69%; REAL: scroll 120 Hz sem efeito (30) / -20% saltos de 1 frame por +25% potência (60); NTE/Roblox 0 fps a mais, +5..10% potência. `pelt_margin`, `rate_limit`, `support_uclamp` não rodados |
| Memory | swappiness 160 / vfs 150 (battery) | **NÃO MEDIDO de verdade**: só num scroll de um app sem pressão de RAM (teste errado — swappiness decide quem fica na RAM na troca de apps). `vm-swappiness` (troca de apps) não rodado |
| I/O | — | `io-scheduler`, `io-readahead` não rodados |
| (fora do FKM) Display | refresh 60 -> 120 Hz | **a maior alavanca medida**: 2x frames, ~6x menos travadas, +34% potência |
Conclusão até aqui (CORRIGIDA 17:30, apontada pelo dono: "tuning certo no teste errado"): no scroll leve e nos
dois jogos GPU-bound, nenhum nó do FKM medido ajuda; refresh (UI) e GPU + gráfico (jogo) mandam. Isso NÃO vale
p/ os momentos em que CPU/memória/IO mandam e que não foram testados: abrir app (frio/quente), trocar de app,
carregar jogo, toque->1º frame. A fila pendente nasceu com o mesmo defeito (EMS/uclamp/cpuset no frame
sintético, IO em MB/s sintético) e precisa ser refeita no workload certo (`launch`/`appswitch`).

## Driver: decisão (2026-10-18) — NÃO mexer/substituir driver neste aparelho
- Risco: temp-root + bootloader travado. Kernel panic = full reboot = root perdido (já aconteceu hoje por uma
  LEITURA de `ems/multi_load`). Trocar/descarregar módulo do vendor (ems, kbase, cpufreq) em memória é bem
  mais arriscado que ler; mexer em partição (vendor_dlkm/boot) é impossível com AVB e, se tentado, bootloop.
- Benefício: os dados não mostram gargalo que só um driver resolveria. Jogos = GPU no teto da tabela (949 MHz)
  e calor; overclock exigiria tabela de tensão (ECT) = mais calor = o térmico corta antes. Relaxar o térmico é
  proibido (nodes-m54.md §10). CPU/escalonador: as alavancas já são graváveis por sysfs e não compram frames.
- Quando reabrir: só se uma medição achar regressão estável e reproduzível do lado do kernel que nenhum nó
  gravável resolve. Até lá: userspace + sysfs allowlist.

## ENCERRAMENTO DOS TESTES 2026-10-18 (~18:10, WiFi `:35419`) — dono: "tá bom de teste"
- **2.9.9 ativo** (soft-reboot 17:45): boot com 2 WARN por policy, `battery pending` + reverify em +33 s
  (`partial 7/10` porque o teto da GPU 650000 virou 949000 = Samsung sobrescreve; pisos soltaram). Battery
  vai mostrar PARCIAL 9/10 quase sempre por isso — decisão do dono: tirar o cap de GPU do battery (não segura).
- **Memória no teste certo (troca de apps)**: 13 apps reais abertos em sequência (os mais usados + outros
  instalados), 12/12 mensuráveis voltaram HOT com 3,7 GB livres. Sem pressão no uso normal -> swappiness não
  tem o que decidir fora de jogo. Pressão real só com jogo (NTE: 114 MB livres, 2,4 GB swap); o teste certo
  seria "NTE em fundo, abre YouTube/WhatsApp, volta". Não rodado. CORREÇÃO 19:45: NÃO precisa do dono — o NTE abre direto no lobby sem login (7 aberturas automáticas hoje); dá p/ automatizar. Cuidado: os hooks do FUSAO reaplicam swappiness no enter/exit (game 60 / battery 160).
- **read_ahead no teste certo** (`launch-readahead`, YouTube frio de disco com drop_caches, 6 blocos, 45/48
  válidos): **dm do /data e da raiz 512 = PIORA +5,9%, 2048 = PIORA +9,2%** (p=0,031); **sda 2048 =
  EQUIVALENTE +0,2%**. Prova do nó errado: o read_ahead da sda (o que FKM e FUSAO `readahead_nodes` ajustam)
  não chega na leitura de arquivo; o nó certo (dm) só piora acima do stock 128. Abertura é limitada por CPU
  (~1,5 s frio de disco = ~1,5 s com cache quente). Consequência p/ o módulo: o `T_RA` dos perfis é inócuo;
  candidato a remoção (não removido: dono decide).
- `launch-iosched` não rodou (parado a pedido). kernlab: `drop_caches` no workload launch; knobs
  `io.dm-60/dm-6.read_ahead_kb` (conferir /proc/mounts: número do dm muda em boot completo).

## 2.10.0/1602 (instalado 18:20, aguardando soft-reboot do dono)
- **read_ahead saiu dos perfis**: o da sda (o único que o `readahead_nodes` ajusta) não chega na leitura de
  arquivo (kernlab: sda 2048 = +0,2%), e no nó certo (dm-60 /data, dm-6 /) subir PIORA a abertura (512 +5,9%,
  2048 +9,2%). Em jogo não foi medido; o ajuste do game (512 na sda) nunca chegou no jogo, então tirar não
  muda nada lá. Os perfis só devolvem ao stock o que versões antigas deixaram na sda. verify passa a 9 nós.
- **battery sem teto de GPU** (escreve o máximo físico 949000, o que também solta o cap de outro perfil): o
  650000 era sobrescrito no boot e pelo térmico e o verify vivia PARCIAL 9/10.
- app: descrições dos 3 perfis com os alvos reais (game dizia "P0 999k / P4 889k", de outro SoC; balanced
  "GPU 858", degrau que não existe; battery "P0 400k" e "GPU 650").
- Validação: harness 202/202 (WSL); controle negativo = 16 FAIL contra o tune.sh 2.9.9; sweep OK; zip
  `c6f7677e...` (731509 b) e APK `56fb1395...` = instalados.

## NTE: PRELOAD E READ_AHEAD NO TESTE CERTO (2026-10-18 ~19:10-19:40, WiFi `:45459`)
Método (automatizado, sem o dono tocar): NTE fechado + `drop_caches` antes de cada abertura; tempo até o
lobby = engrenagem do canto (pixel 2251,33 > 150 em 2 leituras seguidas, mínimo 30 s, via `screencap` cru
sem gravar em disco); toque no centro; tempo até o jogo = botão de correr do HUD (pixel 2125,966 > 150);
bytes lidos pelo jogo de `/proc/<pid>/io`. A fase até o lobby é quase toda a "Verifying resources" do
próprio NTE (~1,6 GB lidos a cada abertura).
- **Preload** (on, off, off, on; read_ahead 128): lobby 114,0/117,3 s com vs 116,8/112,4 s sem (igual);
  toque->jogo 39,7/33,5 s com vs 30,4/32,2 s sem (tendência de piora, n=2); bytes lidos pelo jogo IGUAIS
  (~1,55-1,6 GB até o lobby, ~3,4-3,5 GB até o jogo) = as páginas do WILLNEED não são aproveitadas.
  **Preload desligado no aparelho** (`fusao preload off`; o padrão do módulo já é off).
- **read_ahead do dm-60 (/data)**, preload off: 128 = lobby 119,7 s / 1606 MB; 512 = 134,3 e 126,3 s /
  ~1700 MB (+5..12% de tempo, +6% de leitura); toque->jogo sem diferença clara. Bate com o YouTube:
  read_ahead maior no nó certo atrasa e lê mais. O 128 (stock, mantido pelo 2.10.0) é o certo.
- Estado ao fechar: read_ahead dm-60 = 128, preload off, 2.10.0 ativo (battery 9/9 confirmado 18:24).
- Dono: testes curtos (2 aberturas por lado bastam p/ ver diferença grande); nada de bateria longa.

## fas-rs: fork M54 + braço "daemon" no kernlab (2026-10-18 ~20:00-21:30, sem tocar no aparelho)
Pedido do dono: usar o fas-rs (FAS por eBPF) se for melhor que a abordagem atual.
- **O fas-rs de release não roda aqui (confirmado na tabela de símbolos, não executado)**: o
  frame-analyzer anexa um uprobe em `Surface::queueBuffer(ANativeWindowBuffer*, int[, ...])` do
  `/system/lib64/libgui.so`, e o libgui do OneUI 8.5 (Android 16) não tem nenhum dos dois. Tem
  `Surface::hook_queueBuffer(ANativeWindow*, ANativeWindowBuffer*, int)` (entrada do ANativeWindow:
  EGL e Vulkan passam por ela). Mesmo bug do issue #349 do fas-rs (Android 16 QPR1, aberto).
  Kernel tem o necessário: `UPROBES`, `UPROBE_EVENTS`, `BPF_EVENTS`, BTF, JIT; `perf_event_paranoid=-1`.
- **Fork** em `~/fas-m54` no WSL (branches locais `m54`): fallback para `hook_queueBuffer` em
  `frame-analyzer/src/uprobe.rs` + `[patch.crates-io]` no fas-rs. Receita `~/fas-m54/build.sh`
  (nightly + cargo-ndk + bpf-linker 0.12.0 oficial; o do apt não serve: rustc nightly usa LLVM 23).
  Binários em `tools/kernlab/device/opt/` (fora do git, GPL-3.0): `fas-rs-m54`, `fa-patched`,
  `fa-upstream` (controle negativo, sem a correção). Attach NÃO provado no aparelho ainda.
- **Correção do dono que muda o escopo**: todos os testes de jogo foram com gráficos no MÁXIMO (aí a GPU
  manda e a CPU não compra frame). O dono joga em medium/low: NTE limitado a 60 (nunca chega), Roblox
  sem limite configurável (~60+). Lá a CPU pode decidir, e é onde um FAS faz sentido.
- **Achado**: o perfil `game` capa o P4 em 1824000 achando que é teto de hardware (`fusaod`/`tune.sh`,
  nodes-m54 §10). Não é: o `game-tetos` mediu 2400000 segurando (f4 médio 1,88 GHz). Por isso o
  experimento tem o braço `p4_2400`: separa "o fas-rs ganha por controlar" de "ganha por soltar o teto".
- Com `battery` escolhido + jogo da lista (uso do dono) o `fusaod` NÃO roda: o único escritor de CPU do
  FUSAO em jogo é o `tune.sh`, uma vez, na entrada. fas-rs dono da CPU + FUSAO no resto não briga.
- **kernlab**: nó `daemon.fasrs` (access `daemon`: 1 liga, 0 desliga e devolve a CPU de antes; leitura
  = 1 só com o processo vivo E controlando; ver correção abaixo: "New fas buffer on" NÃO prova controle), valor `@livre` (nó do daemon
  no braço dele: não escreve nem confere), pisos `cpufreq.policy{0,4}.min` (descida por degraus no
  agente), portão de piso pulado no braço com daemon. 36/36 testes offline; as 5 peças novas têm teste
  que FALHA quando a peça é desfeita.
- **Para rodar** (bateria ≥30%, sem carregador, gráficos do dono): (1) `device/sonda_frames.sh <pacote>`
  no jogo em foco: `fa-upstream` tem de falhar, `fa-patched` tem de contar ≈ o fps do TimeStats (2x =
  duas superfícies no mesmo fluxo, o fas-rs veria frametime errado); acertar os alvos do
  `device/opt/games.toml` (provisórios: [60]); (2) `cpu-controle-nte.json` / `cpu-controle-roblox.json`
  (36 trials, ~20/16 min). Regra declarada antes: fps primeiro; fps equivalente -> menor pbat_mw;
  empate -> FUSAO; `p4_2400` empatando com `fasrs` -> só subir o teto do perfil game.
- Risco do passo 1: uprobe dentro do jogo em kernel Samsung stock. Baixo, mas panic = root temporário perdido.
- **Sonda PROVADA no Bleach: Soul Resonance** (21:2x, WiFi `:45459`, bateria 46% sem cabo, boot_id
  `324cbe74` igual antes/depois): `fa-upstream` rc=1 `unknown symbol ...queueBuffer...` (controle negativo);
  `fa-patched` 695 frames / 36,4 fps / mediana 24,1 ms × TimeStats na mesma janela 704 / 35,7 / 24 ms
  (SurfaceView única). **fas-rs ponta a ponta** (25 s, alvo provisório [30, 60]): "New fas buffer on" em
  2 s; com o jogo abaixo de 58 fps ele subiu o teto do P4 1824000 -> 2400000 (cur 2,2-2,4 GHz; o térmico
  cortou para 2304000); desligar devolveu P0/P4 exatamente ao snapshot. No mesmo momento a GPU estava a
  949 MHz com 99% de ocupação (o jogo estava preso na GPU, 36-39 fps): fps 39,1 com fas-rs vs 35,7 antes,
  janelas/cenas diferentes = sem conclusão. `cpu-controle-bleach.json` pronto.
- **Run `20261018-213345-cpu-controle-bleach` (36 trials, 25 válidos) NÃO testou FAS** (erro meu no
  desenho): o fas-rs nunca cravou frequência em nenhum dos 12 trials dele (telemetria: f4 1056-2400 dentro
  de cada trial). "New fas buffer on" sai na criação do buffer, ~10 s antes do controle (300 frames no alvo
  60 + 1 s + DELAY_TIME 3 s), e a checagem aceitou. O que o run mediu: pisos do perfil game × faixa inteira
  = fps -0,1% / pbat +1,4% (EQUIVALENTE ±5%). `p4_2400`: 1 bloco válido (o térmico capa o P4 em
  1824000-2112000 em jogo). Corrigido: fork loga `FAS working on [pkg]` / `FAS stopped` (commit `24fed83`
  local); o agente exige o working e espera até 30 s ao ligar.
- **fas-rs debug, 40 s no Bleach (gráficos mínimos)**: fps CRAVADO em 44,8 (22,3 ms; parece opção de 45 do
  jogo); alvo [30, 60] -> alvo 60 -> 1528 de 1530 decisões = subir; todas as policies pedidas em 2400000;
  fps continuou 44,8 = a CPU não é o limite ali. O fas-rs neste aparelho só escreve teto (piso fica no
  mínimo da tabela: caminho "não crítico"); `core_temperature: 0` = o térmico do fas-rs está inerte.
  Alvo errado = CPU no máximo à toa: a lista do jogo TEM de bater com o limite de fps configurado nele.
- Bug do snapshot de CPU (pegou o piso do P4 em 1536000 de um hint e regravou como pedido): corrigido no
  aparelho (P4 min 1056000) e no agente (menor piso lido em ~1 s).
- **Checagem com controle de verdade** (21:56, release `1704aec3...` com o log novo, alvo [30, 45, 60], 15 s
  por estado, FUSAO / fas-rs / FUSAO): "FAS working on" 9 s depois de ligar; fas-rs cravou P0 e P4 em
  768000 (f4 771 MHz × 1521 com FUSAO); ts_fps 45,9 × 46,4/46,4; pbat ~2354 × ~2868/~2784 mW (−17%);
  frames ≥33 ms 2,2% × 0,1%/0,0%. n=1 por estado = indicação. `cpu-controle-bleach.json` agora é FUSAO ×
  fas-rs (24 trials, ~10 min).
- Run `20261018-220455` abortou no início (`baseline_changed`): o preflight leu o P4 min 1536000 de um hint
  e a restauração do aborto gravou o hint (corrigido à mão para 1056000). Corrigido no kernlab: todo
  `scaling_min_freq` é lido como o menor valor em ~1 s (`kl_read_floor`, no agente e no preflight).
- **Run `20261018-220702-cpu-controle-bleach` (FUSAO × fas-rs, alvo [30, 45, 60], 24 trials)**: o térmico da
  Samsung (bateria ~38,6 °C) baixou os TETOS no braço FUSAO (P0 2002000 -> 1824000-1920000, P4 1824000 ->
  1632000-1728000), 11/12 trials dele excluídos pelo "não segurou" -> leitura estrita = 1 bloco, INSUFICIENTE.
  Leitura de SENSIBILIDADE (não pré-declarada; inclui os trials cujo único problema é teto cortado pelo
  térmico, o que só pode baixar a potência do FUSAO, logo é conservadora para o fas-rs), 6 blocos:
  ts_fps −1,3% [−2,3, −0,2] EQUIVALENTE; **pbat_mw −10,4% [−13,3, −7,5] p=0,031**; frames ≥33 ms
  0,13% -> 0,79% (PIORA, p=0,031); p95 igual; f4 −51%, f0 −36%, MIF −7%, tbig 55,3 -> 51,8 °C. fas-rs
  "controle 1" em 8-10 s nos 12 trials dele. Pela regra declarada (cauda não pode piorar) o FUSAO ganha,
  mas a regra também manda a troca bateria × cauda para o dono decidir.
- Estado ao fechar o run: fas-rs parado; pedidos de CPU restaurados ao perfil game; o efetivo segue capado
  pelo térmico (P0 1824000, P4 1536000, bateria 38,9 °C) até esfriar.

## 2.11.0/1603 + app 1.1.0: FAS (fas-rs) adotado (dono, 2026-10-18 ~23:30)
Decisão do dono depois do `cpu-controle-bleach` (bateria −10,4%, fps −1,3%, frames ≥33 ms 0,13% -> 0,79%).
- **Fork**: + `0003` trava de GPU saturada (`gpu_busy` ≥ 95% = não sobe a CPU; NÃO medida num jogo preso na
  GPU ainda). Binário `0695467d...` em `fusao-v2.0/system/bin/fas-rs`; fonte GPL em `tools/fas-rs-m54/`.
- **Módulo**: `fusao/fas.sh` (enter/exit/gen/status/mode/target) gera a lista do fas-rs da `gamelist.txt`
  (SSOT; escada automática 30/45/60/90/120, limite por jogo em `fas.targets`), liga no game-enter DEPOIS do
  tune (o perfil cobre o aquecimento ~10 s) e desliga no exit ANTES do tune (o tune escreve a CPU por
  último). `lib.sh fas_owns_cpu` = FAS ligado + binário + jogo da gamelist em foco: o verify do tune não
  confere CPU (resumo `CPU:FAS`) e o `fusaod` não sobe em jogo. CLI `fusao fas on|off|status|mode|target|gen`
  + linhas `fas_*` no `fusao status`; boot liga o FAS UMA vez (`.fas_seeded`); kill-switch/cleanup/uninstall
  matam o fas-rs antes de restaurar a CPU; bugreport leva `fas.log`/lista.
- **App 1.1.0 (versionCode 2)**: chave `fas` na aba Flags (CONTROLANDO/AQUECENDO/ARMADO/SEM BINÁRIO), painel
  FAS (estado, GPU saturada, 4 modos), aba Jogos com "FAS · AUTO/N FPS" por jogo (toque troca o limite);
  contadores "x/7" vêm do modelo; módulo < 2.11.0 não mostra nada de FAS.
- Validação: harness 231/231 (29 novos; controle negativo: 9 dos novos FALHAM contra tune/watch/lib 2.10.0);
  kernlab 36/36; sweep OK; zip `da0f39c0...` (64 entradas, self-check 14); APK release R8 `e9c7a20f...`.
- Instalado 23:27: módulo via `ksud module install` (STAGED, vale no próximo soft-reboot) + APK 1.1.0. Com o
  2.10.0 ainda ativo o app abriu sem crash, Status e Flags (5/7) normais (foto). Falta: soft-reboot do dono e
  prova ao vivo (FAS liga no enter, `fusao status` = working, desliga no exit, tela do FAS no app).

## 2.11.1/1604 + app 1.1.1: FAS PROVADO AO VIVO (2026-10-18 23:52 – 2026-10-19 00:01, WiFi `:37087`)
- **Reboot completo depois do soft-reboot (~23:36) NÃO foi o FAS**: `sys.boot.reason =
  reboot,rollback_staged_install(vendor.fingerprint-default)`, reset_reason RPON, auto_comment vazio (sem
  panic). O Android reverteu uma atualização do Play pendente (`pm list staged-sessions`:
  mainline_train_primary) porque o HAL de digital caiu no soft-reboot. fas-rs nunca rodou (sem jogo).
  Enquanto essa atualização estiver pendente, soft-reboot pode virar reboot completo.
- Observer fora após o reboot completo (`app_process: libnativeloader.so not found`, problema antigo): o
  watch caiu no poll de 5 s e os ganchos (inclusive o FAS) funcionaram por ele.
- **Bleach (gráficos mínimos)**: enter -> tune game -> `fas: on` -> `FAS working on` em ~19 s;
  `fusao status` = profile game 5/5 (CPU pulada: `CPU:FAS`), fas_state=working, fusaod parado; fas-rs
  cravou P0/P4 em 672-864 MHz (min=max) com GPU 41-55% (trava de GPU não disparou, certo).
  Exit -> `fas: off` (23:55:56) ANTES de `tune: apply battery` (23:56:01); fas-rs parado; CPU de volta ao
  battery (P0 533000-1728000, P4 533000-1440000), 9/9. Segundo ciclo 00:00:33-00:00:57 igual.
- **Corrigido ao vivo (2.11.1)**: `fas_gen` com um subshell + awk por jogo levava ~24 s dentro do
  game-enter (gamelist com 533 jogos); um awk só = 0,76 s, saída idêntica byte a byte. Harness 231/231.
- **App 1.1.1**: "AQUECENDO" virou AGUARDANDO (fas-rs vivo sem controlar: aquecimento OU jogo fora do foco);
  o modo ativo fica habilitado e destacado (estava apagado, parecia indisponível). Fotos: Flags 6/8 com a
  chave e o painel FAS, 4 modos 2×2; Jogos com "FAS · AUTO" por jogo. Módulo 2.11.1 STAGED (vale no próximo
  boot; o fas.sh vivo já é o do release).
- Framework segue OFF (decisão do dono, sem mudança): ligar com o FAS não briga na CPU, mas volta a trava de
  60 Hz do battery fora do jogo e 120 Hz fixo em jogo (custo de tela em jogo não medido).

## 2.11.2/1605 + app 1.1.2: observer volta depois de boot completo; textos do app (2026-10-19 ~00:30)
- **Observer não subia após reboot completo + root reativado** (KSU late-load): duas causas encadeadas,
  provadas no aparelho com o watch no estado real.
  1. Mount namespace: os scripts do módulo rodam em `mnt:[4026535369]`, sem as APEX -> `app_process`:
     CANNOT LINK `libnativeloader.so` (o zygote64 está em `4026535238`, com a lib).
  2. Ambiente: o ksud passa 17 variáveis, sem BOOTCLASSPATH/ANDROID_*/armazenamento -> no namespace certo o
     `app_process` sai na hora, rc 0, sem log (com o ambiente do zygote64: vivo).
  `observer.sh` entra no namespace do zygote64 (`nsenter -t <zygote64> -m`, uma vez: FUSAO_NS_HOP) e
  herda o ambiente dele (sem ANDROID_SOCKET_*). Prova pelo caminho real: o observer morto às 00:27:12, o
  WATCH relançou e às 00:27:17 `observer relançado OK`; observer = app_process64 no namespace do zygote64,
  38 variáveis, registrado na 1ª tentativa; watch saiu do poll de 5 s. Harness 237/237 (+6; controle
  negativo: 3 FALHAM contra o observer.sh antigo).
- Armadilha de teste: `pgrep -f "[f]usao.observer"` do watch casa a cmdline de QUALQUER shell que contenha
  "fusao/observer" ou "com.fusao.observer" (inclusive comandos de monitoramento via adb).
- **App 1.1.2 (pedido do dono: tirar texto sem sentido)**: cabeçalho "EXYNOS 1380 · PONTE KSU ATIVA" ->
  "SEM JOGO ATIVO"; "SISTEMA EM STANDBY" -> "SEM JOGO ATIVO"; "PERFIL // X" -> "PERFIL X"; "READBACK //" ->
  "LIDO NO APARELHO:"; saiu a linha de jargão em inglês das chaves ("SINGLE-WRITER · BOOT"...); descrições
  em português do que cada chave faz (a do fusaod dizia "sobe no boot", errado: ele só roda em jogo);
  subtítulos sem caminho/comando (pgrep, drop_caches, `fusao <flag>`, /data/adb/...); papéis dos daemons
  em português. Só texto: layout e dados iguais (fotos Status/Flags).
- Instalado: APK 1.1.2 (`628f5db6...`); módulo 2.11.2 STAGED via ksud (`5092862...`), observer.sh e fas.sh
  vivos = release. Falta: soft-reboot do dono e reteste.
- **Soft-reboot do dono 00:39 (2.11.2 ativo; boot_id `4765192b` igual = só soft, sem rollback desta vez)**:
  service.sh 00:39:06, observer registrado 00:39:08 na 1ª tentativa, watch em standby (evento, sem poll).
  No soft-reboot os scripts rodam no namespace global (APEX visíveis, 36 variáveis): a correção do
  observer.sh fica parada, como desenhado. Bleach detectado pelo OBSERVER (`observer: enter`) -> 00:40:27
  ganchos -> 00:40:36 `fas: on` -> `FAS working on` 00:41:14; CPU cravada 672-864 MHz, GPU 75-83%.
- Visto em jogo, NÃO é do FAS: `profile_mismatch=gpu_min:221000<351000` (verify game 4/5). O fas-rs só LÊ
  `gpu_busy` (conferido no fonte); outro escritor baixou o piso da GPU depois do apply. Mesma família da
  "trava externa do piso da GPU" do 2.9.9. Sem impacto visível (GPU ocupada, o governor sobe o clock).

## 2.11.3/1606 + app 1.1.3: perfil game sem piso de GPU (a Samsung é dona dele) — 2026-10-19 ~01:00
- **Quem baixava o piso da GPU em jogo: o Hyper/EPIC da Samsung** (`/vendor/etc/hyper/config_vendor.json`):
  recurso `GPUMinFreq` (Handler sysfs, BoostPath `/sys/kernel/gpu/gpu_min_clock`, DefaultValue `0#` =
  221000); `GPUMaxFreq` -> `gpu_max_clock`, padrão `100#` = 949000 (explica o teto 650000 do battery antigo
  que nunca segurava). Hints que usam o piso: AMS_RESUME, AMS_ACT_RESUME, AMS_ACT_START, AMS_APP_SWITCH
  (duração -1 = até liberar), AMS_APP_HOME, SF_GPU_MINLOCK, RECENTS_GESTURE_BOOSTER, BIXBY_BOOST,
  MULTI_WINDOW_TOUCH_BOOSTER... Ao liberar, escrevem o padrão por cima do FUSAO.
- **Prova causal** (fora do jogo; o FUSAO não reage a app fora da gamelist): 351000 escrito, 5 s parado
  sem mudar; abrir Configurações -> 455000 (hint) -> 351000 -> **221000** em ~2 s; só a reverificação
  (leitura) no log. Em jogo, sem eventos, 351000 segurou ~50 s.
- **Decisão do dono (opção 1)**: o perfil game pede 221000 (= padrão da Samsung, sem piso). Ganho do piso
  nunca medido; reescrever depois de cada hint seria briga com a ROM (proibido). Harness 237/237 (controle
  negativo: `tune game gpu_min` FALHA contra o tune.sh antigo). App 1.1.3: descrição do game sem "GPU
  351–949" (com FAS: "CPU com o FAS em jogo · GPU sem piso · MIF ≥845k").
- **Ao vivo** (Bleach, 01:00): game **5/5 applied**, GPU 221000-949000, FAS working, CPU 672 MHz.
  Módulo 2.11.3 STAGED (tune.sh vivo = release), APK 1.1.3 `355f652d...`.

## 2.11.4/1607 + app 1.1.4: framework LIGADO, battery com tela adaptativa (2026-10-19 ~01:24, cabo)
- Dono: "detesto 60 Hz", prefere fluidez a economia. `framework.sh` battery: 60/60 -> **120/60 adaptativo**
  (igual ao balanced); game segue 120/120 fixo. Harness 237/237 (controle negativo: `framework battery
  adaptive-hz` FALHA contra o framework.sh antigo). App 1.1.4: descrição da chave framework sem "battery =
  60 Hz".
- `fusao framework on` no aparelho (boot `4765192b`, sem reboot): battery -> peak 120 / min 60 + tríade do
  battery; Bleach -> `framework: apply game` -> 120/120, game 5/5; saída -> `fas: off` -> tune battery ->
  `framework: apply battery` -> 120/60, fas-rs parado.
- Segue aberto: `framework.stock` contaminado (desligar não devolve o padrão exato da ROM) e as tríades
  (jobs/standby) nunca medidas (futuro.md §3). Módulo 2.11.4 STAGED (framework.sh vivo = release), APK
  1.1.4 `964e2278...`.

## 2.11.5/1608: fas-rs sem as paradas e sem a trava de GPU falsa (2026-10-20, WiFi `:41273`)
- **Queixa do dono (Roblox)**: fps bom (19-21 ms) com pico de ~50 ms a cada ~3 s e congelamentos de alguns
  segundos. `fas.log` da sessão (2026-10-19 02:28): FAS parou e voltou 6x em 2,5 min ("FAS stopped" -> 9-23 s
  sem controle) e a trava de GPU do patch 0003 alternou ~120 vezes.
- **Causas lidas no fonte e confirmadas no trace** (feature `m54-trace`, só em build de experimento):
  (1) a trava usava só `gpu_busy` >= 95%: ativou com a GPU a **221 MHz** (Forgotton Anne), e enquanto ativa a CPU
  só podia descer; (2) uma travada de ~0,3 s derrubava o fps médio para o degrau de baixo e cada troca de alvo
  zerava o buffer e parava o FAS; (3) na travada a thread parada lê 0% de uso e o rastreio por uso segurava a CPU
  em 533 MHz por ~400 ms; (4) ao voltar, o FAS começava cravado no último clock (muitas vezes o mínimo).
- **Patches 0005-0009** (`tools/fas-rs-m54/README.md`): trava só com a GPU no teto por 500 ms + histerese e nunca
  na travada; troca de alvo só depois de 2 s e nunca durante travada < 5 s; troca entre dois alvos sem zerar o
  buffer; travada = jank; retomada do clock do governor. Binário `dfed4255...`.
- **Prova (NTE, 4 travadas de 0,5 s com SIGSTOP + as naturais do carregamento, mesmo roteiro, n=1/braço)**: antigo
  = 1 "FAS stopped" após travada natural de 839 ms (~20 s sem controle), 10 alternâncias da trava; novo = 0 paradas,
  0 alternâncias, CPU no máximo em <= 100 ms de travada e mantida por 2,8 s, trocas 60 -> 45 -> 30 sem parar.
  Binário de produção (sem trace) repetiu: 0 paradas, saída limpa (fas off -> battery 9/9).
- **Não provado ainda**: o pico de 3 s no Roblox. Inferência: é a trava alternando (no log ela mudava a cada 1-3 s
  e cada ativação corta a subida da CPU). Precisa de uma sessão do dono no Roblox com o binário novo.
- Binário novo VIVO em `/data/adb/modules/encore/system/bin/fas-rs` (o original 2.11.4 em
  `/data/local/tmp/fas-rs.orig`); zip `_releases/fusao-v2.11.5-M54.zip`.
- **Dono, Roblox com o 2.11.5 (mesmo cenário, gráficos no máximo)**: congelamentos e picos de 3 s SUMIRAM
  (Performance Stats do Roblox). Novo: tempo de CPU por frame passou do de GPU (ex.: CPU 30-33 ms, GPU 20-23 ms):
  o FAS segura a CPU no mínimo que o alvo pede. A CONFERIR: o fps final (CPU 33 ms = ~30 fps; se antes eram
  ~50, o alvo pode ter descido para 30/45 e ficado lá).

## battery 4/9 "partial" (2026-10-20 19:53) e Smart Pixels automático — NÃO commitado (pedido do dono)
- **Lido**: `profile_mismatch=p0_min:2002000!=533000,p4_min:2400000!=533000,gpu_min:351000!=221000,
  p0_max:2002000!=1728000,p4_max:2400000!=1440000`; às 20:02 os pisos já tinham voltado, mas os TETOS seguiam
  2002000/2400000 (9+ min). Saída do Roblox com a tela apagada pelo botão às 19:53:36. Fora do jogo o teto
  segura (60 s, e com o hint de abrir as Configurações o kernel prende o piso no NOSSO teto). UFC (cpufreq_limit,
  `drivers/soc/samsung/exynos-ufcc.c`) usa pedidos de QoS próprios: não sobe o nosso. `scaling_max_freq` é
  `system:system 0664` (ueventd.rc): outro escritor regravou o nosso pedido. NÃO identificado; 3 saídas
  reproduzidas (home, tela apagada + volta, home) fecharam 9/9.
- **Falha nossa**: teto ACIMA do pedido virava "partial" definitivo (sem reverificação, sem nova tentativa).
  **Correção** (`tune.sh`): o verify lista os tetos acima do pedido; o apply agenda UM `reassert` em +30 s que
  relê e, se seguir acima, regrava só esse teto uma vez (nunca agenda outro; teto ABAIXO = térmico, nunca
  regravado). Harness 243/243; controle negativo (tune.sh antigo) falha os 2 testes novos.
- **Visto de passagem**: com a tela apagada DENTRO do jogo o observer não vê saída: o perfil game e o fas-rs
  seguem vivos; ao acordar sem bloqueio, o fas-rs cravou P0/P4 em 533000 (min=max) com o jogo em foco.
- **Smart Pixels automático**: `tune.sh` avisa o app em todo apply (`am broadcast -a
  com.orkgabb.fusao.action.PROFILE -p com.orkgabb.fusao -f 0x20 --es profile <perfil>`; receptor exige DUMP).
  Aparelho: o app gravou game/battery pelos dois caminhos (am à mão e tune.sh). A máscara entrar/sair sozinha
  NÃO foi vista (a chave manual do dono está ligada e vence; `cmd statusbar click-tile` não age com o bloco
  fora do painel). Composição da máscara na home: **DEVICE** (plano do HWC, 1080x2400), sem custo de GPU.
- App 1.1.6/8 instalado (ponto 1-3 px, "Ligar sozinho no perfil battery", textos técnicos limpos);
  `tune.sh` novo VIVO em /data/adb/fusao (original em /data/local/tmp/tune.sh.orig).
- **Smart Pixels "piscando sem parar" (dono, 20:25)**: janela estável (mesmos IDs de camada por 10 s, sem
  recriar); `shift_millis=1000` = o padrão inteiro trocava de lugar a cada 1 s. App 1.1.7/9: movimento só
  Parado / 1 / 5 / 15 min, mínimo 1 min, valor antigo sobe ao carregar. Aparelho: 1000 -> 60000 lido do
  shared_prefs depois da instalação, máscara ativa. Testes 15/15; controle negativo (mínimo 80 ms) falha 2.
