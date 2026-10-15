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
