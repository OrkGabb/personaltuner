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
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "999000" ] && ok "tune game p0_min" || bad "tune game p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "889000" ] && ok "tune game p4_min" || bad "tune game p4_min"
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
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune balanced p0_min" || bad "tune balanced p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "533000" ] && ok "tune balanced p4_min" || bad "tune balanced p4_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_min_clock")" = "221000" ] && ok "tune balanced gpu_min" || bad "tune balanced gpu_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "858000" ] && ok "tune balanced gpu_max" || bad "tune balanced gpu_max"
[ "$(cat "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq")" = "421000" ] && ok "tune balanced mif_min" || bad "tune balanced mif_min"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "120" ] && ok "tune balanced swap" || bad "tune balanced swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "100" ] && ok "tune balanced pressure" || bad "tune balanced pressure"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "256" ] && ok "tune balanced readahead" || bad "tune balanced readahead"
[ "$(cat "$FAKE/sys/block/sda/queue/nomerges")" = "0" ] && ok "tune balanced nomerges-clean" || bad "tune balanced nomerges-clean"

# 9c) Transição BALANCED -> BATTERY / PowerSaving (tetos reduzidos + piso 400MHz em P0)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile battery > "$FAKE/prof_bat.out"
grep -q "profile_state=applied" "$FAKE/prof_bat.out" && ok "profile battery verified" || bad "profile battery verified"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "400000" ] && ok "tune battery p0_min" || bad "tune battery p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "1728000" ] && ok "tune battery p0_max" || bad "tune battery p0_max"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1440000" ] && ok "tune battery p4_max" || bad "tune battery p4_max"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "650000" ] && ok "tune battery gpu_max" || bad "tune battery gpu_max"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "150" ] && ok "tune battery" || bad "tune battery"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "128" ] && ok "tune battery readahead" || bad "tune battery readahead"

# 9d) Restauração completa ao stock via idle
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "150" ] && ok "tune idle restore" || bad "tune idle restore"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "cubic" ] && ok "tune idle tcp" || bad "tune idle tcp"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1824000" ] && ok "tune idle p4_max restore" || bad "tune idle p4_max restore"

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
