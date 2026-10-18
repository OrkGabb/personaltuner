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
if [ -x /data/adb/fusao/perf.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/perf.sh exit 2>/dev/null
fi
if [ -x /data/adb/fusao/tune.sh ]; then
	M54_DIR=/data/adb/fusao sh /data/adb/fusao/tune.sh idle 2>/dev/null
fi
for z in /sys/class/thermal/thermal_zone*/mode; do
	[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
done

MODULE_CONFIG="/data/adb/fusao"

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

for z in /sys/class/thermal/thermal_zone*/mode; do
	[ -f "$z" ] && echo enabled >"$z" 2>/dev/null
done

# 3. Stock snapshot: restaura min/max travados por perfil.
#    Policy0 exige descida por degraus da tabela real do Exynos 1380.
if [ -f "$MODULE_CONFIG/stock.snapshot" ]; then
	while read -r p v; do
		case "$p" in
		\#* | "") continue ;;
		*)
			if [ -f "$p" ]; then
				chmod 644 "$p" 2>/dev/null
				if [ "$p" = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" ]; then
					for _step in 1728000 1512000 1320000 1104000 999000 889000 768000 672000 "$v"; do
						echo "$_step" >"$p" 2>/dev/null
					done
				else
					echo "$v" >"$p" 2>/dev/null
				fi
			fi
			;;
		esac
	done <"$MODULE_CONFIG/stock.snapshot"
fi

# IO scheduler e fila de volta ao stock (ssg + add_random=0 no M54).
for b in sda sdb sdc sdd sde sdf mmcblk0; do
	[ -f /sys/block/$b/queue/scheduler ] && echo "ssg" >/sys/block/$b/queue/scheduler 2>/dev/null
	[ -f /sys/block/$b/queue/nomerges ] && echo "0" >/sys/block/$b/queue/nomerges 2>/dev/null
	[ -f /sys/block/$b/queue/add_random ] && echo "0" >/sys/block/$b/queue/add_random 2>/dev/null
	[ -f /sys/block/$b/queue/read_ahead_kb ] && echo "128" >/sys/block/$b/queue/read_ahead_kb 2>/dev/null
done

rm -rf /data/adb/fusao
rm -f /data/adb/service.d/.fusao_cleanup.sh

need_gone="fusaod fusao"
manager_paths="/data/adb/ap/bin /data/adb/ksu/bin"

for dir in $manager_paths; do
	[ -d "$dir" ] && {
		for bin in $need_gone; do
			rm -f "$dir/$bin"
		done
	}
done
