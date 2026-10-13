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
log "watch v2.0: inicio (gamelist=$GAMELIST)"

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
  NOW_R=$(uptime_s)
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
  # Gancho perf (processo, não sysfs): renice/IO no game-enter, atrás de flag.
  if [ -x "$M54_DIR/../fusao/perf.sh" ]; then :; fi
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" enter "$_pkg" 2>/dev/null && HOOKS="$HOOKS perf"
  fi
  if [ -f "$M54_DIR/dnd.enabled" ] && [ -x "$(dirname "$0")/dnd.sh" ]; then
    # Async com backoff próprio: o caminho zen trava na tempestade do enter;
    # bloquear aqui atrasaria preload/framework. O log carrega o veredito.
    M54_DIR="$M54_DIR" nohup sh "$(dirname "$0")/dnd.sh" on >>"$M54_DIR/fusao.log" 2>&1 &
    HOOKS="$HOOKS dnd"
  fi
  # RAM clean ANTES do preload (ordem manda: limpar depois do WILLNEED anula ele).
  # Simples estilo Encore (sync+drop+compact), atrás de flag, síncrono e logado.
  if [ -f "$M54_DIR/ramclean.enabled" ]; then
    _rf0=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
    _ra0=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
    sync 2>/dev/null
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    _rc1=$?
    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    _rc2=$?
    _rf1=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
    log "ramclean ok free=${_rf0}->${_rf1} avail=${_ra0} rc=${_rc1}/${_rc2} [$_pkg]"
    HOOKS="$HOOKS ramclean"
  fi
  # Preload: WILLNEED async nos dados do jogo.
  if [ -f "$M54_DIR/preload.enabled" ]; then
    for _b in "$M54_DIR/../system/bin/fusaod" /data/adb/modules/encore/system/bin/fusaod; do
      if [ -x "$_b" ]; then
        nohup "$_b" preload "$_pkg" >>"$M54_DIR/fusao.log" 2>&1 &
        HOOKS="$HOOKS preload"
        break
      fi
    done
  fi
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" game 2>/dev/null && HOOKS="$HOOKS framework"
  fi
  # Resumo de 1 linha: responde "foi ou não" sem ler 4 logs.
  [ -z "$HOOKS" ] && HOOKS=" nenhum (flags off)"
  log "watch: hooks$HOOKS [$_pkg]"
}

fire_exit() {
  log "watch: exit (foco fora da lista)"
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
    # (Sem isso, com observer vivo NENHUM gancho — perf/dnd/preload/framework —
    #  jamais disparava: bug provado ao vivo, 2 sessões com tudo ON e zero hooks.)
    CUR=$(tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null)
    if [ "$CUR" != "$LAST" ]; then
      if [ -n "$CUR" ] && ! in_gamelist "$CUR"; then
        LAST="$CUR"
      elif [ -n "$CUR" ]; then
        fire_enter "$CUR"
        LAST="$CUR"
      else
        fire_exit
        LAST=""
      fi
    fi
    sleep 5
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
      fire_exit
    fi
    LAST="$CUR"
  fi
done
