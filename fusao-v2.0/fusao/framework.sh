#!/system/bin/sh
# fusao-framework.sh — constantes do ActivityManager × Game/Balanced/Battery
# + display Samsung-safe (One UI 8.5 / Android 16). As chaves job_scheduler/app_standby das antigas tríades
# não são mais gravadas (o Android 16 as ignora); só são devolvidas/apagadas no restore e na purga.
# Display: settings peak/min/user_refresh_rate.
# Sem game_default_frame_rate=1.0 (float inválido no GameManagerService do Android 16
# que causava dessincronização de estado durante o loading do NTE).
# Reversível: idle restaura ROM default (valores capturados no 1º apply).
# Lojas fake p/ teste offline: FAKE_SETTINGS_DIR (arquivos namespace__key).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

STOCK_F="$M54_DIR/framework.stock"
MODE="${1:-auto}"

# Constantes do ActivityManager por perfil (a única chave das antigas "tríades" que o Android 16 lê).
# No perfil game, power_check e background_settle_time ficam folgados para que o ActivityManager/CFMS
# não sufoque players de música/áudio e EQ ativos em segundo plano durante jogos.
# 2026-11-14 (M-1), conferido no aparelho com o balanced aplicado:
#  - service_restart_duration SAIU dos três perfis. O ActivityManager lia o valor (dumpsys activity settings:
#    service_restart_duration=3600000; game pedia 24 h, battery 30 min; padrão da ROM = 1 s): um serviço sticky
#    morto pelo LMK só voltava depois desse tempo — o serviço de reprodução de um player em segundo plano, e o
#    Speaker Boost do próprio app, ficavam mudos até 1 h.
#  - job_scheduler_constants e app_standby_constants NÃO são mais gravados: o Android 16 ignora os dois
#    (dumpsys jobscheduler: min_ready_non_active_jobs_count=5 com a tríade pedindo 16; dumpsys usagestats
#    appstandby: mStrongUsageTimeoutMillis=+5m com a tríade pedindo 20 s) e cada um custava um `settings put`
#    (um app_process) por apply.
TRIAD_AM_GAME="service_reset_run_duration=120000,background_settle_time=30000,power_check_max_cpu_1=25,power_check_max_cpu_2=35,content_provider_retain_time=10000"
TRIAD_AM_BATTERY="service_reset_run_duration=60000,background_settle_time=15000,power_check_max_cpu_1=10,power_check_max_cpu_2=20,content_provider_retain_time=10000"
TRIAD_AM_BALANCED="service_reset_run_duration=60000,background_settle_time=30000,power_check_max_cpu_1=15,power_check_max_cpu_2=25,content_provider_retain_time=15000"

# Valores que versões anteriores gravavam. Não são mais aplicados; ficam só para o own_triad reconhecê-los
# como "do FUSAO" (snapshot/restore/purga), nunca como padrão da ROM.
TRIAD_AM_GAME_V2131="service_restart_duration=86400000,service_reset_run_duration=120000,background_settle_time=30000,power_check_max_cpu_1=25,power_check_max_cpu_2=35,content_provider_retain_time=10000"
TRIAD_AM_GAME_LEGACY="service_restart_duration=86400000,service_reset_run_duration=120000,background_settle_time=10000,power_check_max_cpu_1=10,power_check_max_cpu_2=20,content_provider_retain_time=10000"
TRIAD_AM_BATTERY_V2131="service_restart_duration=1800000,service_reset_run_duration=60000,background_settle_time=15000,power_check_max_cpu_1=10,power_check_max_cpu_2=20,content_provider_retain_time=10000"
TRIAD_AM_BALANCED_V2131="service_restart_duration=3600000,service_reset_run_duration=60000,background_settle_time=30000,power_check_max_cpu_1=15,power_check_max_cpu_2=25,content_provider_retain_time=15000"
TRIAD_JS_GAME="max_job_count_active=1,min_ready_jobs_count=32,bg_normal_job_count=0,bg_moderate_job_count=0,bg_low_job_count=0,enable_adaptive_scheduling=false,min_ready_cpu_only_jobs_count=32,min_ready_non_active_jobs_count=32,heavy_use_factor=0.95,moderate_use_factor=0.8"
TRIAD_SB_GAME="strong_usage_duration=20000,system_interaction_duration=10000,initial_foreground_service_start_duration=90000,prediction_timeout=60000,sync_adapter_duration=10000"
TRIAD_SB_GAME_LEGACY="strong_usage_duration=10000,system_interaction_duration=5000,initial_foreground_service_start_duration=60000,prediction_timeout=60000,sync_adapter_duration=10000"
TRIAD_JS_BATTERY="max_job_count_active=2,min_ready_jobs_count=8,bg_normal_job_count=1,bg_moderate_job_count=1,enable_adaptive_scheduling=true,min_ready_cpu_only_jobs_count=8,min_ready_non_active_jobs_count=8,heavy_use_factor=0.85,moderate_use_factor=0.7"
TRIAD_SB_BATTERY="strong_usage_duration=30000,system_interaction_duration=10000,initial_foreground_service_start_duration=120000,prediction_timeout=300000,sync_adapter_duration=30000"
TRIAD_JS_BALANCED="max_job_count_active=4,min_ready_jobs_count=16,bg_normal_job_count=2,bg_moderate_job_count=1,enable_adaptive_scheduling=true,min_ready_cpu_only_jobs_count=16,min_ready_non_active_jobs_count=16,heavy_use_factor=0.9,moderate_use_factor=0.75"
TRIAD_SB_BALANCED="strong_usage_duration=20000,system_interaction_duration=10000,initial_foreground_service_start_duration=90000,prediction_timeout=180000,sync_adapter_duration=20000"

# own_triad <valor> — é uma tríade do próprio FUSAO (atual ou de versão anterior). Um framework.stock capturado
# com o FUSAO já aplicado (versão antiga por cima, status.md "framework.stock contaminado") guardava ESTES como
# padrão da ROM e o framework off não voltava ao padrão: no snapshot e no restore eles contam como "sem valor"
# (= delete).
own_triad() {
  case "$1" in
    "$TRIAD_AM_GAME"|"$TRIAD_AM_BATTERY"|"$TRIAD_AM_BALANCED"|\
    "$TRIAD_AM_GAME_V2131"|"$TRIAD_AM_GAME_LEGACY"|"$TRIAD_AM_BATTERY_V2131"|"$TRIAD_AM_BALANCED_V2131"|\
    "$TRIAD_JS_GAME"|"$TRIAD_SB_GAME"|"$TRIAD_SB_GAME_LEGACY"|\
    "$TRIAD_JS_BATTERY"|"$TRIAD_SB_BATTERY"|\
    "$TRIAD_JS_BALANCED"|"$TRIAD_SB_BALANCED") return 0;;
  esac
  return 1
}

# --- backend settings/device_config (real ou fake) ---
sget() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/${1}__${2}" 2>/dev/null
  else
    settings get "$1" "$2" 2>/dev/null
  fi
}
sput() {
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
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  else
    device_config get "$1" "$2" 2>/dev/null
  fi
}

# triad_stock <chave> — o valor atual da chave global, "null" se for tríade do próprio FUSAO
triad_stock() {
  local _v
  _v=$(sget global "$1")
  own_triad "$_v" && _v="null"
  echo "$_v"
}

snapshot_stock() {
  [ -s "$STOCK_F" ] && return 0
  {
    echo "activity_manager_constants=$(triad_stock activity_manager_constants)"
    echo "job_scheduler_constants=$(triad_stock job_scheduler_constants)"
    echo "app_standby_constants=$(triad_stock app_standby_constants)"
    echo "peak_refresh_rate=$(sget system peak_refresh_rate)"
    echo "min_refresh_rate=$(sget system min_refresh_rate)"
    echo "user_refresh_rate=$(sget system user_refresh_rate)"
    echo "dc_game_overlay_game_default_frame_rate=$(dget game_overlay game_default_frame_rate)"
  } | atomic_write "$STOCK_F" 0600
}

restore_stock() {
  [ -s "$STOCK_F" ] || return 0
  _v=$(grep "^activity_manager_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ] || own_triad "$_v"; then sdel global activity_manager_constants; else sput global activity_manager_constants "$_v"; fi
  _v=$(grep "^job_scheduler_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ] || own_triad "$_v"; then sdel global job_scheduler_constants; else sput global job_scheduler_constants "$_v"; fi
  _v=$(grep "^app_standby_constants=" "$STOCK_F" 2>/dev/null | cut -d= -f2-)
  if [ -z "$_v" ] || [ "$_v" = "null" ] || own_triad "$_v"; then sdel global app_standby_constants; else sput global app_standby_constants "$_v"; fi
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

stock_val() {
  grep "^${1}=" "$STOCK_F" 2>/dev/null | cut -d= -f2- | tail -n 1
}

display_hz_pref() {
  grep "^hz=" "$M54_DIR/display.hz" 2>/dev/null | cut -d= -f2- | tail -n 1
}

# Resolve e aplica peak/min_refresh_rate fora de jogo respeitando `fusao display` e `framework.stock`.
apply_outside_refresh_rates() {
  local pref sp sm
  pref=$(display_hz_pref)
  case "$pref" in
    120hz|120|fixed)
      sput system peak_refresh_rate "120.0"
      sput system min_refresh_rate "120.0"
      return 0
      ;;
    60hz|60)
      sput system peak_refresh_rate "60.0"
      sput system min_refresh_rate "60.0"
      return 0
      ;;
    adaptive|auto)
      sput system peak_refresh_rate "120.0"
      sput system min_refresh_rate "60.0"
      return 0
      ;;
  esac
  sp=$(stock_val peak_refresh_rate)
  sm=$(stock_val min_refresh_rate)
  if [ "$sm" = "120.0" ] || [ "$sm" = "120" ]; then
    sput system peak_refresh_rate "120.0"
    sput system min_refresh_rate "120.0"
  else
    # Garante teto 120.0 no modo adaptável fora de jogo (inclusive se stock tinha pico limitado).
    sput system peak_refresh_rate "120.0"
    sput system min_refresh_rate "60.0"
  fi
}

# purge_dead_triads — uma vez por instalação: apaga job_scheduler_constants / app_standby_constants deixados
# por versões até a 2.13.1 (valor igual a uma tríade do FUSAO). Valor da ROM ou do usuário não é tocado.
purge_dead_triads() {
  local _k _v
  [ -f "$M54_DIR/.triad_dead_purged" ] && return 0
  for _k in job_scheduler_constants app_standby_constants; do
    _v=$(sget global "$_k")
    if own_triad "$_v"; then
      sdel global "$_k"
      log "framework: $_k do FUSAO antigo apagado (o Android 16 não lê)"
    fi
  done
  : > "$M54_DIR/.triad_dead_purged" 2>/dev/null
}

apply_triad() {
  # apply_triad <game|balanced|battery>
  purge_dead_triads
  case "$1" in
    game)
      sput global activity_manager_constants "$TRIAD_AM_GAME"
      sput system peak_refresh_rate "120.0"
      if [ -s "$M54_DIR/game-active.txt" ]; then
        sput system min_refresh_rate "120.0"
      else
        apply_outside_refresh_rates
      fi
      # Inteiro válido '0' (sem override forçado de FPS no GameManagerService)
      dput game_overlay "game_default_frame_rate" "0"
      ;;
    battery)
      sput global activity_manager_constants "$TRIAD_AM_BATTERY"
      apply_outside_refresh_rates
      dput game_overlay "game_default_frame_rate" "0"
      ;;
    *) # balanced
      sput global activity_manager_constants "$TRIAD_AM_BALANCED"
      apply_outside_refresh_rates
      dput game_overlay "game_default_frame_rate" "0"
      ;;
  esac
  if [ -f "$DIR/tune.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" "$1" 2>/dev/null
  fi
  log "framework: apply $1"
}

resolve_mode() {
  _m="$MODE"
  if [ "$_m" = "auto" ]; then
    effective_profile
  else
    echo "$_m"
  fi
}

case "$MODE" in
  idle|restore)
    restore_stock
    if [ -f "$DIR/tune.sh" ]; then
      M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" idle 2>/dev/null
    fi
    exit 0;;
  game|balanced|battery) snapshot_stock; apply_triad "$MODE"; exit 0;;
  auto) M=$(resolve_mode); snapshot_stock
    case "$M" in game|balanced|battery) apply_triad "$M";; *) apply_triad balanced;; esac
    exit 0;;
  *) echo "uso: framework.sh [game|balanced|battery|idle|auto]" >&2; exit 1;;
esac
