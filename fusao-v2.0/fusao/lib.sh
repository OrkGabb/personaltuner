#!/system/bin/sh
# fusao-lib.sh — utilidades shell compartilhadas (log, escrita atômica,
# clamp, config, uptime, registros de PID com liveness, boot_id).
#
# Funções usadas por thermal_guard.sh / thermal_guard_stop.sh e demais
# scripts: log, atomic_write, clamp_int, read_cfg, uptime_s, pid_record_*,
# boot_id, proc_start_s, proc_cmdline.

umask 077

M54_DIR=${M54_DIR:-/data/adb/fusao}
CONFIG="$M54_DIR/config"
LOG="$M54_DIR/fusao.log"

mkdir -p "$M54_DIR" 2>/dev/null
chmod 0700 "$M54_DIR" 2>/dev/null

log() {
  local size
  size=0
  if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG" 2>/dev/null)
    size=$(echo "$size" | tr -d '[:space:]')
  fi
  case "$size" in ''|*[!0-9]*) size=0;; esac
  if [ "$size" -gt 131072 ]; then mv -f "$LOG" "$LOG.1" 2>/dev/null; fi
  echo "$(date '+%H:%M:%S') $*" >> "$LOG" 2>/dev/null
}

# Atomic state/config writer. Call as: producer | atomic_write /path/file [mode].
atomic_write() {
  local dst="$1" mode="${2:-0600}" tmp="$1.tmp.$$"
  cat > "$tmp" || { rm -f "$tmp"; return 1; }
  chmod "$mode" "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
  mv -f "$tmp" "$dst" || { rm -f "$tmp"; return 1; }
}

clamp_int() {
  local v="$1" lo="$2" hi="$3" def="$4"
  case "$v" in ''|*[!0-9-]*) v="$def";; esac
  [ "$v" -lt "$lo" ] 2>/dev/null && v="$lo"
  [ "$v" -gt "$hi" ] 2>/dev/null && v="$hi"
  echo "$v"
}

# read_cfg <key> <default>
read_cfg() {
  local k="$1" def="$2" v
  [ -f "$CONFIG" ] || { echo "$def"; return; }
  v=$(grep -E "^$k=" "$CONFIG" 2>/dev/null | tail -1 | cut -d= -f2-)
  [ -n "$v" ] && echo "$v" || echo "$def"
}

# uptime_s — whole seconds since boot (pure builtin read, zero fork/cut).
uptime_s() {
  local _up _rest
  read -r _up _rest < /proc/uptime 2>/dev/null || return
  echo "${_up%%.*}"
}

# proc_start_s <pid> — seconds since boot at which that process started (field 22 of
# /proc/PID/stat, in clock ticks; Android runs at 100 Hz). -1 when the process is gone.
proc_start_s() {
  local pid="$1" ticks
  [ -r "/proc/$pid/stat" ] || { echo -1; return; }
  ticks=$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)
  case "$ticks" in ''|*[!0-9]*) echo -1; return;; esac
  echo $(( ticks / 100 ))
}

pid_record_write() {
  local file="$1" tag="$2"
  { echo "pid=$$"; echo "boot=$(boot_id)"; echo "start=$(proc_start_s $$)"; echo "tag=$tag"; } |
    atomic_write "$file" 0600
}

pid_record_pid() { grep '^pid=' "$1" 2>/dev/null | cut -d= -f2; }

# proc_cmdline <pid> — the process's argv joined by spaces, empty when it is gone.
# Never point `tr` straight at /proc/PID/cmdline — see upstream war story.
proc_cmdline() { head -c 4096 "/proc/$1/cmdline" 2>/dev/null | tr '\0' ' '; }

pid_record_alive() {
  local file="$1" tag="$2" pid rec_boot rec_start live_start cmd
  [ -f "$file" ] || return 1
  pid=$(pid_record_pid "$file")
  rec_boot=$(grep '^boot=' "$1" 2>/dev/null | cut -d= -f2-)
  rec_start=$(grep '^start=' "$1" 2>/dev/null | cut -d= -f2)
  [ "$rec_boot" = "$(boot_id)" ] || return 1
  live_start=$(proc_start_s "$pid"); [ "$live_start" != -1 ] && [ "$live_start" = "$rec_start" ] || return 1
  cmd=$(proc_cmdline "$pid")
  echo "$cmd" | grep -Fq "$tag"
}

# boot_id — changes on every real reboot (pure builtin read).
boot_id() {
  local _b
  read -r _b < /proc/sys/kernel/random/boot_id 2>/dev/null && echo "$_b"
}

# selected_profile — o perfil escolhido no app/CLI (/data/adb/fusao/profile); inválido = balanced.
selected_profile() {
  local _p="balanced"
  [ -f "$M54_DIR/profile" ] && _p=$(tr -d '[:space:]' < "$M54_DIR/profile" 2>/dev/null)
  case "$_p" in battery|balanced|game) echo "$_p";; *) echo "balanced";; esac
}

# effective_profile — o perfil EM VIGOR, SSOT p/ tune/framework/perf/watch/CLI (regra do dono, 2026-10-18):
#   battery + jogo da gamelist em foco -> game (fora do jogo volta a battery);
#   balanced fica balanced mesmo em jogo (jogos casuais); game é game.
# "Em foco" = game-active.txt, que o observer/watch grava ANTES do fire_enter e zera antes do fire_exit.
effective_profile() {
  local _p _g
  _p=$(selected_profile)
  if [ "$_p" = "battery" ]; then
    _g=$(tr -d '[:space:]' < "$M54_DIR/game-active.txt" 2>/dev/null)
    [ -n "$_g" ] && grep -Fxq "$_g" "$M54_DIR/gamelist.txt" 2>/dev/null && _p="game"
  fi
  echo "$_p"
}
