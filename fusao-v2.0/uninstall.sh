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

# FUSAO-M54: para cérebro + guard ANTES de reverter sysfs.
# Guard: stop oficial (restaura zones enabled) ou kill + enabled manual.
if [ -x /data/adb/fusao/framework.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/framework.sh idle 2>/dev/null
fi
if [ -x /data/adb/fusao/thermal_guard_stop.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/thermal_guard_stop.sh 2>/dev/null
fi
pkill -f thermal_guard.sh 2>/dev/null
pkill -f "fusaod serve" 2>/dev/null
pkill -f "/system/bin/fusaod" 2>/dev/null
pkill -f "[g]os_killer" 2>/dev/null
pkill -f fusao-watch 2>/dev/null
pkill -f watch.sh 2>/dev/null
pkill -f fusao.observer 2>/dev/null
pkill -f "observer.sh" 2>/dev/null
rm -f /data/adb/fusao/*_pid 2>/dev/null
if [ -x /data/adb/fusao/dnd.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/dnd.sh off 2>/dev/null
fi
if [ -x /data/adb/fusao/display.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/display.sh reset 2>/dev/null
fi
if [ -x /data/adb/fusao/tune.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/tune.sh idle 2>/dev/null
fi
for z in /sys/class/thermal/thermal_zone*/mode; do
	[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
done

# FUSAO-M54: reversão total de sysfs ANTES de apagar o config.
# Regra: tudo que o módulo escreve, o uninstall desfaz. O rm -rf do config
# vem POR ÚLTIMO — o restore abaixo lê snapshot/governor dele.
MODULE_CONFIG="/data/adb/fusao"

# 1. Governor: volta ao default capturado no boot; fallback energy_aware
#    (stock do aparelho) -> schedutil.
if [ -f "$MODULE_CONFIG/default_cpu_gov" ]; then
	read -r revert_gov <"$MODULE_CONFIG/default_cpu_gov"
else
	revert_gov="energy_aware"
fi
grep -q "$revert_gov" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null || revert_gov="energy_aware"
grep -q "$revert_gov" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null || revert_gov="schedutil"
for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
	chmod 644 "$g" 2>/dev/null
	echo "$revert_gov" >"$g" 2>/dev/null
done

# 2. Thermal zones: reabilita (modo stock = enabled).
for z in /sys/class/thermal/thermal_zone*/mode; do
	[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
done

# 3. Stock snapshot (se existir): restaura min/max travados por perfil.
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

# IO scheduler de volta ao stock (ssg neste aparelho).
for b in sda sdb sdc sdd sde; do
	[ -f /sys/block/$b/queue/scheduler ] && echo "ssg" >/sys/block/$b/queue/scheduler 2>/dev/null
done

# Config por último: o restore acima leu daqui.
rm -rf /data/adb/fusao

rm -f /data/adb/service.d/.fusao_cleanup.sh

need_gone="fusaod fusao"
manager_paths="/data/adb/ap/bin /data/adb/ksu/bin"

for dir in $manager_paths; do
	[ -d "$dir" ] && {
		for bin in $need_gone; do
			rm "$dir/$bin"
		done
	}
done
