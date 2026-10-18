#!/system/bin/sh
# fusao-tune.sh — Motor unificado de perfis de hardware (CPU/GPU/MIF/INT/DISP/VM/IO/TCP)
# com restauração limpa entre transições e verificação real por readback.
# Alvo: Samsung Galaxy M54 (SM-M546B), Exynos 1380 (s5e8835), kernel 5.15.189.
# FS_ROOT prefixa /proc e /sys para testes offline determinísticos.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
STOCK="$M54_DIR/tune.stock"
STATE_F="$M54_DIR/profile.state"
MODE="${1:-auto}"
GAME_ACTIVE="$M54_DIR/game-active.txt"
PROFILE_F="$M54_DIR/profile"

# Nós de CPU / GPU / Devfreq do Exynos 1380 (validados em nodes-m54.md)
P0_MIN="$R/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
P0_MAX="$R/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
P4_MIN="$R/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
P4_MAX="$R/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
GPU_MIN="$R/sys/kernel/gpu/gpu_min_clock"
GPU_MAX="$R/sys/kernel/gpu/gpu_max_clock"
MIF_MIN="$R/sys/class/devfreq/17000010.devfreq_mif/min_freq"
MIF_MAX="$R/sys/class/devfreq/17000010.devfreq_mif/max_freq"
INT_MIN="$R/sys/class/devfreq/17000020.devfreq_int/min_freq"
DISP_MIN="$R/sys/class/devfreq/17000040.devfreq_disp/min_freq"

hw_nodes() {
  for _n in "$P0_MIN" "$P0_MAX" "$P4_MIN" "$P4_MAX" "$GPU_MIN" "$GPU_MAX" "$MIF_MIN" "$MIF_MAX" "$INT_MIN" "$DISP_MIN"; do
    [ -f "$_n" ] && echo "$_n"
  done
}

vm_nodes() {
  echo "$R/proc/sys/vm/swappiness"
  echo "$R/proc/sys/vm/vfs_cache_pressure"
  echo "$R/proc/sys/vm/stat_interval"
  echo "$R/proc/sys/vm/page-cluster"
}

readahead_nodes() {
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/read_ahead_kb" ] && echo "$dev/queue/read_ahead_kb"
  done
}

io_queue_nodes() {
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && echo "$dev/queue/nomerges"
    [ -f "$dev/queue/add_random" ] && echo "$dev/queue/add_random"
  done
}

tcp_nodes() {
  echo "$R/proc/sys/net/ipv4/tcp_congestion_control"
  echo "$R/proc/sys/net/ipv4/tcp_fastopen"
}

snapshot_stock() {
  [ -s "$STOCK" ] && return 0
  {
    for n in $(hw_nodes) $(vm_nodes) $(readahead_nodes) $(io_queue_nodes) $(tcp_nodes); do
      [ -f "$n" ] && echo "$n $(cat "$n" 2>/dev/null | tr -d '\r')"
    done
  } | atomic_write "$STOCK" 0600
}

stock_of() {
  grep -F "$1 " "$STOCK" 2>/dev/null | cut -d' ' -f2- | tail -1
}

rval() {
  [ -r "$1" ] && cat "$1" 2>/dev/null | tr -d '[:space:]'
}

w() {
  # w <path> <value> — garante 0644 em sysfs quando possível, sem travar em 0444.
  [ -e "$1" ] || return 0
  chmod 0644 "$1" 2>/dev/null
  if [ -w "$1" ]; then
    echo "$2" > "$1" 2>/dev/null
    return $?
  fi
  return 1
}

# Exynos 1380 policy0 (Cortex-A55) exige descida por degraus da tabela real:
# 2002000 1920000 1824000 1728000 1632000 1512000 1416000 1320000 1200000 1104000 999000 889000 768000 672000 533000 400000
set_policy0() {
  local t_min="$1" t_max="$2" c_min c_max _s
  [ -f "$P0_MIN" ] || [ -f "$P0_MAX" ] || return 0
  c_min=$(rval "$P0_MIN"); case "$c_min" in ''|*[!0-9]*) c_min=533000;; esac
  c_max=$(rval "$P0_MAX"); case "$c_max" in ''|*[!0-9]*) c_max=2002000;; esac
  # Se o novo max é maior que o atual, sobe o max primeiro p/ não rejeitar o min
  if [ "$t_max" -gt "$c_max" ] 2>/dev/null; then
    w "$P0_MAX" "$t_max"
  fi
  if [ "$t_min" -lt "$c_min" ] 2>/dev/null; then
    for _s in 1728000 1512000 1320000 1104000 999000 889000 768000 672000 533000 400000; do
      if [ "$_s" -lt "$c_min" ] && [ "$_s" -gt "$t_min" ]; then
        w "$P0_MIN" "$_s"
      fi
    done
  fi
  w "$P0_MIN" "$t_min"
  w "$P0_MAX" "$t_max"
}

# Exynos 1380 policy4 (Cortex-A78):
# 1824000 1728000 1632000 1536000 1440000 1320000 1200000 1104000 999000 889000 768000 672000 533000
set_policy4() {
  local t_min="$1" t_max="$2" c_min c_max _s
  [ -f "$P4_MIN" ] || [ -f "$P4_MAX" ] || return 0
  c_min=$(rval "$P4_MIN"); case "$c_min" in ''|*[!0-9]*) c_min=533000;; esac
  c_max=$(rval "$P4_MAX"); case "$c_max" in ''|*[!0-9]*) c_max=1824000;; esac
  if [ "$t_max" -gt "$c_max" ] 2>/dev/null; then
    w "$P4_MAX" "$t_max"
  fi
  if [ "$t_min" -lt "$c_min" ] 2>/dev/null; then
    for _s in 1632000 1440000 1200000 999000 889000 768000 672000 533000; do
      if [ "$_s" -lt "$c_min" ] && [ "$_s" -gt "$t_min" ]; then
        w "$P4_MIN" "$_s"
      fi
    done
  fi
  w "$P4_MIN" "$t_min"
  w "$P4_MAX" "$t_max"
}

# Mali-G68 MP5: 949000 858000 754000 650000 552000 455000 351000 221000
set_gpu() {
  local t_min="$1" t_max="$2" c_max
  [ -f "$GPU_MIN" ] || [ -f "$GPU_MAX" ] || return 0
  c_max=$(rval "$GPU_MAX"); case "$c_max" in ''|*[!0-9]*) c_max=949000;; esac
  if [ "$t_max" -gt "$c_max" ] 2>/dev/null; then
    w "$GPU_MAX" "$t_max"
    w "$GPU_MIN" "$t_min"
  else
    w "$GPU_MIN" "$t_min"
    w "$GPU_MAX" "$t_max"
  fi
}

restore_one() {
  local v
  v=$(stock_of "$1")
  [ -n "$v" ] && w "$1" "$v"
}

restore_stock() {
  [ -s "$STOCK" ] || return 0
  # Primeiro desce policy0/policy4/gpu com segurança
  local s_p0min s_p0max s_p4min s_p4max s_gmin s_gmax
  s_p0min=$(stock_of "$P0_MIN"); [ -z "$s_p0min" ] && s_p0min=533000
  s_p0max=$(stock_of "$P0_MAX"); [ -z "$s_p0max" ] && s_p0max=2002000
  s_p4min=$(stock_of "$P4_MIN"); [ -z "$s_p4min" ] && s_p4min=533000
  s_p4max=$(stock_of "$P4_MAX"); [ -z "$s_p4max" ] && s_p4max=1824000
  s_gmin=$(stock_of "$GPU_MIN"); [ -z "$s_gmin" ] && s_gmin=221000
  s_gmax=$(stock_of "$GPU_MAX"); [ -z "$s_gmax" ] && s_gmax=949000
  set_policy0 "$s_p0min" "$s_p0max"
  set_policy4 "$s_p4min" "$s_p4max"
  set_gpu "$s_gmin" "$s_gmax"
  while read -r p v; do
    case "$p" in ""|\#*) continue;; esac
    case "$p" in
      "$P0_MIN"|"$P0_MAX"|"$P4_MIN"|"$P4_MAX"|"$GPU_MIN"|"$GPU_MAX") continue;;
    esac
    [ -f "$p" ] && w "$p" "$v"
  done <"$STOCK"
  log "tune: restore stock"
}

bbr_if_avail() {
  local avail="$R/proc/sys/net/ipv4/tcp_available_congestion_control"
  if grep -qw "bbr" "$avail" 2>/dev/null; then
    echo "bbr"
  else
    stock_of "$R/proc/sys/net/ipv4/tcp_congestion_control"
  fi
}

# Verifica por readback se os nós presentes no sistema refletem o perfil alvo
verify_profile() {
  local target="$1" ok_cnt=0 tot_cnt=0 mis=""
  local exp_p0min exp_p0max exp_p4min exp_p4max exp_gmin exp_gmax exp_mif exp_swap exp_vfs exp_ra
  case "$target" in
    game)
      exp_p0min=999000; exp_p0max=2002000
      exp_p4min=889000; exp_p4max=1824000
      exp_gmin=351000;  exp_gmax=949000
      exp_mif=845000
      exp_swap=60; exp_vfs=50; exp_ra=512
      ;;
    battery)
      exp_p0min=400000; exp_p0max=1728000
      exp_p4min=533000; exp_p4max=1440000
      exp_gmin=221000;  exp_gmax=650000
      exp_mif=421000
      exp_swap=160; exp_vfs=150; exp_ra=128
      ;;
    balanced|*)
      target="balanced"
      exp_p0min=533000; exp_p0max=2002000
      exp_p4min=533000; exp_p4max=1824000
      exp_gmin=221000;  exp_gmax=858000
      exp_mif=421000
      exp_swap=120; exp_vfs=100; exp_ra=256
      ;;
  esac

  check_eq() {
    local path="$1" expected="$2" label="$3" cur
    [ -f "$path" ] || return 0
    tot_cnt=$((tot_cnt + 1))
    cur=$(rval "$path")
    if [ "$cur" = "$expected" ]; then
      ok_cnt=$((ok_cnt + 1))
    else
      mis="${mis}${mis:+,}${label}:${cur}!=${expected}"
    fi
  }

  check_ge() {
    local path="$1" expected="$2" label="$3" cur
    [ -f "$path" ] || return 0
    tot_cnt=$((tot_cnt + 1))
    cur=$(rval "$path")
    case "$cur" in ''|*[!0-9]*) mis="${mis}${mis:+,}${label}:?!=ge${expected}"; return 0;; esac
    if [ "$cur" -ge "$expected" ] 2>/dev/null; then
      ok_cnt=$((ok_cnt + 1))
    else
      mis="${mis}${mis:+,}${label}:${cur}<${expected}"
    fi
  }

  if [ "$target" = "battery" ]; then
    check_eq "$P0_MIN" "$exp_p0min" "p0_min"
    check_eq "$P4_MIN" "$exp_p4min" "p4_min"
    check_eq "$GPU_MIN" "$exp_gmin" "gpu_min"
    check_eq "$MIF_MIN" "$exp_mif" "mif_min"
  else
    check_ge "$P0_MIN" "$exp_p0min" "p0_min"
    check_ge "$P4_MIN" "$exp_p4min" "p4_min"
    check_ge "$GPU_MIN" "$exp_gmin" "gpu_min"
    check_ge "$MIF_MIN" "$exp_mif" "mif_min"
  fi
  check_eq "$P0_MAX" "$exp_p0max" "p0_max"
  check_eq "$P4_MAX" "$exp_p4max" "p4_max"
  check_eq "$GPU_MAX" "$exp_gmax" "gpu_max"
  check_eq "$R/proc/sys/vm/swappiness" "$exp_swap" "swappiness"
  check_eq "$R/proc/sys/vm/vfs_cache_pressure" "$exp_vfs" "vfs_pressure"

  local _ra_node
  _ra_node=$(readahead_nodes | head -n 1)
  [ -n "$_ra_node" ] && check_eq "$_ra_node" "$exp_ra" "read_ahead"

  local state="applied"
  if [ "$tot_cnt" -eq 0 ]; then
    # Ambiente sem sysfs/procfs mapeado: valida se o arquivo de perfil foi gravado
    if [ "$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')" = "$target" ]; then
      state="applied"
    else
      state="failed"
    fi
  elif [ "$ok_cnt" -eq "$tot_cnt" ]; then
    state="applied"
  elif [ "$ok_cnt" -gt 0 ]; then
    state="partial"
  else
    state="failed"
  fi

  local p0_s p4_s gpu_s mif_s vm_s io_s summary
  p0_s="$(rval "$P0_MIN")-$(rval "$P0_MAX")"
  p4_s="$(rval "$P4_MIN")-$(rval "$P4_MAX")"
  gpu_s="$(rval "$GPU_MIN")-$(rval "$GPU_MAX")"
  mif_s="$(rval "$MIF_MIN")"
  vm_s="$(rval "$R/proc/sys/vm/swappiness")/$(rval "$R/proc/sys/vm/vfs_cache_pressure")"
  io_s="$( [ -n "$_ra_node" ] && rval "$_ra_node" || echo "-" )k"
  summary="P0:${p0_s} P4:${p4_s} GPU:${gpu_s} MIF:${mif_s} VM:${vm_s} IO:${io_s}"

  {
    echo "profile=$target"
    echo "profile_state=$state"
    echo "profile_verify=${ok_cnt}/${tot_cnt}"
    echo "sysfs_summary=$summary"
    [ -n "$mis" ] && echo "profile_mismatch=$mis"
  } | atomic_write "$STATE_F" 0644

  [ "$state" != "failed" ]
}

apply_game() {
  snapshot_stock
  local p0_min=999000 p4_min=889000 mif_min=845000 int_min=200000
  if [ -s "$GAME_ACTIVE" ]; then
    p0_min=1104000
    p4_min=999000
    mif_min=1014000
    int_min=267000
  fi
  set_policy0 "$p0_min" "2002000"
  set_policy4 "$p4_min" "1824000"
  set_gpu "351000" "949000"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "$mif_min"
  w "$INT_MIN" "$int_min"
  w "$DISP_MIN" "200000"

  w "$R/proc/sys/vm/swappiness" "60"
  w "$R/proc/sys/vm/vfs_cache_pressure" "50"
  w "$R/proc/sys/vm/stat_interval" "10"
  w "$R/proc/sys/vm/page-cluster" "0"

  for n in $(readahead_nodes); do w "$n" "512"; done
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "1"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  local b
  b=$(bbr_if_avail)
  [ -n "$b" ] && w "$R/proc/sys/net/ipv4/tcp_congestion_control" "$b"
  w "$R/proc/sys/net/ipv4/tcp_fastopen" "3"

  verify_profile game
  log "tune: apply game"
}

apply_balanced() {
  snapshot_stock
  local p0_min=533000 p4_min=533000 mif_min=421000
  if [ -s "$GAME_ACTIVE" ]; then
    p0_min=672000
    p4_min=672000
    mif_min=676000
  fi
  set_policy0 "$p0_min" "2002000"
  set_policy4 "$p4_min" "1824000"
  set_gpu "221000" "858000"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "$mif_min"
  w "$INT_MIN" "100000"
  w "$DISP_MIN" "133000"

  w "$R/proc/sys/vm/swappiness" "120"
  w "$R/proc/sys/vm/vfs_cache_pressure" "100"
  w "$R/proc/sys/vm/stat_interval" "5"
  w "$R/proc/sys/vm/page-cluster" "0"

  for n in $(readahead_nodes); do w "$n" "256"; done
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "0"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  restore_one "$R/proc/sys/net/ipv4/tcp_congestion_control"
  w "$R/proc/sys/net/ipv4/tcp_fastopen" "3"

  verify_profile balanced
  log "tune: apply balanced"
}

apply_battery() {
  snapshot_stock
  set_policy0 "400000" "1728000"
  set_policy4 "533000" "1440000"
  set_gpu "221000" "650000"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "421000"
  w "$INT_MIN" "50000"
  w "$DISP_MIN" "66000"

  w "$R/proc/sys/vm/swappiness" "160"
  w "$R/proc/sys/vm/vfs_cache_pressure" "150"
  w "$R/proc/sys/vm/stat_interval" "15"
  w "$R/proc/sys/vm/page-cluster" "0"

  for n in $(readahead_nodes); do w "$n" "128"; done
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "0"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  restore_one "$R/proc/sys/net/ipv4/tcp_congestion_control"
  restore_one "$R/proc/sys/net/ipv4/tcp_fastopen"

  verify_profile battery
  log "tune: apply battery"
}

resolve_mode() {
  local _m="$MODE" _prof="balanced"
  if [ "$_m" = "auto" ]; then
    [ -f "$PROFILE_F" ] && _prof=$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')
    case "$_prof" in
      battery|balanced|game) echo "$_prof";;
      *) [ -s "$GAME_ACTIVE" ] && echo "game" || echo "balanced";;
    esac
  else
    echo "$_m"
  fi
}

case "$MODE" in
  idle|restore)
    snapshot_stock
    restore_stock
    exit 0
    ;;
  game)
    apply_game
    exit 0
    ;;
  balanced)
    apply_balanced
    exit 0
    ;;
  battery)
    apply_battery
    exit 0
    ;;
  verify)
    _t="${2:-$(resolve_mode)}"
    verify_profile "$_t"
    cat "$STATE_F" 2>/dev/null
    exit 0
    ;;
  auto)
    M=$(resolve_mode)
    case "$M" in
      game) apply_game;;
      battery) apply_battery;;
      *) apply_balanced;;
    esac
    exit 0
    ;;
  *)
    echo "uso: tune.sh [game|balanced|battery|idle|verify|auto]" >&2
    exit 1
    ;;
esac
