#!/system/bin/sh
# fusao-display.sh — downscale via `wm size` (feature real, reversível).
# Downscale NÃO é estado de sessão: escolha explícita do usuário, persiste
# até reset/uninstall. Níveis relativos ao stock (snapshot no 1º uso).
# Uso: display.sh [reset|720p|810p|900p|stock] | display.sh (mostra atual)
DIR=$(dirname "$0")
. "$DIR/lib.sh"

MODE="${1:-}"
STOCK_F="$M54_DIR/display.stock"

cur() {
  if [ -n "$WM_FAKE" ] && [ -f "$WM_FAKE" ]; then
    cat "$WM_FAKE" 2>/dev/null
  else
    wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | tail -1
  fi
}

apply_size() {
  if [ -n "$WM_FAKE" ]; then
    printf "%s" "$1" > "$WM_FAKE" 2>/dev/null
  else
    wm size "$1" >/dev/null 2>&1
  fi
}

reset_size() {
  if [ -n "$WM_FAKE" ]; then
    s=$(stock_of); printf "%s" "$s" > "$WM_FAKE" 2>/dev/null
  else
    wm size reset >/dev/null 2>&1
  fi
}

stock_of() {
  grep -E "^stock=" "$STOCK_F" 2>/dev/null | cut -d= -f2- | tail -1
}

snapshot() {
  [ -f "$STOCK_F" ] && return 0
  c=$(cur)
  [ -n "$c" ] && printf "stock=%s" "$c" | atomic_write "$STOCK_F" 0600
}

scale_of() {
  # scale_of <w> <h> <num> <den> — chão par (requisito hw composer)
  w=$(( $1 * $3 / $4 )); h=$(( $2 * $3 / $4 ))
  [ $((w % 2)) -ne 0 ] && w=$((w - 1))
  [ $((h % 2)) -ne 0 ] && h=$((h - 1))
  echo "${w}x${h}"
}

case "$MODE" in
  ""|show) cur; exit 0;;
  reset|stock) snapshot; reset_size; log "display: reset ($(cur))"; exit 0;;
  720p|810p|900p)
    snapshot
    s=$(stock_of); [ -z "$s" ] && { echo "stock desconhecido" >&2; exit 1; }
    sw=$(echo "$s" | cut -dx -f1); sh=$(echo "$s" | cut -dx -f2)
    case "$MODE" in
      720p) t=$(scale_of "$sw" "$sh" 720 1080);;
      810p) t=$(scale_of "$sw" "$sh" 3 4);;
      900p) t=$(scale_of "$sw" "$sh" 5 6);;
    esac
    apply_size "$t"
    printf "mode=%s" "$MODE" | atomic_write "$M54_DIR/display.mode" 0600
    log "display: downscale $MODE -> $t (stock $s)"
    echo "$t"
    ;;
  *) echo "uso: display.sh [show|720p|810p|900p|reset]" >&2; exit 1;;
esac
