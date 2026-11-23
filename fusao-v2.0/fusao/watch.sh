#!/system/bin/sh
# fusao-watch.sh — Detector e orquestrador de sessão de jogo (contrato game-active.txt).
# Melhorias:
# - Respeita o perfil selecionado pelo usuário (game/balanced/battery) no enter e no exit
#   (não força framework.sh game no enter nem reseta para idle apagando o perfil no exit).
# - Coordena fusaod serve (ativo somente em sessão de jogo nos perfis game/balanced,
#   parado em idle/battery para evitar sobrescrita de sysfs e consumo em background).
# - 2.12.0: sem ramclean (sync + drop_caches + compact_memory) no game-enter: esvaziar o pagecache no
#   launch faz o jogo reler do UFS o que já estava em RAM, e a compactação síncrona trava junto (doutrina
#   do projeto: drop_caches = stutter, recusado). Limpeza só manual: fusao ramclean now.
# - Confirma foco via current_focus antes de disparar fire_exit quando o processo do jogo
#   continua vivo (elimina tempestade de bounce de foco no loading do NTE).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

PIDF="$M54_DIR/fusao_watch_pid"
if pid_record_alive "$PIDF" watch.sh; then exit 0; fi
rm -f "$PIDF"
pid_record_write "$PIDF" watch.sh || { log "watch: PID publication failed"; exit 1; }
cleanup_watch() {
  trap - EXIT INT TERM
  [ "$(pid_record_pid "$PIDF")" = "$$" ] && rm -f "$PIDF"
  exit 0
}
trap cleanup_watch EXIT INT TERM
nap_init watch

GAMEFILE="$M54_DIR/game-active.txt"
GAMELIST="$M54_DIR/gamelist.txt"
LAST=""
log "watch v2.0: inicio (gamelist=$GAMELIST)"

# read_gamefile — pacote em foco (game-active.txt) em CUR, só com builtins: era $(...) + printf|tr a cada 2 s
# (~55 ms de CPU por volta como root no aparelho). Espaço INTERNO cai no tr de sempre: mesma saída de antes.
read_gamefile() {
  CUR=""
  [ -r "$GAMEFILE" ] && IFS= read -r CUR < "$GAMEFILE" 2>/dev/null
  CUR=${CUR#"${CUR%%[![:space:]]*}"}
  CUR=${CUR%"${CUR##*[![:space:]]}"}
  case "$CUR" in *[[:space:]]*) CUR=$(printf "%s" "$CUR" | tr -d '[:space:]');; esac
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
  for _b in /data/adb/modules/fusao/system/bin/fusaod /data/adb/modules_update/fusao/system/bin/fusaod "$M54_DIR/../system/bin/fusaod" /system/bin/fusaod; do
    [ -x "$_b" ] && { echo "$_b"; return 0; }
  done
  _b=$(command -v fusaod 2>/dev/null)
  [ -n "$_b" ] && [ -x "$_b" ] && { echo "$_b"; return 0; }
  return 1
}

sync_fusaod_session() {
  local _in_game="$1" _prof _b _p _w
  [ -n "$FAKE_SETTINGS_DIR" ] && return 0
  _prof=$(selected_profile)
  # FAS ligado = fas-rs dono da CPU no jogo; o fusaod escreve piso de CPU e brigaria com ele
  if [ "$_in_game" = "1" ] && [ -f "$M54_DIR/fusaod.enabled" ] && [ ! -f "$M54_DIR/fas.enabled" ] && [ ! -f "$M54_DIR/disable" ] && [ "$_prof" != "battery" ]; then
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
    _w=0
    while pgrep -f "[f]usaod serve" >/dev/null 2>&1 && [ "$_w" -lt 10 ]; do
      sleep 0.1 2>/dev/null || sleep 1
      _w=$((_w + 1))
    done
    for _p in $(pgrep -f "[f]usaod serve" 2>/dev/null); do
      [ "$_p" = "$$" ] || kill -KILL "$_p" 2>/dev/null
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

# focused_window_pkg — pacote da JANELA em foco: só a linha mCurrentFocus, sem cair para mFocusedApp.
# current_focus (acima) pega o primeiro pacote de mCurrentFocus|mFocusedApp; com a tela apagada ou no keyguard a
# janela em foco é NotificationShade (sem pacote) e o primeiro pacote que aparece é o mFocusedApp = o jogo
# parado atrás do bloqueio. Para decidir se uma sessão continua de pé isso é um falso "em foco" (A-1).
focused_window_pkg() {
  local _line=""
  if [ -n "$FOCUS_FAKE" ] && [ -f "$FOCUS_FAKE" ]; then
    cat "$FOCUS_FAKE" 2>/dev/null | tr -d '[:space:]'
    return
  fi
  if [ -n "$DUMPSYS_FAKE" ] && [ -f "$DUMPSYS_FAKE" ]; then
    _line=$(grep -E "mCurrentFocus" "$DUMPSYS_FAKE" 2>/dev/null | head -1)
  else
    _line=$(dumpsys window 2>/dev/null | grep -E "mCurrentFocus" | head -1)
  fi
  echo "$_line" | grep -oE "[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+" | head -1 | tr -d '[:space:]'
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

# observer_alive — identidade do observer (pid + início do /proc/PID/stat) em cache; o pgrep -f (varre ~700
# processos: ~75 ms de CPU como root no aparelho) só roda quando o pid em cache some ou troca de dono.
OBS_PID=""
OBS_START=""
observer_alive() {
  local _st
  [ -n "$OBSERVER_FAKE_ALIVE" ] && return 0
  if [ -n "$OBS_PID" ] && IFS= read -r _st < "/proc/$OBS_PID/stat" 2>/dev/null; then
    _st=${_st##*) }
    set -- $_st
    # zumbi (morto, pai ainda não colheu) mantém o stat: o pgrep antigo não o casava (cmdline vazio)
    [ "$1" != Z ] && [ "${20}" = "$OBS_START" ] && return 0
  fi
  OBS_PID=""
  OBS_START=""
  set -- $(pgrep -f "[f]usao.observer" 2>/dev/null)
  [ -n "$1" ] || return 1
  IFS= read -r _st < "/proc/$1/stat" 2>/dev/null || return 1
  OBS_PID=$1
  _st=${_st##*) }
  set -- $_st
  OBS_START=${20}
  return 0
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
  # Jogo fora do FAS (fas.targets: pkg=off) vindo direto de outro jogo: o fas-rs do anterior sai ANTES do
  # perfil, que então escreve a CPU por último (mesma ordem do exit: o fas-rs sai com min=max cravados)
  if fas_game_off "$_pkg" && [ -x "$(dirname "$0")/fas.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/fas.sh" exit 2>/dev/null
  fi
  # Aplica o perfil EM VIGOR (auto = effective_profile: battery + jogo da gamelist = game; balanced fica)
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" auto 2>/dev/null && HOOKS="$HOOKS framework"
  elif [ -x "$(dirname "$0")/tune.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/tune.sh" auto 2>/dev/null && HOOKS="$HOOKS tune"
  fi
  # FAS depois do tune: o perfil cobre o aquecimento do fas-rs (~10 s) e então ele assume a CPU
  if [ -f "$M54_DIR/fas.enabled" ] && [ -x "$(dirname "$0")/fas.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/fas.sh" enter "$_pkg" 2>/dev/null && HOOKS="$HOOKS fas"
  fi
  sync_fusaod_session 1
  [ -z "$HOOKS" ] && HOOKS=" nenhum (flags off)"
  log "watch: hooks$HOOKS [$_pkg]"
}

fire_exit() {
  local _spid
  _exited_pkg="$1"
  log "watch: exit (foco fora da lista)"
  # Invalida settle/reverify pendentes ANTES dos hooks de saída ([A-01])
  if [ -s "$M54_DIR/settle.pid" ]; then
    _spid=$(cat "$M54_DIR/settle.pid" 2>/dev/null)
    case "$_spid" in ''|*[!0-9]*) ;; *) kill -TERM "$_spid" 2>/dev/null || : ;; esac
    rm -f "$M54_DIR/settle.pid" 2>/dev/null
  fi
  if [ -f "$M54_DIR/profile.state" ]; then
    grep -v "^applied_at=" "$M54_DIR/profile.state" > "$M54_DIR/profile.state.tmp" 2>/dev/null &&
      mv -f "$M54_DIR/profile.state.tmp" "$M54_DIR/profile.state" 2>/dev/null || :
  fi
  # FAS antes do tune: o fas-rs sai com min=max cravados e o tune.sh do exit tem de escrever por último.
  # Sem checar o .enabled: desligado no meio do jogo, o daemon ainda pode estar vivo.
  [ -x "$(dirname "$0")/fas.sh" ] && M54_DIR="$M54_DIR" sh "$(dirname "$0")/fas.sh" exit 2>/dev/null
  sync_fusaod_session 0
  if [ -f "$M54_DIR/perf.enabled" ] && [ -x "$(dirname "$0")/perf.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/perf.sh" exit "$_exited_pkg" 2>/dev/null
  fi
  # Se a sessao foi aberta via single-shot Turbo (`fusao launch`), restaura perfil original, Mali e game_overlay antes de reaplicar o perfil.
  if [ -s "$M54_DIR/turbo.state" ] && [ -x "$(dirname "$0")/turbo.sh" ]; then
    M54_DIR="$M54_DIR" FS_ROOT="${FS_ROOT:-}" FAKE_SETTINGS_DIR="${FAKE_SETTINGS_DIR:-}" sh "$(dirname "$0")/turbo.sh" restore 2>/dev/null
  fi
  # Ao sair do jogo volta ao perfil escolhido (auto com game-active vazio; não apaga p/ stock idle)
  if [ -f "$M54_DIR/framework.enabled" ] && [ -x "$(dirname "$0")/framework.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/framework.sh" auto 2>/dev/null
  elif [ -x "$(dirname "$0")/tune.sh" ]; then
    M54_DIR="$M54_DIR" sh "$(dirname "$0")/tune.sh" auto 2>/dev/null
  fi
  if [ -f "$M54_DIR/dnd.enabled" ] && [ -x "$(dirname "$0")/dnd.sh" ]; then
    read_gamefile
    if [ -z "$CUR" ]; then
      M54_DIR="$M54_DIR" nohup sh "$(dirname "$0")/dnd.sh" off >>"$M54_DIR/fusao.log" 2>&1 &
    fi
  fi
}

# --- Sessão SEGURADA (A-1) ---------------------------------------------------------------------------------
# O observer grava "" quando o jogo perde a activity em primeiro plano; no loading do NTE isso é um bounce de
# segundos, então o watch regrava o jogo no game-active (os outros leitores — tune/fas/gos/CLI — seguem vendo a
# sessão) e SEGURA. Antes a história acabava aí: com o arquivo igual ao LAST nada mais era reavaliado, e como o
# observer já tinha o jogo como "fora" ele nunca mais gravava o exit — jogo morto com a tela apagada = perfil
# game, fas-rs e Não Perturbe presos até a próxima sessão. Agora a sessão segurada é reconferida:
#   - o observer regravou o arquivo (mais novo que o marcador) => foco confirmado por evento, solta a segurada;
#   - senão, a cada HOLD_EVERY voltas (2 s cada): processo morto OU janela em foco não é a do jogo => exit.
HOLD=0
HOLD_TICK=0
HOLD_MARK="$M54_DIR/.watch.hold"
HOLD_EVERY="${WATCH_HOLD_EVERY:-5}"
case "$HOLD_EVERY" in ''|*[!0-9]*) HOLD_EVERY=5;; esac

hold_begin() {
  write_state "$LAST"
  : > "$HOLD_MARK" 2>/dev/null
  HOLD=1
  HOLD_TICK=0
}

hold_end() {
  HOLD=0
  HOLD_TICK=0
  rm -f "$HOLD_MARK" 2>/dev/null
}

# hold_still_valid — a sessão segurada continua de pé: o processo do jogo vive e a JANELA em foco é a dele.
hold_still_valid() {
  pidof "$LAST" >/dev/null 2>&1 || return 1
  [ "$(focused_window_pkg)" = "$LAST" ]
}

# hold_tick — uma volta com a sessão segurada. rc 0 = segue segurada/solta; rc 1 = sessão encerrada (exit feito).
hold_tick() {
  if [ -e "$HOLD_MARK" ] && [ "$GAMEFILE" -nt "$HOLD_MARK" ]; then
    hold_end
    return 0
  fi
  HOLD_TICK=$((HOLD_TICK + 1))
  [ "$HOLD_TICK" -ge "$HOLD_EVERY" ] || return 0
  HOLD_TICK=0
  hold_still_valid && return 0
  log "watch: sessão segurada sem o jogo em foco ($LAST), encerrando"
  write_state ""
  hold_end
  fire_exit "$LAST"
  LAST=""
  return 1
}

# --- Deriva de teto de CPU e de GPU (M-3) ---------------------------------------------------------------------------
# O tune grava no profile.state o teto que o perfil pede (target_p0_max/target_p4_max; ausentes com o FAS dono
# da CPU) e target_gpu_max. O watch NÃO escreve nó: a cada DRIFT_EVERY voltas lê os tetos (builtins, sem fork) e,
# fora de jogo, se algum está ACIMA do alvo — o nosso pedido regravado por outro escritor, como o battery ao entrar em
# Dozing — pede UMA regravação ao `tune.sh reassert` (verify -> regrava só o que está acima -> verify -> log).
# No máximo uma a cada DRIFT_MIN_GAP_S: é reação a um evento (o nó mudou), nunca laço de reescrita. O intervalo
# era 900 s e a GPU ficava de fora; regra do dono, 2026-11-14 ("não tem essa de OneUI mandar"): a GPU entra e o
# intervalo cai para 60 s. Cada regravação fica no log, então o log conta quantas vezes a ROM insistiu.
DRIFT_EVERY="${WATCH_DRIFT_EVERY:-15}"
DRIFT_MIN_GAP_S="${WATCH_DRIFT_MIN_GAP_S:-60}"
case "$DRIFT_EVERY" in ''|*[!0-9]*) DRIFT_EVERY=15;; esac
case "$DRIFT_MIN_GAP_S" in ''|*[!0-9]*) DRIFT_MIN_GAP_S=60;; esac
DRIFT_TICK=0
DRIFT_FIRED=0
DRIFT_LAST_FIRE=0
DRIFT_P0_MAX="${FS_ROOT:-}/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
DRIFT_P4_MAX="${FS_ROOT:-}/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
DRIFT_GPU_MAX="${FS_ROOT:-}/sys/kernel/gpu/gpu_max_clock"

# node_over <nó> <alvo> — rc 0 só se o nó lê um inteiro MAIOR que o alvo (ilegível/sem alvo = rc 1).
node_over() {
  local _v=""
  case "$2" in ''|*[!0-9]*) return 1;; esac
  [ -r "$1" ] || return 1
  IFS= read -r _v < "$1" 2>/dev/null
  case "$_v" in ''|*[!0-9]*) return 1;; esac
  [ "$_v" -gt "$2" ]
}

check_cap_drift() {
  local _l _prof="" _stamp="" _t0="" _t4="" _tg="" _now
  [ "$DRIFT_EVERY" = 0 ] && return 0
  DRIFT_TICK=$((DRIFT_TICK + 1))
  [ "$DRIFT_TICK" -ge "$DRIFT_EVERY" ] || return 0
  DRIFT_TICK=0
  # em jogo o apply/settle e o FAS são os donos da CPU; com o kill-switch ninguém escreve
  [ -n "$LAST" ] && return 0
  [ -f "$M54_DIR/disable" ] && return 0
  [ -r "$M54_DIR/profile.state" ] || return 0
  [ -x "$(dirname "$0")/tune.sh" ] || return 0
  while IFS= read -r _l || [ -n "$_l" ]; do
    case "$_l" in
      profile=*) _prof=${_l#*=};;
      applied_at=*) _stamp=${_l#*=};;
      target_p0_max=*) _t0=${_l#*=};;
      target_p4_max=*) _t4=${_l#*=};;
      target_gpu_max=*) _tg=${_l#*=};;
    esac
  done < "$M54_DIR/profile.state" 2>/dev/null
  case "$_prof" in battery|balanced|game) ;; *) return 0;; esac
  case "$_stamp" in ''|*[!0-9]*) return 0;; esac
  node_over "$DRIFT_P0_MAX" "$_t0" || node_over "$DRIFT_P4_MAX" "$_t4" || node_over "$DRIFT_GPU_MAX" "$_tg" || return 0
  _now=$(uptime_s)
  case "$_now" in ''|*[!0-9]*) _now=0;; esac
  if [ "$DRIFT_FIRED" = 1 ] && [ $((_now - DRIFT_LAST_FIRE)) -lt "$DRIFT_MIN_GAP_S" ]; then
    return 0
  fi
  DRIFT_FIRED=1
  DRIFT_LAST_FIRE=$_now
  log "watch: teto de CPU/GPU acima do perfil $_prof (regravado por fora), reassert 1x"
  REASSERT_DELAY_S=0 M54_DIR="$M54_DIR" FS_ROOT="${FS_ROOT:-}" nohup sh "$(dirname "$0")/tune.sh" reassert "$_prof" "$_stamp" >/dev/null 2>&1 &
}

while true; do
  if observer_alive; then
    if [ "$STANDBY" = 0 ]; then
      STANDBY=1
      log "watch: observer ativo, standby (consome game-active, sem poll)"
    fi
    read_gamefile
    if [ "$HOLD" = 1 ] && [ "$CUR" = "$LAST" ]; then
      hold_tick
    elif [ "$CUR" != "$LAST" ]; then
      # o arquivo mudou por evento do observer: a segurada (se havia) acabou
      [ "$HOLD" = 1 ] && hold_end
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
          read_gamefile
          if [ -n "$CUR" ] && in_gamelist "$CUR"; then
            [ "$CUR" != "$LAST" ] && { fire_enter "$CUR"; LAST="$CUR"; }
            sleep 2
            continue
          fi
          # janela em foco (mCurrentFocus), não o app em foco: atrás do keyguard o mFocusedApp ainda é o jogo
          FOCUS_CHK=$(focused_window_pkg)
          if [ "$FOCUS_CHK" = "$LAST" ]; then
            hold_begin
            sleep 2
            continue
          fi
        fi
        fire_exit "$LAST"
        LAST=""
      fi
    fi
    check_cap_drift
    nap 2
    continue
  fi
  if [ "$HOLD" = 1 ]; then
    hold_end
  fi
  if [ "$STANDBY" = 1 ]; then
    STANDBY=0
    write_state ""
    log "watch: observer morto, relaunch + poll 5s"
    relaunch_observer
  fi
  relaunch_observer
  check_cap_drift
  nap 5
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
