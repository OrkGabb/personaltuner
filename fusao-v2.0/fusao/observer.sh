#!/system/bin/sh
# fusao observer runner — app_process como root (mesmo mecanismo do `am`).
# pgrep -f "fusao.observer" casa a cmdline (com.fusao.observer.ObserverMain).
M54_DIR=${M54_DIR:-/data/adb/fusao}
DEX="$M54_DIR/observer.dex"
[ -f "$DEX" ] || exit 1
export CLASSPATH="$DEX"
exec app_process /system/bin com.fusao.observer.ObserverMain "$M54_DIR"
