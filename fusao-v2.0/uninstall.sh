#!/system/bin/sh
# FUSAO-M54 — desinstalacao completa e devolucao de estado stock/permissoes (MIT).

DATA_DIR="/data/adb/fusao"

# Para executores em ordem segura antes de devolver nos do sistema.
pkill -f "[f]as-rs run" 2>/dev/null
[ -x "$DATA_DIR/framework.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/framework.sh" idle 2>/dev/null
[ -x "$DATA_DIR/thermal_guard_stop.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/thermal_guard_stop.sh" 2>/dev/null

for _pat in "thermal_guard.sh" "fusaod serve" "/system/bin/fusaod" "[g]os_killer" "fusao-watch" "watch.sh" "fusao.observer"; do
	pkill -f "$_pat" 2>/dev/null
done
pkill -f "observer.sh" 2>/dev/null
rm -f "$DATA_DIR"/*_pid 2>/dev/null

[ -x "$DATA_DIR/dnd.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/dnd.sh" off 2>/dev/null
[ -x "$DATA_DIR/display.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/display.sh" reset 2>/dev/null
[ -x "$DATA_DIR/perf.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/perf.sh" exit 2>/dev/null
[ -x "$DATA_DIR/tune.sh" ] && M54_DIR="$DATA_DIR" sh "$DATA_DIR/tune.sh" idle 2>/dev/null

if [ -f "$DATA_DIR/default_cpu_gov" ]; then
	read -r _rev_gov <"$DATA_DIR/default_cpu_gov"
else
	_rev_gov="energy_aware"
fi
grep -q "$_rev_gov" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null || _rev_gov="energy_aware"
grep -q "$_rev_gov" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null || _rev_gov="schedutil"
for _g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
	[ -f "$_g" ] && {
		chmod 644 "$_g" 2>/dev/null
		echo "$_rev_gov" >"$_g" 2>/dev/null
	}
done

for _z in /sys/class/thermal/thermal_zone*/mode; do
	[ -f "$_z" ] && echo enabled >"$_z" 2>/dev/null
done

# Restaura stock.snapshot descendo policy0 pelos degraus reais do aparelho.
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

# Restaura scheduler e fila IO stock do Galaxy M54.
for _b in sda sdb sdc sdd sde; do
	[ -f "/sys/block/$_b/queue/scheduler" ] && echo "ssg" >"/sys/block/$_b/queue/scheduler" 2>/dev/null
	[ -f "/sys/block/$_b/queue/nomerges" ] && echo "0" >"/sys/block/$_b/queue/nomerges" 2>/dev/null
	[ -f "/sys/block/$_b/queue/add_random" ] && echo "0" >"/sys/block/$_b/queue/add_random" 2>/dev/null
	[ -f "/sys/block/$_b/queue/read_ahead_kb" ] && echo "128" >"/sys/block/$_b/queue/read_ahead_kb" 2>/dev/null
done

# Devolve triagem de chamadas, listener de SMS e lista de bateria ao estado anterior.
if [ -f "$DATA_DIR/block.state" ]; then
	_bk_pkg="com.orkgabb.fusao"
	_bk_prev=$(sed -n 's/^role_prev=//p' "$DATA_DIR/block.state" 2>/dev/null | head -n 1)
	_bk_idle=$(sed -n 's/^idle_prev=//p' "$DATA_DIR/block.state" 2>/dev/null | head -n 1)
	cmd role remove-role-holder android.app.role.CALL_SCREENING "$_bk_pkg" >/dev/null 2>&1
	[ -n "$_bk_prev" ] && cmd role add-role-holder android.app.role.CALL_SCREENING "$_bk_prev" >/dev/null 2>&1
	cmd notification disallow_listener "$_bk_pkg/$_bk_pkg.block.BlockNotificationListener" >/dev/null 2>&1
	[ "$_bk_idle" = "1" ] || cmd deviceidle whitelist -"$_bk_pkg" >/dev/null 2>&1
fi

rm -rf "$DATA_DIR" /sdcard/Android/fas-rs
rm -f /data/adb/service.d/.fusao_cleanup.sh

for _dir in /data/adb/ap/bin /data/adb/ksu/bin; do
	[ -d "$_dir" ] && rm -f "$_dir/fusaod" "$_dir/fusao"
done
