#
# FUSAO-M54 — instalador single-module.
# Chassi de higiene (verify.sh + .sha256), resto próprio.
# shellcheck disable=SC1091,SC2034,SC2317
SKIPUNZIP=1

FUSAO_SEED="/data/adb/fusao"

abort_unsupported_arch() {
	ui_print "*********************************************************"
	ui_print "! Unsupported Architecture: $ARCH"
	ui_print "! FUSAO-M54 needs arm64 (armeabi-v7a legacy only)."
	abort "*********************************************************"
}

abort_corrupted() {
	ui_print "*********************************************************"
	ui_print "! Unable to extract verify.sh!"
	ui_print "! Installation aborted. The module may be corrupted."
	ui_print "! Please re-download and try again."
	abort "*********************************************************"
}

abort_android_version() {
	ui_print "*********************************************************"
	ui_print "! Your Android Version is not supported!"
	ui_print "! Please use Android 10 (Q) or higher."
	ui_print "! Installation aborted."
	abort "*********************************************************"
}

# Check Android version
[ "$API" -lt 29 ] && abort_android_version

# Flashable integrity checkup
ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
[ ! -f "$TMPDIR/verify.sh" ] && abort_corrupted
source "$TMPDIR/verify.sh"

# Extract module files
ui_print "- Extracting module files"
extract "$ZIPFILE" 'module.prop' "$MODPATH"
extract "$ZIPFILE" 'service.sh' "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh' "$MODPATH"
extract "$ZIPFILE" 'action.sh' "$MODPATH"
extract "$ZIPFILE" 'cleanup.sh' "$MODPATH"
extract "$ZIPFILE" 'system/bin/fusaod' "$MODPATH"
extract "$ZIPFILE" 'system/bin/fusao' "$MODPATH"
extract "$ZIPFILE" 'fusao/thermal_guard.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/thermal_guard_stop.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/lib.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/gos_killer.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/watch.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/framework.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/perf.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/bugreport.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/dnd.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/display.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/tune.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/observer.sh' "$MODPATH"
extract "$ZIPFILE" 'fusao/observer.dex' "$MODPATH"
extract "$ZIPFILE" 'fusao/gamelist.txt' "$MODPATH"
extract "$ZIPFILE" 'fusao/config' "$MODPATH"
cp "$MODPATH/module.prop" "$MODPATH/module.prop.orig"

# Target architecture (fusaod = arm64; v7a mantido p/ futuro)
case $ARCH in
"arm64") ;;
"arm") abort_unsupported_arch ;;
*) abort_unsupported_arch ;;
esac

# FUSAO-M54: self-check de extração (lição temp-root: instalador que não
# desembarca subdir em silêncio). Falha aqui, com nome, em vez de boot capenga.
for f in service.sh uninstall.sh cleanup.sh action.sh system/bin/fusaod system/bin/fusao fusao/thermal_guard.sh fusao/thermal_guard_stop.sh fusao/lib.sh fusao/gos_killer.sh fusao/watch.sh fusao/framework.sh fusao/perf.sh fusao/bugreport.sh fusao/tune.sh fusao/dnd.sh fusao/display.sh fusao/observer.sh fusao/observer.dex fusao/gamelist.txt fusao/config; do
	if [ -f "$MODPATH/$f" ]; then
		ui_print "- [OK] $f"
	else
		ui_print "- [MISSING] $f"
	fi
done
[ -f "$MODPATH/system/bin/fusaod" ] || abort "self-check: fusaod ausente após extração"

# Skip mountify
touch "$MODPATH/skip_mountify"

if [ "$KSU" = "true" ] || [ "$APATCH" = "true" ]; then
  ui_print "- KSU/AP Detected, skipping module mount (skip_mount)"
  rm "$MODPATH/action.sh"
  touch "$MODPATH/skip_mount"

  # symlink ourselves on $PATH
  manager_paths="/data/adb/ap/bin /data/adb/ksu/bin"
  BIN_PATH="/data/adb/modules/encore/system/bin"
  for dir in $manager_paths; do
	[ -d "$dir" ] && {
		ui_print "- Creating symlink in $dir"
	ln -sf "$BIN_PATH/fusaod" "$dir/fusaod"
	ln -sf "$BIN_PATH/fusao" "$dir/fusao"
	}
  done
fi

# Seed do SSOT em /data/adb/fusao (só cria o ausente — nunca sobrescreve
# perfil/gamelist do usuário; regra: instalador não apaga estado).
ui_print "- Seeding /data/adb/fusao"
mkdir -p "$FUSAO_SEED"
chmod 0700 "$FUSAO_SEED"
[ -f "$FUSAO_SEED/config" ] || cp "$MODPATH/fusao/config" "$FUSAO_SEED/config"
[ -f "$FUSAO_SEED/gamelist.txt" ] || cp "$MODPATH/fusao/gamelist.txt" "$FUSAO_SEED/gamelist.txt"
[ -f "$FUSAO_SEED/profile" ] || printf "balanced" >"$FUSAO_SEED/profile"
[ -f "$FUSAO_SEED/game-active.txt" ] || : >"$FUSAO_SEED/game-active.txt"
chmod 0600 "$FUSAO_SEED/config" "$FUSAO_SEED/profile" 2>/dev/null

# Permission settings
ui_print "- Permission setup"
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/fusao" 0 0 0755 0755

ui_print "- $(grep -E ^version= "$MODPATH/module.prop" 2>/dev/null | cut -d= -f2-) pronto"
