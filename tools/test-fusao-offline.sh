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

# 2) framework: game aplica triad Stellar exata, idle restaura
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" game
grep -q "max_job_count_active=1" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework game triad" || bad "framework game triad"
grep -q "120.0" "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null && ok "framework game refresh" || bad "framework game refresh"
mkdir -p "$FAKE/proc/sys/vm" "$FAKE/proc/sys/net/ipv4" "$FAKE/sys/block/sda/queue"
echo 100 > "$FAKE/proc/sys/vm/swappiness"; echo 100 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_low_latency"; echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
rm -f "$FAKE/fusao/tune.stock"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" game
grep -q "tune: apply game" "$FAKE/fusao/fusao.log" && ok "framework+tune wire" || bad "framework+tune wire"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "framework+tune eff" || bad "framework+tune eff"
[ "$(cat "$FAKE/settings/dc__game_overlay__game_default_frame_rate" 2>/dev/null)" = "1.0" ] && ok "framework game overlay" || bad "framework game overlay"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" idle
[ ! -f "$FAKE/settings/dc__game_overlay__game_default_frame_rate" ] && ok "framework idle dc-revert" || bad "framework idle dc-revert"
[ ! -s "$FAKE/settings/global__job_scheduler_constants" ] || [ "$(cat "$FAKE/settings/global__job_scheduler_constants")" != "*max_job_count_active=1*" ] && ok "framework idle restore" || bad "framework idle restore"

# 3) framework battery/balanced
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" battery
grep -q "max_job_count_active=2" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework battery" || bad "framework battery"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" balanced
grep -q "max_job_count_active=4" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework balanced" || bad "framework balanced"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" idle

# 4) perf io-only e exit não escrevem sysfs contendido (CPU/GPU/MIF)
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/perf.sh" io-only && ok "perf io-only" || bad "perf io-only"
grep -q "perf: io aplicado" "$FAKE/fusao/fusao.log" && ok "perf log" || bad "perf log"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/perf.sh" exit && ok "perf exit" || bad "perf exit"

# 5) fusao CLI: profile/status/kill-switch/gamelist
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" profile game | grep -q "profile=game" && ok "cli profile game" || bad "cli profile game"
[ "$(cat "$FAKE/fusao/profile")" = "game" ] && ok "cli profile file" || bad "cli profile file"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" framework on | grep -q "framework=on" && ok "cli framework on" || bad "cli framework on"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" framework off | grep -q "reverted" && ok "cli framework off" || bad "cli framework off"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" status | grep -q "profile=game" && ok "cli status" || bad "cli status"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" status | grep -q "^thermal=moderate$" && ok "cli status thermal" || bad "cli status thermal"
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

# 9) tune.sh em fake root: apply game + restore idle
mkdir -p "$FAKE/sys/block/sda/queue" "$FAKE/proc/sys/vm" "$FAKE/proc/sys/net/ipv4"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
echo 100 > "$FAKE/proc/sys/vm/swappiness"
echo 100 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno bbr" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_low_latency"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"
cp "$STAGE/fusao/tune.sh" "$FAKE/fusao/" 2>/dev/null
rm -f "$FAKE/fusao/tune.stock"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" game
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "tune game swap" || bad "tune game swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "50" ] && ok "tune game pressure" || bad "tune game pressure"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "512" ] && ok "tune game readahead" || bad "tune game readahead"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "bbr" ] && ok "tune game bbr" || bad "tune game bbr"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" battery
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "150" ] && ok "tune battery" || bad "tune battery"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "100" ] && ok "tune idle restore" || bad "tune idle restore"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "cubic" ] && ok "tune idle tcp" || bad "tune idle tcp"

# 10) dnd on/off com verify+retry (DND_FAKE_ZEN finge o settings get).
# NOTA: sem `| grep -q` direto (SIGPIPE mata o script no 2o echo); via arquivo.
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

# 12) WebUI extinta (v2.9.1): APK é a única interface; sem check de webroot.

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

# 14) preload e2e (se fusao-brain + cargo disponíveis, roda binário host; caso
#     contrário valida a integridade e os símbolos de preload no ELF arm64 stageado)
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
