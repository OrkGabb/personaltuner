#!/system/bin/sh
# fusao-bugreport.sh — comando único de bugreport.
# Gera tar.gz em /sdcard/Download/fusao-bugreport-<data>.tar.gz com:
#  fusao.log, fusao.log.1, fusaod.log, config, profile, gamelist.txt,
#  game-active, framework.stock, tune.stock, display.stock, stock.snapshot,
#  default_cpu_gov, thermal_guard_state, fas.log/.1, fas.targets, fas.mode, fas-games.toml,
#  getprop, activity-settings.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

OUTDIR="${OUTDIR:-/sdcard/Download}"
STAMP=$(date '+%Y%m%d-%H%M%S' 2>/dev/null || echo unknown)
OUT="$OUTDIR/fusao-bugreport-$STAMP.tar.gz"
TMPD="/data/local/tmp/fusao-bugreport-$$"
mkdir -p "$TMPD" 2>/dev/null

collect() {
  for _f in fusao.log fusao.log.1 fusaod.log config profile gamelist.txt \
    game-active.txt framework.stock tune.stock display.stock \
    thermal_guard_state stock.snapshot default_cpu_gov dnd.state \
    fas.log fas.log.1 fas.targets fas.mode; do
    [ -f "$M54_DIR/$_f" ] && cp -f "$M54_DIR/$_f" "$TMPD/$_f" 2>/dev/null
  done
  cp -f "$M54_DIR/fas/games.toml" "$TMPD/fas-games.toml" 2>/dev/null
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
