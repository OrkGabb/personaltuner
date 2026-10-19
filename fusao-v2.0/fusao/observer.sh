#!/system/bin/sh
# fusao observer runner — app_process como root (mesmo mecanismo do `am`).
# pgrep -f "fusao.observer" casa a cmdline (com.fusao.observer.ObserverMain).
M54_DIR=${M54_DIR:-/data/adb/fusao}
DEX="$M54_DIR/observer.dex"
[ -f "$DEX" ] || exit 1
# Root reativado depois de um boot completo (KSU late-load): o ksud roda os scripts do módulo
#  1) num mount namespace sem as APEX: app_process morre com CANNOT LINK (libnativeloader.so not found);
#  2) com um ambiente de 17 variáveis, sem BOOTCLASSPATH/ANDROID_*/armazenamento: app_process sai na
#     hora, rc 0, sem log.
# O watch ficava no poll de 5 s (aparelho 2026-10-19). O zygote64 é a referência dos dois: entra no
# namespace dele (uma vez só, FUSAO_NS_HOP) e herda o ambiente dele (sem os sockets do zygote).
ART_LIB="${FUSAO_ART_LIB:-/apex/com.android.art/lib64/libnativeloader.so}"
_z=$(pidof zygote64 2>/dev/null | cut -d' ' -f1)
if [ ! -e "$ART_LIB" ] && [ -z "$FUSAO_NS_HOP" ] && [ -n "$_z" ]; then
  FUSAO_NS_HOP=1 M54_DIR="$M54_DIR" exec nsenter -t "$_z" -m -- sh "$0"
fi
ZYGOTE_ENV="${FUSAO_ZYGOTE_ENV:-/proc/$_z/environ}"
if [ -z "$BOOTCLASSPATH" ] && [ -n "$_z" ] && [ -r "$ZYGOTE_ENV" ]; then
  while IFS= read -r _kv; do
    export "$_kv"
  done <<EOF
$(tr '\0' '\n' < "$ZYGOTE_ENV" | grep -v '^ANDROID_SOCKET_' | grep -E '^[A-Z_][A-Z0-9_]*=')
EOF
fi
export CLASSPATH="$DEX"
exec app_process /system/bin com.fusao.observer.ObserverMain "$M54_DIR"
