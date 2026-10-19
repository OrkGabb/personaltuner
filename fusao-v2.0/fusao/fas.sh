#!/system/bin/sh
# fusao-fas.sh — FAS (fas-rs, fork M54: tools/fas-rs-m54) como dono da CPU na sessão de jogo.
#   enter <pkg>  gera a lista do fas-rs a partir da gamelist (SSOT) e liga o daemon
#   exit         desliga; o tune.sh do exit é quem devolve a CPU ao perfil (o fas-rs sai com min=max)
#   gen | status | mode <powersave|balance|performance|fast> | target <pkg> <auto|30,45,60>
# Medido (kernlab 2026-10-18, Bleach nos gráficos mínimos, limite 45, 6 blocos): bateria -10,4%,
# fps -1,3%, frames >= 33 ms 0,13% -> 0,79%. Alvo errado (acima do que o jogo alcança) = CPU no
# máximo à toa; por isso o automático é uma escada e não um número.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

FAS_DIR="$M54_DIR/fas"
FAS_TOML="$FAS_DIR/games.toml"
# o fas-rs lê a lista do usuário deste caminho fixo (upstream); a nossa é a única que vai para lá
FAS_USER_TOML="${FAS_USER_TOML:-/sdcard/Android/fas-rs/games.toml}"
FAS_LOG="$M54_DIR/fas.log"
FAS_TARGETS="$M54_DIR/fas.targets"
FAS_MODE_F="$M54_DIR/fas.mode"
FAS_NODE="${FAS_NODE:-/dev/fas_rs/mode}"
# fas-rs escolhe o primeiro alvo >= fps atual - 3 (e não controla abaixo do primeiro - 10)
FAS_AUTO="30, 45, 60, 90, 120"

fas_pids() { pgrep -f "[f]as-rs run" 2>/dev/null; }

fas_mode() {
  local _m=""
  [ -f "$FAS_MODE_F" ] && _m=$(tr -d '[:space:]' < "$FAS_MODE_F" 2>/dev/null)
  case "$_m" in powersave|balance|performance|fast) echo "$_m";; *) echo "balance";; esac
}

# fas_targets_for <pkg> — "30, 45, 60" do override (fas.targets: pkg=30,45,60) ou o automático
fas_targets_for() {
  local _t
  _t=$(awk -F= -v p="$1" '$1 == p { v = $2 } END { print v }' "$FAS_TARGETS" 2>/dev/null)
  case "$_t" in
    ''|auto) echo "$FAS_AUTO";;
    *) echo "$_t" | tr -d ' ' | sed 's/,/, /g';;
  esac
}

# valid_targets <lista> — só "auto" ou inteiros 10..240 crescentes separados por vírgula
valid_targets() {
  [ "$1" = auto ] && return 0
  echo "$1" | grep -Eq '^[0-9]{2,3}(,[0-9]{2,3})*$' || return 1
  echo "$1" | tr ',' '\n' | awk 'NR > 1 && $1 <= prev { bad = 1 } $1 < 10 || $1 > 240 { bad = 1 } { prev = $1 } END { exit bad }'
}

fas_gen() {
  mkdir -p "$FAS_DIR" 2>/dev/null
  {
    echo "# gerado pelo FUSAO (fas.sh) a partir de gamelist.txt — não editar à mão;"
    echo "# alvo por jogo: fusao fas target <pkg> <auto|30,45,60>"
    echo "[config]"
    echo "keep_std = false"
    echo "scene_game_list = false"
    echo ""
    echo "[game_list]"
    # um awk só: no aparelho a gamelist tem 533 jogos e um subshell + awk por jogo levava > 30 s
    # dentro do game-enter (2026-10-18). Override por jogo: fas.targets (pkg=30,45,60; o último vale).
    awk -v tf="$FAS_TARGETS" -v auto="$FAS_AUTO" '
      BEGIN {
        while ((getline line < tf) > 0) {
          i = index(line, "=")
          if (i > 1) t[substr(line, 1, i - 1)] = substr(line, i + 1)
        }
      }
      {
        gsub(/[[:space:]]/, "")
        if ($0 == "" || $0 ~ /^#/) next
        v = (($0 in t) && t[$0] != "auto") ? t[$0] : auto
        gsub(/ /, "", v)
        gsub(/,/, ", ", v)
        printf "\"%s\" = [%s]\n", $0, v
      }' "$M54_DIR/gamelist.txt"
    echo ""
    echo "[powersave]"; echo "margin_fps = 3.0"; echo "core_temp_thresh = 80000"; echo ""
    echo "[balance]"; echo "margin_fps = 1.0"; echo "core_temp_thresh = 90000"; echo ""
    echo "[performance]"; echo "margin_fps = 0.3"; echo "core_temp_thresh = 95000"; echo ""
    echo "[fast]"; echo "margin_fps = 0"; echo "core_temp_thresh = 95000"
  } | atomic_write "$FAS_TOML" 0644
}

fas_publish() {
  fas_gen || return 1
  mkdir -p "${FAS_USER_TOML%/*}" 2>/dev/null
  cp -f "$FAS_TOML" "$FAS_USER_TOML" 2>/dev/null
}

# o nó /dev/fas_rs/mode aparece logo depois do fas-rs subir; sem gerenciador de modo ele fica em balance
fas_apply_mode() {
  local _i=0
  while [ ! -e "$FAS_NODE" ] && [ "$_i" -lt 50 ]; do
    sleep 0.2
    _i=$((_i + 1))
  done
  [ -e "$FAS_NODE" ] && echo "$(fas_mode)" > "$FAS_NODE" 2>/dev/null
}

fas_start() {
  local _bin _pkg="$1"
  [ -f "$M54_DIR/fas.enabled" ] || return 1
  [ -f "$M54_DIR/disable" ] && return 1
  _bin=$(fas_bin) || { log "fas: binário fas-rs ausente"; return 1; }
  fas_publish
  if [ -n "$(fas_pids)" ]; then
    # já vivo (troca de jogo): o fas-rs relê a lista sozinho (inotify) e segue
    log "fas: já ativo [$_pkg]"
    return 0
  fi
  # uma sessão por log: o status lê a última linha de controle sem pegar a da sessão anterior
  [ -f "$FAS_LOG" ] && mv -f "$FAS_LOG" "$FAS_LOG.1" 2>/dev/null
  nohup "$_bin" run "$FAS_TOML" >> "$FAS_LOG" 2>&1 &
  log "fas: on pid=$! modo=$(fas_mode) [$_pkg]"
  fas_apply_mode &
  return 0
}

fas_stop() {
  local _p _i=0
  _p=$(fas_pids)
  [ -n "$_p" ] || return 0
  kill -TERM $_p 2>/dev/null
  while [ -n "$(fas_pids)" ] && [ "$_i" -lt 20 ]; do
    sleep 0.1
    _i=$((_i + 1))
  done
  _p=$(fas_pids)
  [ -n "$_p" ] && kill -KILL $_p 2>/dev/null
  log "fas: off"
}

fas_status() {
  local _last _game _gpu
  [ -f "$M54_DIR/fas.enabled" ] && echo "fas=on" || echo "fas=off"
  echo "fas_mode=$(fas_mode)"
  if [ -z "$(fas_pids)" ]; then
    echo "fas_state=stopped"
  else
    _last=$(grep -E "FAS working on|FAS stopped" "$FAS_LOG" 2>/dev/null | tail -n 1)
    case "$_last" in
      *"FAS working on"*)
        echo "fas_state=working"
        _game=$(echo "$_last" | sed -n 's/.*FAS working on \[\(.*\)\].*/\1/p')
        ;;
      *) echo "fas_state=waiting";;
    esac
  fi
  echo "fas_game=$_game"
  _gpu=$(grep "GPU guard:" "$FAS_LOG" 2>/dev/null | tail -n 1)
  case "$_gpu" in
    *saturated*) echo "fas_gpu=saturated";;
    *released*) echo "fas_gpu=released";;
    *) echo "fas_gpu=";;
  esac
  fas_bin >/dev/null && echo "fas_bin=ok" || echo "fas_bin=missing"
}

[ "${FAS_SOURCE_ONLY:-0}" = "1" ] && return 0

case "$1" in
  enter) fas_start "$2";;
  exit) fas_stop;;
  gen) fas_gen && cat "$FAS_TOML";;
  status) fas_status;;
  mode)
    case "$2" in
      powersave|balance|performance|fast)
        printf "%s" "$2" | atomic_write "$FAS_MODE_F" 0644
        [ -e "$FAS_NODE" ] && echo "$2" > "$FAS_NODE" 2>/dev/null
        echo "fas_mode=$2"
        ;;
      *) echo "fas_mode=$(fas_mode)";;
    esac
    ;;
  target)
    if [ -z "$2" ] || ! valid_targets "$3"; then
      echo "uso: fas.sh target <pkg> <auto|30,45,60> (crescente, 10..240)" >&2
      exit 1
    fi
    { awk -F= -v p="$2" '$1 != p' "$FAS_TARGETS" 2>/dev/null
      [ "$3" = auto ] || echo "$2=$3"
    } | atomic_write "$FAS_TARGETS" 0644
    [ -n "$(fas_pids)" ] && fas_publish
    echo "fas_target $2=$(fas_targets_for "$2")"
    ;;
  *) echo "uso: fas.sh enter <pkg>|exit|gen|status|mode <m>|target <pkg> <alvos>" >&2; exit 1;;
esac
