#!/system/bin/sh
# fusao-tune.sh — sysfs fino por perfil (VM/IO-readahead/TCP).
# Writer ÚNICO destes nós (nada mais toca). Snapshot em tune.stock no 1º apply;
# idle/balanced restauram stock (balanced = stock por definição).
# Chamada via framework.sh (mesma família, mesmo flag framework.enabled).
# FS_ROOT prefixa /proc e /sys (testes offline). Sem FS_ROOT = aparelho.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
STOCK="$M54_DIR/tune.stock"
MODE="${1:-auto}"
GAME_ACTIVE="$M54_DIR/game-active.txt"
PROFILE_F="$M54_DIR/profile"

# --- nós possuídos (leitura p/ snapshot, escrita p/ apply) ---
vm_nodes() {
  echo "$R/proc/sys/vm/swappiness"
  echo "$R/proc/sys/vm/vfs_cache_pressure"
}
readahead_nodes() {
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/read_ahead_kb" ] && echo "$dev/queue/read_ahead_kb"
  done
}
tcp_nodes() {
  echo "$R/proc/sys/net/ipv4/tcp_congestion_control"
  echo "$R/proc/sys/net/ipv4/tcp_low_latency"
  echo "$R/proc/sys/net/ipv4/tcp_fastopen"
}

snapshot_stock() {
  [ -s "$STOCK" ] && return 0
  {
    for n in $(vm_nodes) $(readahead_nodes) $(tcp_nodes); do
      [ -f "$n" ] && echo "$n $(cat "$n" 2>/dev/null)"
    done
  } | atomic_write "$STOCK" 0600
}

stock_of() {
  grep -F "$1 " "$STOCK" 2>/dev/null | cut -d' ' -f2- | tail -1
}

w() {
  # w <path> <value> — no-op onde não há escrita (teste non-root nunca falha)
  [ -w "$1" ] && echo "$2" > "$1" 2>/dev/null
  return 0
}

restore_stock() {
  [ -s "$STOCK" ] || return 0
  while read -r p v; do
    case "$p" in ""|\#*) continue;; esac
    [ -f "$p" ] && w "$p" "$v"
  done <"$STOCK"
  log "tune: restore stock"
}

bbr_if_avail() {
  avail="$R/proc/sys/net/ipv4/tcp_available_congestion_control"
  if grep -qw "bbr" "$avail" 2>/dev/null; then echo "bbr"; else stock_of "$R/proc/sys/net/ipv4/tcp_congestion_control"; fi
}

apply_game() {
  snapshot_stock
  w "$R/proc/sys/vm/swappiness" "60"
  w "$R/proc/sys/vm/vfs_cache_pressure" "50"
  for n in $(readahead_nodes); do w "$n" "512"; done
  b=$(bbr_if_avail); [ -n "$b" ] && w "$R/proc/sys/net/ipv4/tcp_congestion_control" "$b"
  w "$R/proc/sys/net/ipv4/tcp_low_latency" "1"
  w "$R/proc/sys/net/ipv4/tcp_fastopen" "3"
  log "tune: apply game"
}

apply_battery() {
  snapshot_stock
  w "$R/proc/sys/vm/vfs_cache_pressure" "150"
  restore_one "$R/proc/sys/vm/swappiness"
  for n in $(readahead_nodes); do restore_one "$n"; done
  restore_one "$R/proc/sys/net/ipv4/tcp_congestion_control"
  restore_one "$R/proc/sys/net/ipv4/tcp_low_latency"
  restore_one "$R/proc/sys/net/ipv4/tcp_fastopen"
  log "tune: apply battery"
}

restore_one() {
  v=$(stock_of "$1")
  [ -n "$v" ] && w "$1" "$v"
}

resolve_mode() {
  _m="$MODE"
  if [ "$_m" = "auto" ]; then
    if [ -s "$GAME_ACTIVE" ]; then
      _prof="game"
      [ -f "$PROFILE_F" ] && _prof=$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')
      case "$_prof" in battery|balanced|game) echo "$_prof";; *) echo "game";; esac
    else
      echo "balanced"
    fi
  else
    echo "$_m"
  fi
}

case "$MODE" in
  idle|restore|balanced) snapshot_stock; restore_stock; exit 0;;
  game) apply_game; exit 0;;
  battery) apply_battery; exit 0;;
  auto) M=$(resolve_mode)
    case "$M" in game) apply_game;; battery) apply_battery;; *) snapshot_stock; restore_stock;; esac
    exit 0;;
  *) echo "uso: tune.sh [game|balanced|battery|idle|auto]" >&2; exit 1;;
esac
