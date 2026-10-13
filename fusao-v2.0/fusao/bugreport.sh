#!/system/bin/sh
# fusao-bugreport.sh — comando único de bugreport.
# Gera tar.gz em /sdcard/Download/fusao-bugreport-<data>.tar.gz com:
#  fusao.log, config, profile, game-active, framework.stock, stock.snapshot,
#  default_cpu_gov, current_profile, thermal_guard_state, dmesg? (só leitura).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

OUTDIR="${OUTDIR:-/sdcard/Download}"
STAMP=$(date '+%Y%m%d-%H%M%S' 2>/dev/null || echo unknown)
OUT="$OUTDIR/fusao-bugreport-$STAMP.tar.gz"
TMPD="/data/local/tmp/fusao-bugreport-$$"
mkdir -p "$TMPD" 2>/dev/null

collect() {
  cp -f "$M54_DIR/fusao.log" "$TMPD/fusao.log" 2>/dev/null
  cp -f "$M54_DIR/config" "$TMPD/config" 2>/dev/null
  cp -f "$M54_DIR/profile" "$TMPD/profile" 2>/dev/null
  cp -f "$M54_DIR/game-active.txt" "$TMPD/game-active.txt" 2>/dev/null
  cp -f "$M54_DIR/framework.stock" "$TMPD/framework.stock" 2>/dev/null
  cp -f "$M54_DIR/thermal_guard_state" "$TMPD/thermal_guard_state" 2>/dev/null
  cp -f "$M54_DIR/stock.snapshot" "$TMPD/stock.snapshot" 2>/dev/null
  cp -f "$M54_DIR/default_cpu_gov" "$TMPD/default_cpu_gov" 2>/dev/null
  cp -f "$M54_DIR/profile" "$TMPD/current_profile" 2>/dev/null
  getprop > "$TMPD/getprop.txt" 2>/dev/null
  dumpsys activity settings 2>/dev/null | head -100 > "$TMPD/activity-settings.txt" 2>/dev/null
  echo "$STAMP" > "$TMPD/stamp.txt" 2>/dev/null
}

pack() {
  mkdir -p "$OUTDIR" 2>/dev/null
  tar -czf "$OUT" -C "$TMPD" . 2>/dev/null
  rm -rf "$TMPD" 2>/dev/null
  echo "$OUT"
}

collect
pack
log "bugreport: $OUT"
