#!/system/bin/sh
# FUSAO-M54 — servico de inicializacao do modulo (MIT).

MODDIR=$(dirname "$0")
FUSAO_DIR="/data/adb/fusao"
CLEANUP_SCRIPT="/data/adb/service.d/.fusao_cleanup.sh"
CPUFREQ="/sys/devices/system/cpu/cpu0/cpufreq"

[ -f "$MODDIR/module.prop.orig" ] && cp -f "$MODDIR/module.prop.orig" "$MODDIR/module.prop" 2>/dev/null

# Garante ausencia de diretorio residual de instalacoes antigas (encore -> fusao).
if [ -d /data/adb/modules/encore ] || [ -d /data/adb/modules_update/encore ]; then
	rm -rf /data/adb/modules/encore /data/adb/modules_update/encore 2>/dev/null
fi

mkdir -p "$FUSAO_DIR" 2>/dev/null
chmod 0700 "$FUSAO_DIR" 2>/dev/null

# Limita fusaod.log a 1 MiB antes de iniciar nova sessao.
if [ -f "$FUSAO_DIR/fusaod.log" ]; then
	_sz=$(wc -c <"$FUSAO_DIR/fusaod.log" 2>/dev/null | tr -d '[:space:]')
	case "$_sz" in '' | *[!0-9]*) _sz=0 ;; esac
	if [ "$_sz" -gt 1048576 ]; then
		tail -n 2000 "$FUSAO_DIR/fusaod.log" >"$FUSAO_DIR/fusaod.log.tmp" 2>/dev/null
		mv -f "$FUSAO_DIR/fusaod.log.tmp" "$FUSAO_DIR/fusaod.log" 2>/dev/null
	fi
fi

# Registra e garante governor stock (nunca deixa boot preso em performance).
chmod 644 "$CPUFREQ/scaling_governor" 2>/dev/null
_default_gov=$(cat "$CPUFREQ/scaling_governor" 2>/dev/null)
if [ "$_default_gov" = "performance" ] || [ -z "$_default_gov" ]; then
	for _cand in energy_aware schedutil; do
		if grep -q "$_cand" "$CPUFREQ/scaling_available_governors" 2>/dev/null; then
			_default_gov="$_cand"
			break
		fi
	done
fi
echo "$_default_gov" >"$FUSAO_DIR/default_cpu_gov" 2>/dev/null
for _g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
	[ -f "$_g" ] && echo "$_default_gov" >"$_g" 2>/dev/null
done

# Mantem o cleanup em service.d sempre alinhado com a versao atual do modulo.
mkdir -p "$(dirname "$CLEANUP_SCRIPT")" 2>/dev/null
cp -f "$MODDIR/cleanup.sh" "$CLEANUP_SCRIPT" 2>/dev/null
chmod 0755 "$CLEANUP_SCRIPT" 2>/dev/null

while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
	sleep 5
done

if [ ! -s "$FUSAO_DIR/stock.snapshot" ]; then
	{
		echo "# stock.snapshot $(date)"
		for _p in /sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq \
			/sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq; do
			[ -f "$_p" ] && echo "$_p $(cat "$_p")"
		done
		[ -f /sys/kernel/gpu/gpu_min_clock ] && echo "/sys/kernel/gpu/gpu_min_clock $(cat /sys/kernel/gpu/gpu_min_clock)"
		[ -f /sys/kernel/gpu/gpu_max_clock ] && echo "/sys/kernel/gpu/gpu_max_clock $(cat /sys/kernel/gpu/gpu_max_clock)"
		for _d in 17000010.devfreq_mif 17000020.devfreq_int 17000040.devfreq_disp; do
			[ -f "/sys/class/devfreq/$_d/min_freq" ] && echo "/sys/class/devfreq/$_d/min_freq $(cat "/sys/class/devfreq/$_d/min_freq")"
			[ -f "/sys/class/devfreq/$_d/max_freq" ] && echo "/sys/class/devfreq/$_d/max_freq $(cat "/sys/class/devfreq/$_d/max_freq")"
		done
		echo "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor $_default_gov"
	} >"$FUSAO_DIR/stock.snapshot" 2>/dev/null
fi

_stock_bad=0
grep -Fq "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 533000" "$FUSAO_DIR/stock.snapshot" 2>/dev/null || _stock_bad=1
grep -Fq "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 533000" "$FUSAO_DIR/stock.snapshot" 2>/dev/null || _stock_bad=1
grep -Fq "/sys/kernel/gpu/gpu_max_clock 949000" "$FUSAO_DIR/stock.snapshot" 2>/dev/null || _stock_bad=1
if [ "$_stock_bad" = 1 ]; then
	{
		echo "# stock.snapshot canonical (reseed boot $(date 2>/dev/null))"
		echo "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 533000"
		echo "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 533000"
		echo "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2002000"
		echo "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2400000"
		echo "/sys/kernel/gpu/gpu_min_clock 221000"
		echo "/sys/kernel/gpu/gpu_max_clock 949000"
		echo "/sys/class/devfreq/17000010.devfreq_mif/min_freq 421000"
		echo "/sys/class/devfreq/17000010.devfreq_mif/max_freq 2093000"
		echo "/sys/class/devfreq/17000020.devfreq_int/min_freq 50000"
		echo "/sys/class/devfreq/17000020.devfreq_int/max_freq 666000"
		echo "/sys/class/devfreq/17000040.devfreq_disp/min_freq 66000"
		echo "/sys/class/devfreq/17000040.devfreq_disp/max_freq 666000"
		echo "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor energy_aware"
	} >"$FUSAO_DIR/stock.snapshot" 2>/dev/null
	chmod 0600 "$FUSAO_DIR/stock.snapshot" 2>/dev/null
	echo "$(date '+%H:%M:%S') service: stock.snapshot reseeded (canonico)" >>"$FUSAO_DIR/fusao.log" 2>/dev/null
fi

kill_stale() {
	for _p in $(pgrep -f "$1" 2>/dev/null); do
		[ "$_p" = "$$" ] || kill -9 "$_p" 2>/dev/null
	done
}
for _pat in "fusaod serve" "fusao.observer" "watch.sh" "thermal_guard.sh" "gos_killer.sh" "fas-rs run"; do
	kill_stale "$_pat"
done

for _f in thermal_guard.sh thermal_guard_stop.sh lib.sh gos_killer.sh watch.sh framework.sh perf.sh bugreport.sh tune.sh dnd.sh display.sh observer.sh observer.dex fas.sh; do
	[ -f "$MODDIR/fusao/$_f" ] && cp -f "$MODDIR/fusao/$_f" "$FUSAO_DIR/$_f" 2>/dev/null
done
[ -s "$FUSAO_DIR/gamelist.txt" ] || cp -f "$MODDIR/fusao/gamelist.txt" "$FUSAO_DIR/gamelist.txt" 2>/dev/null
chmod 0755 "$FUSAO_DIR"/*.sh 2>/dev/null

[ -f "$FUSAO_DIR/config" ] || {
	{
		echo "thermal=moderate"
		echo "profile=balanced"
		echo "thermal_guard_high=78000"
		echo "thermal_guard_low=70000"
		echo "thermal_guard_interval=2"
		echo "thermal_guard_timeout=900"
	} >"$FUSAO_DIR/config"
	chmod 0600 "$FUSAO_DIR/config" 2>/dev/null
}

if [ ! -f "$FUSAO_DIR/.flags_seeded" ]; then
	touch "$FUSAO_DIR/framework.enabled" "$FUSAO_DIR/perf.enabled" "$FUSAO_DIR/.flags_seeded" 2>/dev/null
fi
if [ ! -f "$FUSAO_DIR/.fas_seeded" ]; then
	touch "$FUSAO_DIR/fas.enabled" "$FUSAO_DIR/.fas_seeded" 2>/dev/null
fi
rm -f "$FUSAO_DIR/ramclean.enabled" 2>/dev/null
rm -f "$FUSAO_DIR/preload.enabled" "$FUSAO_DIR/preload.state" "$FUSAO_DIR/preload.last" 2>/dev/null
rm -f "$FUSAO_DIR/preload"/*.pack "$FUSAO_DIR/preload"/*.tmp 2>/dev/null
rmdir "$FUSAO_DIR/preload" 2>/dev/null
if [ ! -f "$FUSAO_DIR/.fas_roblox_seeded" ]; then
	{
		grep -v -e '^com\.roblox\.client=' -e '^com\.roblox\.client\.vnggames=' "$FUSAO_DIR/fas.targets" 2>/dev/null
		echo "com.roblox.client=off"
		echo "com.roblox.client.vnggames=off"
	} >"$FUSAO_DIR/fas.targets.tmp" 2>/dev/null &&
		chmod 0644 "$FUSAO_DIR/fas.targets.tmp" 2>/dev/null &&
		mv -f "$FUSAO_DIR/fas.targets.tmp" "$FUSAO_DIR/fas.targets" 2>/dev/null &&
		touch "$FUSAO_DIR/.fas_roblox_seeded" 2>/dev/null &&
		echo "$(date '+%H:%M:%S') service: Roblox fora do FAS (fas.targets off, 1x)" >>"$FUSAO_DIR/fusao.log" 2>/dev/null
fi

if [ ! -f "$FUSAO_DIR/disable" ] && grep -q "^thermal=aggressive" "$FUSAO_DIR/config" 2>/dev/null; then
	M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/thermal_guard.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
fi
[ -f "$FUSAO_DIR/profile" ] || {
	_grep_prof=$(grep -E "^profile=" "$FUSAO_DIR/config" 2>/dev/null | tail -1 | cut -d= -f2-)
	[ -z "$_grep_prof" ] && _grep_prof="balanced"
	printf "%s" "$_grep_prof" >"$FUSAO_DIR/profile" 2>/dev/null
	chmod 0600 "$FUSAO_DIR/profile" 2>/dev/null
}
: >"$FUSAO_DIR/game-active.txt" 2>/dev/null

if [ ! -f "$FUSAO_DIR/disable" ]; then
	if [ -f "$FUSAO_DIR/framework.enabled" ] && [ -x "$FUSAO_DIR/framework.sh" ]; then
		M54_DIR="$FUSAO_DIR" sh "$FUSAO_DIR/framework.sh" auto >/dev/null 2>&1
	elif [ -x "$FUSAO_DIR/tune.sh" ]; then
		M54_DIR="$FUSAO_DIR" sh "$FUSAO_DIR/tune.sh" auto >/dev/null 2>&1
	fi
fi

if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/observer.sh" ] && [ -f "$FUSAO_DIR/observer.dex" ]; then
	if ! pgrep -f "[f]usao.observer" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/observer.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/watch.sh" ]; then
	if ! pgrep -f "[f]usao-watch" >/dev/null 2>&1 && ! pgrep -f "[w]atch.sh" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/watch.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/gos_killer.sh" ]; then
	if ! pgrep -f "[g]os_killer" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/gos_killer.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
if [ ! -f "$FUSAO_DIR/disable" ] && [ -f "$FUSAO_DIR/fusaod.enabled" ] && [ -s "$FUSAO_DIR/game-active.txt" ] && [ -x "$MODDIR/system/bin/fusaod" ]; then
	if ! pgrep -f "[f]usaod serve" >/dev/null 2>&1; then
		nohup "$MODDIR/system/bin/fusaod" serve >>"$FUSAO_DIR/fusaod.log" 2>&1 &
	fi
fi
