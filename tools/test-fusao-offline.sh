#!/bin/bash
# test-fusao-offline.sh — harness Fase 8 offline (fake sysfs/settings, sem device).
# Roda no WSL/Linux: bash tools/test-fusao-offline.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/fusao-v2.0"
FAKE="${FAKE_DIR:-/tmp/fusao-fake-test-$$}"
rm -rf "$FAKE"; mkdir -p "$FAKE/fusao" "$FAKE/settings" "$FAKE/sys"
trap 'rm -rf "$FAKE" 2>/dev/null || true' EXIT
export M54_DIR="$FAKE/fusao"
export FAKE_SETTINGS_DIR="$FAKE/settings"
# 2.9.8: apply pending agenda reverificação em background (sleep 30..185 s); no harness ela é chamada à mão
export TUNE_NO_DEFER=1
for _s in lib.sh framework.sh perf.sh watch.sh bugreport.sh tune.sh dnd.sh display.sh gos_killer.sh thermal_guard.sh thermal_guard_stop.sh; do
  cp "$STAGE/fusao/$_s" "$FAKE/fusao/" 2>/dev/null
done
cp "$STAGE/fusao/config" "$FAKE/fusao/" 2>/dev/null
cp "$STAGE/fusao/gamelist.txt" "$FAKE/fusao/" 2>/dev/null
cp "$STAGE/system/bin/fusao" "$FAKE/fusao/fusao-cli" 2>/dev/null
chmod +x "$FAKE/fusao/"*.sh "$FAKE/fusao/fusao-cli" 2>/dev/null
echo "balanced" > "$FAKE/fusao/profile"
: > "$FAKE/fusao/game-active.txt"
echo "com.roblox.client" > "$FAKE/focus.txt"
export FOCUS_FAKE="$FAKE/focus.txt"

pass=0; fail=0
ok(){ pass=$((pass+1)); echo "PASS $1"; }
bad(){ fail=$((fail+1)); echo "FAIL $1"; }

# 1) bash -n em todos os scripts
for s in lib.sh watch.sh framework.sh perf.sh bugreport.sh tune.sh observer.sh dnd.sh display.sh thermal_guard.sh thermal_guard_stop.sh gos_killer.sh; do
  if [ -f "$STAGE/fusao/$s" ]; then
    bash -n "$STAGE/fusao/$s" 2>"$FAKE/shn.err" && ok "sh-n $s" || { bad "sh-n $s"; cat "$FAKE/shn.err"; }
  fi
done
for s in service.sh customize.sh uninstall.sh cleanup.sh action.sh; do
  bash -n "$STAGE/$s" 2>"$FAKE/shn.err" && ok "sh-n $s" || { bad "sh-n $s"; cat "$FAKE/shn.err"; }
done
bash -n "$STAGE/system/bin/fusao" 2>"$FAKE/shn.err" && ok "sh-n fusao-cli" || bad "sh-n fusao-cli"

# 2) framework: game aplica triad + refresh 120Hz + game_overlay seguro ('0'), idle restaura
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" game
grep -q "max_job_count_active=1" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework game triad" || bad "framework game triad"
grep -q "120.0" "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null && ok "framework game refresh" || bad "framework game refresh"
mkdir -p "$FAKE/proc/sys/vm" "$FAKE/proc/sys/net/ipv4" "$FAKE/sys/block/sda/queue"
echo 150 > "$FAKE/proc/sys/vm/swappiness"; echo 120 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo 1 > "$FAKE/proc/sys/vm/stat_interval"; echo 0 > "$FAKE/proc/sys/vm/page-cluster"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
echo 0 > "$FAKE/sys/block/sda/queue/nomerges"
echo 0 > "$FAKE/sys/block/sda/queue/add_random"
rm -f "$FAKE/fusao/tune.stock"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" game
grep -q "tune: apply game" "$FAKE/fusao/fusao.log" && ok "framework+tune wire" || bad "framework+tune wire"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "framework+tune eff" || bad "framework+tune eff"
[ "$(cat "$FAKE/settings/dc__game_overlay__game_default_frame_rate" 2>/dev/null)" = "0" ] && ok "framework game overlay safe-int" || bad "framework game overlay safe-int"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" idle
[ ! -f "$FAKE/settings/dc__game_overlay__game_default_frame_rate" ] && ok "framework idle dc-revert" || bad "framework idle dc-revert"
[ ! -s "$FAKE/settings/global__job_scheduler_constants" ] || [ "$(cat "$FAKE/settings/global__job_scheduler_constants")" != "*max_job_count_active=1*" ] && ok "framework idle restore" || bad "framework idle restore"

# 3) framework battery/balanced
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" battery
grep -q "max_job_count_active=2" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework battery" || bad "framework battery"
[ "$(cat "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null)" = "60.0" ] && ok "framework battery 60hz" || bad "framework battery 60hz"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" balanced
grep -q "max_job_count_active=4" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework balanced" || bad "framework balanced"
[ "$(cat "$FAKE/settings/system__min_refresh_rate" 2>/dev/null)" = "60.0" ] && [ "$(cat "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null)" = "120.0" ] && ok "framework balanced adaptive-hz" || bad "framework balanced adaptive-hz"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" idle

# 4) perf io-only, exit e proteção NTE (sem cgroup.procs global)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/perf.sh" io-only && ok "perf io-only" || bad "perf io-only"
grep -q "perf: io aplicado" "$FAKE/fusao/fusao.log" && ok "perf log" || bad "perf log"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/perf.sh" exit && ok "perf exit" || bad "perf exit"
grep -Eq '>[[:space:]]*"?/dev/(cpuset|stune)/top-app/cgroup\.procs' "$STAGE/fusao/perf.sh" && bad "perf no-cgroup-procs" || ok "perf no-cgroup-procs"

# 5) fusao CLI: profile/status/kill-switch/gamelist
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile game | grep -q "profile=game" && ok "cli profile game" || bad "cli profile game"
[ "$(cat "$FAKE/fusao/profile")" = "game" ] && ok "cli profile file" || bad "cli profile file"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" framework on | grep -q "framework=on" && ok "cli framework on" || bad "cli framework on"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" framework off | grep -q "reverted" && ok "cli framework off" || bad "cli framework off"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" status | grep -q "profile=game" && ok "cli status" || bad "cli status"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" status | grep -q "^thermal=moderate$" && ok "cli status thermal" || bad "cli status thermal"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist add com.teste.jogo | grep -q "added" && ok "cli gamelist add" || bad "cli gamelist add"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist list | grep -q "com.teste.jogo" && ok "cli gamelist list" || bad "cli gamelist list"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist del com.teste.jogo | grep -q "removed" && ok "cli gamelist del" || bad "cli gamelist del"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist list | grep -q "com.teste.jogo" && bad "cli gamelist del-eff" || ok "cli gamelist del-eff"
# Teste de deleção do último item restante na gamelist (regressão grep -v rc=1)
echo "com.ultimo.item" > "$FAKE/fusao/gamelist.txt"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist del com.ultimo.item >/dev/null 2>&1
[ ! -s "$FAKE/fusao/gamelist.txt" ] && ok "cli gamelist del-last" || bad "cli gamelist del-last"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" dnd on | grep -q "dnd=on" && ok "cli dnd on" || bad "cli dnd on"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" dnd off | grep -q "dnd=off" && ok "cli dnd off" || bad "cli dnd off"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" display show >/dev/null 2>&1 && ok "cli display show" || bad "cli display show"
cp "$STAGE/fusao/gamelist.txt" "$FAKE/fusao/gamelist.txt"

# 6) watch parser: foco na lista vs fora (lógica, sem loop)
FOCUS="com.roblox.client"
grep -Fxq "$FOCUS" "$FAKE/fusao/gamelist.txt" && ok "watch gamelist hit" || bad "watch gamelist hit"
FOCUS="com.android.launcher"
grep -Fxq "$FOCUS" "$FAKE/fusao/gamelist.txt" && bad "watch gamelist miss" || ok "watch gamelist miss"

# 7) dex do observer: só nossas classes, sem stub android.*
python3 "$ROOT/tools/check-dex.py" "$STAGE/fusao/observer.dex" && ok "observer dex" || bad "observer dex"

# 8) watch standby: com observer vivo não escreve; sem observer assume poll
cp "$STAGE/fusao/watch.sh" "$FAKE/fusao/" 2>/dev/null
echo "SENTINEL" > "$FAKE/fusao/game-active.txt"
OBSERVER_FAKE_ALIVE=1 M54_DIR="$FAKE/fusao" timeout 6 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
[ "$(cat "$FAKE/fusao/game-active.txt")" = "SENTINEL" ] && ok "watch standby" || bad "watch standby"
grep -q "standby" "$FAKE/fusao/fusao.log" && ok "watch standby log" || bad "watch standby log"
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
OBSERVER_FAKE_ALIVE=1 M54_DIR="$FAKE/fusao" timeout 6 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
grep -q "watch: hooks.*com.roblox.client" "$FAKE/fusao/fusao.log" && ok "watch standby hooks" || bad "watch standby hooks"
export FOCUS_FAKE="$FAKE/focus.txt"
M54_DIR="$FAKE/fusao" timeout 8 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
[ "$(cat "$FAKE/fusao/game-active.txt")" = "com.roblox.client" ] && ok "watch takeover poll" || bad "watch takeover poll"
grep -q "watch: hooks" "$FAKE/fusao/fusao.log" && ok "watch hooks line" || bad "watch hooks line"
: > "$FAKE/fusao/game-active.txt"

# 9) tune.sh + fusao profile e2e em fake sysfs completo (CPU P0/P4, GPU, MIF/INT/DISP, VM, IO, TCP + readback)
mkdir -p \
  "$FAKE/sys/devices/system/cpu/cpufreq/policy0" \
  "$FAKE/sys/devices/system/cpu/cpufreq/policy4" \
  "$FAKE/sys/kernel/gpu" \
  "$FAKE/sys/class/devfreq/17000010.devfreq_mif" \
  "$FAKE/sys/class/devfreq/17000020.devfreq_int" \
  "$FAKE/sys/class/devfreq/17000040.devfreq_disp" \
  "$FAKE/sys/block/sda/queue" \
  "$FAKE/proc/sys/vm" \
  "$FAKE/proc/sys/net/ipv4"

# Tabelas REAIS do M54 (leitura ao vivo 2026-10-18): é o que o tune.sh 2.9.7 lê p/ fazer snap dos alvos.
P0_TAB_REAL="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000"
P4_TAB_REAL="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2016000 2112000 2208000 2304000 2400000"
GPU_TAB_REAL="221000 351000 455000 552000 650000 754000 845000 949000"
CPUF="$FAKE/sys/devices/system/cpu/cpufreq"
echo "$P0_TAB_REAL" > "$CPUF/policy0/scaling_available_frequencies"
echo 533000 > "$CPUF/policy0/cpuinfo_min_freq"
echo 2002000 > "$CPUF/policy0/cpuinfo_max_freq"
echo "$P4_TAB_REAL" > "$CPUF/policy4/scaling_available_frequencies"
echo 533000 > "$CPUF/policy4/cpuinfo_min_freq"
echo 2400000 > "$CPUF/policy4/cpuinfo_max_freq"
echo "$GPU_TAB_REAL" > "$FAKE/sys/kernel/gpu/gpu_freq_table"
echo 533000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo 533000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
echo 1824000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
echo 221000 > "$FAKE/sys/kernel/gpu/gpu_min_clock"
echo 949000 > "$FAKE/sys/kernel/gpu/gpu_max_clock"
echo 421000 > "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq"
echo 2093000 > "$FAKE/sys/class/devfreq/17000010.devfreq_mif/max_freq"
echo 50000 > "$FAKE/sys/class/devfreq/17000020.devfreq_int/min_freq"
echo 66000 > "$FAKE/sys/class/devfreq/17000040.devfreq_disp/min_freq"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
echo 0 > "$FAKE/sys/block/sda/queue/nomerges"
echo 0 > "$FAKE/sys/block/sda/queue/add_random"
echo 150 > "$FAKE/proc/sys/vm/swappiness"
echo 120 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo 1 > "$FAKE/proc/sys/vm/stat_interval"
echo 0 > "$FAKE/proc/sys/vm/page-cluster"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno bbr" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"

cp "$STAGE/fusao/tune.sh" "$FAKE/fusao/" 2>/dev/null
rm -f "$FAKE/fusao/tune.stock"

# 9a) Perfil GAME via CLI (mesmo sem framework.enabled precisa aplicar e confirmar readback!)
rm -f "$FAKE/fusao/framework.enabled"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile game > "$FAKE/prof_game.out"
grep -q "profile_state=applied" "$FAKE/prof_game.out" && ok "profile game verified" || bad "profile game verified"
grep -q "profile_verify=10/10" "$FAKE/prof_game.out" && ok "profile game verify 10/10" || bad "profile game verify 10/10"
# alvos do game = degraus REAIS da tabela (999000/889000 eram de outro SoC; 1056000/960000 existem no 1380)
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "1056000" ] && ok "tune game p0_min" || bad "tune game p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "960000" ] && ok "tune game p4_min" || bad "tune game p4_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "2002000" ] && ok "tune game p0_max" || bad "tune game p0_max"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1824000" ] && ok "tune game p4_max" || bad "tune game p4_max"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_min_clock")" = "351000" ] && ok "tune game gpu_min" || bad "tune game gpu_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "949000" ] && ok "tune game gpu_max" || bad "tune game gpu_max"
[ "$(cat "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq")" = "845000" ] && ok "tune game mif_min" || bad "tune game mif_min"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "tune game swap" || bad "tune game swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "50" ] && ok "tune game pressure" || bad "tune game pressure"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "512" ] && ok "tune game readahead" || bad "tune game readahead"
[ "$(cat "$FAKE/sys/block/sda/queue/nomerges")" = "1" ] && ok "tune game nomerges" || bad "tune game nomerges"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "bbr" ] && ok "tune game bbr" || bad "tune game bbr"

# 9b) Transição GAME -> BALANCED (sem resíduos do modo Game)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile balanced > "$FAKE/prof_bal.out"
grep -q "profile_state=applied" "$FAKE/prof_bal.out" && ok "profile balanced verified" || bad "profile balanced verified"
grep -q "profile_verify=10/10" "$FAKE/prof_bal.out" && ok "profile balanced verify 10/10" || bad "profile balanced verify 10/10"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune balanced p0_min" || bad "tune balanced p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "533000" ] && ok "tune balanced p4_min" || bad "tune balanced p4_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_min_clock")" = "221000" ] && ok "tune balanced gpu_min" || bad "tune balanced gpu_min"
# 858000 não existe na tabela do Mali-G68; o degrau real logo abaixo do máximo (949000) é 845000
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "845000" ] && ok "tune balanced gpu_max" || bad "tune balanced gpu_max"
[ "$(cat "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq")" = "421000" ] && ok "tune balanced mif_min" || bad "tune balanced mif_min"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "120" ] && ok "tune balanced swap" || bad "tune balanced swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "100" ] && ok "tune balanced pressure" || bad "tune balanced pressure"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "256" ] && ok "tune balanced readahead" || bad "tune balanced readahead"
[ "$(cat "$FAKE/sys/block/sda/queue/nomerges")" = "0" ] && ok "tune balanced nomerges-clean" || bad "tune balanced nomerges-clean"

# 9c) Transição BALANCED -> BATTERY / PowerSaving (tetos reduzidos; piso do P0 = 533000, o mínimo físico e o stock)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile battery > "$FAKE/prof_bat.out"
grep -q "profile_state=applied" "$FAKE/prof_bat.out" && ok "profile battery verified" || bad "profile battery verified"
grep -q "profile_verify=10/10" "$FAKE/prof_bat.out" && ok "profile battery verify 10/10" || bad "profile battery verify 10/10"
# 400000 não existe no 1380: o driver clampa p/ 533000 em silêncio. O alvo agora É 533000 (e não 672000: subir o piso gasta mais em ocioso)
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune battery p0_min" || bad "tune battery p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "1728000" ] && ok "tune battery p0_max" || bad "tune battery p0_max"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1440000" ] && ok "tune battery p4_max" || bad "tune battery p4_max"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "650000" ] && ok "tune battery gpu_max" || bad "tune battery gpu_max"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "150" ] && ok "tune battery" || bad "tune battery"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "128" ] && ok "tune battery readahead" || bad "tune battery readahead"

# 9c2) GAME com jogo em foco -> BATTERY direto: o piso do P0 só chega a 533000 se o dado de fato desce
# pelos degraus da tabela (o estado anterior é 1152000; um nó que não mexeu não passaria)
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" game >/dev/null 2>&1
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "1152000" ] && ok "tune game+jogo p0_min" || bad "tune game+jogo p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "1056000" ] && ok "tune game+jogo p4_min" || bad "tune game+jogo p4_min"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify game | grep -q "profile_verify=10/10" && ok "tune game+jogo verify 10/10" || bad "tune game+jogo verify 10/10"
: > "$FAKE/fusao/game-active.txt"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune game->battery p0_min desce a 533000" || bad "tune game->battery p0_min desce a 533000"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery | grep -q "profile_verify=10/10" && ok "tune game->battery verify 10/10" || bad "tune game->battery verify 10/10"

# 9d) Restauração completa ao stock via idle
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "150" ] && ok "tune idle restore" || bad "tune idle restore"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "cubic" ] && ok "tune idle tcp" || bad "tune idle tcp"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1824000" ] && ok "tune idle p4_max restore" || bad "tune idle p4_max restore"

# 9e) 2.9.7: tabela LIDA do nó + snap + readback. O probe carrega só as funções do tune.sh
# (TUNE_SOURCE_ONLY=1) e roda o comando recebido, com um driver falso opcional:
#   clamp: escrever abaixo do mínimo em policy0/scaling_min_freq "dá certo" (rc 0, sem errno) mas o nó
#          guarda o mínimo (400000 -> 533000), exatamente como o driver do Exynos 1380 faz no aparelho;
#   trace: anota cada valor escrito (prova a SEQUÊNCIA de degraus, não só o estado final).
cat > "$FAKE/fusao/tune-probe.sh" <<'EOF'
#!/bin/sh
TUNE_SOURCE_ONLY=1
. "$(dirname "$0")/tune.sh"
if [ -n "${PROBE_CLAMP_MIN:-}${PROBE_TRACE:-}" ]; then
  sysfs_put() {
    local v="$2"
    case "$1" in
      */policy0/scaling_min_freq) [ -n "${PROBE_CLAMP_MIN:-}" ] && [ "$v" -lt "$PROBE_CLAMP_MIN" ] 2>/dev/null && v="$PROBE_CLAMP_MIN";;
    esac
    [ -n "${PROBE_TRACE:-}" ] && echo "$1 $v" >> "$PROBE_TRACE"
    { echo "$v" > "$1"; } 2>/dev/null
  }
fi
# w() do 2.9.6 (literal; só o primitivo de escrita é o mesmo do driver falso): controle negativo do clamp.
w_legacy() {
  [ -e "$1" ] || return 0
  chmod 0644 "$1" 2>/dev/null
  if [ -w "$1" ]; then
    sysfs_put "$1" "$2"
    return $?
  fi
  return 1
}
"$@"
EOF
# probe_at <FS_ROOT> <clamp-min|-> <trace-file|-> <comando...>
probe_at() {
  local _root="$1" _clamp="$2" _trace="$3"; shift 3
  [ "$_clamp" = "-" ] && _clamp=""
  [ "$_trace" = "-" ] && _trace=""
  M54_DIR="$FAKE/fusao" FS_ROOT="$_root" PROBE_CLAMP_MIN="$_clamp" PROBE_TRACE="$_trace" sh "$FAKE/fusao/tune-probe.sh" "$@"
}
FLOG="$FAKE/fusao/fusao.log"
PNODE="$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"

# 9e-1) snap na tabela real: min arredonda p/ cima (como o kernel), max p/ baixo; alvo que não existe vira degrau
: > "$FLOG"
[ "$(probe_at "$FAKE" - - snap_domain min p0 999000)" = "1056000" ] && ok "snap p0 min 999000->1056000" || bad "snap p0 min 999000->1056000"
[ "$(probe_at "$FAKE" - - snap_domain min p4 889000)" = "960000" ] && ok "snap p4 min 889000->960000" || bad "snap p4 min 889000->960000"
[ "$(probe_at "$FAKE" - - snap_domain max p0 1700000)" = "1632000" ] && ok "snap p0 max 1700000->1632000" || bad "snap p0 max 1700000->1632000"
[ "$(probe_at "$FAKE" - - snap_domain max p4 1500000)" = "1440000" ] && ok "snap p4 max 1500000->1440000" || bad "snap p4 max 1500000->1440000"
[ "$(probe_at "$FAKE" - - snap_domain max gpu 858000)" = "845000" ] && ok "snap gpu max 858000->845000" || bad "snap gpu max 858000->845000"
[ "$(probe_at "$FAKE" - - snap_domain min gpu 300000)" = "351000" ] && ok "snap gpu min 300000->351000" || bad "snap gpu min 300000->351000"
[ "$(probe_at "$FAKE" - - snap_domain min p0 1056000)" = "1056000" ] && ok "snap degrau exato fica" || bad "snap degrau exato fica"
grep -q "tune: snap p0/min pedido=999000 -> 1056000" "$FLOG" && ok "snap loga o arredondamento" || bad "snap loga o arredondamento"
grep -q "pedido=1056000" "$FLOG" && bad "snap degrau exato nao loga" || ok "snap degrau exato nao loga"

# 9e-2) fora da faixa vira o limite físico, com WARN explícito (fail-loud no log, rc 0, fluxo segue)
: > "$FLOG"
[ "$(probe_at "$FAKE" - - snap_domain min p0 400000)" = "533000" ] && ok "snap p0 min 400000->533000" || bad "snap p0 min 400000->533000"
grep -q "WARN tune: snap p0/min pedido=400000 abaixo do mínimo físico 533000, usando 533000" "$FLOG" && ok "snap abaixo do minimo loga WARN" || bad "snap abaixo do minimo loga WARN"
[ "$(probe_at "$FAKE" - - snap_domain max p0 3000000)" = "2002000" ] && ok "snap p0 max 3000000->2002000" || bad "snap p0 max 3000000->2002000"
grep -q "WARN tune: snap p0/max pedido=3000000 acima do máximo físico 2002000, usando 2002000" "$FLOG" && ok "snap acima do maximo loga WARN" || bad "snap acima do maximo loga WARN"
[ "$(probe_at "$FAKE" - - snap_domain min gpu 100000)" = "221000" ] && ok "snap gpu min 100000->221000" || bad "snap gpu min 100000->221000"
[ "$(probe_at "$FAKE" - - snap_domain min p0 abc)" = "533000" ] && ok "snap alvo invalido vira limite" || bad "snap alvo invalido vira limite"
grep -q "WARN tune: snap p0/min alvo inválido 'abc'" "$FLOG" && ok "snap alvo invalido loga WARN" || bad "snap alvo invalido loga WARN"

# 9e-3) a tabela vem do NÓ (e não do fallback embutido): tabela falsa com degraus que o 1380 não tem
# (700000, 1100000), descendente e com CRLF. Só quem leu o nó responde 1100000 p/ 999000 (fallback real: 1056000).
ALT="$FAKE/alt"
mkdir -p "$ALT/sys/devices/system/cpu/cpufreq/policy0" "$ALT/sys/kernel/gpu"
printf '2002000 1100000 700000 533000\r\n' > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
echo 533000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq"
echo 1100000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo "221000 500000 949000" > "$ALT/sys/kernel/gpu/gpu_freq_table"
echo 221000 > "$ALT/sys/kernel/gpu/gpu_min_clock"
echo 949000 > "$ALT/sys/kernel/gpu/gpu_max_clock"
[ "$(probe_at "$ALT" - - snap_domain min p0 999000)" = "1100000" ] && ok "tabela p0 lida do no (min)" || bad "tabela p0 lida do no (min)"
[ "$(probe_at "$ALT" - - snap_domain max p0 1099000)" = "700000" ] && ok "tabela p0 lida do no (max)" || bad "tabela p0 lida do no (max)"
[ "$(probe_at "$ALT" - - snap_domain max gpu 858000)" = "500000" ] && ok "tabela gpu lida do no" || bad "tabela gpu lida do no"
# descida do piso por degraus DO NÓ: de 1100000 até 533000 só passa por 700000 (a lista antiga hardcoded nunca teve 700000)
rm -f "$FAKE/trace.txt"
probe_at "$ALT" - "$FAKE/trace.txt" set_policy0 533000 2002000
[ "$(grep scaling_min_freq "$FAKE/trace.txt" | cut -d' ' -f2 | tr '\n' ' ')" = "700000 533000 " ] && ok "descida de degraus usa a tabela do no" || bad "descida de degraus usa a tabela do no"
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "descida chegou ao piso" || bad "descida chegou ao piso"
# controle negativo: sem os nós de tabela cai no fallback (tabela real do M54) e avisa no log
: > "$FLOG"
rm -f "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" "$ALT/sys/kernel/gpu/gpu_freq_table"
[ "$(probe_at "$ALT" - - snap_domain min p0 999000)" = "1056000" ] && ok "fallback p0 = tabela real do M54" || bad "fallback p0 = tabela real do M54"
[ "$(probe_at "$ALT" - - snap_domain max gpu 858000)" = "845000" ] && ok "fallback gpu = tabela real do M54" || bad "fallback gpu = tabela real do M54"
grep -q "WARN tune: p0 sem tabela legível" "$FLOG" && ok "fallback loga WARN" || bad "fallback loga WARN"

# 9e-4) w() relê: driver honesto devolve 0; driver que clampa em silêncio devolve 1 + WARN (nao mente rc 0)
echo 533000 > "$PNODE"
probe_at "$FAKE" - - w "$PNODE" 768000
[ "$?" = "0" ] && [ "$(cat "$PNODE")" = "768000" ] && ok "w honesto rc 0" || bad "w honesto rc 0"
: > "$FLOG"
probe_at "$FAKE" 533000 - w "$PNODE" 400000
_rc=$?
[ "$_rc" = "1" ] && ok "w clampado rc 1" || bad "w clampado rc 1 (rc=$_rc)"
[ "$(cat "$PNODE")" = "533000" ] && ok "w clampado: driver guardou o minimo" || bad "w clampado: driver guardou o minimo"
grep -q "WARN tune: clamp .*policy0/scaling_min_freq pedido=400000 lido=533000" "$FLOG" && ok "w clampado loga WARN clamp" || bad "w clampado loga WARN clamp"
# controle negativo: o w() do 2.9.6 no MESMO driver devolve 0 com o dado errado no nó (a mentira que a 2.9.7 corrige)
echo 768000 > "$PNODE"
probe_at "$FAKE" 533000 - w_legacy "$PNODE" 400000
_rc=$?
[ "$_rc" = "0" ] && [ "$(cat "$PNODE")" = "533000" ] && ok "controle negativo: w legado mente rc 0" || bad "controle negativo: w legado mente rc 0 (rc=$_rc)"
probe_at "$FAKE" - - w "$FAKE/nao/existe" 1 && ok "w nó ausente = no-op rc 0" || bad "w nó ausente = no-op rc 0"
grep -v '^[[:space:]]*#' "$STAGE/fusao/tune.sh" | grep -Eq 'chmod[[:space:]]+0*444' && bad "tune sem chmod 444" || ok "tune sem chmod 444"

# 9e-5) driver que clampa o P0 min p/ 672000 (o aparelho em 2026-10-18: profile_verify=9/10 e p0_min:533000!=400000):
# o battery pede 533000 e o nó guarda 672000 => o verify NÃO fecha 10/10 e aponta o nó
echo 2002000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo 533000 > "$PNODE"
rm -f "$FAKE/fusao/profile.state"; : > "$FLOG"
# 2.9.8: piso de CPU lido ACIMA do alvo é indistinguível de QoS externo (hint/boost da Samsung) logo após o
# apply => pending dentro da janela; o veredito final (fora da janela) continua partial apontando o nó.
TUNE_NOW=1000 probe_at "$FAKE" 672000 - apply_battery
grep -q "^profile_verify=9/10$" "$FAKE/fusao/profile.state" && ok "driver clampa: verify 9/10" || bad "driver clampa: verify 9/10"
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "driver clampa: pending na janela" || bad "driver clampa: pending na janela"
grep -q "^profile_external=p0_min:672000!=533000$" "$FAKE/fusao/profile.state" && ok "driver clampa: external aponta p0_min" || bad "driver clampa: external aponta p0_min"
grep -q "WARN tune: clamp .*policy0/scaling_min_freq pedido=533000 lido=672000" "$FLOG" && ok "driver clampa: WARN clamp no apply" || bad "driver clampa: WARN clamp no apply"
TUNE_NOW=1181 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=partial$" "$FAKE/fusao/profile.state" && ok "driver clampa: partial fora da janela" || bad "driver clampa: partial fora da janela"
grep -q "^profile_mismatch=p0_min:672000!=533000$" "$FAKE/fusao/profile.state" && ok "driver clampa: mismatch aponta p0_min" || bad "driver clampa: mismatch aponta p0_min"
grep -q "^applied_at=1000$" "$FAKE/fusao/profile.state" && ok "verify herda applied_at do apply" || bad "verify herda applied_at do apply"
# mesmo apply num driver honesto fecha 10/10
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
grep -q "^profile_verify=10/10$" "$FAKE/fusao/profile.state" && ok "driver honesto: verify 10/10" || bad "driver honesto: verify 10/10"

# 9e-6) verify confere o valor JÁ com snap: tabela do aparelho sem os degraus do perfil (P0 = 700000 1100000 2002000,
# cpuinfo_min 700000). O battery pede 533000/1728000 e o aparelho só pode dar 700000/1100000: com o alvo cru
# (1728000) o p0_max nunca fecharia; com o alvo snapado fecha 4/4 (P0 min/max + GPU min/max) e o nó prova o valor.
printf '700000 1100000 2002000\n' > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
echo 700000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"
echo 700000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
: > "$FLOG"
M54_DIR="$FAKE/fusao" FS_ROOT="$ALT" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "1100000" ] && ok "battery snapado p0_max=1100000" || bad "battery snapado p0_max=1100000"
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "700000" ] && ok "battery snapado p0_min=700000" || bad "battery snapado p0_min=700000"
grep -q "^profile_verify=4/4$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && ok "verify usa alvo snapado (4/4)" || bad "verify usa alvo snapado (4/4)"
grep -q "WARN tune: snap p0/min pedido=533000 abaixo do mínimo físico 700000, usando 700000" "$FLOG" && ok "apply loga WARN do alvo abaixo do minimo" || bad "apply loga WARN do alvo abaixo do minimo"
# o verify só confere: não repete os WARN do snap que o apply já logou (o app consulta o status toda hora)
_n0=$(grep -c "WARN tune: snap" "$FLOG")
M54_DIR="$FAKE/fusao" FS_ROOT="$ALT" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
[ "$_n0" -ge 1 ] && [ "$(grep -c "WARN tune: snap" "$FLOG")" = "$_n0" ] && ok "verify nao repete WARN do snap" || bad "verify nao repete WARN do snap"

# 9e-7) 2.9.8 piso EXTERNO (boost de boot / hint de launch, medido 2026-10-18 15:29 e 16:03): o QoS da
# Samsung segura scaling_min no alto e todo degrau "pega" o valor dela. A descida para no primeiro degrau
# (2.9.7: 15 WARN seguidos) e o pedido final ainda é gravado.
P0D="$FAKE/sys/devices/system/cpu/cpufreq/policy0"
echo 2002000 > "$P0D/scaling_max_freq"; echo 2002000 > "$P0D/scaling_min_freq"
rm -f "$FAKE/trace.txt"; : > "$FLOG"
probe_at "$FAKE" 2002000 "$FAKE/trace.txt" set_policy0 533000 1728000
[ "$(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt")" = "2" ] && ok "piso externo: descida para no 1o degrau (2 escritas)" || bad "piso externo: descida para no 1o degrau ($(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt") escritas)"
[ "$(grep -c "WARN tune: clamp .*policy0/scaling_min_freq" "$FLOG")" = "2" ] && ok "piso externo: 2 WARN, nao 15" || bad "piso externo: 2 WARN, nao 15"
# controle: driver honesto do mesmo ponto desce TODOS os degraus reais entre 2002000 e 533000 (14) + o final
echo 2002000 > "$P0D/scaling_min_freq"; rm -f "$FAKE/trace.txt"
probe_at "$FAKE" - "$FAKE/trace.txt" set_policy0 533000 1728000
[ "$(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt")" = "15" ] && [ "$(cat "$P0D/scaling_min_freq")" = "533000" ] && ok "controle: honesto desce 14 degraus + final" || bad "controle: honesto desce 14 degraus + final"

# 9e-8) janela do pending: hint segura P0 min 1152000 no apply do battery (como na entrada do Roblox)
echo 1728000 > "$P0D/scaling_max_freq"; echo 533000 > "$P0D/scaling_min_freq"
rm -f "$FAKE/fusao/profile.state"; : > "$FLOG"
TUNE_NOW=5000 probe_at "$FAKE" 1152000 - apply_battery
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "hint no apply: pending" || bad "hint no apply: pending"
grep -q "^profile_mismatch=" "$FAKE/fusao/profile.state" && bad "hint no apply: sem mismatch real" || ok "hint no apply: sem mismatch real"
grep -q "tune: battery pending (piso/teto de CPU externo: p0_min:1152000!=533000)" "$FLOG" && ok "hint no apply: loga pending" || bad "hint no apply: loga pending"
# o hint solta (o nó guarda o NOSSO pedido): verify do status dentro da janela fecha 10/10 applied
echo 533000 > "$P0D/scaling_min_freq"
TUNE_NOW=5040 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && grep -q "^profile_verify=10/10$" "$FAKE/fusao/profile.state" && ok "hint soltou: applied 10/10" || bad "hint soltou: applied 10/10"
# teto de CPU clampado ABAIXO pelo térmico dentro da janela = pending; ACIMA do alvo nunca é externo
echo 1344000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
TUNE_NOW=5050 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_external=p4_max:1344000!=1440000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "teto termico: pending" || bad "teto termico: pending"
echo 2400000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
TUNE_NOW=5050 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_mismatch=p4_max:2400000!=1440000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=partial$" "$FAKE/fusao/profile.state" && ok "teto acima do alvo: partial" || bad "teto acima do alvo: partial"
echo 1440000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

# 9e-9) reverify (só leitura): converge p/ applied quando o hint solta; job obsoleto não toca no state
echo 1152000 > "$P0D/scaling_min_freq"
TUNE_NOW=6000 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
sed -i 's/^applied_at=.*/applied_at=6000/' "$FAKE/fusao/profile.state"
TUNE_NOW=6000 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "reverify: parte de pending" || bad "reverify: parte de pending"
echo 533000 > "$P0D/scaling_min_freq"
: > "$FLOG"
TUNE_NOW=6030 REVERIFY_STEPS=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reverify battery 6000 >/dev/null 2>&1
grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && grep -q "tune: reverify battery -> applied 10/10" "$FLOG" && ok "reverify: converge p/ applied" || bad "reverify: converge p/ applied"
echo 1152000 > "$P0D/scaling_min_freq"
cp "$FAKE/fusao/profile.state" "$FAKE/state.before"
TUNE_NOW=6030 REVERIFY_STEPS=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reverify battery 4242 >/dev/null 2>&1
cmp -s "$FAKE/fusao/profile.state" "$FAKE/state.before" && ok "reverify obsoleto nao toca no state" || bad "reverify obsoleto nao toca no state"
echo 533000 > "$P0D/scaling_min_freq"

# 9e-10) restauração do stock (CLI/cleanup/uninstall): degraus REAIS do nó, só abaixo do piso atual.
# A função do CLI é extraída e rodada contra um nó falso; a lista antiga (outro SoC, começando em 1728000) sumiu.
for _f in "$STAGE/system/bin/fusao" "$STAGE/cleanup.sh" "$STAGE/uninstall.sh"; do
  grep -q "1728000 1512000 1320000" "$_f" && bad "lista de outro SoC fora de $(basename "$_f")" || ok "lista de outro SoC fora de $(basename "$_f")"
done
sed -n '/^restore_snapshot_nodes() {/,/^}/p' "$STAGE/system/bin/fusao" > "$FAKE/restore_fn.sh"
echo 1152000 > "$P0D/scaling_min_freq"
printf '# stock\n%s 533000\n' "$P0D/scaling_min_freq" > "$FAKE/fusao/stock.snapshot"
( . "$FAKE/restore_fn.sh"; M54_DIR="$FAKE/fusao" restore_snapshot_nodes )
[ "$(cat "$P0D/scaling_min_freq")" = "533000" ] && ok "restore snapshot: p0 min volta ao stock" || bad "restore snapshot: p0 min volta ao stock"
rm -f "$FAKE/fusao/stock.snapshot"

# restaura o fake principal p/ as seções seguintes
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle

# 10) dnd on/off com verify+retry (DND_FAKE_ZEN finge o settings get).
cp "$STAGE/fusao/dnd.sh" "$FAKE/fusao/" 2>/dev/null
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=1 sh "$FAKE/fusao/dnd.sh" on >"$FAKE/dndout.txt" 2>&1
grep -q "set_dnd priority" "$FAKE/dndout.txt" && ok "dnd on seq" || bad "dnd on seq"
[ "$(cat "$FAKE/fusao/dnd.state")" = "on" ] && ok "dnd on verified" || bad "dnd on verified"
[ -s "$FAKE/fusao/dnd.seq" ] && ok "dnd seq guard" || bad "dnd seq guard"
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=0 sh "$FAKE/fusao/dnd.sh" off >"$FAKE/dndout.txt" 2>&1
grep -q "set_dnd off" "$FAKE/dndout.txt" && ok "dnd off seq" || bad "dnd off seq"
[ "$(cat "$FAKE/fusao/dnd.state")" = "off" ] && ok "dnd off verified" || bad "dnd off verified"
rm -f "$FAKE/fusao/dnd.state"
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=9 sh "$FAKE/fusao/dnd.sh" on >/dev/null 2>&1
grep -q "dnd: FALHOU on" "$FAKE/fusao/fusao.log" && ok "dnd fail loud" || bad "dnd fail loud"
[ "$(M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/dnd.sh")" = "off" ] && ok "dnd state" || bad "dnd state"

# 11) display downscale em wm fake (stock 1080x2340)
cp "$STAGE/fusao/display.sh" "$FAKE/fusao/" 2>/dev/null
echo "1080x2340" > "$FAKE/wm.txt"
export WM_FAKE="$FAKE/wm.txt"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/display.sh" 720p | grep -q "720x1560" && ok "display 720p" || bad "display 720p"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/display.sh" reset >/dev/null
[ "$(cat "$FAKE/wm.txt")" = "1080x2340" ] && ok "display reset" || bad "display reset"
WM_FAKE="$FAKE/wm.txt" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" display show | grep -q "1080x2340" && ok "cli display" || bad "cli display"
unset WM_FAKE

# 13) higiene de órfãos temp-root (service/uninstall/cleanup) + preservação de gamelist
grep -q 'kill_stale()' "$STAGE/service.sh" && ok "orphan killfn" || bad "orphan killfn"
for _pt in "fusaod serve" "fusao.observer" "watch.sh" "thermal_guard.sh" "gos_killer.sh"; do
  grep -Fq "$_pt" "$STAGE/service.sh" && ok "orphan pat $_pt" || bad "orphan pat $_pt"
done
grep -q '"$$"' "$STAGE/service.sh" && ok "orphan self-guard" || bad "orphan self-guard"
grep -q '\[ -s "\$FUSAO_DIR/gamelist.txt" \]' "$STAGE/service.sh" && ok "service gamelist preserve" || bad "service gamelist preserve"
grep -q 'pkill -f "observer.sh"' "$STAGE/uninstall.sh" && ok "uninstall obs-sh" || bad "uninstall obs-sh"
grep -q '\*_pid' "$STAGE/uninstall.sh" && ok "uninstall pidfiles" || bad "uninstall pidfiles"
grep -q 'thermal_guard.sh' "$STAGE/cleanup.sh" && ok "cleanup guard" || bad "cleanup guard"
grep -q 'dnd.sh.*off' "$STAGE/cleanup.sh" && ok "cleanup dnd" || bad "cleanup dnd"

# 14) preload e2e (binário + fluxo sem jogo em primeiro plano via gamelist.txt)
BRAIN_DIR=""
if [ -d "$ROOT/fusao-brain" ]; then
  BRAIN_DIR="$ROOT/fusao-brain"
elif [ -d "$ROOT/_historico/fusao-brain" ]; then
  BRAIN_DIR="$ROOT/_historico/fusao-brain"
fi
if [ -n "$BRAIN_DIR" ] && command -v cargo >/dev/null 2>&1; then
  (cd "$BRAIN_DIR" && cargo build 2>/dev/null)
  mkdir -p "$FAKE/predata/data/com.jogo"
  echo conteudo > "$FAKE/predata/data/com.jogo/a.dat"
  mkdir -p "$FAKE/predata/media/0/Android/data/com.jogo"
  echo mais > "$FAKE/predata/media/0/Android/data/com.jogo/b.dat"
  "$BRAIN_DIR/target/debug/fusaod" preload com.jogo "$FAKE/predata" | grep -q "files=2" && ok "preload e2e" || bad "preload e2e"
  mkdir -p "$FAKE/packbase/preload"
  _sz=$(wc -c < "$FAKE/predata/data/com.jogo/a.dat")
  _mt=$(stat -c %Y "$FAKE/predata/data/com.jogo/a.dat" 2>/dev/null || stat -f %m "$FAKE/predata/data/com.jogo/a.dat")
  printf "%s %s %s\n" "$_mt" "$_sz" "$FAKE/predata/data/com.jogo/a.dat" > "$FAKE/packbase/preload/com_jogo.pack"
  FUSAO_PACK_BASE="$FAKE/packbase" "$BRAIN_DIR/target/debug/fusaod" preload com.jogo "$FAKE/predata" | grep -q "files=1" && ok "preload replay e2e" || bad "preload replay e2e"
else
  strings "$STAGE/system/bin/fusaod" | grep -q "preload ok pkg=" && ok "preload e2e (elf-sym)" || bad "preload e2e (elf-sym)"
  strings "$STAGE/system/bin/fusaod" | grep -q "preload_replay" && ok "preload replay e2e (elf-sym)" || bad "preload replay e2e (elf-sym)"
fi

M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload on | grep -q "preload=on" && ok "cli preload on" || bad "cli preload on"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload off | grep -q "preload=off" && ok "cli preload off" || bad "cli preload off"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "packs: nenhum" && ok "cli preload status" || bad "cli preload status"

# 14a) Preload sem jogo em primeiro plano (game-active.txt vazio, usando gamelist.txt como fonte de verdade)
: > "$FAKE/fusao/game-active.txt"
mkdir -p "$FAKE/predata/data/data/com.roblox.client" "$FAKE/predata/data/media/0/Android/obb/com.hottagames.nte"
echo "asset_roblox_1" > "$FAKE/predata/data/data/com.roblox.client/main.pak"
echo "asset_nte_1" > "$FAKE/predata/data/media/0/Android/obb/com.hottagames.nte/main.obb"
grep -Fxq "com.hottagames.nte" "$FAKE/fusao/gamelist.txt" || echo "com.hottagames.nte" >> "$FAKE/fusao/gamelist.txt"

PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "eligible_count=2" && ok "preload eligible discovery" || bad "preload eligible discovery"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload prepare | grep -q "preload-prepare ok prepared=2/2" && ok "preload prepare without fg game" || bad "preload prepare without fg game"
[ -s "$FAKE/fusao/preload/com_roblox_client.pack" ] && [ -s "$FAKE/fusao/preload/com_hottagames_nte.pack" ] && ok "preload packs created" || bad "preload packs created"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload now | grep -q "preload ok pkg=" && ok "preload now without fg game" || bad "preload now without fg game"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "preload_state=executed" && ok "preload state executed" || bad "preload state executed"
PRELOAD_DATA_ROOT="$FAKE/predata" PRELOAD_MEM_AVAIL_KB=400000 M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload now com.roblox.client | grep -q "reason=low_ram" && ok "preload low-ram gate" || bad "preload low-ram gate"

M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" status | grep -q "atividade dos ganchos" && ok "cli status hooks" || bad "cli status hooks"

# 14b) killer respeita pausa (marker = dorme sem matar)
cp "$STAGE/fusao/gos_killer.sh" "$FAKE/fusao/" 2>/dev/null
: > "$FAKE/fusao/gos_killer.paused"
M54_DIR="$FAKE/fusao" timeout 8 sh "$FAKE/fusao/gos_killer.sh" >/dev/null 2>&1
grep -q "gos killer: pausado" "$FAKE/fusao/fusao.log" && ok "killer pause" || bad "killer pause"
rm -f "$FAKE/fusao/gos_killer.paused"

# 15) dumpsys parser (formato real)
cat > "$FAKE/dump.txt" <<'EOF'
mCurrentFocus=Window{abc u0 com.roblox.client/com.roblox.client.Activity}
EOF
export DUMPSYS_FAKE="$FAKE/dump.txt"
unset FOCUS_FAKE
_got=$(grep -oE "[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+" "$FAKE/dump.txt" | head -1)
[ "$_got" = "com.roblox.client" ] && ok "watch dumpsys parse" || bad "watch dumpsys parse ($_got)"

echo "----"
echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
