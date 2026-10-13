#!/system/bin/sh
# fusao gos_killer v0.3 — GOS (com.samsung.android.game.gos) ressuscita no boot
# e nasce no game-launch (fora das marcas esparsas: foi pego vivo com o NTE
# aberto). Mata por schedule + guarda de game-enter (poll 5s, só age com jogo
# na lista) e loga cada abate em fusao.log. Exceção deliberada ao single-writer
# (processo, não sysfs), mesmo padrão do thermal_guard.sh. Sem pm disable
# (a Samsung reverte; kill é transitório e seguro).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

PKG="com.samsung.android.game.gos"
GAMEFILE="$M54_DIR/game-active.txt"
PIDF="$M54_DIR/gos_killer_pid"
if pid_record_alive "$PIDF" gos_killer.sh; then exit 0; fi
rm -f "$PIDF"
pid_record_write "$PIDF" gos_killer.sh || { log "gos killer: PID publication failed"; exit 1; }
cleanup_killer() {
  trap - EXIT INT TERM
  [ "$(pid_record_pid "$PIDF")" = "$$" ] && rm -f "$PIDF"
  exit 0
}
trap cleanup_killer EXIT INT TERM

kill_gos() {
  local mark="$1" p found=0
  for p in $(pidof "$PKG" 2>/dev/null); do
    found=1
    kill -9 "$p" 2>/dev/null && log "gos killer [$mark]: matou $PKG pid $p"
  done
  am force-stop "$PKG" >/dev/null 2>&1
  [ "$found" = 0 ] && log "gos killer [$mark]: ausente (force-stop aplicado)"
}

START=$(uptime_s)
M30=0; M300=0; M600=0; M3600=0; LASTH=$START; LASTGAME=""; PAUSED_LOGGED=0
log "gos killer v0.3: inicio"
[ -f "$M54_DIR/gos_killer.paused" ] && { log "gos killer: pausado (fusao gos on p/ retomar)"; PAUSED_LOGGED=1; } || kill_gos boot
while true; do
  sleep 5
  # Pausa explícita: dorme sem matar (retoma ao remover o marker).
  if [ -f "$M54_DIR/gos_killer.paused" ]; then
    if [ "$PAUSED_LOGGED" = 0 ]; then PAUSED_LOGGED=1; log "gos killer: pausado (fusao gos on p/ retomar)"; fi
    sleep 30
    continue
  fi
  PAUSED_LOGGED=0
  NOW=$(uptime_s); ELAPSED=$((NOW - START))
  # Guarda de game-enter: GOS nasce no launch, fora das marcas.
  G=$(cat "$GAMEFILE" 2>/dev/null)
  if [ -n "$G" ]; then
    if [ "$G" != "$LASTGAME" ]; then
      kill_gos "game-enter:$G"
    elif pidof "$PKG" >/dev/null 2>&1; then
      kill_gos "game-guard"
    fi
  fi
  LASTGAME="$G"
  # Marcas do dono (uma vez cada).
  if [ "$ELAPSED" -ge 30 ] && [ "$M30" = 0 ]; then M30=1; kill_gos 30s; fi
  if [ "$ELAPSED" -ge 300 ] && [ "$M300" = 0 ]; then M300=1; kill_gos 5min; fi
  if [ "$ELAPSED" -ge 600 ] && [ "$M600" = 0 ]; then M600=1; kill_gos 10min; fi
  if [ "$ELAPSED" -ge 3600 ] && [ "$M3600" = 0 ]; then M3600=1; kill_gos 1h; fi
  if [ "$((NOW - LASTH))" -ge 3600 ]; then LASTH=$NOW; kill_gos horario; fi
done
