#!/system/bin/sh
# FUSAO-M54 — limpeza e devolucao ao stock quando o modulo e desativado ou removido (MIT).

MODULE_DIR="/data/adb/modules/fusao"
THIS_SCRIPT="/data/adb/service.d/.fusao_cleanup.sh"
DATA_DIR="/data/adb/fusao"

if [ ! -d "$MODULE_DIR" ] || [ -f "$MODULE_DIR/disable" ]; then
	# Para o fas-rs primeiro para evitar sobrescrita de CPU durante a restauracao.
	pkill -f "[f]as-rs run" 2>/dev/null
	[ -x "$DATA_DIR/framework.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/framework.sh" idle 2>/dev/null
	[ -x "$DATA_DIR/thermal_guard_stop.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/thermal_guard_stop.sh" 2>/dev/null

	for _pat in "fusaod serve" "fusao-watch" "watch.sh" "fusao.observer" "observer.sh" "thermal_guard.sh" "[g]os_killer"; do
		pkill -f "$_pat" 2>/dev/null
	done
	rm -f "$DATA_DIR"/*_pid 2>/dev/null
	: >"$DATA_DIR/game-active.txt" 2>/dev/null

	[ -x "$DATA_DIR/dnd.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/dnd.sh" off 2>/dev/null
	[ -x "$DATA_DIR/display.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/display.sh" reset 2>/dev/null
	[ -x "$DATA_DIR/perf.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/perf.sh" exit 2>/dev/null
	[ -x "$DATA_DIR/tune.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/tune.sh" idle 2>/dev/null

	if [ -f "$DATA_DIR/default_cpu_gov" ]; then
		read -r _rev_gov <"$DATA_DIR/default_cpu_gov"
		for _g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
			[ -f "$_g" ] && {
				chmod 644 "$_g" 2>/dev/null
				echo "$_rev_gov" >"$_g" 2>/dev/null
			}
		done
	fi

	for _z in /sys/class/thermal/thermal_zone*/mode; do
		[ -f "$_z" ] && echo enabled >"$_z" 2>/dev/null
	done

	# Restaura stock.snapshot; policy0 exige descida por degraus reais do no no M54.
	if [ -f "$DATA_DIR/stock.snapshot" ]; then
		while read -r _p _v; do
			case "$_p" in
			\#* | "") continue ;;
			*)
				if [ -f "$_p" ]; then
					chmod 644 "$_p" 2>/dev/null
					case "$_p" in
					*/cpufreq/policy0/scaling_min_freq)
						_cur=$(cat "$_p" 2>/dev/null)
						_tab=$(cat "${_p%/*}/scaling_available_frequencies" 2>/dev/null)
						[ -n "$_tab" ] || _tab="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000"
						for _step in $(printf '%s\n' $_tab | sort -rn); do
							[ "$_step" -lt "${_cur:-0}" ] 2>/dev/null && [ "$_step" -gt "$_v" ] 2>/dev/null && echo "$_step" >"$_p" 2>/dev/null
						done
						;;
					esac
					echo "$_v" >"$_p" 2>/dev/null
				fi
				;;
			esac
		done <"$DATA_DIR/stock.snapshot"
	fi

	rm -f "$THIS_SCRIPT"
	exit 0
fi

[ -f "$MODULE_DIR/module.prop.orig" ] && cp -f "$MODULE_DIR/module.prop.orig" "$MODULE_DIR/module.prop" 2>/dev/null
