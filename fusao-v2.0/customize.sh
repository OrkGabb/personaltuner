#!/system/bin/sh
# FUSAO-M54 — instalador single-module para Galaxy M54 (MIT).
# shellcheck disable=SC1091,SC2034,SC2317
SKIPUNZIP=1

FUSAO_SEED="/data/adb/fusao"
FUSAO_BIN_DIR="/data/adb/modules/fusao/system/bin"
LEGACY_MOD="/data/adb/modules/encore"
LEGACY_UPD="/data/adb/modules_update/encore"
CLEANUP_SCRIPT="/data/adb/service.d/.fusao_cleanup.sh"

abort_install() {
	ui_print "*********************************************************"
	ui_print "! $1"
	abort "*********************************************************"
}

[ "${API:-0}" -ge 29 ] || abort_install "Android 10 (API 29) ou superior e obrigatorio."
case "$ARCH" in
arm64) ;;
*) abort_install "Arquitetura nao suportada ($ARCH). O FUSAO-M54 exige arm64." ;;
esac

ui_print "- Preparando verificador de integridade"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2 || abort_install "Nao foi possivel extrair verify.sh."
[ -f "$TMPDIR/verify.sh" ] || abort_install "verify.sh ausente no pacote."
. "$TMPDIR/verify.sh"

ui_print "- Extraindo arquivos do modulo FUSAO-M54"
for _rel in \
	module.prop service.sh uninstall.sh cleanup.sh action.sh LICENSE NOTICE.md \
	system/bin/fusaod system/bin/fusao system/bin/fas-rs \
	fusao/thermal_guard.sh fusao/thermal_guard_stop.sh fusao/lib.sh \
	fusao/gos_killer.sh fusao/watch.sh fusao/framework.sh fusao/perf.sh \
	fusao/bugreport.sh fusao/dnd.sh fusao/display.sh fusao/tune.sh \
	fusao/observer.sh fusao/observer.dex fusao/fas.sh fusao/gamelist.txt fusao/config; do
	extract "$ZIPFILE" "$_rel" "$MODPATH"
done
cp "$MODPATH/module.prop" "$MODPATH/module.prop.orig"

# Self-check pos-extracao: garante que nenhum script/binario sumiu silenciosamente.
for f in \
	service.sh uninstall.sh cleanup.sh action.sh system/bin/fusaod system/bin/fusao system/bin/fas-rs \
	fusao/thermal_guard.sh fusao/thermal_guard_stop.sh fusao/lib.sh fusao/gos_killer.sh \
	fusao/watch.sh fusao/framework.sh fusao/perf.sh fusao/bugreport.sh fusao/tune.sh \
	fusao/dnd.sh fusao/display.sh fusao/observer.sh fusao/observer.dex fusao/fas.sh \
	fusao/gamelist.txt fusao/config; do
	if [ -f "$MODPATH/$f" ]; then
		ui_print "- [OK] $f"
	else
		ui_print "- [MISSING] $f"
	fi
done
[ -f "$MODPATH/system/bin/fusaod" ] || abort_install "self-check: system/bin/fusaod ausente."

# Migracao automatica de instalacoes antigas com id=encore para id=fusao,
# removendo apenas o diretorio do modulo antigo (sem chamar uninstall.sh para
# preservar config, perfil, gamelist e logs em /data/adb/fusao).
[ -f "$CLEANUP_SCRIPT" ] && rm -f "$CLEANUP_SCRIPT" 2>/dev/null
if [ -d "$LEGACY_MOD" ] || [ -d "$LEGACY_UPD" ]; then
	ui_print "- Migrando modulo antigo (encore -> fusao); preservando /data/adb/fusao"
	rm -rf "$LEGACY_MOD" "$LEGACY_UPD" 2>/dev/null
fi

touch "$MODPATH/skip_mountify"

if [ "$KSU" = "true" ] || [ "$APATCH" = "true" ]; then
	ui_print "- KSU/AP detectado; ativando skip_mount e symlinks de CLI"
	rm -f "$MODPATH/action.sh"
	touch "$MODPATH/skip_mount"
	for _dir in /data/adb/ap/bin /data/adb/ksu/bin; do
		if [ -d "$_dir" ]; then
			ui_print "- Linkando fusao/fusaod em $_dir"
			ln -sf "$FUSAO_BIN_DIR/fusaod" "$_dir/fusaod"
			ln -sf "$FUSAO_BIN_DIR/fusao" "$_dir/fusao"
		fi
	done
fi

# Seed inicial em /data/adb/fusao (nunca sobrescreve estado existente do usuario).
ui_print "- Inicializando estado em $FUSAO_SEED"
mkdir -p "$FUSAO_SEED"
chmod 0700 "$FUSAO_SEED"
[ -f "$FUSAO_SEED/config" ] || cp "$MODPATH/fusao/config" "$FUSAO_SEED/config"
[ -f "$FUSAO_SEED/gamelist.txt" ] || cp "$MODPATH/fusao/gamelist.txt" "$FUSAO_SEED/gamelist.txt"
[ -f "$FUSAO_SEED/profile" ] || printf "balanced" >"$FUSAO_SEED/profile"
[ -f "$FUSAO_SEED/game-active.txt" ] || : >"$FUSAO_SEED/game-active.txt"
chmod 0600 "$FUSAO_SEED/config" "$FUSAO_SEED/profile" 2>/dev/null

ui_print "- Ajustando permissoes"
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/fusao" 0 0 0755 0755

ui_print "- $(grep -E ^version= "$MODPATH/module.prop" 2>/dev/null | cut -d= -f2-) pronto"
