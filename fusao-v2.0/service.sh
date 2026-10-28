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

[ -f "$MODDIR/module.prop.orig" ] && {
  cp "$MODDIR/module.prop.orig" "$MODDIR/module.prop"
}

if [ -f "$MODULE_CONFIG/fusaod.log" ]; then
  _sz=$(wc -c <"$MODULE_CONFIG/fusaod.log" 2>/dev/null)
  _sz=$(echo "$_sz" | tr -d '[:space:]')
  case "$_sz" in ''|*[!0-9]*) _sz=0;; esac
  if [ "$_sz" -gt 1048576 ]; then
    tail -n 2000 "$MODULE_CONFIG/fusaod.log" >"$MODULE_CONFIG/fusaod.log.tmp" 2>/dev/null
    mv -f "$MODULE_CONFIG/fusaod.log.tmp" "$MODULE_CONFIG/fusaod.log" 2>/dev/null
  fi
fi

chmod 644 "$CPUFREQ/scaling_governor" 2>/dev/null
default_gov=$(cat "$CPUFREQ/scaling_governor" 2>/dev/null)
echo "$default_gov" >"$MODULE_CONFIG/default_cpu_gov"

[ ! -f "$CLEANUP_SCRIPT" ] && {
  mkdir -p "$(dirname "$CLEANUP_SCRIPT")"
  cp "$MODDIR/cleanup.sh" "$CLEANUP_SCRIPT"
  chmod +x "$CLEANUP_SCRIPT"
}

while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
	sleep 5
done

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

if [ "$default_gov" = "performance" ]; then
	for gov in $default_gov_preferred_array; do
		grep -q "$gov" "$CPUFREQ/scaling_available_governors" 2>/dev/null && {
			echo "$gov" >"$MODULE_CONFIG/default_cpu_gov"
			default_gov="$gov"
			break
		}
	done
fi

for _g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
	[ -f "$_g" ] && echo "$default_gov" >"$_g" 2>/dev/null
done

if [ ! -s "$MODULE_CONFIG/stock.snapshot" ]; then
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
	} >"$MODULE_CONFIG/stock.snapshot" 2>/dev/null
	chmod 0600 "$MODULE_CONFIG/stock.snapshot" 2>/dev/null
	echo "$(date '+%H:%M:%S') service: stock.snapshot reseeded (canonico)" >>"$MODULE_CONFIG/fusao.log" 2>/dev/null
fi

FUSAO_DIR="/data/adb/fusao"
mkdir -p "$FUSAO_DIR" 2>/dev/null
chmod 0700 "$FUSAO_DIR" 2>/dev/null

kill_stale() {
	for _p in $(pgrep -f "$1" 2>/dev/null); do
		[ "$_p" = "$$" ] || kill -9 "$_p" 2>/dev/null
	done
}
for _pat in "fusaod serve" "fusao.observer" "watch.sh" "thermal_guard.sh" "gos_killer.sh" "fas-rs run"; do
	kill_stale "$_pat"
done

for f in thermal_guard.sh thermal_guard_stop.sh lib.sh gos_killer.sh watch.sh framework.sh perf.sh bugreport.sh tune.sh dnd.sh display.sh observer.sh observer.dex fas.sh; do
	[ -f "$MODDIR/fusao/$f" ] && cp -f "$MODDIR/fusao/$f" "$FUSAO_DIR/$f" 2>/dev/null
done
[ -s "$FUSAO_DIR/gamelist.txt" ] || cp -f "$MODDIR/fusao/gamelist.txt" "$FUSAO_DIR/gamelist.txt" 2>/dev/null
chmod 0755 "$FUSAO_DIR/thermal_guard.sh" "$FUSAO_DIR/thermal_guard_stop.sh" "$FUSAO_DIR/gos_killer.sh" "$FUSAO_DIR/watch.sh" "$FUSAO_DIR/framework.sh" "$FUSAO_DIR/perf.sh" "$FUSAO_DIR/bugreport.sh" "$FUSAO_DIR/tune.sh" "$FUSAO_DIR/dnd.sh" "$FUSAO_DIR/display.sh" "$FUSAO_DIR/observer.sh" "$FUSAO_DIR/fas.sh" 2>/dev/null

[ -f "$FUSAO_DIR/config" ] || {
	echo "thermal=moderate" >"$FUSAO_DIR/config"
	echo "profile=balanced" >>"$FUSAO_DIR/config"
	echo "thermal_guard_high=78000" >>"$FUSAO_DIR/config"
	echo "thermal_guard_low=70000" >>"$FUSAO_DIR/config"
	echo "thermal_guard_interval=2" >>"$FUSAO_DIR/config"
	echo "thermal_guard_timeout=900" >>"$FUSAO_DIR/config"
	chmod 0600 "$FUSAO_DIR/config" 2>/dev/null
}

# Inicializa flags essenciais na 1ª instalação se ainda não inicializado
if [ ! -f "$FUSAO_DIR/.flags_seeded" ]; then
	touch "$FUSAO_DIR/framework.enabled" "$FUSAO_DIR/perf.enabled" "$FUSAO_DIR/.flags_seeded" 2>/dev/null
fi
# 2.11.0: FAS (fas-rs) ligado UMA vez (decisão do dono, medido: -10,4% de bateria no jogo); o marcador
# separado respeita um "fusao fas off" posterior e também vale p/ quem atualiza de versão antiga.
if [ ! -f "$FUSAO_DIR/.fas_seeded" ]; then
	touch "$FUSAO_DIR/fas.enabled" "$FUSAO_DIR/.fas_seeded" 2>/dev/null
fi
# 2.12.0: ramclean saiu do game-enter (drop_caches + compact_memory no launch); a flag antiga não arma mais nada
rm -f "$FUSAO_DIR/ramclean.enabled" 2>/dev/null
# 2.12.0: Roblox fora do FAS UMA vez (marcador próprio; um "fusao fas target" posterior vale). Sem limite de fps
# fixo, a escada do fas-rs vira catraca (60 -> 45 -> 30 e não volta: ele freia a CPU no degrau de baixo) e toda
# sessão do Roblox com o FAS saiu pior (status.md 2.11.5: CPU 30-33 ms por frame > GPU 20-23 ms). Sobrescreve
# também o limite posto pelo app, que gravava a escada abaixo dele (30,45,60) e caía na mesma catraca.
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

# Aplica o perfil salvo no boot para garantir que o estado inicial corresponda ao perfil ativo
if [ ! -f "$FUSAO_DIR/disable" ]; then
	if [ -f "$FUSAO_DIR/framework.enabled" ] && [ -x "$FUSAO_DIR/framework.sh" ]; then
		M54_DIR="$FUSAO_DIR" sh "$FUSAO_DIR/framework.sh" auto >/dev/null 2>&1
	elif [ -x "$FUSAO_DIR/tune.sh" ]; then
		M54_DIR="$FUSAO_DIR" sh "$FUSAO_DIR/tune.sh" auto >/dev/null 2>&1
	fi
fi

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
if [ ! -f "$FUSAO_DIR/disable" ] && [ -x "$FUSAO_DIR/gos_killer.sh" ]; then
	if ! pgrep -f "[g]os_killer" >/dev/null 2>&1; then
		M54_DIR="$FUSAO_DIR" nohup sh "$FUSAO_DIR/gos_killer.sh" >>"$FUSAO_DIR/fusao.log" 2>&1 &
	fi
fi
# fusaod serve é gerenciado por sessão pelo watch.sh/fusao CLI (quando fusaod.enabled
# e há jogo ativo nos perfis game/balanced), evitando sobrescrever o perfil em idle.
if [ ! -f "$FUSAO_DIR/disable" ] && [ -f "$FUSAO_DIR/fusaod.enabled" ] && [ -s "$FUSAO_DIR/game-active.txt" ] && [ -x "$MODDIR/system/bin/fusaod" ]; then
	if ! pgrep -f "[f]usaod serve" >/dev/null 2>&1; then
		nohup "$MODDIR/system/bin/fusaod" serve >>"$FUSAO_DIR/fusaod.log" 2>&1 &
	fi
fi
