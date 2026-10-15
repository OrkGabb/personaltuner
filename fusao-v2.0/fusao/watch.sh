#!/system/bin/sh
# fusao-watch.sh — detector próprio (contrato game-active.txt).
# Standalone: escreve /data/adb/fusao/game-active.txt (SSOT).
# Poll dumpsys window 5s (fallback documentado — provou funcionar em campo).
# Hot-reload gamelist a cada tick. Só loga transição (enter/exit), não spam.
# Exceção single-writer: escreve ARQUIVO de estado, nunca sysfs.
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
  # Formatos: com.pkg/.Activity, u0 com.pkg/..., {u0 com.pkg/...}
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
# Sem observer no ar, o watch tenta relançá-lo com backoff e assume o poll.
# (reboot não relança app_process; e a janela pós-root pode negar o link —
#  visto ao vivo: CANNOT LINK nas 2 tentativas de 10:11:28, OK 1min depois.
#  Por isso: tenta, verifica, e repete 1×/min até registrar — sem pkill.)
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

# Sem observer no ar, o watch assume: limpa resíduo de sessão morta.
if ! observer_alive; then
  write_state ""
  log "watch: inicio sem observer, relaunch + poll 5s"
  relaunch_observer
fi

fire_enter() {
  _pkg="$1"
  HOOKS=""
  log "watch: enter $_pkg"
  # Gancho perf (processo + IO queue): renice/IO no game-enter, atrás de flag.
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" enter "$_pkg" 2>/dev/null && HOOKS="$HOOKS perf"
  fi
  if [ -f "$M54_DIR/dnd.enabled" ] && [ -x "$(dirname "$0")/dnd.sh" ]; then
    # Async com backoff próprio: o caminho zen trava na tempestade do enter;
    # bloquear aqui atrasaria preload/framework. O log carrega o veredito.
    M54_DIR="$M54_DIR" nohup sh "$(dirname "$0")/dnd.sh" on >>"$M54_DIR/fusao.log" 2>&1 &
    HOOKS="$HOOKS dnd"
  fi
  # Proteção contra bounce de foco durante o loading/sessão: se o mesmo jogo
  # já sofreu ramclean+preload há menos de 120s e o processo não morreu, evita
  # derrubar o pagecache recém-aquecido com novo drop_caches=3 + WILLNEED.
  _now_h=$(uptime_s)
  case "$_now_h" in ''|*[!0-9]*) _now_h=0;; esac
  _skip_heavy=0
  if [ "$_pkg" = "$LAST_HEAVY_PKG" ] && [ "$LAST_HEAVY_TS" != "0" ] && [ $((_now_h - LAST_HEAVY_TS)) -lt 120 ]; then
    _skip_heavy=1
  fi
  # RAM clean ANTES do preload (ordem manda: limpar depois do WILLNEED anula ele).
  # Simples estilo Encore (sync+drop+compact), atrás de flag, síncrono e logado.
  if [ -f "$M54_DIR/ramclean.enabled" ]; then
    if [ "$_skip_heavy" = 0 ]; then
      _rf0=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
      sync 2>/dev/null
      echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
      _rc1=$?
      echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
      _rc2=$?
      _rf1=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
      _ra1=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
      log "ramclean ok free=${_rf0}->${_rf1} avail=${_ra1} rc=${_rc1}/${_rc2} [$_pkg]"
      HOOKS="$HOOKS ramclean"
    else
      HOOKS="$HOOKS ramclean(warm)"
    fi
  fi
  # Preload: WILLNEED async nos dados do jogo.
  if [ -f "$M54_DIR/preload.enabled" ]; then
    if [ "$_skip_heavy" = 0 ]; then
      for _b in "$M54_DIR/../system/bin/fusaod" /data/adb/modules/encore/system/bin/fusaod; do
        if [ -x "$_b" ]; then
          nohup "$_b" preload "$_pkg" >>"$M54_DIR/fusao.log" 2>&1 &
          HOOKS="$HOOKS preload"
          break
        fi
      done
    else
      HOOKS="$HOOKS preload(warm)"
    fi
  fi
  if [ "$_skip_heavy" = 0 ] && { [ -f "$M54_DIR/ramclean.enabled" ] || [ -f "$M54_DIR/preload.enabled" ]; }; then
    LAST_HEAVY_PKG="$_pkg"
    LAST_HEAVY_TS="$_now_h"
  fi
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" game 2>/dev/null && HOOKS="$HOOKS framework"
  fi
  # Resumo de 1 linha: responde "foi ou não" sem ler 4 logs.
  [ -z "$HOOKS" ] && HOOKS=" nenhum (flags off)"
  log "watch: hooks$HOOKS [$_pkg]"
}

fire_exit() {
  _exited_pkg="$1"
  log "watch: exit (foco fora da lista)"
  # Se o processo do jogo realmente encerrou, zera o cooldown de ramclean/preload.
  if [ -n "$_exited_pkg" ] && ! pidof "$_exited_pkg" >/dev/null 2>&1; then
    LAST_HEAVY_PKG=""
    LAST_HEAVY_TS=0
  fi
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" exit "$_exited_pkg" 2>/dev/null
  fi
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" idle 2>/dev/null
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
    # Observer é o escritor; o watch CONSOME o estado p/ disparar os ganchos.
    CUR=$(read_gamefile)
    if [ "$CUR" != "$LAST" ]; then
      if [ -n "$CUR" ] && ! in_gamelist "$CUR"; then
        LAST="$CUR"
      elif [ -n "$CUR" ]; then
        fire_enter "$CUR"
        LAST="$CUR"
      else
        # Debounce curto (2s) se o processo do jogo continua vivo (evita bounce
        # de foco na transição splash -> GameActivity durante o loading).
        if [ -n "$LAST" ] && pidof "$LAST" >/dev/null 2>&1; then
          sleep 2
          CUR=$(read_gamefile)
          if [ -n "$CUR" ] && in_gamelist "$CUR"; then
            [ "$CUR" != "$LAST" ] && { fire_enter "$CUR"; LAST="$CUR"; }
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
  # Observer segue morto: retenta o relaunch (backoff 1min dentro da função).
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
