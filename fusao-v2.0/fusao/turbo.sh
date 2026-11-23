#!/system/bin/sh
# fusao-turbo.sh — Sessao turbo single-shot para abertura de jogo no Galaxy M54
# (Exynos 1380 / Mali-G68 MP5 / Android 16 / One UI 8.5).
#
# Cada ajuste aqui foi auditado e comprovado no aparelho com readback e restauracao:
#   1. Limpeza profunda real de RAM (`fusao ramclean now <pkg>`), preservando pagecache.
#   2. Pre-aquecimento limitado (<= 32 MiB) dos caches de shader ja compilados do jogo.
#   3. Perfil `game` temporario (restaura o perfil original do usuario ao sair do jogo).
#   4. Mali-G68 (`/sys/devices/platform/10300000.mali` + `/sys/kernel/gpu`):
#      power_policy=always_on, highspeed_clock=754000, highspeed_load=75,
#      polling_speed=15, dvfs_period=50, gpu_cl_boost_disable=0.
#   5. GameManagerService (Android 16): `cmd game set --fps 0 --downscale disable <pkg>`
#      (modo custom=4 persistente, Scaling=1.0, Fps=0 — comprovado sobreviver ao launch
#      na One UI 8.5, ao contrario de device_config game_overlay que a ROM apaga no launch).
#   6. ADPF HintManager transitorio (`debug.hwui.use_hint_manager=true`) apenas na janela de
#      abertura do processo, restaurado logo apos o processo nascer. O backend de renderizacao
#      permanece no SkiaGL threaded nativo da ROM (mais rapido na Mali-G68 MP5 r38p1).
#
# Sem trocar debug.hwui.renderer, sem reiniciar SurfaceFlinger, sem props placebo, sem apagar shaders.
# Tudo e restaurado em `turbo.sh restore` (chamado pelo watch.sh no exit, cleanup, uninstall, kill-switch).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
STATE_F="$M54_DIR/turbo.state"
CANCEL_F="$M54_DIR/turbo.cancel"
PID_F="$M54_DIR/turbo.launch.pid"
HM_ORIG_F="$M54_DIR/turbo.hm.orig"
MALI_DEV="$R/sys/devices/platform/10300000.mali"
GPU_DIR="$R/sys/kernel/gpu"
MODE="${1:-status}"
PKG="${2:-}"

turbo_cli() {
  local _b
  for _b in "${FUSAO_CLI:-}" "$DIR/../system/bin/fusao" /data/adb/ksu/bin/fusao /data/adb/ap/bin/fusao /data/adb/modules/fusao/system/bin/fusao /system/bin/fusao; do
    [ -n "$_b" ] && [ -x "$_b" ] && { echo "$_b"; return 0; }
  done
  _b=$(command -v fusao 2>/dev/null)
  [ -n "$_b" ] && [ -x "$_b" ] && { echo "$_b"; return 0; }
  return 1
}

# Backend device_config / props (real ou fake p/ testes offline via FAKE_SETTINGS_DIR).
t_dget() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  elif command -v device_config >/dev/null 2>&1; then
    device_config get "$1" "$2" 2>/dev/null
  fi
}

t_dput() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    mkdir -p "$FAKE_SETTINGS_DIR" 2>/dev/null
    printf '%s' "$3" > "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  elif command -v device_config >/dev/null 2>&1; then
    device_config put "$1" "$2" "$3" >/dev/null 2>&1
  fi
}

t_ddel() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    rm -f "$FAKE_SETTINGS_DIR/dc__${1}__${2}" 2>/dev/null
  elif command -v device_config >/dev/null 2>&1; then
    device_config delete "$1" "$2" >/dev/null 2>&1
  fi
}

t_getprop() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    cat "$FAKE_SETTINGS_DIR/prop__${1}" 2>/dev/null
  elif command -v getprop >/dev/null 2>&1; then
    getprop "$1" 2>/dev/null
  fi
}

t_setprop() {
  if [ -n "$FAKE_SETTINGS_DIR" ]; then
    mkdir -p "$FAKE_SETTINGS_DIR" 2>/dev/null
    printf '%s' "$2" > "$FAKE_SETTINGS_DIR/prop__${1}" 2>/dev/null
  elif command -v setprop >/dev/null 2>&1; then
    setprop "$1" "$2" >/dev/null 2>&1
  fi
}

state_get() {
  [ -r "$STATE_F" ] || return 0
  grep -E "^${1}=" "$STATE_F" 2>/dev/null | tail -n 1 | cut -d= -f2-
}

# Extrai politica ativa entre colchetes: "[coarse_demand] always_on" -> "coarse_demand".
read_power_policy() {
  local _raw
  [ -r "$1" ] || return 1
  _raw=$(cat "$1" 2>/dev/null | tr '\n' ' ')
  case "$_raw" in
    *"[always_on]"*) echo "always_on";;
    *"[coarse_demand]"*) echo "coarse_demand";;
    *)
      printf '%s' "$_raw" | sed -n 's/.*\[\([^]]*\)\].*/\1/p'
      ;;
  esac
}

read_trim() {
  [ -r "$1" ] || return 1
  cat "$1" 2>/dev/null | tr -d '\r\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

write_node() {
  local _p="$1" _v="$2"
  [ -w "$_p" ] || return 1
  printf '%s' "$_v" > "$_p" 2>/dev/null
}

# Pre-aquece caches de shader do jogo no pagecache sem ultrapassar 32 MiB totais.
warm_shader_caches() {
  local _pkg="$1" _warmed=0 _bytes=0 _cap=33554432 _f _sz _rem _blocks
  for _f in \
    "$R/data/user_de/0/$_pkg/cache/com.android.skia.shaders_cache" \
    "$R/data/user_de/0/$_pkg/cache/com.android.opengl.shaders_cache" \
    "$R/data/user/0/$_pkg/cache/shadercachevk.bin" \
    "$R/data/user_de/0/$_pkg/code_cache/"*shader* \
    "$R/data/user_de/0/$_pkg/code_cache/"*vk* \
    "$R/data/user/0/$_pkg/code_cache/"*shader* \
    "$R/data/user/0/$_pkg/code_cache/"*vk*; do
    [ -f "$_f" ] && [ -r "$_f" ] || continue
    _sz=$(wc -c < "$_f" 2>/dev/null | tr -d '[:space:]')
    case "$_sz" in ''|0|*[!0-9]*) continue;; esac
    _rem=$((_cap - _bytes))
    [ "$_rem" -gt 0 ] || break
    if [ "$_sz" -gt "$_rem" ]; then
      _blocks=$((_rem / 262144))
      [ "$_blocks" -gt 0 ] || break
      dd if="$_f" of=/dev/null bs=262144 count="$_blocks" >/dev/null 2>&1 && {
        _bytes=$((_bytes + _blocks * 262144))
        _warmed=$((_warmed + 1))
      }
    else
      cat "$_f" > /dev/null 2>&1 && {
        _bytes=$((_bytes + _sz))
        _warmed=$((_warmed + 1))
      }
    fi
  done
  echo "$_warmed"
}

snapshot_turbo() {
  local _pkg="$1" _stamp="$2" _orig_prof _pp _hc _hl _ps _dp _cl _ov _gm
  if [ -s "$STATE_F" ]; then
    # Ja existe sessao turbo ativa: preserva o snapshot stock original e atualiza apenas o jogo/selo.
    local _prev_pkg _prev_ov _prev_gm
    _prev_pkg=$(state_get pkg)
    if [ -n "$_prev_pkg" ] && [ "$_prev_pkg" != "$_pkg" ]; then
      # Devolve modo de jogo do jogo anterior antes de gravar o novo.
      _prev_gm=$(state_get orig_game_mode)
      if command -v cmd >/dev/null 2>&1; then
        cmd game mode "${_prev_gm:-standard}" "$_prev_pkg" >/dev/null 2>&1 || :
      fi
    fi
    _orig_prof=$(state_get orig_profile)
    _pp=$(state_get orig_power_policy)
    _hc=$(state_get orig_highspeed_clock)
    _hl=$(state_get orig_highspeed_load)
    _ps=$(state_get orig_polling_speed)
    _dp=$(state_get orig_dvfs_period)
    _cl=$(state_get orig_cl_boost_disable)
  else
    _orig_prof=$(selected_profile)
    _pp=$(read_power_policy "$MALI_DEV/power_policy" 2>/dev/null)
    _hc=$(read_trim "$MALI_DEV/highspeed_clock" 2>/dev/null)
    _hl=$(read_trim "$MALI_DEV/highspeed_load" 2>/dev/null)
    _ps=$(read_trim "$MALI_DEV/polling_speed" 2>/dev/null)
    _dp=$(read_trim "$MALI_DEV/dvfs_period" 2>/dev/null)
    _cl=$(read_trim "$GPU_DIR/gpu_cl_boost_disable" 2>/dev/null)
  fi
  _ov=$(t_dget game_overlay "$_pkg")
  [ -n "$_ov" ] || _ov="null"
  _gm="standard"
  if command -v cmd >/dev/null 2>&1; then
    _gm=$(cmd game list-modes "$_pkg" 2>/dev/null | sed -n 's/.*current mode:[[:space:]]*\([a-zA-Z0-9_-]*\).*/\1/p' | head -n 1)
    [ -n "$_gm" ] || _gm="standard"
  fi
  {
    echo "pkg=$_pkg"
    echo "stamp=$_stamp"
    echo "orig_profile=${_orig_prof:-balanced}"
    echo "orig_power_policy=$_pp"
    echo "orig_highspeed_clock=$_hc"
    echo "orig_highspeed_load=$_hl"
    echo "orig_polling_speed=$_ps"
    echo "orig_dvfs_period=$_dp"
    echo "orig_cl_boost_disable=$_cl"
    echo "orig_game_overlay=$_ov"
    echo "orig_game_mode=$_gm"
  } | atomic_write "$STATE_F" 0600
}

apply_mali_turbo() {
  local _ok=0 _tot=0
  if [ -e "$MALI_DEV/power_policy" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/power_policy" "always_on" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/highspeed_clock" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/highspeed_clock" "754000" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/highspeed_load" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/highspeed_load" "75" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/polling_speed" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/polling_speed" "15" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/dvfs_period" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/dvfs_period" "50" && _ok=$((_ok + 1))
  fi
  if [ -e "$GPU_DIR/gpu_cl_boost_disable" ]; then
    _tot=$((_tot + 1))
    write_node "$GPU_DIR/gpu_cl_boost_disable" "0" && _ok=$((_ok + 1))
  fi
  echo "${_ok}/${_tot}"
}

set_profile_game_files() {
  printf '%s' "game" > "$M54_DIR/profile" 2>/dev/null
  if [ -f "$M54_DIR/config" ]; then
    grep -v "^profile=" "$M54_DIR/config" > "$M54_DIR/config.tmp" 2>/dev/null &&
      mv -f "$M54_DIR/config.tmp" "$M54_DIR/config" 2>/dev/null
  fi
  echo "profile=game" >> "$M54_DIR/config" 2>/dev/null
}

apply_profile_game_hw() {
  if [ -f "$M54_DIR/framework.enabled" ] && [ -f "$DIR/framework.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" FAKE_SETTINGS_DIR="$FAKE_SETTINGS_DIR" sh "$DIR/framework.sh" game >/dev/null 2>&1
  elif [ -f "$DIR/tune.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" game >/dev/null 2>&1
  fi
}

apply_profile_auto_hw() {
  if [ -f "$M54_DIR/framework.enabled" ] && [ -f "$DIR/framework.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" FAKE_SETTINGS_DIR="$FAKE_SETTINGS_DIR" sh "$DIR/framework.sh" auto >/dev/null 2>&1
  elif [ -f "$DIR/tune.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/tune.sh" auto >/dev/null 2>&1
  fi
}

restore_hm_prop() {
  if [ -f "$HM_ORIG_F" ]; then
    local _oh
    _oh=$(cat "$HM_ORIG_F" 2>/dev/null)
    t_setprop debug.hwui.use_hint_manager "$_oh"
    rm -f "$HM_ORIG_F" 2>/dev/null
  fi
}

cancel_turbo() {
  local _lp=""
  : > "$CANCEL_F" 2>/dev/null
  if [ -r "$PID_F" ]; then
    _lp=$(tr -d '[:space:]' < "$PID_F" 2>/dev/null)
    case "$_lp" in
      ''|*[!0-9]*) _lp="";;
      *) [ "$_lp" != "$$" ] && kill -TERM "$_lp" 2>/dev/null || :;;
    esac
  fi
  sleep 0.15 2>/dev/null || :
  restore_hm_prop
  if [ -s "$STATE_F" ]; then
    restore_turbo
    apply_profile_auto_hw
  fi
  rm -f "$PID_F" "$CANCEL_F" 2>/dev/null
  log "turbo: cancelled by user"
  echo "turbo=cancelled"
  return 0
}

restore_turbo() {
  local _pkg _orig_prof _pp _hc _hl _ps _dp _cl _ov _gm
  [ -s "$STATE_F" ] || return 0
  _pkg=$(state_get pkg)
  _orig_prof=$(state_get orig_profile)
  _pp=$(state_get orig_power_policy)
  _hc=$(state_get orig_highspeed_clock)
  _hl=$(state_get orig_highspeed_load)
  _ps=$(state_get orig_polling_speed)
  _dp=$(state_get orig_dvfs_period)
  _cl=$(state_get orig_cl_boost_disable)
  _ov=$(state_get orig_game_overlay)
  _gm=$(state_get orig_game_mode)

  [ -n "$_pp" ] && write_node "$MALI_DEV/power_policy" "$_pp"
  [ -n "$_hc" ] && write_node "$MALI_DEV/highspeed_clock" "$_hc"
  [ -n "$_hl" ] && write_node "$MALI_DEV/highspeed_load" "$_hl"
  [ -n "$_ps" ] && write_node "$MALI_DEV/polling_speed" "$_ps"
  [ -n "$_dp" ] && write_node "$MALI_DEV/dvfs_period" "$_dp"
  [ -n "$_cl" ] && write_node "$GPU_DIR/gpu_cl_boost_disable" "$_cl"

  if [ -n "$_pkg" ]; then
    if command -v cmd >/dev/null 2>&1; then
      cmd game mode "${_gm:-standard}" "$_pkg" >/dev/null 2>&1 || :
    fi
  fi

  case "$_orig_prof" in
    battery|balanced|game)
      printf '%s' "$_orig_prof" > "$M54_DIR/profile" 2>/dev/null
      if [ -f "$M54_DIR/config" ]; then
        grep -v "^profile=" "$M54_DIR/config" > "$M54_DIR/config.tmp" 2>/dev/null &&
          mv -f "$M54_DIR/config.tmp" "$M54_DIR/config" 2>/dev/null
      fi
      echo "profile=$_orig_prof" >> "$M54_DIR/config" 2>/dev/null
      ;;
  esac

  rm -f "$STATE_F" 2>/dev/null
  log "turbo: restore ok (pkg=${_pkg:-?} profile->${_orig_prof:-?})"
  return 0
}

launch_turbo() {
  local _pkg="$1" _act="" _cli _ram_out="" _ram_delta="0" _ram_avail="" _warm="0" _mali="0/0" _gm_ok="0"
  local _orig_hm _stamp _started=0 _i _wd_s _tb_tmp

  case "$_pkg" in
    ""|*[!A-Za-z0-9_.]*)
      echo "FALHOU: pacote invalido '$_pkg'" >&2
      return 1
      ;;
  esac

  rm -f "$CANCEL_F" 2>/dev/null
  echo "$$" > "$PID_F" 2>/dev/null
  _tb_tmp="$M54_DIR/.tb.$$"
  mkdir -p "$_tb_tmp" 2>/dev/null

  _tb_cleanup() {
    rm -f "$_tb_tmp"/* 2>/dev/null
    rmdir "$_tb_tmp" 2>/dev/null
    rm -f "$PID_F" 2>/dev/null
  }
  _abort_cancelled() {
    restore_hm_prop
    restore_turbo
    apply_profile_auto_hw
    _tb_cleanup
    rm -f "$CANCEL_F" 2>/dev/null
    log "turbo: cancelled pkg=$_pkg"
    echo "turbo cancelled pkg=$_pkg"
    exit 130
  }
  trap '_abort_cancelled' INT TERM

  # Resolve launcher activity (confirma que o jogo esta instalado).
  if command -v cmd >/dev/null 2>&1; then
    _act=$(cmd package resolve-activity --brief --user 0 "$_pkg" 2>/dev/null | tail -n 1 | tr -d '\r')
    case "$_act" in
      */*) ;;
      *) _act=$(cmd package resolve-activity --brief "$_pkg" 2>/dev/null | tail -n 1 | tr -d '\r');;
    esac
  fi
  case "$_act" in
    */*) ;;
    *)
      if command -v pm >/dev/null 2>&1 && [ -z "$(pm path "$_pkg" 2>/dev/null)" ]; then
        _tb_cleanup
        echo "FALHOU: pacote $_pkg nao instalado" >&2
        return 1
      fi
      _act=""
      ;;
  esac

  [ -f "$CANCEL_F" ] && _abort_cancelled

  # Garante o jogo na gamelist para o observer/watch restaurar tudo automaticamente ao sair.
  if [ -f "$M54_DIR/gamelist.txt" ]; then
    grep -Fxq "$_pkg" "$M54_DIR/gamelist.txt" 2>/dev/null || echo "$_pkg" >> "$M54_DIR/gamelist.txt"
  else
    echo "$_pkg" > "$M54_DIR/gamelist.txt" 2>/dev/null
  fi

  # 1+2+3 em PARALELO (~0,6-0,9s em vez de ~6-8s):
  #  - Snapshot + perfil=game + 6 nos Mali (<5ms inline)
  #  - RAM clean rapido (force-stop paralelo + kill-all + zram compact + slab, sem sleep 2s / compact_memory)
  #  - Pre-aquecimento de shaders + GameManager custom (fps=0, downscale=disable)
  _stamp="$(uptime_s)-$$"
  snapshot_turbo "$_pkg" "$_stamp"
  set_profile_game_files
  _mali=$(apply_mali_turbo)

  _cli=$(turbo_cli)
  if [ -n "$_cli" ]; then
    (
      M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" FAKE_SETTINGS_DIR="$FAKE_SETTINGS_DIR" \
        RAMCLEAN_COMPACT_RESIDENTS=0 RAMCLEAN_COMPACT=0 RAMCLEAN_SETTLE_S=0 \
        sh "$_cli" ramclean now "$_pkg" 2>/dev/null > "$_tb_tmp/ram"
    ) &
  fi
  ( warm_shader_caches "$_pkg" > "$_tb_tmp/warm" ) &
  if command -v cmd >/dev/null 2>&1; then
    ( cmd game set --fps 0 --downscale disable "$_pkg" >/dev/null 2>&1 && echo 1 > "$_tb_tmp/gm" || echo 0 > "$_tb_tmp/gm" ) &
  fi
  wait

  [ -f "$CANCEL_F" ] && _abort_cancelled

  if [ -s "$_tb_tmp/ram" ]; then
    _ram_out=$(cat "$_tb_tmp/ram" 2>/dev/null)
    _ram_delta=$(printf '%s\n' "$_ram_out" | sed -n 's/.*delta_mb=\([+-]*[0-9]*\).*/\1/p' | head -n 1)
    _ram_avail=$(printf '%s\n' "$_ram_out" | sed -n 's/.*avail=[0-9]*->\([0-9]*\).*/\1/p' | head -n 1)
  fi
  [ -n "$_ram_delta" ] || _ram_delta="0"
  [ -s "$_tb_tmp/warm" ] && _warm=$(tr -d '[:space:]' < "$_tb_tmp/warm" 2>/dev/null)
  [ -n "$_warm" ] || _warm="0"
  [ -s "$_tb_tmp/gm" ] && _gm_ok=$(tr -d '[:space:]' < "$_tb_tmp/gm" 2>/dev/null)
  [ -n "$_gm_ok" ] || _gm_ok="0"

  # 4) Injecao transitoria de ADPF HintManager apenas no launch (mantem SkiaGL threaded stock).
  _orig_hm=$(t_getprop debug.hwui.use_hint_manager)
  printf '%s' "$_orig_hm" > "$HM_ORIG_F" 2>/dev/null
  t_setprop debug.hwui.use_hint_manager "true"

  # Ultima checagem de cancelamento imediatamente antes do am start (garante nunca abrir se cancelou).
  [ -f "$CANCEL_F" ] && _abort_cancelled

  if [ -n "$_act" ] && command -v am >/dev/null 2>&1; then
    if am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$_act" >/dev/null 2>&1; then
      _started=1
    fi
  fi
  if [ "$_started" -eq 0 ] && command -v monkey >/dev/null 2>&1; then
    monkey -p "$_pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && _started=1
  fi

  # Aplica framework/tune game sem atrasar a abertura da janela (em testes offline roda sincrono).
  if [ "$_started" -eq 1 ]; then
    if [ -n "$FAKE_SETTINGS_DIR" ]; then
      apply_profile_game_hw
    else
      ( apply_profile_game_hw ) >/dev/null 2>&1 &
    fi
  fi

  # Aguarda brevemente o processo nascer para o RenderThread ler use_hint_manager e devolve o prop global.
  if [ "$_started" -eq 1 ] && [ "${TURBO_HWUI_WAIT_S:-0.35}" != "0" ]; then
    _i=0
    while [ "$_i" -lt 6 ]; do
      command -v pidof >/dev/null 2>&1 && pidof "$_pkg" >/dev/null 2>&1 && break
      sleep 0.15 2>/dev/null || sleep 1
      _i=$((_i + 1))
    done
    sleep "${TURBO_HWUI_WAIT_S:-0.35}" 2>/dev/null || :
  fi

  restore_hm_prop
  trap - INT TERM
  _tb_cleanup

  if [ "$_started" -eq 0 ]; then
    restore_turbo
    apply_profile_auto_hw
    echo "FALHOU: nao foi possivel abrir $_pkg" >&2
    return 1
  fi

  # Guarda de seguranca: se o jogo morrer/nao assumir foco em 15s, restaura automaticamente.
  _wd_s="${TURBO_WATCHDOG_S:-15}"
  if [ "$_wd_s" != "0" ] && [ -z "$FAKE_SETTINGS_DIR" ]; then
    (
      sleep "$_wd_s"
      [ -s "$STATE_F" ] || exit 0
      [ "$(state_get stamp)" = "$_stamp" ] || exit 0
      _ga=$(tr -d '[:space:]' < "$M54_DIR/game-active.txt" 2>/dev/null)
      if [ -z "$_ga" ] && ! pidof "$_pkg" >/dev/null 2>&1; then
        M54_DIR="$M54_DIR" FS_ROOT="$FS_ROOT" sh "$DIR/turbo.sh" restore >/dev/null 2>&1
        apply_profile_auto_hw
      fi
    ) >/dev/null 2>&1 &
  fi

  local _avail_mb=""
  case "$_ram_avail" in
    ''|*[!0-9]*) ;;
    *) _avail_mb="$((_ram_avail / 1024))MB";;
  esac
  local _msg="turbo ok pkg=$_pkg ram_delta=${_ram_delta}MB${_avail_mb:+ avail=$_avail_mb} mali=$_mali shaders=$_warm adpf=1 game_mode=${_gm_ok}"
  log "$_msg"
  echo "$_msg"
  return 0
}

case "$MODE" in
  launch)
    launch_turbo "$PKG"
    ;;
  cancel)
    cancel_turbo
    ;;
  restore)
    restore_turbo
    ;;
  status|"")
    if [ -s "$STATE_F" ]; then
      echo "turbo=active pkg=$(state_get pkg) orig_profile=$(state_get orig_profile)"
    else
      echo "turbo=idle"
    fi
    ;;
  *)
    echo "uso: turbo.sh [launch <pkg>|cancel|restore|status]" >&2
    exit 1
    ;;
esac
