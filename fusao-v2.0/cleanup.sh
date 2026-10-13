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

# FUSAO-M54: roda quando o módulo sumiu (uninstall) ou foi desabilitado.
# (O teste antigo checava [ ! -d .../disable ] mas disable é ARQUIVO — nunca casava.)
if [ ! -d "$MODULE_DIR" ] || [ -f "$MODULE_DIR/disable" ]; then
	# FUSAO: framework de volta ao stock + processos parados.
	if [ -x /data/adb/fusao/framework.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/framework.sh idle 2>/dev/null
	fi
	pkill -f "fusaod serve" 2>/dev/null
	pkill -f fusao-watch 2>/dev/null
	pkill -f watch.sh 2>/dev/null
	pkill -f fusao.observer 2>/dev/null
	pkill -f thermal_guard.sh 2>/dev/null
	pkill -f "[g]os_killer" 2>/dev/null
	if [ -x /data/adb/fusao/dnd.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/dnd.sh off 2>/dev/null
	fi
	if [ -x /data/adb/fusao/tune.sh ]; then
		M54_DIR=/data/adb/fusao sh /data/adb/fusao/tune.sh idle 2>/dev/null
	fi
	# governor de volta
	if [ -f "$MODULE_CONFIG/default_cpu_gov" ]; then
		read -r revert_gov <"$MODULE_CONFIG/default_cpu_gov"
		echo "$revert_gov" | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null
	fi
	# thermal de volta
	for z in /sys/class/thermal/thermal_zone*/mode; do
		[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
	done
	# floors/maxes de volta ao snapshot (disable não apaga o config).
	if [ -f "$MODULE_CONFIG/stock.snapshot" ]; then
		while IFS= read -r line; do
			case "$line" in
			\#* | "") continue ;;
			*)
				p=$(echo "$line" | cut -d' ' -f1)
				v=$(echo "$line" | cut -d' ' -f2-)
				if [ -f "$p" ]; then
					chmod 644 "$p" 2>/dev/null
					echo "$v" >"$p" 2>/dev/null
				fi
				;;
			esac
		done <"$MODULE_CONFIG/stock.snapshot"
	fi
	rm -f "$THIS_SCRIPT"
	exit 0
fi

if [ ! -d "$MODULE_DIR/disable" ]; then
  cat "$MODULE_DIR/module.prop.orig" >"$MODULE_DIR/module.prop"
  rm -f "$THIS_SCRIPT"
fi
