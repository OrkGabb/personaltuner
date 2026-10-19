#!/system/bin/sh
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

# shellcheck disable=SC2016

MODULE_DIR="/data/adb/modules/encore"
THIS_SCRIPT="/data/adb/service.d/.fusao_cleanup.sh"
MODULE_CONFIG="/data/adb/fusao"

if [ ! -d "$MODULE_DIR" ] || [ -f "$MODULE_DIR/disable" ]; then
	# fas-rs primeiro: ele reescreve a CPU por frame e desfaria a restauração abaixo
	pkill -f "[f]as-rs run" 2>/dev/null
	if [ -x /data/adb/fusao/framework.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/framework.sh idle 2>/dev/null
	fi
	if [ -x /data/adb/fusao/thermal_guard_stop.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/thermal_guard_stop.sh 2>/dev/null
	fi
	pkill -f "fusaod serve" 2>/dev/null
	pkill -f fusao-watch 2>/dev/null
	pkill -f watch.sh 2>/dev/null
	pkill -f fusao.observer 2>/dev/null
	pkill -f "observer.sh" 2>/dev/null
	pkill -f thermal_guard.sh 2>/dev/null
	pkill -f "[g]os_killer" 2>/dev/null
	rm -f /data/adb/fusao/*_pid 2>/dev/null
	: > /data/adb/fusao/game-active.txt 2>/dev/null
	if [ -x /data/adb/fusao/dnd.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/dnd.sh off 2>/dev/null
	fi
	if [ -x /data/adb/fusao/display.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/display.sh reset 2>/dev/null
	fi
	if [ -x /data/adb/fusao/perf.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/perf.sh exit 2>/dev/null
	fi
	if [ -x /data/adb/fusao/tune.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/tune.sh idle 2>/dev/null
	fi
	if [ -f "$MODULE_CONFIG/default_cpu_gov" ]; then
		read -r revert_gov <"$MODULE_CONFIG/default_cpu_gov"
		for _g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
			[ -f "$_g" ] && {
				chmod 644 "$_g" 2>/dev/null
				echo "$revert_gov" >"$_g" 2>/dev/null
			}
		done
	fi
	for z in /sys/class/thermal/thermal_zone*/mode; do
		[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
	done
	# Policy0 exige descida por degraus da tabela REAL do nó (do piso atual p/ baixo; a lista antiga era
	# de outro SoC e começava subindo o piso p/ 1728000). Fallback = tabela lida no aparelho.
	if [ -f "$MODULE_CONFIG/stock.snapshot" ]; then
		while read -r p v; do
			case "$p" in
			\#* | "") continue ;;
			*)
				if [ -f "$p" ]; then
					chmod 644 "$p" 2>/dev/null
					case "$p" in
					*/cpufreq/policy0/scaling_min_freq)
						_cur=$(cat "$p" 2>/dev/null)
						_tab=$(cat "${p%/*}/scaling_available_frequencies" 2>/dev/null)
						[ -n "$_tab" ] || _tab="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000"
						for _step in $(printf '%s\n' $_tab | sort -rn); do
							[ "$_step" -lt "${_cur:-0}" ] 2>/dev/null && [ "$_step" -gt "$v" ] 2>/dev/null && echo "$_step" >"$p" 2>/dev/null
						done
						;;
					esac
					echo "$v" >"$p" 2>/dev/null
				fi
				;;
			esac
		done <"$MODULE_CONFIG/stock.snapshot"
	fi
	rm -f "$THIS_SCRIPT"
	exit 0
fi

if [ ! -f "$MODULE_DIR/disable" ]; then
  [ -f "$MODULE_DIR/module.prop.orig" ] && cat "$MODULE_DIR/module.prop.orig" >"$MODULE_DIR/module.prop"
  rm -f "$THIS_SCRIPT"
fi
