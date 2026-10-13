#
# Copyright (C) 2024-2026 Rem01Gaming
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

MODDIR=$(dirname "$0")
MODULE_CONFIG="/data/adb/fusao"
CLEANUP_SCRIPT="/data/adb/service.d/.fusao_cleanup.sh"
CPUFREQ="/sys/devices/system/cpu/cpu0/cpufreq"

# Restore original module.prop
[ -f "$MODDIR/module.prop.orig" ] && {
  cp "$MODDIR/module.prop.orig" "$MODDIR/module.prop"
}

# Rotação de logs no boot: fusaod.log não tem rotação própria (Rust só
# escreve) — sem isso, 1 dia de briga com driver externo gera MBs.
# Guarda a cauda (últimas 2000 linhas) em vez de apagar tudo.
if [ -f "$MODULE_CONFIG/fusaod.log" ]; then
  _sz=$(wc -c <"$MODULE_CONFIG/fusaod.log" 2>/dev/null)
  case "$_sz" in ''|*[!0-9]*) _sz=0;; esac
  if [ "$_sz" -gt 1048576 ]; then
    tail -n 2000 "$MODULE_CONFIG/fusaod.log" >"$MODULE_CONFIG/fusaod.log.tmp" 2>/dev/null
    mv -f "$MODULE_CONFIG/fusaod.log.tmp" "$MODULE_CONFIG/fusaod.log" 2>/dev/null
  fi
fi

# Parse Governor to use
chmod 644 "$CPUFREQ/scaling_governor"
default_gov=$(cat "$CPUFREQ/scaling_governor")
echo "$default_gov" >$MODULE_CONFIG/default_cpu_gov

# Create cleanup script
[ ! -f "$CLEANUP_SCRIPT" ] && {
  mkdir -p "$(dirname $CLEANUP_SCRIPT)"
  cp "$MODDIR/cleanup.sh" "$CLEANUP_SCRIPT"
  chmod +x "$CLEANUP_SCRIPT"
}

# Wait until boot completed
while [ -z "$(getprop sys.boot_completed)" ]; do
	sleep 40
done

# Handle case when 'default_gov' is performance
# FUSAO-M54: energy_aware primeiro — é o stock do M54 (provado no aparelho).
default_gov_preferred_array="
energy_aware
scx
schedhorizon
walt
sched_pixel
sugov_ext
uag
schedplus
energy_step
schedutil
interactive
conservative
powersave
"

if [ "$default_gov" == "performance" ]; then
	for gov in $default_gov_preferred_array; do
		grep -q "$gov" "$CPUFREQ/scaling_available_governors" && {
			echo "$gov" >$MODULE_CONFIG/default_cpu_gov
			default_gov="$gov"
			break
		}
	done
fi

# Revert to normal CPU governor
echo "$default_gov" | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# FUSAO-M54: snapshot de stock no primeiro boot (o uninstall consome).
# Só captura UMA vez — boot com estado tunado contaminaria o default.
if [ ! -f "$MODULE_CONFIG/stock.snapshot" ]; then
	{
		echo "# stock.snapshot $(date)"
		for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq \
			/sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq; do
			[ -f "$p" ] && echo "$p $(cat "$p")"
		done
		[ -f /sys/kernel/gpu/gpu_min_clock ] && echo "/sys/kernel/gpu/gpu_min_clock $(cat /sys/kernel/gpu/gpu_min_clock)"
		[ -f /sys/kernel/gpu/gpu_max_clock ] && echo "/sys/kernel/gpu/gpu_max_clock $(cat /sys/kernel/gpu/gpu_max_clock)"
		for d in 17000010.devfreq_mif 17000020.devfreq_int 17000040.devfreq_disp; do
			[ -f /sys/class/devfreq/$d/min_freq ] && echo "/sys/class/devfreq/$d/min_freq $(cat /sys/class/devfreq/$d/min_freq)"
			[ -f /sys/class/devfreq/$d/max_freq ] && echo "/sys/class/devfreq/$d/max_freq $(cat /sys/class/devfreq/$d/max_freq)"
		done
		echo "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor $default_gov"
	} >"$MODULE_CONFIG/stock.snapshot" 2>/dev/null
fi
# Guarda contra snapshot contaminado: se os floors/maxes não batem com o
# stock canônico (boot anterior com jogo/driver tunado), regrava o canônico.
# Sem isso, uninstall e idle-restore restaurariam valores tunados como stock.
_stock_bad=0
grep -Fq "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 533000" "$MODULE_CONFIG/stock.snapshot" 2>/dev/null || _stock_bad=1
grep -Fq "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 533000" "$MODULE_CONFIG/stock.snapshot" 2>/dev/null || _stock_bad=1
grep -Fq "/sys/kernel/gpu/gpu_max_clock 949000" "$MODULE_CONFIG/stock.snapshot" 2>/dev/null || _stock_bad=1
if [ "$_stock_bad" = 1 ]; then
	{
		echo "# stock.snapshot canonical (reseed boot $(date 2>/dev/null))"
		echo "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 533000"
		echo "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 533000"
		echo "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2002000"
		echo "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 1824000"
		echo "/sys/kernel/gpu/gpu_min_clock 221000"
		echo "/sys/kernel/gpu/gpu_max_clock 949000"
		echo "/sys/class/devfreq/17000010.devfreq_mif/min_freq 421000"
		echo "/sys/class/devfreq/17000010.devfreq_mif/max_freq 2093000"
		echo "/sys/class/devfreq/17000020.devfreq_int/min_freq 50000"
		echo "/sys/class/devfreq/17000020.devfreq_int/max_freq 666000"
		echo "/sys/class/devfreq/17000040.devfreq_disp/min_freq 66000"
		echo "/sys/class/devfreq/17000040.devfreq_disp/max_freq 666000"
		echo "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor energy_aware"
	} >"$MODULE_CONFIG/stock.snapshot" 2>/dev/null
	chmod 0600 "$MODULE_CONFIG/stock.snapshot" 2>/dev/null
	echo "$(date '+%H:%M:%S') service: stock.snapshot reseeded (canonico)" >>"$MODULE_CONFIG/fusao.log" 2>/dev/null
fi

# FUSAO-M54 single-module: UM writer (fusaod via cache), UM detector
# (observer event-driven + watch fallback). Governor intocado (stock governa, floors guiam).
# Kill-switch: toque /data/adb/fusao/disable e nada sobe no próximo boot.
FUSAO_DIR="/data/adb/fusao"
mkdir -p "$FUSAO_DIR" 2>/dev/null
chmod 0700 "$FUSAO_DIR" 2>/dev/null
# Temp-root: soft-reboot NÃO mata loops nohup — sem isso, código VELHO
# sobrevive embaixo do novo. Mata por PID (nunca pkill inline suicida:
# $$ excluído, padrões nunca casam este script).
kill_stale() {
	for _p in $(pgrep -f "$1" 2>/dev/null); do
		[ "$_p" = "$$" ] || kill -9 "$_p" 2>/dev/null
	done
}
for _pat in "fusaod serve" "fusao.observer" "watch.sh" "thermal_guard.sh" "gos_killer.sh"; do
	kill_stale "$_pat"
done
# Self-heal (lição temp-root): boot recopia guard a partir do módulo.
for f in thermal_guard.sh thermal_guard_stop.sh lib.sh gos_killer.sh watch.sh framework.sh perf.sh bugreport.sh tune.sh dnd.sh display.sh observer.sh observer.dex gamelist.txt; do
	[ -f "$MODDIR/fusao/$f" ] && cp -f "$MODDIR/fusao/$f" "$FUSAO_DIR/$f" 2>/dev/null
done
chmod 0755 "$FUSAO_DIR/thermal_guard.sh" "$FUSAO_DIR/thermal_guard_stop.sh" "$FUSAO_DIR/gos_killer.sh" "$FUSAO_DIR/watch.sh" "$FUSAO_DIR/framework.sh" "$FUSAO_DIR/perf.sh" "$FUSAO_DIR/bugreport.sh" "$FUSAO_DIR/tune.sh" "$FUSAO_DIR/dnd.sh" "$FUSAO_DIR/display.sh" "$FUSAO_DIR/observer.sh" 2>/dev/null
[ -f "$FUSAO_DIR/config" ] || {
	echo "thermal=moderate" >"$FUSAO_DIR/config"
	echo "profile=balanced" >>"$FUSAO_DIR/config"
	echo "thermal_guard_high=78000" >>"$FUSAO_DIR/config"
	echo "thermal_guard_low=70000" >>"$FUSAO_DIR/config"
	echo "thermal_guard_interval=2" >>"$FUSAO_DIR/config"
	echo "thermal_guard_timeout=900" >>"$FUSAO_DIR/config"
	chmod 0600 "$FUSAO_DIR/config" 2>/dev/null
}
# Guard: só com thermal=aggressive; reverte sozinho (HIGH/BATT/timeout).
if grep -q "^thermal=aggressive" "$FUSAO_DIR/config" 2>/dev/null; then
	M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/thermal_guard.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
fi
[ -f "$FUSAO_DIR/profile" ] || {
	_grep_prof=$(grep -E "^profile=" "$FUSAO_DIR/config" 2>/dev/null | tail -1 | cut -d= -f2-)
	[ -z "$_grep_prof" ] && _grep_prof="balanced"
	printf "%s" "$_grep_prof" >"$FUSAO_DIR/profile" 2>/dev/null
	chmod 0600 "$FUSAO_DIR/profile" 2>/dev/null
}
[ -f "$FUSAO_DIR/game-active.txt" ] || : >"$FUSAO_DIR/game-active.txt" 2>/dev/null
# Observer event-driven (primário): app_process como root.
if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/observer.sh" ] && [ -f "$FUSAO_DIR/observer.dex" ]; then
	if ! pgrep -f "[f]usao.observer" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/observer.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
# Watch (fallback): standby sozinho quando o observer está vivo.
if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/watch.sh" ]; then
	if ! pgrep -f "[f]usao-watch" >/dev/null 2>&1 && ! pgrep -f "[w]atch.sh" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/watch.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
# GOS killer: Samsung ressuscita o GOS; mata por schedule e loga.
# Padrão pgrep com colchetes: não casa a própria shell.
if [ -x "$FUSAO_DIR/gos_killer.sh" ]; then
	if ! pgrep -f "[g]os_killer" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/gos_killer.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
# Cérebro (CPU floors + GPU/MIF/INT/DISP via cache): só com fusaod.enabled.
# Descida de policy0 por degraus (tabela real). Sem o arquivo, zero writer.
if [ -f "$FUSAO_DIR/fusaod.enabled" ] && [ -x "$MODDIR/system/bin/fusaod" ]; then
	if ! pgrep -f "fusaod serve" >/dev/null 2>&1; then
		nohup "$MODDIR/system/bin/fusaod" serve >>"$FUSAO_DIR/fusaod.log" 2>&1 &
	fi
fi

