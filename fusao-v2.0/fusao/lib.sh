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
  [ -f "$LOG" ] && size=$(wc -c < "$LOG" 2>/dev/null)
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

# uptime_s — whole seconds since boot. The clock for "when did we write this prop".
uptime_s() { cut -d' ' -f1 /proc/uptime 2>/dev/null | cut -d. -f1; }

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
  rec_boot=$(grep '^boot=' "$file" 2>/dev/null | cut -d= -f2-)
  rec_start=$(grep '^start=' "$file" 2>/dev/null | cut -d= -f2)
  [ "$rec_boot" = "$(boot_id)" ] || return 1
  live_start=$(proc_start_s "$pid"); [ "$live_start" != -1 ] && [ "$live_start" = "$rec_start" ] || return 1
  cmd=$(proc_cmdline "$pid")
  echo "$cmd" | grep -Fq "$tag"
}

# boot_id — changes on every real reboot.
boot_id() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
