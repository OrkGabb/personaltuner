#!/system/bin/sh
# fusao-framework.sh — triads activity/job/standby × GameActive/Balanced/Battery
# + display Samsung-safe.
# Display: settings peak/min/user_refresh_rate (provado OneUI) + device_config
# game_overlay. SEM service call SF 1035 (código AOSP, falha mudo na OneUI).
# Reversível: idle restaura ROM default (valores capturados no 1º apply).
# Lojas fake p/ teste offline: FAKE_SETTINGS_DIR (arquivos namespace__key).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

GAME_ACTIVE="$M54_DIR/game-active.txt"
PROFILE_F="$M54_DIR/profile"
STOCK_F="$M54_DIR/framework.stock"
MODE="${1:-auto}"

# --- backend settings/device_config (real ou fake) ---
sget() {
  # sget <namespace> <key>
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings get "$1" "$2" 2>/dev/null
  fi
}
sput() {
  # sput <namespace> <key> <value>
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    mkdir -p "$FAKE_SETTINGS_DIR" 2>/dev/null
    printf "%s" "$3" > "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings put "$1" "$2" "$3" >/dev/null 2>&1
  fi
}
sdel() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    rm -f "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings delete "$1" "$2" >/dev/null 2>&1
  fi
}
dput() {
  # dput <namespace> <key> <value>  (device_config)
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    mkdir -p "$FAKE_SETTINGS_DIR" 2>/dev/null
    printf "%s" "$3" > "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  else
    device_config put "$1" "$2" "$3" >/dev/null 2>&1
  fi
}
ddel() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    rm -f "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  else
    device_config delete "$1" "$2" >/dev/null 2>&1
  fi
}
dget() {
  # dget <namespace> <key>
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  else
    device_config get "$1" "$2" 2>/dev/null
  fi
}

snapshot_stock() {
  [ -s "$STOCK_F" ] && return 0
  {
    echo "activity_manager_constants=$(sget global activity_manager_constants)"
    echo "job_scheduler_constants=$(sget global job_scheduler_constants)"
    echo "app_standby_constants=$(sget global app_standby_constants)"
    echo "peak_refresh_rate=$(sget system peak_refresh_rate)"
    echo "min_refresh_rate=$(sget system min_refresh_rate)"
    echo "user_refresh_rate=$(sget system user_refresh_rate)"
    echo "dc_game_overlay_game_default_frame_rate=$(dget game_overlay game_default_frame_rate)"
  } | atomic_write "$STOCK_F" 0600
}

restore_stock() {
  [ -s "$STOCK_F" ] || return 0
  _v=$(grep "^activity_manager_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel global activity_manager_constants; else sput global activity_manager_constants "$_v"; fi
  _v=$(grep "^job_scheduler_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel global job_scheduler_constants; else sput global job_scheduler_constants "$_v"; fi
  _v=$(grep "^app_standby_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel global app_standby_constants; else sput global app_standby_constants "$_v"; fi
  _v=$(grep "^peak_refresh_rate=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel system peak_refresh_rate; else sput system peak_refresh_rate "$_v"; fi
  _v=$(grep "^min_refresh_rate=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel system min_refresh_rate; else sput system min_refresh_rate "$_v"; fi
  _v=$(grep "^user_refresh_rate=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then sdel system user_refresh_rate; else sput system user_refresh_rate "$_v"; fi
  _v=$(grep "^dc_game_overlay_game_default_frame_rate=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ]; then ddel game_overlay game_default_frame_rate; else dput game_overlay game_default_frame_rate "$_v"; fi
  log "framework: restore stock"
}

apply_triad() {
  # apply_triad <game|balanced|battery>
  case "$1" in
    game)
      sput global activity_manager_constants "service_restart_duration=86400000,service_reset_run_duration=120000,background_settle_time=10000,power_check_max_cpu_1=5,power_check_max_cpu_2=15,content_provider_retain_time=5000"
      sput global job_scheduler_constants "max_job_count_active=1,min_ready_jobs_count=32,bg_normal_job_count=0,bg_moderate_job_count=0,bg_low_job_count=0,enable_adaptive_scheduling=false,min_ready_cpu_only_jobs_count=32,min_ready_non_active_jobs_count=32,heavy_use_factor=0.95,moderate_use_factor=0.8"
      sput global app_standby_constants "strong_usage_duration=10000,system_interaction_duration=5000,initial_foreground_service_start_duration=60000,prediction_timeout=60000,sync_adapter_duration=10000"
      sput system peak_refresh_rate "120.0"
      sput system min_refresh_rate "120.0"
      dput game_overlay "game_default_frame_rate" "1.0"
      ;;
    battery)
      sput global activity_manager_constants "service_restart_duration=1800000,service_reset_run_duration=60000,background_settle_time=15000,power_check_max_cpu_1=10,power_check_max_cpu_2=20,content_provider_retain_time=10000"
      sput global job_scheduler_constants "max_job_count_active=2,min_ready_jobs_count=8,bg_normal_job_count=1,bg_moderate_job_count=1,enable_adaptive_scheduling=true,min_ready_cpu_only_jobs_count=8,min_ready_non_active_jobs_count=8,heavy_use_factor=0.85,moderate_use_factor=0.7"
      sput global app_standby_constants "strong_usage_duration=30000,system_interaction_duration=10000,initial_foreground_service_start_duration=120000,prediction_timeout=300000,sync_adapter_duration=30000"
      sput system peak_refresh_rate "60.0"
      sput system min_refresh_rate "60.0"
      dput game_overlay "game_default_frame_rate" "0"
      ;;
    *) # balanced
      sput global activity_manager_constants "service_restart_duration=3600000,service_reset_run_duration=60000,background_settle_time=30000,power_check_max_cpu_1=15,power_check_max_cpu_2=25,content_provider_retain_time=15000"
      sput global job_scheduler_constants "max_job_count_active=4,min_ready_jobs_count=16,bg_normal_job_count=2,bg_moderate_job_count=1,enable_adaptive_scheduling=true,min_ready_cpu_only_jobs_count=16,min_ready_non_active_jobs_count=16,heavy_use_factor=0.9,moderate_use_factor=0.75"
      sput global app_standby_constants "strong_usage_duration=20000,system_interaction_duration=10000,initial_foreground_service_start_duration=90000,prediction_timeout=180000,sync_adapter_duration=20000"
      sput system peak_refresh_rate "120.0"
      sput system min_refresh_rate "60.0"
      dput game_overlay "game_default_frame_rate" "0"
      ;;
  esac
  if [ -x "$DIR/tune.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" "$1" 2>/dev/null
  fi
  log "framework: apply $1"
}

resolve_mode() {
  _m="$MODE"
  if [ "$_m" = "auto" ]; then
    if [ -s "$GAME_ACTIVE" ]; then
      _prof="game"
      [ -f "$PROFILE_F" ] && _prof=$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')
      case "$_prof" in battery|balanced|game) echo "$_prof";; *) echo "game";; esac
    else
      _prof="balanced"
      [ -f "$PROFILE_F" ] && _prof=$(cat "$PROFILE_F" 2>/dev/null | tr -d '[:space:]')
      case "$_prof" in battery|balanced|game) echo "$_prof";; *) echo "balanced";; esac
    fi
  else
    echo "$_m"
  fi
}

case "$MODE" in
  idle|restore)
    restore_stock
    if [ -x "$DIR/tune.sh" ]; then
      M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" idle 2>/dev/null
    fi
    exit 0;;
  game|balanced|battery) snapshot_stock; apply_triad "$MODE"; exit 0;;
  auto) M=$(resolve_mode); snapshot_stock
    case "$M" in game|balanced|battery) apply_triad "$M";; *) apply_triad balanced;; esac
    exit 0;;
  *) echo "uso: framework.sh [game|balanced|battery|idle|auto]" >&2; exit 1;;
esac
