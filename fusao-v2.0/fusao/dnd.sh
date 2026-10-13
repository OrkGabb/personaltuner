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
#   mentir. O watch chama async (não bloqueia preload/framework).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

MODE="${1:-}"
STATE="$M54_DIR/dnd.state"
SEQF="$M54_DIR/dnd.seq"

run() {
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
  sleep "$1" 2>/dev/null
}

zen_get() {
  if [ -n "$DND_DRYRUN" ]; then
    printf "%s" "$DND_FAKE_ZEN"
    return
  fi
  settings get global zen_mode 2>/dev/null | tr -d '[:space:]'
}

zen_on_ok() {
  _z="$1"
  [ "$_z" = "1" ] || [ "$_z" = "2" ]
}

# Guarda de geração: enter/exit novos cancelam o run em voo (sem flip-flop).
myseq="$(uptime_s 2>/dev/null).$$"
printf "%s" "$myseq" > "$SEQF" 2>/dev/null
mine() {
  [ "$(cat "$SEQF" 2>/dev/null)" = "$myseq" ]
}

case "$MODE" in
  on)
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
    run settings put global heads_up_notifications_enabled 1
    _got=$(zen_get)
    if [ "$_got" = "0" ]; then
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
