#!/system/bin/sh
# fusao gos_killer v0.5 — GOS (com.samsung.android.game.gos) ressuscita no boot
# e nasce no game-launch. Mata por schedule + guarda de game-enter, mas NUNCA
# dispara `cmd activity force-stop` durante jogo ativo se o GOS não estiver vivo
# (evita contenção de lock global no ActivityManager durante loading de jogos).
# v0.5 (2026-11-14): guarda também FORA de jogo. O GOS nasce para qualquer app que a Samsung considera jogo,
# esteja ou não na gamelist do módulo, e antes só caía na matança horária. Aparelho, Plague Inc. fora da
# gamelist, balanced, bateria a 31 C: GOS vivo = ufc cpufreq_max_limit 960000 (P4 chegou a 533000), P0 max
# 1632000 e gpu_max_clock 455000. Matar o GOS soltou tudo em 3 s (ufc -1, P0 2002000, P4 1824000, GPU 949000 —
# a GPU volta ao máximo, não ao teto do perfil: era a origem do "partial 12/13" do balanced), então depois de
# matar fora de jogo o perfil é reaplicado uma vez (tune.sh auto).
# Desativado, não removido: `pm uninstall --user 0` foi desfeito pelo próprio system_server em 0,5 s (logcat
# 21:21:03 "SGM:Util: isPkgInstalled(), ... does not exist" e firstInstallTime no mesmo segundo: o SemGameManager
# reinstala e sobe o GameIntentService). `pm disable-user` ficou de pé por duas aberturas de jogo sem o processo
# nascer. O killer desativa no início e de novo sempre que achar o processo vivo (= alguém reativou). Nada no
# módulo reativa o pacote (regra do dono: GOS nunca); desfazer é manual: pm enable --user 0 <pacote>.
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
nap_init gos

# gos_disable — estado disabled-user (reversível: pm enable). Um app_process; só no início e após achar o GOS vivo.
gos_disable() {
  command -v pm >/dev/null 2>&1 || return 0
  pm disable-user --user 0 "$PKG" >/dev/null 2>&1 && log "gos killer [$1]: pacote desativado (disable-user)"
}

# GOS_KILLED=1 quando esta chamada matou um processo vivo (o chamador decide se reaplica o perfil).
kill_gos() {
  local mark="$1" force_when_absent="${2:-1}" p found=0
  GOS_KILLED=0
  for p in $(pidof "$PKG" 2>/dev/null); do
    found=1
    kill -9 "$p" 2>/dev/null && log "gos killer [$mark]: matou $PKG pid $p"
  done
  if [ "$found" = 1 ] || [ "$force_when_absent" = 1 ]; then
    cmd activity force-stop "$PKG" >/dev/null 2>&1 || am force-stop "$PKG" >/dev/null 2>&1
    [ "$found" = 0 ] && log "gos killer [$mark]: ausente (force-stop aplicado)"
  fi
  [ "$found" = 1 ] && gos_disable "$mark"
  GOS_KILLED=$found
  return 0
}

# reapply_profile — o GOS morto solta os limites dele para o máximo do hardware, não para o perfil: uma
# reaplicação (em segundo plano; o tune confere e loga o resultado).
reapply_profile() {
  [ -f "$M54_DIR/disable" ] && return 0
  [ -x "$DIR/tune.sh" ] || return 0
  M54_DIR="$M54_DIR" FS_ROOT="${FS_ROOT:-}" nohup sh "$DIR/tune.sh" auto >/dev/null 2>&1 &
}

# Fora de jogo o pidof roda a cada IDLE_EVERY voltas de 5 s (um exec; em jogo é a cada volta, como antes).
IDLE_EVERY="${GOS_IDLE_EVERY:-3}"
case "$IDLE_EVERY" in ''|*[!0-9]*) IDLE_EVERY=3;; esac
IDLE_TICK=0

START=$(uptime_s)
case "$START" in ''|*[!0-9]*) START=0;; esac
M30=0; M300=0; M600=0; M3600=0; LASTH=$START; LASTGAME=""; PAUSED_LOGGED=0
log "gos killer v0.5: inicio"
if [ -f "$M54_DIR/gos_killer.paused" ]; then
  log "gos killer: pausado (fusao gos on p/ retomar)"; PAUSED_LOGGED=1
else
  kill_gos boot 1
  [ "$GOS_KILLED" = 1 ] || gos_disable boot
fi
while true; do
  nap 5
  if [ -f "$M54_DIR/gos_killer.paused" ]; then
    if [ "$PAUSED_LOGGED" = 0 ]; then PAUSED_LOGGED=1; log "gos killer: pausado (fusao gos on p/ retomar)"; fi
    nap 30
    continue
  fi
  PAUSED_LOGGED=0
  NOW=$(uptime_s)
  case "$NOW" in ''|*[!0-9]*) NOW=$START;; esac
  ELAPSED=$((NOW - START))
  G=""
  [ -r "$GAMEFILE" ] && IFS= read -r G < "$GAMEFILE" 2>/dev/null
  G="${G%%[[:space:]]*}"
  if [ -n "$G" ]; then
    # Durante o jogo, só executa kill/force-stop se o processo do GOS realmente existir
    if pidof "$PKG" >/dev/null 2>&1; then
      if [ "$G" != "$LASTGAME" ]; then
        kill_gos "game-enter:$G" 0
      else
        kill_gos "game-guard" 0
      fi
    fi
    IDLE_TICK=0
  elif [ "$IDLE_EVERY" != 0 ]; then
    IDLE_TICK=$((IDLE_TICK + 1))
    if [ "$IDLE_TICK" -ge "$IDLE_EVERY" ]; then
      IDLE_TICK=0
      if pidof "$PKG" >/dev/null 2>&1; then
        kill_gos "guarda" 0
        [ "$GOS_KILLED" = 1 ] && reapply_profile
      fi
    fi
  fi
  LASTGAME="$G"
  _force_idle=1
  [ -n "$G" ] && _force_idle=0
  if [ "$ELAPSED" -ge 30 ] && [ "$M30" = 0 ]; then M30=1; kill_gos 30s "$_force_idle"; fi
  if [ "$ELAPSED" -ge 300 ] && [ "$M300" = 0 ]; then M300=1; kill_gos 5min "$_force_idle"; fi
  if [ "$ELAPSED" -ge 600 ] && [ "$M600" = 0 ]; then M600=1; kill_gos 10min "$_force_idle"; fi
  if [ "$ELAPSED" -ge 3600 ] && [ "$M3600" = 0 ]; then M3600=1; kill_gos 1h "$_force_idle"; fi
  if [ "$((NOW - LASTH))" -ge 3600 ]; then LASTH=$NOW; kill_gos horario "$_force_idle"; fi
done
