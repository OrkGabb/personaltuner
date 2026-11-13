#!/system/bin/sh
# fusao-display.sh — downscale via `wm size` e taxa de atualização (Hz) persistente, reversível.
# Downscale e preferência de Hz NÃO são estado de sessão: escolha explícita do usuário, persistem
# até reset/uninstall. Níveis de resolução relativos ao stock (snapshot no 1º uso).
# Uso: display.sh [show|720p|810p|900p|reset|adaptive|120hz|60hz|hz-reset|hz [show|adaptive|120hz|60hz|reset]]
DIR=$(dirname "$0")
. "$DIR/lib.sh"

MODE="${1:-}"
ARG2="${2:-}"
STOCK_F="$M54_DIR/display.stock"
HZ_F="$M54_DIR/display.hz"
STOCK_FW="$M54_DIR/framework.stock"

cur() {
  if [ -n "$WM_FAKE" ] && [ -f "$WM_FAKE" ]; then
    cat "$WM_FAKE" 2>/dev/null
  else
    wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | tail -1
  fi
}

sget_d() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings get "$1" "$2" 2>/dev/null
  fi
}

sput_d() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    mkdir -p "$FAKE_SETTINGS_DIR" 2>/dev/null
    printf "%s" "$3" > "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings put "$1" "$2" "$3" >/dev/null 2>&1
  fi
}

hz_pref() {
  grep "^hz=" "$HZ_F" 2>/dev/null | cut -d= -f2- | tail -n 1
}

apply_hz() {
  local want="$1" sp sm
  case "$want" in
    adaptive|auto)
      sput_d system peak_refresh_rate "120.0"
      sput_d system min_refresh_rate "60.0"
      printf "hz=adaptive\n" | atomic_write "$HZ_F" 0600
      log "display: hz adaptive (peak=120.0 min=60.0)"
      echo "hz=adaptive peak=120.0 min=60.0"
      ;;
    120hz|120|fixed)
      sput_d system peak_refresh_rate "120.0"
      sput_d system min_refresh_rate "120.0"
      printf "hz=120hz\n" | atomic_write "$HZ_F" 0600
      log "display: hz 120hz fixo (peak=120.0 min=120.0)"
      echo "hz=120hz peak=120.0 min=120.0"
      ;;
    60hz|60)
      sput_d system peak_refresh_rate "60.0"
      sput_d system min_refresh_rate "60.0"
      printf "hz=60hz\n" | atomic_write "$HZ_F" 0600
      log "display: hz 60hz (peak=60.0 min=60.0)"
      echo "hz=60hz peak=60.0 min=60.0"
      ;;
    reset|hz-reset|stock)
      rm -f "$HZ_F" 2>/dev/null
      sp=$(grep "^peak_refresh_rate=" "$STOCK_FW" 2>/dev/null | cut -d= -f2- | tail -n 1)
      sm=$(grep "^min_refresh_rate=" "$STOCK_FW" 2>/dev/null | cut -d= -f2- | tail -n 1)
      if [ "$sm" = "120.0" ] || [ "$sm" = "120" ]; then
        sput_d system peak_refresh_rate "120.0"
        sput_d system min_refresh_rate "120.0"
      elif [ "$sp" = "60.0" ] && [ "$sm" = "60.0" ]; then
        sput_d system peak_refresh_rate "60.0"
        sput_d system min_refresh_rate "60.0"
      else
        sput_d system peak_refresh_rate "120.0"
        sput_d system min_refresh_rate "60.0"
      fi
      log "display: hz reset (peak=$(sget_d system peak_refresh_rate) min=$(sget_d system min_refresh_rate))"
      echo "hz=reset peak=$(sget_d system peak_refresh_rate) min=$(sget_d system min_refresh_rate)"
      ;;
    ""|show)
      echo "hz=$(hz_pref) peak=$(sget_d system peak_refresh_rate) min=$(sget_d system min_refresh_rate)"
      ;;
    *)
      echo "uso: display.sh hz [show|adaptive|120hz|60hz|reset]" >&2
      exit 1
      ;;
  esac
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
  [ -s "$STOCK_F" ] && return 0
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
  reset|stock)
    snapshot
    reset_size
    rm -f "$M54_DIR/display.mode" 2>/dev/null
    log "display: reset ($(cur))"
    exit 0
    ;;
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
  adaptive|120hz|60hz|hz-reset)
    apply_hz "$MODE"
    ;;
  hz-show)
    apply_hz show
    ;;
  hz)
    apply_hz "${ARG2:-show}"
    ;;
  *) echo "uso: display.sh [show|720p|810p|900p|reset|adaptive|120hz|60hz|hz-reset|hz ...]" >&2; exit 1;;
esac
