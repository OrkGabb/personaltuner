#!/system/bin/sh
# fusao-perf.sh — executor processo+IO (renice/ionice/oom + IO queue).
# Lê SSOT (/data/adb/fusao/game-active.txt), nunca fora dele.
# sysfs contendido (CPU/GPU/MIF) = proibido aqui (é do fusaod).
# Permitido: renice/ionice/oom/top-app tasks (processo) + IO queue
# (nomerges/add_random em sd*/ufs* — nó não disputado, writer único = aqui).
# Uso: perf.sh enter [pkg] | perf.sh exit | perf.sh io-only
DIR=$(dirname "$0")
. "$DIR/lib.sh"

GAMEFILE="$M54_DIR/game-active.txt"
MODE="${1:-enter}"
PKG="${2:-}"

pick_pkg() {
  if [ -n "$PKG" ]; then echo "$PKG"; return; fi
  tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null
}

optimize_io() {
  for dev in /sys/block/mmcblk* /sys/block/sd* /sys/block/nvme*; do
    [ ! -d "$dev/queue" ] && continue
    [ -w "$dev/queue/add_random" ] && echo 0 > "$dev/queue/add_random" 2>/dev/null
    [ -w "$dev/queue/nomerges" ] && echo 2 > "$dev/queue/nomerges" 2>/dev/null
  done
  for u in /sys/devices/platform/*.ufs; do
    [ -d "$u" ] || continue
    echo 1 > "$u/clkgate_enable" 2>/dev/null
  done
  log "perf: io aplicado"
}

optimize_proc() {
  _pkg="$1"
  [ -z "$_pkg" ] && return 1
  _pids=$(pidof "$_pkg" 2>/dev/null)
  [ -z "$_pids" ] && return 1
  for pid in $_pids; do
    renice -n -10 -p "$pid" >/dev/null 2>&1
    if command -v ionice >/dev/null 2>&1; then
      ionice -c 2 -n 0 -p "$pid" >/dev/null 2>&1
    fi
    [ -d "/dev/stune/top-app" ] && echo "$pid" > "/dev/stune/top-app/tasks" 2>/dev/null
    [ -d "/dev/cpuset/top-app" ] && echo "$pid" > "/dev/cpuset/top-app/tasks" 2>/dev/null
    echo "-500" > "/proc/$pid/oom_score_adj" 2>/dev/null
  done
  log "perf: proc $1 aplicado"
}

case "$MODE" in
  enter)
    T=$(pick_pkg)
    [ -z "$T" ] && exit 0
    optimize_io
    optimize_proc "$T"
    ;;
  io-only) optimize_io;;
  exit) log "perf: exit (sem revert: renice/oom são transitórios)";;
  *) echo "uso: perf.sh [enter|io-only|exit] [pkg]" >&2; exit 1;;
esac
