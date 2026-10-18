#!/system/bin/sh
# fusao-watch.sh — Detector e orquestrador de sessão de jogo (contrato game-active.txt).
# Melhorias:
# - Respeita o perfil selecionado pelo usuário (game/balanced/battery) no enter e no exit
#   (não força framework.sh game no enter nem reseta para idle apagando o perfil no exit).
# - Coordena fusaod serve (ativo somente em sessão de jogo nos perfis game/balanced,
#   parado em idle/battery para evitar sobrescrita de sysfs e consumo em background).
# - Protege o cache de preload recém-aquecido (lê preload.last p/ não rodar drop_caches=3
#   por cima de um preload manual feito antes de abrir o jogo).
# - Confirma foco via current_focus antes de disparar fire_exit quando o processo do jogo
#   continua vivo (elimina tempestade de bounce de foco no loading do NTE).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

PIDF="$M54_DIR/fusao_watch_pid"
if pid_record_alive "$PIDF" fusao-watch.sh; then exit 0; fi
rm -f "$PIDF"
pid_record_write "$PIDF" fusao-watch.sh || { log "watch: PID publication failed"; exit 1; }
cleanup_watch() {
  trap - EXIT INT TERM
  [ "$(pid_record_pid "$PIDF")" = "$$" ] && rm -f "$PIDF"
  exit 0
}
trap cleanup_watch EXIT INT TERM

GAMEFILE="$M54_DIR/game-active.txt"
GAMELIST="$M54_DIR/gamelist.txt"
PRELOAD_LAST="$M54_DIR/preload.last"
LAST=""
LAST_HEAVY_PKG=""
LAST_HEAVY_TS=0
log "watch v2.0: inicio (gamelist=$GAMELIST)"

read_gamefile() {
  local _g=""
  [ -r "$GAMEFILE" ] && IFS= read -r _g < "$GAMEFILE" 2>/dev/null
  _g=$(printf "%s" "$_g" | tr -d '[:space:]')
  echo "$_g"
}

# Perfil ESCOLHIDO (selected_profile do lib.sh, SSOT). Subshell: o watch não carrega a lib (umask/log
# próprios), só pergunta a ela. O gate do fusaod usa o escolhido, NÃO o em vigor: o fusaod lê
# /data/adb/fusao/profile por conta própria e com "battery" aplicaria os tetos dele de battery (GPU 552000,
# P0/P4 1056000) dentro do jogo — o oposto da regra battery+jogo=game, que fica a cargo do tune.sh.
selected_profile() {
  ( . "$(dirname "$0")/lib.sh" 2>/dev/null && selected_profile ) || echo "balanced"
}

find_fusaod() {
  local _b
  _b=$(command -v fusaod 2>/dev/null)
  [ -n "$_b" ] && [ -x "$_b" ] && { echo "$_b"; return 0; }
  for _b in /system/bin/fusaod /data/adb/modules/fusao/system/bin/fusaod /data/adb/modules/encore/system/bin/fusaod "$M54_DIR/../system/bin/fusaod"; do
    [ -x "$_b" ] && { echo "$_b"; return 0; }
  done
  return 1
}

find_fusao_cli() {
  local _c
  for _c in "$M54_DIR/fusao-cli" /system/bin/fusao /data/adb/ksu/bin/fusao /data/adb/ap/bin/fusao /data/adb/modules/fusao/system/bin/fusao /data/adb/modules/encore/system/bin/fusao "$M54_DIR/../system/bin/fusao"; do
    [ -f "$_c" ] && { echo "$_c"; return 0; }
  done
  return 1
}

sync_fusaod_session() {
  local _in_game="$1" _prof _b _p
  [ -n "$FAKE_SETTINGS_DIR" ] && return 0
  _prof=$(selected_profile)
  if [ "$_in_game" = "1" ] && [ -f "$M54_DIR/fusaod.enabled" ] && [ ! -f "$M54_DIR/disable" ] && [ "$_prof" != "battery" ]; then
    if ! pgrep -f "[f]usaod serve" >/dev/null 2>&1; then
      _b=$(find_fusaod)
      if [ -n "$_b" ] && [ -d /sys/kernel/gpu ]; then
        nohup "$_b" serve >>"$M54_DIR/fusaod.log" 2>&1 &
      fi
    fi
  else
    for _p in $(pgrep -f "[f]usaod serve" 2>/dev/null); do
      [ "$_p" = "$$" ] || kill -TERM "$_p" 2>/dev/null
    done
  fi
}

# FOCUS_FAKE (testes offline): arquivo com nome do pacote em foco.
# DUMPSYS_FAKE: saída fake de dumpsys para parser.
current_focus() {
  if [ -n "$FOCUS_FAKE" ] && [ -f "$FOCUS_FAKE" ]; then
    cat "$FOCUS_FAKE" 2>/dev/null | tr -d '[:space:]'
    return
  fi
  _dump=""
  if [ -n "$DUMPSYS_FAKE" ] && [ -f "$DUMPSYS_FAKE" ]; then
    _dump=$(cat "$DUMPSYS_FAKE" 2>/dev/null)
  else
    _dump=$(dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -3)
  fi
  echo "$_dump" | grep -oE "[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+" | head -1 | tr -d '[:space:]'
}

in_gamelist() {
  _pkg="$1"
  [ -z "$_pkg" ] && return 1
  [ ! -f "$GAMELIST" ] && return 1
  grep -Fxq "$_pkg" "$GAMELIST" 2>/dev/null
}

write_state() {
  printf "%s" "$1" | atomic_write "$GAMEFILE" 0644
}

observer_alive() {
  [ -n "$OBSERVER_FAKE_ALIVE" ] && return 0
  pgrep -f "[f]usao.observer" >/dev/null 2>&1
}

STANDBY=0
LAST_RELAUNCH=0
relaunch_observer() {
  [ -n "$WATCH_NORELAUNCH" ] && return 1
  [ -x "$M54_DIR/observer.sh" ] || return 1
  [ -f "$M54_DIR/observer.dex" ] || return 1
  [ -f "$M54_DIR/disable" ] && return 1
  pgrep -f "[o]bserver.sh" >/dev/null 2>&1 && return 0
  NOW_R=$(uptime_s)
  case "$NOW_R" in ''|*[!0-9]*) NOW_R=0;; esac
  if [ "$LAST_RELAUNCH" != "0" ] && [ $((NOW_R - LAST_RELAUNCH)) -lt 60 ]; then
    return 1
  fi
  LAST_RELAUNCH=$NOW_R
  M54_DIR="$M54_DIR" nohup sh "$M54_DIR/observer.sh" >>"$M54_DIR/fusao.log" 2>&1 &
  sleep 5
  if observer_alive; then
    log "watch: observer relançado OK"
    return 0
  fi
  log "watch: relaunch falhou (nova tentativa em 1min)"
  return 1
}

if ! observer_alive; then
  write_state ""
  log "watch: inicio sem observer, relaunch + poll 5s"
  relaunch_observer
fi

recently_preloaded() {
  local _pkg="$1" _now="$2" _lp _lts _rest
  [ -f "$PRELOAD_LAST" ] || return 1
  while read -r _lp _lts _rest; do
    if [ "$_lp" = "$_pkg" ]; then
      case "$_lts" in ''|*[!0-9]*) continue;; esac
      if [ $((_now - _lts)) -ge 0 ] && [ $((_now - _lts)) -lt 180 ]; then
        return 0
      fi
    fi
  done < "$PRELOAD_LAST"
  return 1
}

fire_enter() {
  _pkg="$1"
  HOOKS=""
  log "watch: enter $_pkg"
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" enter "$_pkg" 2>/dev/null && HOOKS="$HOOKS perf"
  fi
  if [ -f "$M54_DIR/dnd.enabled" ] && [ -x "$(dirname "$0")/dnd.sh" ]; then
    M54_DIR="$M54_DIR" nohup sh "$(dirname "$0")/dnd.sh" on >>"$M54_DIR/fusao.log" 2>&1 &
    HOOKS="$HOOKS dnd"
  fi
  _now_h=$(uptime_s)
  case "$_now_h" in ''|*[!0-9]*) _now_h=0;; esac
  _skip_heavy=0
  if [ "$_pkg" = "$LAST_HEAVY_PKG" ] && [ "$LAST_HEAVY_TS" != "0" ] && [ $((_now_h - LAST_HEAVY_TS)) -lt 180 ]; then
    _skip_heavy=1
  elif recently_preloaded "$_pkg" "$_now_h"; then
    # Se o usuário acabou de aquecer o cache manualmente no app antes de abrir o jogo,
    # NÃO rodar drop_caches=3 nem repetir WILLNEED por cima.
    _skip_heavy=1
  fi
  if [ -f "$M54_DIR/ramclean.enabled" ]; then
    if [ "$_skip_heavy" = 0 ]; then
      _rf0=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
      sync 2>/dev/null
      # Usa drop_caches=1 (pagecache limpa não mapeada) quando o processo do jogo já
      # está aberto há mais de 5s para evitar arrancar páginas ativas no meio do loading.
      _dc_lv=3
      _gpid=$(pidof "$_pkg" 2>/dev/null | awk '{print $1}')
      if [ -n "$_gpid" ]; then
        _pstart=$(proc_start_s "$_gpid")
        if [ "$_pstart" != "-1" ] && [ $((_now_h - _pstart)) -gt 5 ]; then
          _dc_lv=1
        fi
      fi
      echo "$_dc_lv" > /proc/sys/vm/drop_caches 2>/dev/null
      _rc1=$?
      echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
      _rc2=$?
      _rf1=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
      _ra1=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
      log "ramclean ok free=${_rf0}->${_rf1} avail=${_ra1} rc=${_rc1}/${_rc2} [${_pkg}]"
      HOOKS="$HOOKS ramclean"
    else
      HOOKS="$HOOKS ramclean(warm)"
    fi
  fi
  if [ -f "$M54_DIR/preload.enabled" ]; then
    if [ "$_skip_heavy" = 0 ]; then
      _cli=$(find_fusao_cli)
      if [ -n "$_cli" ]; then
        M54_DIR="$M54_DIR" nohup sh "$_cli" preload now "$_pkg" >>"$M54_DIR/fusao.log" 2>&1 &
        HOOKS="$HOOKS preload"
      else
        _b=$(find_fusaod)
        if [ -n "$_b" ]; then
          nohup "$_b" preload "$_pkg" >>"$M54_DIR/fusao.log" 2>&1 &
          HOOKS="$HOOKS preload"
        fi
      fi
    else
      HOOKS="$HOOKS preload(warm)"
    fi
  fi
  if [ "$_skip_heavy" = 0 ] && { [ -f "$M54_DIR/ramclean.enabled" ] || [ -f "$M54_DIR/preload.enabled" ]; }; then
    LAST_HEAVY_PKG="$_pkg"
    LAST_HEAVY_TS="$_now_h"
  fi
  # Aplica o perfil EM VIGOR (auto = effective_profile: battery + jogo da gamelist = game; balanced fica)
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" auto 2>/dev/null && HOOKS="$HOOKS framework"
  elif [ -x "$(dirname "$0")/tune.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/tune.sh" auto 2>/dev/null && HOOKS="$HOOKS tune"
  fi
  sync_fusaod_session 1
  [ -z "$HOOKS" ] && HOOKS=" nenhum (flags off)"
  log "watch: hooks$HOOKS [$_pkg]"
}

fire_exit() {
  _exited_pkg="$1"
  log "watch: exit (foco fora da lista)"
  sync_fusaod_session 0
  if [ -n "$_exited_pkg" ] && ! pidof "$_exited_pkg" >/dev/null 2>&1; then
    LAST_HEAVY_PKG=""
    LAST_HEAVY_TS=0
  fi
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" exit "$_exited_pkg" 2>/dev/null
  fi
  # Ao sair do jogo volta ao perfil escolhido (auto com game-active vazio; não apaga p/ stock idle)
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" auto 2>/dev/null
  elif [ -x "$(dirname "$0")/tune.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/tune.sh" auto 2>/dev/null
  fi
  if [ -f "$M54_DIR/dnd.enabled" ] && [ -x "$(dirname "$0")/dnd.sh" ]; then
    M54_DIR="$M54_DIR" nohup sh "$(dirname "$0")/dnd.sh" off >>"$M54_DIR/fusao.log" 2>&1 &
  fi
}

while true; do
  if observer_alive; then
    if [ "$STANDBY" = 0 ]; then
      STANDBY=1
      log "watch: observer ativo, standby (consome game-active, sem poll)"
    fi
    CUR=$(read_gamefile)
    if [ "$CUR" != "$LAST" ]; then
      if [ -n "$CUR" ] && ! in_gamelist "$CUR"; then
        LAST="$CUR"
      elif [ -n "$CUR" ]; then
        fire_enter "$CUR"
        LAST="$CUR"
      else
        # Se o processo principal do jogo continua vivo, aguarda debounce (2s) e
        # confere também o foco da janela antes de disparar fire_exit (evita falso
        # exit causado por subprocessos ou telas de loading do NTE).
        if [ -n "$LAST" ] && pidof "$LAST" >/dev/null 2>&1; then
          sleep 2
          CUR=$(read_gamefile)
          if [ -n "$CUR" ] && in_gamelist "$CUR"; then
            [ "$CUR" != "$LAST" ] && { fire_enter "$CUR"; LAST="$CUR"; }
            sleep 2
            continue
          fi
          FOCUS_CHK=$(current_focus)
          if [ "$FOCUS_CHK" = "$LAST" ]; then
            write_state "$LAST"
            sleep 2
            continue
          fi
        fi
        fire_exit "$LAST"
        LAST=""
      fi
    fi
    sleep 2
    continue
  fi
  if [ "$STANDBY" = 1 ]; then
    STANDBY=0
    write_state ""
    log "watch: observer morto, relaunch + poll 5s"
    relaunch_observer
  fi
  relaunch_observer
  sleep 5
  FOCUS=$(current_focus)
  if [ -n "$FOCUS" ] && in_gamelist "$FOCUS"; then
    CUR="$FOCUS"
  else
    CUR=""
  fi
  if [ "$CUR" != "$LAST" ]; then
    write_state "$CUR"
    if [ -n "$CUR" ]; then
      fire_enter "$CUR"
    else
      fire_exit "$LAST"
    fi
    LAST="$CUR"
  fi
done
