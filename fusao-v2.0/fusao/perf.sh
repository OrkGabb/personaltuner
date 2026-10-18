#!/system/bin/sh
# fusao-perf.sh — Executor processo+IO consciente do perfil e seguro para UE5/NTE.
# Regras críticas (validadas na investigação do Neverness to Everness / UE5):
# 1. NUNCA escrever o PID inteiro em /dev/cpuset/top-app/cgroup.procs ou
#    /dev/stune/top-app/cgroup.procs — isso puxa todos os worker pools de
#    descompressão/streaming assíncrono (FAsyncLoadingThread / TaskGraphBP)
#    para os 4 núcleos Cortex-A78 (policy4), causando contenção direta com a
#    GameThread durante estados de streaming/voo. Usar apenas /tasks por TID.
# 2. Priorizar GameThread/UnityMain com nice moderado (-6 no Game, -2 no Balanced,
#    0 no Battery) e executar uma 2ª passagem atrasada (3s) quando a GameThread
#    ainda não nasceu no instante do launch.
# 3. Salvar e restaurar os valores originais de nomerges/add_random ao sair.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
GAMEFILE="$M54_DIR/game-active.txt"
PROFILE_F="$M54_DIR/profile"
IO_STOCK="$M54_DIR/perf.io.stock"
MODE="${1:-enter}"
PKG="${2:-}"

pick_pkg() {
  if [ -n "$PKG" ]; then echo "$PKG"; return; fi
  tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null
}

read_profile() {
  local _p="balanced"
  [ -f "$PROFILE_F" ] && _p=$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')
  case "$_p" in battery|balanced|game) echo "$_p";; *) echo "balanced";; esac
}

snapshot_io() {
  [ -s "$IO_STOCK" ] && return 0
  {
    for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
      [ ! -d "$dev/queue" ] && continue
      [ -f "$dev/queue/nomerges" ] && echo "$dev/queue/nomerges $(cat "$dev/queue/nomerges" 2>/dev/null)"
      [ -f "$dev/queue/add_random" ] && echo "$dev/queue/add_random $(cat "$dev/queue/add_random" 2>/dev/null)"
    done
  } | atomic_write "$IO_STOCK" 0600
}

optimize_io() {
  local prof
  prof=$(read_profile)
  snapshot_io
  local target_nomerges="1"
  [ "$prof" != "game" ] && target_nomerges="0"
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ ! -d "$dev/queue" ] && continue
    [ -w "$dev/queue/add_random" ] && echo 0 > "$dev/queue/add_random" 2>/dev/null
    [ -w "$dev/queue/nomerges" ] && echo "$target_nomerges" > "$dev/queue/nomerges" 2>/dev/null
  done
  for u in "$R"/sys/devices/platform/*.ufs; do
    [ -d "$u" ] || continue
    echo 1 > "$u/clkgate_enable" 2>/dev/null
  done
  log "perf: io aplicado (profile=$prof nomerges=$target_nomerges)"
}

restore_io() {
  if [ -s "$IO_STOCK" ]; then
    while read -r p v; do
      case "$p" in ""|\#*) continue;; esac
      [ -w "$p" ] && echo "$v" > "$p" 2>/dev/null
    done < "$IO_STOCK"
  else
    for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
      [ ! -d "$dev/queue" ] && continue
      [ -w "$dev/queue/nomerges" ] && echo 0 > "$dev/queue/nomerges" 2>/dev/null
      [ -w "$dev/queue/add_random" ] && echo 0 > "$dev/queue/add_random" 2>/dev/null
    done
  fi
}

apply_threads_once() {
  local _pkg="$1" _prof="$2" _found_engine=0
  local _main_nice="-4" _game_nice="-6" _render_nice="-4" _oom="-500"
  case "$_prof" in
    battery)
      # Em PowerSaving, preserva hierarquia stock sem elevar nice/stune
      return 0
      ;;
    balanced)
      _main_nice="-2"
      _game_nice="-4"
      _render_nice="-2"
      _oom="-300"
      ;;
    game|*)
      _main_nice="-4"
      _game_nice="-6"
      _render_nice="-4"
      _oom="-500"
      ;;
  esac

  local _pids pid _tdir _tcomm _tid
  _pids=$(pidof "$_pkg" 2>/dev/null)
  [ -z "$_pids" ] && return 1
  for pid in $_pids; do
    renice -n "$_main_nice" -p "$pid" >/dev/null 2>&1
    if command -v ionice >/dev/null 2>&1; then
      ionice -c 2 -n 2 -p "$pid" >/dev/null 2>&1
    fi
    # Escreve APENAS o TID principal em /tasks (NUNCA em cgroup.procs!)
    [ -w "/dev/stune/top-app/tasks" ] && echo "$pid" > "/dev/stune/top-app/tasks" 2>/dev/null
    [ -w "/dev/cpuset/top-app/tasks" ] && echo "$pid" > "/dev/cpuset/top-app/tasks" 2>/dev/null
    echo "$_oom" > "/proc/$pid/oom_score_adj" 2>/dev/null

    for _tdir in "/proc/$pid/task/"*; do
      [ -r "$_tdir/comm" ] || continue
      IFS= read -r _tcomm < "$_tdir/comm" 2>/dev/null
      _tid="${_tdir##*/}"
      case "$_tcomm" in
        GameThread*|UnityMain*)
          _found_engine=1
          renice -n "$_game_nice" -p "$_tid" >/dev/null 2>&1
          [ -w "/dev/cpuset/top-app/tasks" ] && echo "$_tid" > "/dev/cpuset/top-app/tasks" 2>/dev/null
          [ -w "/dev/stune/top-app/tasks" ] && echo "$_tid" > "/dev/stune/top-app/tasks" 2>/dev/null
          ;;
        RenderThread*|RHIThread*|UnityGfx*|GLThread*|Vulkan*)
          _found_engine=1
          renice -n "$_render_nice" -p "$_tid" >/dev/null 2>&1
          [ -w "/dev/cpuset/top-app/tasks" ] && echo "$_tid" > "/dev/cpuset/top-app/tasks" 2>/dev/null
          [ -w "/dev/stune/top-app/tasks" ] && echo "$_tid" > "/dev/stune/top-app/tasks" 2>/dev/null
          ;;
      esac
    done
  done
  [ "$_found_engine" -eq 1 ] && return 0 || return 2
}

optimize_proc() {
  local _pkg="$1" _prof _rc
  [ -z "$_pkg" ] && return 1
  _prof=$(read_profile)
  apply_threads_once "$_pkg" "$_prof"
  _rc=$?
  # Se a GameThread/UnityMain ainda não nasceu no t=0s do launch (caso típico de UE5/NTE),
  # agenda uma segunda passagem leve após 3s em background.
  if [ "$_rc" -eq 2 ] && [ -z "$PERF_NO_DELAY" ] && [ "$_prof" != "battery" ]; then
    (
      sleep 3
      [ "$(tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null)" = "$_pkg" ] && apply_threads_once "$_pkg" "$_prof"
    ) >/dev/null 2>&1 &
  fi
  log "perf: proc $_pkg aplicado (profile=$_prof)"
}

case "$MODE" in
  enter)
    T=$(pick_pkg)
    [ -z "$T" ] && exit 0
    optimize_io
    optimize_proc "$T"
    ;;
  io-only) optimize_io;;
  exit)
    restore_io
    if [ -n "$PKG" ]; then
      for _p in $(pidof "$PKG" 2>/dev/null); do
        echo "0" > "/proc/$_p/oom_score_adj" 2>/dev/null
        renice -n 0 -p "$_p" >/dev/null 2>&1
      done
    fi
    log "perf: exit (io restaurado)"
    ;;
  *) echo "uso: perf.sh [enter|io-only|exit] [pkg]" >&2; exit 1;;
esac
