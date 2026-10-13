# nodes-m54.md — Mapa de nós do Exynos 1380 (Galaxy M54)
> Fonte primária: PUCTuner (`OrkGabb/PUCTuner`) — `platform.cpp`, `apply_profile.sh`,
> `thermal_guard.sh`, `unlock_nodes.sh`. Validado contra o aparelho real pelo autor.
> Convenção: `apply_node <id> <path> <valor> [ceiling]` = escrita auditada do PUCTuner.

## 1. GPU Mali-G68 (`/sys/kernel/gpu/` + mali0 KMD)

| Nó | Uso | Default relevante |
|---|---|---|
| `/sys/kernel/gpu/gpu_governor` | governor (`Interactive` default do applier) | stock |
| `/sys/kernel/gpu/gpu_min_clock` | piso (applier fixa `hwmin` = mínimo HW) | stock |
| `/sys/kernel/gpu/gpu_max_clock` | teto por perfil (`gpu_max`, com semântica `ceiling`) | stock |
| `/sys/kernel/gpu/gpu_freq_table` | leitura — DVFS resolve closest-freq nela | — |
| `/sys/kernel/gpu/gpu_busy` | leitura — utilização p/ telemetria | — |
| `/sys/kernel/gpu/gpu_cl_boost_disable` | CL boost (`gpu_cl_boost`, default 1) | stock |
| `/sys/class/misc/mali0/device` | presença KMD (gate: sem isso, pula GPU) | — |
| High-speed: `gpu_hs_load=94`, `gpu_hs_clock=650000`, `gpu_hs_delay=0` | patamar agressivo do applier | stock |
| `gpu_dvfs_period=100`, `gpu_polling_speed=30`, `gpu_js_period=100` | temporização DVFS/job | stock |

Fusão consome em: Fase 4 (executores via cache) + telemetria do overlay.

## 2. Barramento / INT / Display (`devfreq` Exynos-exato)

| Nó | Uso |
|---|---|
| `/sys/class/devfreq/17000010.devfreq_mif/{min,max}_freq` | MIF (memória). `mif_min` default = `hwmin` |
| `/sys/class/devfreq/17000020.devfreq_int` | INT (interconnect) |
| `/sys/class/devfreq/17000040.devfreq_disp` | DISP (display) |

Nenhum dos 3 módulos tem esses endereços exatos (só globs). Fusão: viram `apply_mif/int/disp`
dedicados em vez de varredura cega.

## 3. CPU (`cpufreq` + scheduler)

| Nó | Uso |
|---|---|
| `/sys/devices/system/cpu/cpufreq/policy*/{scaling_governor,scaling_{min,max}_freq}` | trava + governor (padrão dos 3 módulos também) |
| `/proc/sys/kernel/sched_pelt_multiplier` | PELT (entropia do scheduler; applier + unlock list) |
| `/proc/sys/kernel/sched_energy_aware` | EAS on/off por perfil/bench |
| `/sys/devices/system/cpu/cpu0/cpuidle/state1/disable` | leitura (status) |
| `/sys/devices/system/cpu/cpuidle/current_governor` | leitura (status) |

## 4. Thermal (zones + fail-safe) ⭐ padrão a adotar

| Nó / padrão | Uso |
|---|---|
| `/sys/class/thermal/thermal_zone*/{type,temp,mode}` | leitura de `temp` por tipo (`big/little/g3d/*cpu*/*gpu*/*batt*`); `mode` = `disabled` (agressivo) / `enabled` |
| `thermal_guard.sh` | **Fail-safe independente**: histerese 78°C/70°C, bateria 45°C, timeout 15min, PID file com liveness, `atomic_write`, volta sozinho. NENHUM dos 3 módulos tem isso — fusão adota como está |
| `thermal_guard_forced` (flag) | trava: perfil agressivo não religa zones enquanto guard atuou |

## 5. Memória / IO / storage

| Nó | Uso |
|---|---|
| `/proc/sys/vm/{swappiness,vfs_cache_pressure,page-cluster,stat_interval}` | VM por perfil |
| `/sys/block/{mmcblk*,sd*,nvme*}` + `/sys/devices/platform/*.ufs` | scheduler/fila |
| `/sys/devices/platform/*.ufs/{clkgate_enable,clkgate_delay_ms,auto_hibern8,rpm_lvl}` | UFS (`clkgate=1`, delay 150ms, hibern8 2000, `rpm_lvl=3`) |
| `/sys/kernel/ufs_perf_0/pm_qos_cluster1` | QoS big (`1248000`) |
| `/sys/fs/f2fs/*` | FS por perfil |
| `/sys/block/zram0`, `/dev/block/zram0`, `/proc/swaps` | zram |
| `/proc/sys/vm/{compact_memory,drop_caches}` | reclaim (idle-ram do Rairin cobre o daemon; flags aqui) |

## 6. Telemetria (o que o cérebro lê)

`/proc/{stat,meminfo,vmstat,pressure/{cpu,io,memory}}`, `/proc/$pid/{stat,cmdline}`,
`/sys/class/power_supply/battery/uevent`, `/sys/kernel/gpu/gpu_{busy,clock}`,
`/proc/uptime`, `/proc/sys/kernel/random/boot_id`. = entradas do motor de decisão.

## 7. Allowlist canônica (de `unlock_nodes.sh` — a verdade de capacidade do M54)

`scaling_{governor,min,max}_freq` + `gpu_{governor,min,max}_clock` + `devfreq mif min/max` +
`thermal_zone*/mode` + `sched_pelt_multiplier`, com a lição de ouro: **`access(W_OK)` mente
como root** — capacidade se mede com `stat`, não com teste de escrita. Inclui ainda a história
de guerra: módulo desabilitado há 3 dias tinha deixado nós em 0444. Fusão: esse script vira
diagnóstico de primeira linha (`status.sh` já faz metade).

## 8. Padrões do PUCTuner que a fusão adota (mais importantes que os paths)

1. `apply_node` com **report + semântica ceiling** — toda escrita é auditável (é o state-cache
   com outro nome; fundir com o do Stellar, não duplicar).
2. `fget`/`ovr` (config + override) — perfis parametrizados em vez de valores hardcodados.
3. PID records com liveness (`pid_record_alive`) — daemons órfãos nunca acumulam.
4. `thermal_guard.sh` como fail-safe — adotar sem mudar uma linha.
5. Perfis como funções (`apply_cpu/gpu/mif/io/ufs/f2fs/cpuidle/pelt/vm/gos/thermal`) —
   viram os **executores** da Fase 4, chamados através do cache (Fase 3), nunca direto.
6. `bench.sh`/`bench_sched.sh`/`measure_ablation.py` + `M54_BENCH_*` overrides — harness de
   medição pronto p/ Fase 8 (não reinventar).

## 9. O que descarta (MCTS)
O motor de busca MCTS (`core.cpp` 70KB, `engine_test.cpp` 90KB): lento por construção p/
decisão online — o próprio autor já enterrou. Substitui pelo núcleo heurístico FEAS (Fase 3);
o que sobrevive do `core.cpp` são as **funções de medição**, não a busca. `platform.cpp`
(sondagem de capacidades) sobrevive quase integral — é o detector de plataforma da fusão.
`runqueue.bpf.o` + `bpf.cpp`: promissor p/ telemetria de scheduler, mas eBPF em kernel Samsung
(GKI restrito?) precisa de prova no M54 antes de entrar no plano — mover p/ spike na Fase 3.

## 10. Fase 4 — veredito dos executores Rairin no M54 (2026-10-10, ao vivo)

| Executor | Veredito |
|---|---|
| `perf1.sh lock_policy_max` | ADOTA a lógica (retry 5x + `chmod 644` antes). Mas NUNCA o `chmod 444` final — foi ele que travou os nós (stat provou 0444). Via cache, sem trava |
| `RaiRin-performance1` (ELF) | Único locker real da GPU (`/sys/kernel/gpu/*_clock`). Auditar antes de adotar; checar Termux-leak nas strings |
| `gpu_max/balance/eco/reset` (ELF NDK r29) | BANIDOS no Exynos — os 4 imprimem `[ERROR] GPU path not found` e não movem 1 nó (alvos só MTK/QCOM). Vão p/ `device_mitigation` |
| `thermal/enable` (ELF one-shot) | Neutro (modes seguem `enabled`, sem daemon residual). Adotável como no-op documentado |
| `thermal/extreme2` + `lite2` (ELF) | **BANIDOS — adulteração de sensores térmicos.** `extreme2` deu bind-mount de arquivos em 50+ nós (`temp`, `trip_point_*`, `battery/temp`: 30000/250 flat, `/proc/mounts` provou). Cega o step_wise = risco de hardware. Removido ao vivo (`unspoof-thermal.sh`, 53 umounts, sensores 39–42°C de volta). `lite2` tem `mount/umount2/thermal_zone` nas strings = mesma família, nunca executar |
| `thermal_guard.sh` (PUCTuner) | Único fail-safe térmico da fusão (histerese real, sem adulteração). Adotar intacto |
| `$MOD` em `perf1.sh` | Bug: `export MOD` não existe → `gpu_max` nunca executava (kill-list Fase 7) |

Tetos rígidos (driver/DT, write acima ignora silencioso): `policy4/max=1824000`
(`cpuinfo` 2400000 inalcançável), `mif/max=2093000`. Degrau: `policy0/min`
só desce via intermediário (1152000→672000→533000). Pós-jogo o SoC segura
`scaling_max` abaixo do pedido (latch térmico Samsung, cdevs em 0) — retestar a frio;
_floors sempre colam, tetos nem sempre_.
