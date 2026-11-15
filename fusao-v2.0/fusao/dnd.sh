#!/system/bin/sh
# fusao-dnd.sh — Não Perturbe em jogo (feature, não fps).
# Sem path externo, com DND_DRYRUN p/ teste (DND_FAKE_ZEN finge o `settings get`).
# Uso: dnd.sh on | off. Estado persistido em dnd.state (uninstall força off).
#
# Fatos provados no M54 (OneUI 8.5, 2026-10-13):
# - `settings put global zen_mode` SOZINHO não move nada (NotificationManager é
#   o dono do valor; puts mantidos por compat, sem efeito).
# - `cmd notification set_dnd priority` => zen=1 (modo desejado).
# - `cmd notification set_dnd on` => zen=2 (fallback válido, mais forte).
# - `cmd`/`settings` no caminho zen falham com "Failed transaction" na
#   tempestade do game-enter/exit ( binder do notification saturado; o `settings`
#   do framework no mesmo minuto APLICA — só o caminho zen trava). Por isso:
#   retry longo com backoff (tenta por ~35s) + guarda de geração (um enter novo
#   cancela o run velho; um exit cancela o on em voo) + log FALHOU em vez de
#   mentir. O watch chama async (não bloqueia o framework).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

MODE="${1:-}"
STATE="$M54_DIR/dnd.state"
SEQF="$M54_DIR/dnd.seq"
# Estado do USUÁRIO antes do 1º `on` (M-6): zen=<0|1|2|3> e heads_up=<0|1>. Existe enquanto o FUSAO "deve" um
# off; o off devolve exatamente isto e apaga o arquivo. Antes o off sempre forçava zen_mode 0 e heads-up 1:
# quem já estava em Não Perturbe (ou sem heads-up) perdia a configuração ao sair do jogo, no `fusao dnd off`
# e no uninstall.
PREVF="$M54_DIR/dnd.prev"

# dnd_fake <comando...> — backend de teste com ESTADO (DND_FAKE_DIR): zen e heads_up vivem em arquivos e os
# comandos os alteram como o aparelho faz (set_dnd priority=1 / on=2 / off=0; `settings put zen_mode` sozinho
# não move nada). Permite ao teste offline conferir o estado FINAL, não a sequência de comandos.
dnd_fake() {
  echo "$*" >> "$DND_FAKE_DIR/calls" 2>/dev/null
  case "$*" in
    "cmd notification set_dnd priority") echo 1 > "$DND_FAKE_DIR/zen";;
    "cmd notification set_dnd on") echo 2 > "$DND_FAKE_DIR/zen";;
    "cmd notification set_dnd off") echo 0 > "$DND_FAKE_DIR/zen";;
    "settings put global heads_up_notifications_enabled "*) echo "$5" > "$DND_FAKE_DIR/heads";;
  esac
  return 0
}

run() {
  if [ -n "$DND_FAKE_DIR" ]; then
    dnd_fake "$@"
    return 0
  fi
  if [ -n "$DND_DRYRUN" ]; then
    echo "dryrun: $*"
    return 0
  fi
  # REGRA STDIO (provada ao vivo E1 x E4 + framework ok x dnd fail): `cmd` e
  # `settings` com stdout herdado de arquivo em /data/adb FALHAM no binder
  # ("Failed transaction"). Sempre pipe (`$()`) ou /dev/null — nunca herdar.
  # (framework.sh já cumpre: writes com >/dev/null 2>&1, reads em $().)
  _o=$("$@" 2>&1)
  _r=$?
  [ "$_r" -ne 0 ] && log "dnd: comando falhou ($*) => $_o"
  return "$_r"
}

nap() {
  [ -n "$DND_DRYRUN" ] && return 0
  [ -n "$DND_FAKE_DIR" ] && return 0
  sleep "$1" 2>/dev/null
}

zen_get() {
  if [ -n "$DND_FAKE_DIR" ]; then
    tr -d '[:space:]' < "$DND_FAKE_DIR/zen" 2>/dev/null
    return
  fi
  if [ -n "$DND_DRYRUN" ]; then
    printf "%s" "$DND_FAKE_ZEN"
    return
  fi
  settings get global zen_mode 2>/dev/null | tr -d '[:space:]'
}

# heads_get — heads_up_notifications_enabled atual (0|1; vazio/null se o sistema não respondeu).
heads_get() {
  if [ -n "$DND_FAKE_DIR" ]; then
    tr -d '[:space:]' < "$DND_FAKE_DIR/heads" 2>/dev/null
    return
  fi
  if [ -n "$DND_DRYRUN" ]; then
    printf "%s" "${DND_FAKE_HEADS:-1}"
    return
  fi
  settings get global heads_up_notifications_enabled 2>/dev/null | tr -d '[:space:]'
}

zen_on_ok() {
  _z="$1"
  [ "$_z" = "1" ] || [ "$_z" = "2" ]
}

# prev_field <zen|heads_up> — campo do snapshot do usuário (vazio se não há snapshot).
prev_field() {
  grep "^$1=" "$PREVF" 2>/dev/null | tail -n 1 | cut -d= -f2-
}

# user_dnd_active <zen> — o usuário já estava em algum modo Não Perturbe (1 prioridade, 2 silêncio, 3 alarmes).
user_dnd_active() {
  case "$1" in 1|2|3) return 0;; esac
  return 1
}

# Guarda de geração: enter/exit novos cancelam o run em voo (sem flip-flop).
myseq="$(uptime_s 2>/dev/null).$$"
printf "%s" "$myseq" > "$SEQF" 2>/dev/null
mine() {
  [ "$(cat "$SEQF" 2>/dev/null)" = "$myseq" ]
}

case "$MODE" in
  on)
    # Snapshot do usuário só quando o FUSAO ainda não deve um off: jogo A -> jogo B sem exit (ou um on repetido)
    # não pode regravar o snapshot com o estado que o próprio FUSAO ligou.
    if [ ! -s "$PREVF" ]; then
      { echo "zen=$(zen_get)"; echo "heads_up=$(heads_get)"; } | atomic_write "$PREVF" 0600
    fi
    _pz=$(prev_field zen)
    if user_dnd_active "$_pz"; then
      # o usuário já está em Não Perturbe: o modo dele vale, o FUSAO não mexe (e por isso não desliga no off)
      mine || { log "dnd: on preterido (evento novo)"; exit 0; }
      printf "on" | atomic_write "$STATE" 0600
      log "dnd: on (Não Perturbe do usuário já ativo, zen=$_pz: nada alterado)"
      exit 0
    fi
    _n=0
    _got=""
    for _wait in 0 2 4 8 16; do
      [ "$_wait" != "0" ] && nap "$_wait"
      mine || { log "dnd: on preterido (evento novo)"; exit 0; }
      run cmd notification set_dnd priority || run cmd notification set_dnd on
      nap 1
      _got=$(zen_get)
      zen_on_ok "$_got" && break
      _n=$((_n + 1))
    done
    mine || { log "dnd: on preterido (evento novo)"; exit 0; }
    run settings put global zen_mode 1
    run settings put global heads_up_notifications_enabled 0
    _got=$(zen_get)
    if zen_on_ok "$_got"; then
      printf "on" | atomic_write "$STATE" 0600
      log "dnd: on (zen=$_got)"
    else
      log "dnd: FALHOU on (zen=$_got após 5 tentativas)"
    fi
    ;;
  off)
    # Sem snapshot e sem estado "on" o FUSAO não ligou nada: não desliga nada (fusao dnd off, uninstall e
    # cleanup com o usuário em Não Perturbe por conta própria).
    _st=""
    [ -f "$STATE" ] && _st=$(cat "$STATE" 2>/dev/null)
    if [ ! -s "$PREVF" ] && [ "$_st" != "on" ]; then
      printf "off" | atomic_write "$STATE" 0600
      exit 0
    fi
    _pz=$(prev_field zen)
    _ph=$(prev_field heads_up)
    if user_dnd_active "$_pz"; then
      # o on não mexeu (o usuário já estava em Não Perturbe): o off também não
      mine || { log "dnd: off preterido (evento novo)"; exit 0; }
      rm -f "$PREVF" 2>/dev/null
      printf "off" | atomic_write "$STATE" 0600
      log "dnd: off (Não Perturbe do usuário preservado, zen=$_pz)"
      exit 0
    fi
    _got="?"
    for _wait in 0 2 4 8 16; do
      [ "$_wait" != "0" ] && nap "$_wait"
      mine || { log "dnd: off preterido (evento novo)"; exit 0; }
      run cmd notification set_dnd off
      nap 1
      _got=$(zen_get)
      [ "$_got" = "0" ] && break
    done
    mine || { log "dnd: off preterido (evento novo)"; exit 0; }
    run settings put global zen_mode 0
    # heads-up volta ao que o usuário tinha (0 = ele já usava sem heads-up); sem snapshot, o padrão 1
    case "$_ph" in
      0) run settings put global heads_up_notifications_enabled 0;;
      *) run settings put global heads_up_notifications_enabled 1;;
    esac
    _got=$(zen_get)
    if [ "$_got" = "0" ]; then
      rm -f "$PREVF" 2>/dev/null
      printf "off" | atomic_write "$STATE" 0600
      log "dnd: off"
    else
      log "dnd: FALHOU off (zen=$_got após 5 tentativas)"
    fi
    ;;
  *)
    if [ -f "$STATE" ]; then cat "$STATE"; else echo off; fi
    ;;
esac
