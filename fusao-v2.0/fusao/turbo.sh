#!/system/bin/sh
# fusao-turbo.sh — Sessao turbo single-shot para abertura de jogo no Galaxy M54
# (Exynos 1380 / Mali-G68 MP5 / Android 16 / One UI 8.5).
#
# Cada ajuste aqui foi auditado e comprovado no aparelho com readback e restauracao:
#   1. Limpeza profunda real de RAM (`fusao ramclean now <pkg>`), preservando pagecache.
#   2. Pre-aquecimento limitado (<= 32 MiB) dos caches de shader ja compilados do jogo
#      (incluindo VulkanProgramBinaryCache externo usado por jogos UE5 como NTE).
#   3. Perfil `game` temporario (restaura o perfil original do usuario ao sair do jogo).
#   4. Mali-G68 (`/sys/devices/platform/10300000.mali` + `/sys/kernel/gpu`):
#      mantem `power_policy=coarse_demand` stock (permite power-gating dos shader cores e evita
#      aquecimento desnecessario em jogos GPU-bound/30 FPS); ajusta 5 nos DVFS:
#      highspeed_clock=754000, highspeed_load=85, polling_speed=20, dvfs_period=50, gpu_cl_boost_disable=0.
#   5. Downscale por jogo opcional (`downscale.targets`: 1x/900p/810p/720p): aplicado via `wm size`
#      somente quando != 1x no momento do launch (com force-stop previo apenas se o jogo ja estiver
#      em execucao, para recriar o swapchain na resolucao reduzida) e restaurado no exit/restore/cancel.
#   6. GameManagerService (Android 16): `cmd game set --fps 0 --downscale disable <pkg>`
#      (modo custom=4 persistente, Scaling=1.0, Fps=0 — comprovado sobreviver ao launch
#      na One UI 8.5, ao contrario de device_config game_overlay que a ROM apaga no launch).
#   7. ADPF HintManager transitorio (`debug.hwui.use_hint_manager=true`) apenas na janela de
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
SCALE_TARGETS="$M54_DIR/downscale.targets"
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

state_set_field() {
  local _k="$1" _v="$2"
  [ -f "$STATE_F" ] || return 1
  {
    grep -v "^${_k}=" "$STATE_F" 2>/dev/null
    echo "${_k}=${_v}"
  } | atomic_write "$STATE_F" 0600
}

wm_physical_size() {
  local _s=""
  if [ -n "$WM_FAKE" ]; then
    if [ -n "$WM_FAKE_PHYS" ]; then
      echo "$WM_FAKE_PHYS"
      return 0
    fi
    if [ -f "${WM_FAKE}.phys" ]; then
      _s=$(tr -d '[:space:]' < "${WM_FAKE}.phys" 2>/dev/null)
    elif [ -f "$WM_FAKE" ]; then
      _s=$(tr -d '[:space:]' < "$WM_FAKE" 2>/dev/null)
    fi
    [ -n "$_s" ] && echo "$_s" || echo "1080x2400"
    return 0
  fi
  _s=$(wm size 2>/dev/null | sed -n 's/^Physical size:[[:space:]]*\([0-9]*x[0-9]*\).*/\1/p' | head -n 1)
  [ -n "$_s" ] && echo "$_s" || wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -n 1
}

wm_override_size() {
  if [ -n "$WM_FAKE" ]; then
    [ -f "${WM_FAKE}.override" ] && tr -d '[:space:]' < "${WM_FAKE}.override" 2>/dev/null
    return 0
  fi
  wm size 2>/dev/null | sed -n 's/^Override size:[[:space:]]*\([0-9]*x[0-9]*\).*/\1/p' | head -n 1
}

wm_apply_size() {
  local _dims="$1"
  [ -n "$_dims" ] || return 1
  if [ -n "$WM_FAKE" ]; then
    printf '%s' "$_dims" > "$WM_FAKE" 2>/dev/null
    printf '%s' "$_dims" > "${WM_FAKE}.override" 2>/dev/null
    return 0
  fi
  wm size "$_dims" >/dev/null 2>&1
}

wm_reset_size() {
  if [ -n "$WM_FAKE" ]; then
    local _p
    _p=$(wm_physical_size)
    printf '%s' "${_p:-1080x2400}" > "$WM_FAKE" 2>/dev/null
    rm -f "${WM_FAKE}.override" 2>/dev/null
    return 0
  fi
  wm size reset >/dev/null 2>&1
}

# Mesmo calculo de chao par do display.sh (requisito do HWC Samsung).
scale_dims() {
  local _phys="$1" _mode="$2" _sw _sh _num _den _w _h
  case "$_phys" in
    *[0-9]x[0-9]*) ;;
    *) return 1;;
  esac
  _sw="${_phys%x*}"
  _sh="${_phys#*x}"
  case "$_sw$_sh" in ''|*[!0-9]*) return 1;; esac
  case "$_mode" in
    720p) _num=720; _den=1080;;
    810p) _num=3; _den=4;;
    900p) _num=5; _den=6;;
    *) return 1;;
  esac
  _w=$(( _sw * _num / _den ))
  _h=$(( _sh * _num / _den ))
  [ $((_w % 2)) -ne 0 ] && _w=$((_w - 1))
  [ $((_h % 2)) -ne 0 ] && _h=$((_h - 1))
  [ "$_w" -gt 0 ] && [ "$_h" -gt 0 ] || return 1
  echo "${_w}x${_h}"
}

scale_cmd() {
  local _sub="${1:-list}" _pkg="${2:-}" _val="${3:-}"
  case "$_sub" in
    list|"")
      [ -r "$SCALE_TARGETS" ] || return 0
      awk -F= '
        /^[A-Za-z0-9_.]+=(720p|810p|900p)$/ { val[$1] = $2; seen[$1] = 1 }
        /^[A-Za-z0-9_.]+=1x$/ { delete val[$1]; delete seen[$1] }
        END { for (k in seen) print k "=" val[k] }
      ' "$SCALE_TARGETS" 2>/dev/null | sort
      return 0
      ;;
    get)
      case "$_pkg" in
        ""|*[!A-Za-z0-9_.]*) echo "uso: turbo.sh scale get <pkg>" >&2; return 1;;
      esac
      turbo_scale_for "$_pkg"
      return 0
      ;;
    set)
      case "$_pkg" in
        ""|*[!A-Za-z0-9_.]*) echo "FALHOU: pacote invalido '$_pkg'" >&2; return 1;;
      esac
      case "$_val" in
        1x|off|reset|native) _val="1x";;
        900p|810p|720p) ;;
        *) echo "FALHOU: escala invalida '$_val' (use 1x|900p|810p|720p)" >&2; return 1;;
      esac
      {
        if [ -r "$SCALE_TARGETS" ]; then
          grep -v "^${_pkg}=" "$SCALE_TARGETS" 2>/dev/null || :
        fi
        [ "$_val" != "1x" ] && echo "${_pkg}=${_val}"
      } | atomic_write "$SCALE_TARGETS" 0600
      log "turbo: scale ${_pkg}=${_val}"
      echo "scale ${_pkg}=${_val}"
      return 0
      ;;
    *)
      echo "uso: turbo.sh scale [list|get <pkg>|set <pkg> <1x|900p|810p|720p>]" >&2
      return 1
      ;;
  esac
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
# Inclui caches externos Vulkan/UE5 (Android/data/<pkg>/files/VulkanProgramBinaryCache).
warm_shader_caches() {
  local _pkg="$1" _warmed=0 _bytes=0 _cap=33554432 _f _sz _rem _blocks
  for _f in \
    "$R/data/user_de/0/$_pkg/cache/com.android.skia.shaders_cache" \
    "$R/data/user_de/0/$_pkg/cache/com.android.opengl.shaders_cache" \
    "$R/data/user/0/$_pkg/cache/shadercachevk.bin" \
    "$R/data/user_de/0/$_pkg/code_cache/"*shader* \
    "$R/data/user_de/0/$_pkg/code_cache/"*vk* \
    "$R/data/user/0/$_pkg/code_cache/"*shader* \
    "$R/data/user/0/$_pkg/code_cache/"*vk* \
    "$R/sdcard/Android/data/$_pkg/files/VulkanProgramBinaryCache/"*; do
    case "$_f" in *.lru) continue;; esac
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
  local _pkg="$1" _stamp="$2" _orig_prof _pp="" _hc _hl _ps _dp _cl _ov _gm _orig_wm _app_scale="1x"
  if [ -s "$STATE_F" ]; then
    # Ja existe sessao turbo ativa: preserva o snapshot stock original e atualiza apenas o jogo/selo.
    local _prev_pkg _prev_gm
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
    _orig_wm=$(state_get orig_wm_override)
    _app_scale=$(state_get applied_scale)
    [ -n "$_app_scale" ] || _app_scale="1x"
  else
    _orig_prof=$(selected_profile)
    # power_policy nao e mais sobrescrito (mantem coarse_demand stock para power-gating dos shader cores).
    _pp=""
    _hc=$(read_trim "$MALI_DEV/highspeed_clock" 2>/dev/null)
    _hl=$(read_trim "$MALI_DEV/highspeed_load" 2>/dev/null)
    _ps=$(read_trim "$MALI_DEV/polling_speed" 2>/dev/null)
    _dp=$(read_trim "$MALI_DEV/dvfs_period" 2>/dev/null)
    _cl=$(read_trim "$GPU_DIR/gpu_cl_boost_disable" 2>/dev/null)
    _orig_wm=$(wm_override_size)
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
    echo "orig_wm_override=$_orig_wm"
    echo "applied_scale=$_app_scale"
  } | atomic_write "$STATE_F" 0600
}

apply_mali_turbo() {
  local _ok=0 _tot=0
  if [ -e "$MALI_DEV/highspeed_clock" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/highspeed_clock" "754000" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/highspeed_load" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/highspeed_load" "85" && _ok=$((_ok + 1))
  fi
  if [ -e "$MALI_DEV/polling_speed" ]; then
    _tot=$((_tot + 1))
    write_node "$MALI_DEV/polling_speed" "20" && _ok=$((_ok + 1))
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
  local _pkg _orig_prof _pp _hc _hl _ps _dp _cl _ov _gm _orig_wm _app_scale _wm_msg=""
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
  _orig_wm=$(state_get orig_wm_override)
  _app_scale=$(state_get applied_scale)

  [ -n "$_pp" ] && write_node "$MALI_DEV/power_policy" "$_pp"
  [ -n "$_hc" ] && write_node "$MALI_DEV/highspeed_clock" "$_hc"
  [ -n "$_hl" ] && write_node "$MALI_DEV/highspeed_load" "$_hl"
  [ -n "$_ps" ] && write_node "$MALI_DEV/polling_speed" "$_ps"
  [ -n "$_dp" ] && write_node "$MALI_DEV/dvfs_period" "$_dp"
  [ -n "$_cl" ] && write_node "$GPU_DIR/gpu_cl_boost_disable" "$_cl"

  case "$_app_scale" in
    720p|810p|900p)
      if [ -n "$_orig_wm" ]; then
        wm_apply_size "$_orig_wm"
        _wm_msg=" scale->$_orig_wm"
      else
        wm_reset_size
        _wm_msg=" scale->reset"
      fi
      ;;
  esac

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
  log "turbo: restore ok (pkg=${_pkg:-?} profile->${_orig_prof:-?}${_wm_msg})"
  return 0
}

launch_turbo() {
  local _pkg="$1" _act="" _cli _ram_out="" _ram_delta="0" _ram_avail="" _warm="0" _mali="0/0" _gm_ok="0"
  local _orig_hm _stamp _started=0 _i _wd_s _tb_tmp _scale="1x" _prev_scale="1x" _phys="" _dims="" _orig_wm=""

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
  #  - Snapshot + perfil=game + 5 nos Mali DVFS (<5ms inline; mantem power_policy=coarse_demand)
  #  - RAM clean rapido (force-stop paralelo + kill-all + zram compact + slab, sem sleep 2s / compact_memory)
  #  - Pre-aquecimento de shaders (inclui VulkanProgramBinaryCache) + GameManager custom (fps=0, downscale=disable)
  _stamp="$(uptime_s)-$$"
  snapshot_turbo "$_pkg" "$_stamp"
  _prev_scale=$(state_get applied_scale)
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

  # 4) Downscale por jogo (se != 1x em downscale.targets): aplicado via wm size somente nesta sessao.
  # Se o jogo ja estiver aberto em segundo plano na resolucao nativa, encerra antes para recriar o swapchain.
  _scale=$(turbo_scale_for "$_pkg")
  case "$_scale" in
    720p|810p|900p)
      _phys=$(wm_physical_size)
      _dims=$(scale_dims "$_phys" "$_scale")
      if [ -n "$_dims" ]; then
        if command -v pidof >/dev/null 2>&1 && pidof "$_pkg" >/dev/null 2>&1; then
          if command -v cmd >/dev/null 2>&1; then
            cmd activity force-stop --user 0 "$_pkg" >/dev/null 2>&1 || :
          elif command -v am >/dev/null 2>&1; then
            am force-stop "$_pkg" >/dev/null 2>&1 || :
          fi
        fi
        [ -f "$CANCEL_F" ] && _abort_cancelled
        state_set_field applied_scale "$_scale"
        wm_apply_size "$_dims" || {
          state_set_field applied_scale "1x"
          _scale="1x"
        }
      else
        _scale="1x"
      fi
      ;;
    *)
      _scale="1x"
      case "$_prev_scale" in
        720p|810p|900p)
          _orig_wm=$(state_get orig_wm_override)
          if [ -n "$_orig_wm" ]; then wm_apply_size "$_orig_wm"; else wm_reset_size; fi
          state_set_field applied_scale "1x"
          ;;
      esac
      ;;
  esac

  # 5) Injecao transitoria de ADPF HintManager apenas no launch (mantem SkiaGL threaded stock).
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
  local _msg="turbo ok pkg=$_pkg ram_delta=${_ram_delta}MB${_avail_mb:+ avail=$_avail_mb} mali=$_mali shaders=$_warm adpf=1 game_mode=${_gm_ok} scale=${_scale}"
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
  scale)
    shift
    scale_cmd "$@"
    ;;
  status|"")
    if [ -s "$STATE_F" ]; then
      echo "turbo=active pkg=$(state_get pkg) orig_profile=$(state_get orig_profile) scale=$(state_get applied_scale)"
    else
      echo "turbo=idle"
    fi
    ;;
  *)
    echo "uso: turbo.sh [launch <pkg>|cancel|restore|scale [list|get <pkg>|set <pkg> <1x|900p|810p|720p>]|status]" >&2
    exit 1
    ;;
esac
