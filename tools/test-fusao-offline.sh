#!/bin/bash
# test-fusao-offline.sh — harness Fase 8 offline (fake sysfs/settings, sem device).
# Roda no WSL/Linux: bash tools/test-fusao-offline.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/fusao-v2.0"
FAKE="${FAKE_DIR:-/tmp/fusao-fake-test-$$}"
rm -rf "$FAKE"; mkdir -p "$FAKE/fusao" "$FAKE/settings" "$FAKE/sys"
trap 'rm -rf "$FAKE" 2>/dev/null || true' EXIT
export M54_DIR="$FAKE/fusao"
export FAKE_SETTINGS_DIR="$FAKE/settings"
# 2.9.8: apply pending agenda reverificação em background (sleep 30..185 s); no harness ela é chamada à mão
export TUNE_NO_DEFER=1
for _s in lib.sh framework.sh perf.sh watch.sh bugreport.sh tune.sh dnd.sh display.sh gos_killer.sh thermal_guard.sh thermal_guard_stop.sh fas.sh; do
  cp "$STAGE/fusao/$_s" "$FAKE/fusao/" 2>/dev/null
done
cp "$STAGE/fusao/config" "$FAKE/fusao/" 2>/dev/null
cp "$STAGE/fusao/gamelist.txt" "$FAKE/fusao/" 2>/dev/null
cp "$STAGE/system/bin/fusao" "$FAKE/fusao/fusao-cli" 2>/dev/null
chmod +x "$FAKE/fusao/"*.sh "$FAKE/fusao/fusao-cli" 2>/dev/null
echo "balanced" > "$FAKE/fusao/profile"
: > "$FAKE/fusao/game-active.txt"
echo "com.roblox.client" > "$FAKE/focus.txt"
export FOCUS_FAKE="$FAKE/focus.txt"

pass=0; fail=0
ok(){ pass=$((pass+1)); echo "PASS $1"; }
bad(){ fail=$((fail+1)); echo "FAIL $1"; }

# 1) bash -n em todos os scripts
for s in lib.sh watch.sh framework.sh perf.sh bugreport.sh tune.sh observer.sh dnd.sh display.sh thermal_guard.sh thermal_guard_stop.sh gos_killer.sh fas.sh; do
  if [ -f "$STAGE/fusao/$s" ]; then
    bash -n "$STAGE/fusao/$s" 2>"$FAKE/shn.err" && ok "sh-n $s" || { bad "sh-n $s"; cat "$FAKE/shn.err"; }
  fi
done
for s in service.sh customize.sh uninstall.sh cleanup.sh action.sh; do
  bash -n "$STAGE/$s" 2>"$FAKE/shn.err" && ok "sh-n $s" || { bad "sh-n $s"; cat "$FAKE/shn.err"; }
done
bash -n "$STAGE/system/bin/fusao" 2>"$FAKE/shn.err" && ok "sh-n fusao-cli" || bad "sh-n fusao-cli"

# 2) framework: game aplica triad + refresh 120Hz + game_overlay seguro ('0'), idle restaura
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" game
grep -q "max_job_count_active=1" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework game triad" || bad "framework game triad"
grep -q "120.0" "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null && ok "framework game refresh" || bad "framework game refresh"
mkdir -p "$FAKE/proc/sys/vm" "$FAKE/proc/sys/net/ipv4" "$FAKE/sys/block/sda/queue"
echo 150 > "$FAKE/proc/sys/vm/swappiness"; echo 120 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo 1 > "$FAKE/proc/sys/vm/stat_interval"; echo 0 > "$FAKE/proc/sys/vm/page-cluster"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
echo 0 > "$FAKE/sys/block/sda/queue/nomerges"
echo 0 > "$FAKE/sys/block/sda/queue/add_random"
rm -f "$FAKE/fusao/tune.stock"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" game
grep -q "tune: apply game" "$FAKE/fusao/fusao.log" && ok "framework+tune wire" || bad "framework+tune wire"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "framework+tune eff" || bad "framework+tune eff"
[ "$(cat "$FAKE/settings/dc__game_overlay__game_default_frame_rate" 2>/dev/null)" = "0" ] && ok "framework game overlay safe-int" || bad "framework game overlay safe-int"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/framework.sh" idle
[ ! -f "$FAKE/settings/dc__game_overlay__game_default_frame_rate" ] && ok "framework idle dc-revert" || bad "framework idle dc-revert"
# padrão no case (dentro de [ ] o *...* era literal e o teste passava sempre)
case "$(cat "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null)" in
  *max_job_count_active=1*) bad "framework idle restore";;
  *) ok "framework idle restore";;
esac

# 3) framework battery/balanced
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" battery
grep -q "max_job_count_active=2" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework battery" || bad "framework battery"
# 2.11.4: battery adaptativo 120/60 (dono: nada de 60 Hz travado)
[ "$(cat "$FAKE/settings/system__min_refresh_rate" 2>/dev/null)" = "60.0" ] && [ "$(cat "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null)" = "120.0" ] && ok "framework battery adaptive-hz" || bad "framework battery adaptive-hz"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" balanced
grep -q "max_job_count_active=4" "$FAKE/settings/global__job_scheduler_constants" 2>/dev/null && ok "framework balanced" || bad "framework balanced"
[ "$(cat "$FAKE/settings/system__min_refresh_rate" 2>/dev/null)" = "60.0" ] && [ "$(cat "$FAKE/settings/system__peak_refresh_rate" 2>/dev/null)" = "120.0" ] && ok "framework balanced adaptive-hz" || bad "framework balanced adaptive-hz"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/framework.sh" idle

# 4) perf io-only, exit e proteção NTE (sem cgroup.procs global)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/perf.sh" io-only && ok "perf io-only" || bad "perf io-only"
grep -q "perf: io aplicado" "$FAKE/fusao/fusao.log" && ok "perf log" || bad "perf log"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/perf.sh" exit && ok "perf exit" || bad "perf exit"
grep -Eq '>[[:space:]]*"?/dev/(cpuset|stune)/top-app/cgroup\.procs' "$STAGE/fusao/perf.sh" && bad "perf no-cgroup-procs" || ok "perf no-cgroup-procs"

# 5) fusao CLI: profile/status/kill-switch/gamelist
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile game | grep -q "profile=game" && ok "cli profile game" || bad "cli profile game"
[ "$(cat "$FAKE/fusao/profile")" = "game" ] && ok "cli profile file" || bad "cli profile file"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" framework on | grep -q "framework=on" && ok "cli framework on" || bad "cli framework on"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" framework off | grep -q "reverted" && ok "cli framework off" || bad "cli framework off"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" status | grep -q "profile=game" && ok "cli status" || bad "cli status"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" status | grep -q "^thermal=moderate$" && ok "cli status thermal" || bad "cli status thermal"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist add com.teste.jogo | grep -q "added" && ok "cli gamelist add" || bad "cli gamelist add"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist list | grep -q "com.teste.jogo" && ok "cli gamelist list" || bad "cli gamelist list"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist del com.teste.jogo | grep -q "removed" && ok "cli gamelist del" || bad "cli gamelist del"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist list | grep -q "com.teste.jogo" && bad "cli gamelist del-eff" || ok "cli gamelist del-eff"
# Teste de deleção do último item restante na gamelist (regressão grep -v rc=1)
echo "com.ultimo.item" > "$FAKE/fusao/gamelist.txt"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist del com.ultimo.item >/dev/null 2>&1
[ ! -s "$FAKE/fusao/gamelist.txt" ] && ok "cli gamelist del-last" || bad "cli gamelist del-last"
# M11: verbo errado (ou set sem pacote) = rc!=0 e lista intacta; set substitui
printf 'com.a.jogo\ncom.b.jogo\n' > "$FAKE/fusao/gamelist.txt"; _gl0=$(cksum < "$FAKE/fusao/gamelist.txt")
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist lsit >/dev/null 2>&1 && bad "cli gamelist verbo invalido rc" || ok "cli gamelist verbo invalido rc"
[ "$(cksum < "$FAKE/fusao/gamelist.txt")" = "$_gl0" ] && ok "cli gamelist verbo invalido nao apaga" || bad "cli gamelist verbo invalido nao apaga"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist set >/dev/null 2>&1
[ "$(cksum < "$FAKE/fusao/gamelist.txt")" = "$_gl0" ] && ok "cli gamelist set vazio nao apaga" || bad "cli gamelist set vazio nao apaga"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" gamelist set com.x.um com.x.dois | grep -q "substituida" &&
  [ "$(cat "$FAKE/fusao/gamelist.txt")" = "$(printf 'com.x.um\ncom.x.dois')" ] && ok "cli gamelist set substitui" || bad "cli gamelist set substitui"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" dnd on | grep -q "dnd=on" && ok "cli dnd on" || bad "cli dnd on"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" dnd off | grep -q "dnd=off" && ok "cli dnd off" || bad "cli dnd off"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" display show >/dev/null 2>&1 && ok "cli display show" || bad "cli display show"
cp "$STAGE/fusao/gamelist.txt" "$FAKE/fusao/gamelist.txt"

# 6) watch parser: foco na lista vs fora (lógica, sem loop)
FOCUS="com.roblox.client"
grep -Fxq "$FOCUS" "$FAKE/fusao/gamelist.txt" && ok "watch gamelist hit" || bad "watch gamelist hit"
FOCUS="com.android.launcher"
grep -Fxq "$FOCUS" "$FAKE/fusao/gamelist.txt" && bad "watch gamelist miss" || ok "watch gamelist miss"

# 7) dex do observer: só nossas classes, sem stub android.*
python3 "$ROOT/tools/check-dex.py" "$STAGE/fusao/observer.dex" && ok "observer dex" || bad "observer dex"

# 8) watch standby: com observer vivo não escreve; sem observer assume poll
cp "$STAGE/fusao/watch.sh" "$FAKE/fusao/" 2>/dev/null
echo "SENTINEL" > "$FAKE/fusao/game-active.txt"
OBSERVER_FAKE_ALIVE=1 M54_DIR="$FAKE/fusao" timeout 6 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
[ "$(cat "$FAKE/fusao/game-active.txt")" = "SENTINEL" ] && ok "watch standby" || bad "watch standby"
grep -q "standby" "$FAKE/fusao/fusao.log" && ok "watch standby log" || bad "watch standby log"
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
OBSERVER_FAKE_ALIVE=1 M54_DIR="$FAKE/fusao" timeout 6 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
grep -q "watch: hooks.*com.roblox.client" "$FAKE/fusao/fusao.log" && ok "watch standby hooks" || bad "watch standby hooks"
export FOCUS_FAKE="$FAKE/focus.txt"
M54_DIR="$FAKE/fusao" timeout 8 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1
[ "$(cat "$FAKE/fusao/game-active.txt")" = "com.roblox.client" ] && ok "watch takeover poll" || bad "watch takeover poll"
grep -q "watch: hooks" "$FAKE/fusao/fusao.log" && ok "watch hooks line" || bad "watch hooks line"
: > "$FAKE/fusao/game-active.txt"

# 9) tune.sh + fusao profile e2e em fake sysfs completo (CPU P0/P4, GPU, MIF/INT/DISP, VM, IO, TCP + readback)
mkdir -p \
  "$FAKE/sys/devices/system/cpu/cpufreq/policy0" \
  "$FAKE/sys/devices/system/cpu/cpufreq/policy4" \
  "$FAKE/sys/kernel/gpu" \
  "$FAKE/sys/class/devfreq/17000010.devfreq_mif" \
  "$FAKE/sys/class/devfreq/17000020.devfreq_int" \
  "$FAKE/sys/class/devfreq/17000040.devfreq_disp" \
  "$FAKE/sys/block/sda/queue" \
  "$FAKE/proc/sys/vm" \
  "$FAKE/proc/sys/net/ipv4"

# Tabelas REAIS do M54 (leitura ao vivo 2026-10-18): é o que o tune.sh 2.9.7 lê p/ fazer snap dos alvos.
P0_TAB_REAL="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000"
P4_TAB_REAL="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2016000 2112000 2208000 2304000 2400000"
GPU_TAB_REAL="221000 351000 455000 552000 650000 754000 845000 949000"
CPUF="$FAKE/sys/devices/system/cpu/cpufreq"
echo "$P0_TAB_REAL" > "$CPUF/policy0/scaling_available_frequencies"
echo 533000 > "$CPUF/policy0/cpuinfo_min_freq"
echo 2002000 > "$CPUF/policy0/cpuinfo_max_freq"
echo "$P4_TAB_REAL" > "$CPUF/policy4/scaling_available_frequencies"
echo 533000 > "$CPUF/policy4/cpuinfo_min_freq"
echo 2400000 > "$CPUF/policy4/cpuinfo_max_freq"
echo "$GPU_TAB_REAL" > "$FAKE/sys/kernel/gpu/gpu_freq_table"
echo 533000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo 533000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
echo 1824000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
echo 221000 > "$FAKE/sys/kernel/gpu/gpu_min_clock"
echo 949000 > "$FAKE/sys/kernel/gpu/gpu_max_clock"
echo 421000 > "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq"
echo 2093000 > "$FAKE/sys/class/devfreq/17000010.devfreq_mif/max_freq"
echo 50000 > "$FAKE/sys/class/devfreq/17000020.devfreq_int/min_freq"
echo 66000 > "$FAKE/sys/class/devfreq/17000040.devfreq_disp/min_freq"
echo 128 > "$FAKE/sys/block/sda/queue/read_ahead_kb"
echo 0 > "$FAKE/sys/block/sda/queue/nomerges"
echo 0 > "$FAKE/sys/block/sda/queue/add_random"
echo 150 > "$FAKE/proc/sys/vm/swappiness"
echo 120 > "$FAKE/proc/sys/vm/vfs_cache_pressure"
echo 1 > "$FAKE/proc/sys/vm/stat_interval"
echo 0 > "$FAKE/proc/sys/vm/page-cluster"
echo cubic > "$FAKE/proc/sys/net/ipv4/tcp_congestion_control"
echo "cubic reno bbr" > "$FAKE/proc/sys/net/ipv4/tcp_available_congestion_control"
echo 0 > "$FAKE/proc/sys/net/ipv4/tcp_fastopen"

cp "$STAGE/fusao/tune.sh" "$FAKE/fusao/" 2>/dev/null
rm -f "$FAKE/fusao/tune.stock"

# 9a) Perfil GAME via CLI (mesmo sem framework.enabled precisa aplicar e confirmar readback!)
rm -f "$FAKE/fusao/framework.enabled"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile game > "$FAKE/prof_game.out"
grep -q "profile_state=applied" "$FAKE/prof_game.out" && ok "profile game verified" || bad "profile game verified"
grep -q "profile_verify=9/9" "$FAKE/prof_game.out" && ok "profile game verify 9/9" || bad "profile game verify 9/9"
# alvos do game = degraus REAIS da tabela (999000/889000 eram de outro SoC; 1056000/960000 existem no 1380)
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "1056000" ] && ok "tune game p0_min" || bad "tune game p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "960000" ] && ok "tune game p4_min" || bad "tune game p4_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "2002000" ] && ok "tune game p0_max" || bad "tune game p0_max"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1824000" ] && ok "tune game p4_max" || bad "tune game p4_max"
# 2.11.3: game sem piso de GPU (Hyper da Samsung reescreve o gpu_min_clock a cada troca de app)
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_min_clock")" = "221000" ] && ok "tune game gpu_min" || bad "tune game gpu_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "949000" ] && ok "tune game gpu_max" || bad "tune game gpu_max"
[ "$(cat "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq")" = "845000" ] && ok "tune game mif_min" || bad "tune game mif_min"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "60" ] && ok "tune game swap" || bad "tune game swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "50" ] && ok "tune game pressure" || bad "tune game pressure"
# 2.10.0: read_ahead saiu dos perfis (o da sda não chega na leitura de arquivo; no dm subir piora): fica no stock
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "128" ] && ok "tune game readahead fica no stock" || bad "tune game readahead fica no stock"
[ "$(cat "$FAKE/sys/block/sda/queue/nomerges")" = "1" ] && ok "tune game nomerges" || bad "tune game nomerges"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "bbr" ] && ok "tune game bbr" || bad "tune game bbr"

# 9b) Transição GAME -> BALANCED (sem resíduos do modo Game)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile balanced > "$FAKE/prof_bal.out"
grep -q "profile_state=applied" "$FAKE/prof_bal.out" && ok "profile balanced verified" || bad "profile balanced verified"
grep -q "profile_verify=9/9" "$FAKE/prof_bal.out" && ok "profile balanced verify 9/9" || bad "profile balanced verify 9/9"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune balanced p0_min" || bad "tune balanced p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "533000" ] && ok "tune balanced p4_min" || bad "tune balanced p4_min"
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_min_clock")" = "221000" ] && ok "tune balanced gpu_min" || bad "tune balanced gpu_min"
# 858000 não existe na tabela do Mali-G68; o degrau real logo abaixo do máximo (949000) é 845000
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "845000" ] && ok "tune balanced gpu_max" || bad "tune balanced gpu_max"
[ "$(cat "$FAKE/sys/class/devfreq/17000010.devfreq_mif/min_freq")" = "421000" ] && ok "tune balanced mif_min" || bad "tune balanced mif_min"
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "120" ] && ok "tune balanced swap" || bad "tune balanced swap"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "100" ] && ok "tune balanced pressure" || bad "tune balanced pressure"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "128" ] && ok "tune balanced readahead fica no stock" || bad "tune balanced readahead fica no stock"
[ "$(cat "$FAKE/sys/block/sda/queue/nomerges")" = "0" ] && ok "tune balanced nomerges-clean" || bad "tune balanced nomerges-clean"

# 9c) Transição BALANCED -> BATTERY / PowerSaving (tetos reduzidos; piso do P0 = 533000, o mínimo físico e o stock)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile battery > "$FAKE/prof_bat.out"
grep -q "profile_state=applied" "$FAKE/prof_bat.out" && ok "profile battery verified" || bad "profile battery verified"
grep -q "profile_verify=9/9" "$FAKE/prof_bat.out" && ok "profile battery verify 9/9" || bad "profile battery verify 9/9"
# 400000 não existe no 1380: o driver clampa p/ 533000 em silêncio. O alvo agora É 533000 (e não 672000: subir o piso gasta mais em ocioso)
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune battery p0_min" || bad "tune battery p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "1728000" ] && ok "tune battery p0_max" || bad "tune battery p0_max"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1440000" ] && ok "tune battery p4_max" || bad "tune battery p4_max"
# 2.10.0: battery não capa a GPU (o 650000 era sobrescrito pela Samsung e o verify vivia 9/10)
[ "$(cat "$FAKE/sys/kernel/gpu/gpu_max_clock")" = "949000" ] && ok "tune battery gpu_max sem cap" || bad "tune battery gpu_max sem cap"

# 9c-2) 2.9.9, regra do dono: battery + jogo da gamelist em foco = game (fora do jogo volta a battery);
# balanced fica balanced em jogo (jogos casuais). O hook do watch chama `tune.sh auto` depois de gravar o
# game-active; o app chama `fusao status`/`fusao profile`. Prova pelo NÓ (tetos do game), não pela string.
GA="$FAKE/fusao/game-active.txt"
GMAXN="$FAKE/sys/kernel/gpu/gpu_max_clock"
P4MAXN="$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
echo "com.roblox.client" > "$GA"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" auto >/dev/null 2>&1
if [ "$(cat "$GMAXN")" = "949000" ] && [ "$(cat "$P4MAXN")" = "1824000" ] && \
   [ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "1152000" ]; then
  ok "battery+jogo: hook aplica os tetos do game"
else
  bad "battery+jogo: hook aplica os tetos do game"
fi
grep -q "^profile=game$" "$FAKE/fusao/profile.state" && ok "battery+jogo: state confere game" || bad "battery+jogo: state confere game"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" status > "$FAKE/st_bat_game.out" 2>&1
grep -q "^profile=battery$" "$FAKE/st_bat_game.out" && grep -q "^profile_effective=game$" "$FAKE/st_bat_game.out" && \
  ok "status: escolhido battery, em vigor game" || bad "status: escolhido battery, em vigor game"
grep -q "^profile_state=applied$" "$FAKE/st_bat_game.out" && ok "status em jogo confere o em vigor (applied, nao partial)" || bad "status em jogo confere o em vigor (applied, nao partial)"
# escolher battery COM o jogo aberto aplica game (o CLI aplicava "$arg" direto = battery)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile balanced >/dev/null 2>&1
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile battery > "$FAKE/prof_bat_game.out" 2>&1
grep -q "^profile_effective=game$" "$FAKE/prof_bat_game.out" && [ "$(cat "$P4MAXN")" = "1824000" ] && \
  ok "CLI battery em jogo aplica game" || bad "CLI battery em jogo aplica game"
# balanced + jogo = balanced (GPU max 845000)
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile balanced > "$FAKE/prof_bal_game.out" 2>&1
grep -q "^profile_effective=balanced$" "$FAKE/prof_bal_game.out" && [ "$(cat "$GMAXN")" = "845000" ] && \
  ok "balanced em jogo fica balanced" || bad "balanced em jogo fica balanced"
# app fora da gamelist no game-active não conta como jogo
echo "com.android.settings" > "$GA"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/fusao-cli" profile battery > "$FAKE/prof_bat_app.out" 2>&1
grep -q "^profile_effective=battery$" "$FAKE/prof_bat_app.out" && [ "$(cat "$P4MAXN")" = "1440000" ] && \
  ok "app fora da gamelist: battery" || bad "app fora da gamelist: battery"
# saída do jogo (game-active zerado antes do fire_exit): o hook volta a battery
echo "com.roblox.client" > "$GA"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" auto >/dev/null 2>&1
: > "$GA"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" auto >/dev/null 2>&1
[ "$(cat "$P4MAXN")" = "1440000" ] && grep -q "^profile=battery$" "$FAKE/fusao/profile.state" && \
  ok "saiu do jogo: volta a battery" || bad "saiu do jogo: volta a battery"
[ "$(cat "$FAKE/proc/sys/vm/vfs_cache_pressure")" = "150" ] && ok "tune battery" || bad "tune battery"
[ "$(cat "$FAKE/sys/block/sda/queue/read_ahead_kb")" = "128" ] && ok "tune battery readahead" || bad "tune battery readahead"

# 9c2) GAME com jogo em foco -> BATTERY direto: o piso do P0 só chega a 533000 se o dado de fato desce
# pelos degraus da tabela (o estado anterior é 1152000; um nó que não mexeu não passaria)
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" game >/dev/null 2>&1
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "1152000" ] && ok "tune game+jogo p0_min" || bad "tune game+jogo p0_min"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq")" = "1056000" ] && ok "tune game+jogo p4_min" || bad "tune game+jogo p4_min"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify game | grep -q "profile_verify=9/9" && ok "tune game+jogo verify 9/9" || bad "tune game+jogo verify 9/9"
: > "$FAKE/fusao/game-active.txt"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "tune game->battery p0_min desce a 533000" || bad "tune game->battery p0_min desce a 533000"
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery | grep -q "profile_verify=9/9" && ok "tune game->battery verify 9/9" || bad "tune game->battery verify 9/9"

# 9d) Restauração completa ao stock via idle
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle
[ "$(cat "$FAKE/proc/sys/vm/swappiness")" = "150" ] && ok "tune idle restore" || bad "tune idle restore"
[ "$(cat "$FAKE/proc/sys/net/ipv4/tcp_congestion_control")" = "cubic" ] && ok "tune idle tcp" || bad "tune idle tcp"
[ "$(cat "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq")" = "1824000" ] && ok "tune idle p4_max restore" || bad "tune idle p4_max restore"

# 9e) 2.9.7: tabela LIDA do nó + snap + readback. O probe carrega só as funções do tune.sh
# (TUNE_SOURCE_ONLY=1) e roda o comando recebido, com um driver falso opcional:
#   clamp: escrever abaixo do mínimo em policy0/scaling_min_freq "dá certo" (rc 0, sem errno) mas o nó
#          guarda o mínimo (400000 -> 533000), exatamente como o driver do Exynos 1380 faz no aparelho;
#   trace: anota cada valor escrito (prova a SEQUÊNCIA de degraus, não só o estado final).
cat > "$FAKE/fusao/tune-probe.sh" <<'EOF'
#!/bin/sh
TUNE_SOURCE_ONLY=1
. "$(dirname "$0")/tune.sh"
if [ -n "${PROBE_CLAMP_MIN:-}${PROBE_TRACE:-}" ]; then
  sysfs_put() {
    local v="$2"
    case "$1" in
      */policy0/scaling_min_freq) [ -n "${PROBE_CLAMP_MIN:-}" ] && [ "$v" -lt "$PROBE_CLAMP_MIN" ] 2>/dev/null && v="$PROBE_CLAMP_MIN";;
    esac
    [ -n "${PROBE_TRACE:-}" ] && echo "$1 $v" >> "$PROBE_TRACE"
    { echo "$v" > "$1"; } 2>/dev/null
  }
fi
# w() do 2.9.6 (literal; só o primitivo de escrita é o mesmo do driver falso): controle negativo do clamp.
w_legacy() {
  [ -e "$1" ] || return 0
  chmod 0644 "$1" 2>/dev/null
  if [ -w "$1" ]; then
    sysfs_put "$1" "$2"
    return $?
  fi
  return 1
}
"$@"
EOF
# probe_at <FS_ROOT> <clamp-min|-> <trace-file|-> <comando...>
probe_at() {
  local _root="$1" _clamp="$2" _trace="$3"; shift 3
  [ "$_clamp" = "-" ] && _clamp=""
  [ "$_trace" = "-" ] && _trace=""
  M54_DIR="$FAKE/fusao" FS_ROOT="$_root" PROBE_CLAMP_MIN="$_clamp" PROBE_TRACE="$_trace" sh "$FAKE/fusao/tune-probe.sh" "$@"
}
FLOG="$FAKE/fusao/fusao.log"
PNODE="$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"

# 9e-1) snap na tabela real: min arredonda p/ cima (como o kernel), max p/ baixo; alvo que não existe vira degrau
: > "$FLOG"
[ "$(probe_at "$FAKE" - - snap_domain min p0 999000)" = "1056000" ] && ok "snap p0 min 999000->1056000" || bad "snap p0 min 999000->1056000"
[ "$(probe_at "$FAKE" - - snap_domain min p4 889000)" = "960000" ] && ok "snap p4 min 889000->960000" || bad "snap p4 min 889000->960000"
[ "$(probe_at "$FAKE" - - snap_domain max p0 1700000)" = "1632000" ] && ok "snap p0 max 1700000->1632000" || bad "snap p0 max 1700000->1632000"
[ "$(probe_at "$FAKE" - - snap_domain max p4 1500000)" = "1440000" ] && ok "snap p4 max 1500000->1440000" || bad "snap p4 max 1500000->1440000"
[ "$(probe_at "$FAKE" - - snap_domain max gpu 858000)" = "845000" ] && ok "snap gpu max 858000->845000" || bad "snap gpu max 858000->845000"
[ "$(probe_at "$FAKE" - - snap_domain min gpu 300000)" = "351000" ] && ok "snap gpu min 300000->351000" || bad "snap gpu min 300000->351000"
[ "$(probe_at "$FAKE" - - snap_domain min p0 1056000)" = "1056000" ] && ok "snap degrau exato fica" || bad "snap degrau exato fica"
grep -q "tune: snap p0/min pedido=999000 -> 1056000" "$FLOG" && ok "snap loga o arredondamento" || bad "snap loga o arredondamento"
grep -q "pedido=1056000" "$FLOG" && bad "snap degrau exato nao loga" || ok "snap degrau exato nao loga"

# 9e-2) fora da faixa vira o limite físico, com WARN explícito (fail-loud no log, rc 0, fluxo segue)
: > "$FLOG"
[ "$(probe_at "$FAKE" - - snap_domain min p0 400000)" = "533000" ] && ok "snap p0 min 400000->533000" || bad "snap p0 min 400000->533000"
grep -q "WARN tune: snap p0/min pedido=400000 abaixo do mínimo físico 533000, usando 533000" "$FLOG" && ok "snap abaixo do minimo loga WARN" || bad "snap abaixo do minimo loga WARN"
[ "$(probe_at "$FAKE" - - snap_domain max p0 3000000)" = "2002000" ] && ok "snap p0 max 3000000->2002000" || bad "snap p0 max 3000000->2002000"
grep -q "WARN tune: snap p0/max pedido=3000000 acima do máximo físico 2002000, usando 2002000" "$FLOG" && ok "snap acima do maximo loga WARN" || bad "snap acima do maximo loga WARN"
[ "$(probe_at "$FAKE" - - snap_domain min gpu 100000)" = "221000" ] && ok "snap gpu min 100000->221000" || bad "snap gpu min 100000->221000"
[ "$(probe_at "$FAKE" - - snap_domain min p0 abc)" = "533000" ] && ok "snap alvo invalido vira limite" || bad "snap alvo invalido vira limite"
grep -q "WARN tune: snap p0/min alvo inválido 'abc'" "$FLOG" && ok "snap alvo invalido loga WARN" || bad "snap alvo invalido loga WARN"

# 9e-3) a tabela vem do NÓ (e não do fallback embutido): tabela falsa com degraus que o 1380 não tem
# (700000, 1100000), descendente e com CRLF. Só quem leu o nó responde 1100000 p/ 999000 (fallback real: 1056000).
ALT="$FAKE/alt"
mkdir -p "$ALT/sys/devices/system/cpu/cpufreq/policy0" "$ALT/sys/kernel/gpu"
printf '2002000 1100000 700000 533000\r\n' > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
echo 533000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq"
echo 1100000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo "221000 500000 949000" > "$ALT/sys/kernel/gpu/gpu_freq_table"
echo 221000 > "$ALT/sys/kernel/gpu/gpu_min_clock"
echo 949000 > "$ALT/sys/kernel/gpu/gpu_max_clock"
[ "$(probe_at "$ALT" - - snap_domain min p0 999000)" = "1100000" ] && ok "tabela p0 lida do no (min)" || bad "tabela p0 lida do no (min)"
[ "$(probe_at "$ALT" - - snap_domain max p0 1099000)" = "700000" ] && ok "tabela p0 lida do no (max)" || bad "tabela p0 lida do no (max)"
[ "$(probe_at "$ALT" - - snap_domain max gpu 858000)" = "500000" ] && ok "tabela gpu lida do no" || bad "tabela gpu lida do no"
# descida do piso por degraus DO NÓ: de 1100000 até 533000 só passa por 700000 (a lista antiga hardcoded nunca teve 700000)
rm -f "$FAKE/trace.txt"
probe_at "$ALT" - "$FAKE/trace.txt" set_policy0 533000 2002000
[ "$(grep scaling_min_freq "$FAKE/trace.txt" | cut -d' ' -f2 | tr '\n' ' ')" = "700000 533000 " ] && ok "descida de degraus usa a tabela do no" || bad "descida de degraus usa a tabela do no"
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "533000" ] && ok "descida chegou ao piso" || bad "descida chegou ao piso"
# controle negativo: sem os nós de tabela cai no fallback (tabela real do M54) e avisa no log
: > "$FLOG"
rm -f "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" "$ALT/sys/kernel/gpu/gpu_freq_table"
[ "$(probe_at "$ALT" - - snap_domain min p0 999000)" = "1056000" ] && ok "fallback p0 = tabela real do M54" || bad "fallback p0 = tabela real do M54"
[ "$(probe_at "$ALT" - - snap_domain max gpu 858000)" = "845000" ] && ok "fallback gpu = tabela real do M54" || bad "fallback gpu = tabela real do M54"
grep -q "WARN tune: p0 sem tabela legível" "$FLOG" && ok "fallback loga WARN" || bad "fallback loga WARN"

# 9e-4) w() relê: driver honesto devolve 0; driver que clampa em silêncio devolve 1 + WARN (nao mente rc 0)
echo 533000 > "$PNODE"
probe_at "$FAKE" - - w "$PNODE" 768000
[ "$?" = "0" ] && [ "$(cat "$PNODE")" = "768000" ] && ok "w honesto rc 0" || bad "w honesto rc 0"
: > "$FLOG"
probe_at "$FAKE" 533000 - w "$PNODE" 400000
_rc=$?
[ "$_rc" = "1" ] && ok "w clampado rc 1" || bad "w clampado rc 1 (rc=$_rc)"
[ "$(cat "$PNODE")" = "533000" ] && ok "w clampado: driver guardou o minimo" || bad "w clampado: driver guardou o minimo"
grep -q "WARN tune: clamp .*policy0/scaling_min_freq pedido=400000 lido=533000" "$FLOG" && ok "w clampado loga WARN clamp" || bad "w clampado loga WARN clamp"
# controle negativo: o w() do 2.9.6 no MESMO driver devolve 0 com o dado errado no nó (a mentira que a 2.9.7 corrige)
echo 768000 > "$PNODE"
probe_at "$FAKE" 533000 - w_legacy "$PNODE" 400000
_rc=$?
[ "$_rc" = "0" ] && [ "$(cat "$PNODE")" = "533000" ] && ok "controle negativo: w legado mente rc 0" || bad "controle negativo: w legado mente rc 0 (rc=$_rc)"
probe_at "$FAKE" - - w "$FAKE/nao/existe" 1 && ok "w nó ausente = no-op rc 0" || bad "w nó ausente = no-op rc 0"
grep -v '^[[:space:]]*#' "$STAGE/fusao/tune.sh" | grep -Eq 'chmod[[:space:]]+0*444' && bad "tune sem chmod 444" || ok "tune sem chmod 444"

# 9e-5) driver que clampa o P0 min p/ 672000 (o aparelho em 2026-10-18: profile_verify=9/10 e p0_min:533000!=400000):
# o battery pede 533000 e o nó guarda 672000 => o verify NÃO fecha 9/9 e aponta o nó
echo 2002000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo 533000 > "$PNODE"
rm -f "$FAKE/fusao/profile.state"; : > "$FLOG"
# 2.9.8: piso de CPU lido ACIMA do alvo é indistinguível de QoS externo (hint/boost da Samsung) logo após o
# apply => pending dentro da janela; o veredito final (fora da janela) continua partial apontando o nó.
TUNE_NOW=1000 probe_at "$FAKE" 672000 - apply_battery
grep -q "^profile_verify=8/9$" "$FAKE/fusao/profile.state" && ok "driver clampa: verify 8/9" || bad "driver clampa: verify 8/9"
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "driver clampa: pending na janela" || bad "driver clampa: pending na janela"
grep -q "^profile_external=p0_min:672000!=533000$" "$FAKE/fusao/profile.state" && ok "driver clampa: external aponta p0_min" || bad "driver clampa: external aponta p0_min"
grep -q "WARN tune: clamp .*policy0/scaling_min_freq pedido=533000 lido=672000" "$FLOG" && ok "driver clampa: WARN clamp no apply" || bad "driver clampa: WARN clamp no apply"
TUNE_NOW=1181 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=partial$" "$FAKE/fusao/profile.state" && ok "driver clampa: partial fora da janela" || bad "driver clampa: partial fora da janela"
grep -q "^profile_mismatch=p0_min:672000!=533000$" "$FAKE/fusao/profile.state" && ok "driver clampa: mismatch aponta p0_min" || bad "driver clampa: mismatch aponta p0_min"
grep -q "^applied_at=1000$" "$FAKE/fusao/profile.state" && ok "verify herda applied_at do apply" || bad "verify herda applied_at do apply"
# mesmo apply num driver honesto fecha 9/9
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
grep -q "^profile_verify=9/9$" "$FAKE/fusao/profile.state" && ok "driver honesto: verify 9/9" || bad "driver honesto: verify 9/9"

# 9e-6) verify confere o valor JÁ com snap: tabela do aparelho sem os degraus do perfil (P0 = 700000 1100000 2002000,
# cpuinfo_min 700000). O battery pede 533000/1728000 e o aparelho só pode dar 700000/1100000: com o alvo cru
# (1728000) o p0_max nunca fecharia; com o alvo snapado fecha 4/4 (P0 min/max + GPU min/max) e o nó prova o valor.
printf '700000 1100000 2002000\n' > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
echo 700000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"
echo 700000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
echo 2002000 > "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
: > "$FLOG"
M54_DIR="$FAKE/fusao" FS_ROOT="$ALT" sh "$FAKE/fusao/tune.sh" battery >/dev/null 2>&1
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")" = "1100000" ] && ok "battery snapado p0_max=1100000" || bad "battery snapado p0_max=1100000"
[ "$(cat "$ALT/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")" = "700000" ] && ok "battery snapado p0_min=700000" || bad "battery snapado p0_min=700000"
grep -q "^profile_verify=4/4$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && ok "verify usa alvo snapado (4/4)" || bad "verify usa alvo snapado (4/4)"
grep -q "WARN tune: snap p0/min pedido=533000 abaixo do mínimo físico 700000, usando 700000" "$FLOG" && ok "apply loga WARN do alvo abaixo do minimo" || bad "apply loga WARN do alvo abaixo do minimo"
# o verify só confere: não repete os WARN do snap que o apply já logou (o app consulta o status toda hora)
_n0=$(grep -c "WARN tune: snap" "$FLOG")
M54_DIR="$FAKE/fusao" FS_ROOT="$ALT" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
[ "$_n0" -ge 1 ] && [ "$(grep -c "WARN tune: snap" "$FLOG")" = "$_n0" ] && ok "verify nao repete WARN do snap" || bad "verify nao repete WARN do snap"

# 9e-7) 2.9.8 piso EXTERNO (boost de boot / hint de launch, medido 2026-10-18 15:29 e 16:03): o QoS da
# Samsung segura scaling_min no alto e todo degrau "pega" o valor dela. A descida para no primeiro degrau
# (2.9.7: 15 WARN seguidos) e o pedido final ainda é gravado.
P0D="$FAKE/sys/devices/system/cpu/cpufreq/policy0"
echo 2002000 > "$P0D/scaling_max_freq"; echo 2002000 > "$P0D/scaling_min_freq"
rm -f "$FAKE/trace.txt"; : > "$FLOG"
probe_at "$FAKE" 2002000 "$FAKE/trace.txt" set_policy0 533000 1728000
[ "$(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt")" = "2" ] && ok "piso externo: descida para no 1o degrau (2 escritas)" || bad "piso externo: descida para no 1o degrau ($(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt") escritas)"
[ "$(grep -c "WARN tune: clamp .*policy0/scaling_min_freq" "$FLOG")" = "2" ] && ok "piso externo: 2 WARN, nao 15" || bad "piso externo: 2 WARN, nao 15"
# controle: driver honesto do mesmo ponto desce TODOS os degraus reais entre 2002000 e 533000 (14) + o final
echo 2002000 > "$P0D/scaling_min_freq"; rm -f "$FAKE/trace.txt"
probe_at "$FAKE" - "$FAKE/trace.txt" set_policy0 533000 1728000
[ "$(grep -c "policy0/scaling_min_freq" "$FAKE/trace.txt")" = "15" ] && [ "$(cat "$P0D/scaling_min_freq")" = "533000" ] && ok "controle: honesto desce 14 degraus + final" || bad "controle: honesto desce 14 degraus + final"

# 9e-8) janela do pending: hint segura P0 min 1152000 no apply do battery (como na entrada do Roblox)
echo 1728000 > "$P0D/scaling_max_freq"; echo 533000 > "$P0D/scaling_min_freq"
rm -f "$FAKE/fusao/profile.state"; : > "$FLOG"
TUNE_NOW=5000 probe_at "$FAKE" 1152000 - apply_battery
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "hint no apply: pending" || bad "hint no apply: pending"
grep -q "^profile_mismatch=" "$FAKE/fusao/profile.state" && bad "hint no apply: sem mismatch real" || ok "hint no apply: sem mismatch real"
grep -q "tune: battery pending (piso/teto de CPU externo: p0_min:1152000!=533000)" "$FLOG" && ok "hint no apply: loga pending" || bad "hint no apply: loga pending"
# o hint solta (o nó guarda o NOSSO pedido): verify do status dentro da janela fecha 9/9 applied
echo 533000 > "$P0D/scaling_min_freq"
TUNE_NOW=5040 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && grep -q "^profile_verify=9/9$" "$FAKE/fusao/profile.state" && ok "hint soltou: applied 9/9" || bad "hint soltou: applied 9/9"
# piso da GPU segurado no boot (aparelho 16:50: 455000 com alvo 221000, depois 221000 sem reescrita) = pending;
# teto da GPU sobrescrito (aqui p/ baixo, 949000 -> 845000) continua falha real = partial: a GPU não é QoS
echo 455000 > "$FAKE/sys/kernel/gpu/gpu_min_clock"
TUNE_NOW=5045 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_external=gpu_min:455000!=221000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "piso gpu segurado: pending" || bad "piso gpu segurado: pending"
echo 221000 > "$FAKE/sys/kernel/gpu/gpu_min_clock"; echo 845000 > "$FAKE/sys/kernel/gpu/gpu_max_clock"
TUNE_NOW=5045 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_mismatch=gpu_max:845000!=949000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=partial$" "$FAKE/fusao/profile.state" && ok "teto gpu sobrescrito: partial" || bad "teto gpu sobrescrito: partial"
echo 949000 > "$FAKE/sys/kernel/gpu/gpu_max_clock"
# teto de CPU clampado ABAIXO pelo térmico dentro da janela = pending; ACIMA do alvo nunca é externo
echo 1344000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
TUNE_NOW=5050 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_external=p4_max:1344000!=1440000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "teto termico: pending" || bad "teto termico: pending"
echo 2400000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
TUNE_NOW=5050 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_mismatch=p4_max:2400000!=1440000$" "$FAKE/fusao/profile.state" && grep -q "^profile_state=partial$" "$FAKE/fusao/profile.state" && ok "teto acima do alvo: partial" || bad "teto acima do alvo: partial"
echo 1440000 > "$FAKE/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

# 9e-8b) 2.11.5: teto acima do pedido = o NOSSO pedido regravado por outro escritor (aparelho 2026-10-20 19:53,
# saída do Roblox: P0/P4 max 2002000/2400000 por 9+ min). O reassert regrava UMA vez; obsoleto não toca.
P4D="$FAKE/sys/devices/system/cpu/cpufreq/policy4"
rm -f "$FAKE/fusao/profile.state"; : > "$FLOG"
TUNE_NOW=7000 probe_at "$FAKE" - - apply_battery
grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && ok "reassert: parte de applied" || bad "reassert: parte de applied"
echo 2400000 > "$P4D/scaling_max_freq"
TUNE_NOW=7005 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_mismatch=p4_max:2400000!=1440000$" "$FAKE/fusao/profile.state" && ok "reassert: teto regravado visto" || bad "reassert: teto regravado visto"
cp "$FAKE/fusao/profile.state" "$FAKE/state.before"
TUNE_NOW=7030 REASSERT_DELAY_S=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reassert battery 4242 >/dev/null 2>&1
[ "$(cat "$P4D/scaling_max_freq")" = "2400000" ] && cmp -s "$FAKE/fusao/profile.state" "$FAKE/state.before" && ok "reassert obsoleto nao escreve" || bad "reassert obsoleto nao escreve"
TUNE_NOW=7030 REASSERT_DELAY_S=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reassert battery 7000 >/dev/null 2>&1
[ "$(cat "$P4D/scaling_max_freq")" = "1440000" ] && grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && ok "reassert: teto volta ao pedido, applied" || bad "reassert: teto volta ao pedido, applied ($(cat "$P4D/scaling_max_freq"))"
grep -q "tune: reassert battery (p4_max regravado 1x) -> applied 9/9" "$FLOG" && ok "reassert: loga a regravacao" || bad "reassert: loga a regravacao"
# teto ABAIXO do pedido (térmico) nunca é regravado
echo 1344000 > "$P4D/scaling_max_freq"
TUNE_NOW=7031 REASSERT_DELAY_S=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reassert battery 7000 >/dev/null 2>&1
[ "$(cat "$P4D/scaling_max_freq")" = "1344000" ] && ok "reassert nao regrava teto termico" || bad "reassert nao regrava teto termico"
echo 1440000 > "$P4D/scaling_max_freq"

# 9e-9) reverify (só leitura): converge p/ applied quando o hint solta; job obsoleto não toca no state
echo 1152000 > "$P0D/scaling_min_freq"
TUNE_NOW=6000 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
sed -i 's/^applied_at=.*/applied_at=6000/' "$FAKE/fusao/profile.state"
TUNE_NOW=6000 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" verify battery >/dev/null 2>&1
grep -q "^profile_state=pending$" "$FAKE/fusao/profile.state" && ok "reverify: parte de pending" || bad "reverify: parte de pending"
echo 533000 > "$P0D/scaling_min_freq"
: > "$FLOG"
TUNE_NOW=6030 REVERIFY_STEPS=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reverify battery 6000 >/dev/null 2>&1
grep -q "^profile_state=applied$" "$FAKE/fusao/profile.state" && grep -q "tune: reverify battery -> applied 9/9" "$FLOG" && ok "reverify: converge p/ applied" || bad "reverify: converge p/ applied"
echo 1152000 > "$P0D/scaling_min_freq"
cp "$FAKE/fusao/profile.state" "$FAKE/state.before"
TUNE_NOW=6030 REVERIFY_STEPS=0 M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" reverify battery 4242 >/dev/null 2>&1
cmp -s "$FAKE/fusao/profile.state" "$FAKE/state.before" && ok "reverify obsoleto nao toca no state" || bad "reverify obsoleto nao toca no state"
echo 533000 > "$P0D/scaling_min_freq"

# 9e-10) restauração do stock (CLI/cleanup/uninstall): degraus REAIS do nó, só abaixo do piso atual.
# A função do CLI é extraída e rodada contra um nó falso; a lista antiga (outro SoC, começando em 1728000) sumiu.
for _f in "$STAGE/system/bin/fusao" "$STAGE/cleanup.sh" "$STAGE/uninstall.sh"; do
  grep -q "1728000 1512000 1320000" "$_f" && bad "lista de outro SoC fora de $(basename "$_f")" || ok "lista de outro SoC fora de $(basename "$_f")"
done
sed -n '/^restore_snapshot_nodes() {/,/^}/p' "$STAGE/system/bin/fusao" > "$FAKE/restore_fn.sh"
echo 1152000 > "$P0D/scaling_min_freq"
printf '# stock\n%s 533000\n' "$P0D/scaling_min_freq" > "$FAKE/fusao/stock.snapshot"
( . "$FAKE/restore_fn.sh"; M54_DIR="$FAKE/fusao" restore_snapshot_nodes )
[ "$(cat "$P0D/scaling_min_freq")" = "533000" ] && ok "restore snapshot: p0 min volta ao stock" || bad "restore snapshot: p0 min volta ao stock"
rm -f "$FAKE/fusao/stock.snapshot"
# CLI novo + lib.sh antiga (sem restore_snapshot_nodes; o vivo só é atualizado pelo service.sh): falha explícita,
# rc=1, sem recursão de subshells e sem mexer no nó. Limitado por ulimit -u e timeout (mata o grupo) se regredir.
OLDL="$FAKE/oldlib"; mkdir -p "$OLDL"
sed '/^restore_snapshot_nodes() {/,/^}/d' "$FAKE/fusao/lib.sh" > "$OLDL/lib.sh"
echo 1152000 > "$P0D/scaling_min_freq"
printf '# stock\n%s 533000\n' "$P0D/scaling_min_freq" > "$OLDL/stock.snapshot"
( ulimit -u $(( $(ps -u "$(id -u)" -o pid= | wc -l) + 60 )) 2>/dev/null
  M54_DIR="$OLDL" timeout 5 sh -c '. "$1"; restore_snapshot_nodes' _ "$FAKE/restore_fn.sh" ) > "$FAKE/m14.out" 2>&1
_rc=$?
[ "$_rc" = 1 ] && grep -q "sem restore_snapshot_nodes" "$FAKE/m14.out" && ok "restore com lib antiga falha explicita (rc=1)" || bad "restore com lib antiga falha explicita (rc=$_rc)"
[ "$(cat "$P0D/scaling_min_freq")" = "1152000" ] && ok "restore com lib antiga nao mexe no no" || bad "restore com lib antiga nao mexe no no"
echo 533000 > "$P0D/scaling_min_freq"

# restaura o fake principal p/ as seções seguintes
M54_DIR="$FAKE/fusao" FS_ROOT="$FAKE" sh "$FAKE/fusao/tune.sh" idle

# 10) dnd on/off com verify+retry (DND_FAKE_ZEN finge o settings get).
cp "$STAGE/fusao/dnd.sh" "$FAKE/fusao/" 2>/dev/null
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=1 sh "$FAKE/fusao/dnd.sh" on >"$FAKE/dndout.txt" 2>&1
grep -q "set_dnd priority" "$FAKE/dndout.txt" && ok "dnd on seq" || bad "dnd on seq"
[ "$(cat "$FAKE/fusao/dnd.state")" = "on" ] && ok "dnd on verified" || bad "dnd on verified"
[ -s "$FAKE/fusao/dnd.seq" ] && ok "dnd seq guard" || bad "dnd seq guard"
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=0 sh "$FAKE/fusao/dnd.sh" off >"$FAKE/dndout.txt" 2>&1
grep -q "set_dnd off" "$FAKE/dndout.txt" && ok "dnd off seq" || bad "dnd off seq"
[ "$(cat "$FAKE/fusao/dnd.state")" = "off" ] && ok "dnd off verified" || bad "dnd off verified"
rm -f "$FAKE/fusao/dnd.state"
M54_DIR="$FAKE/fusao" DND_DRYRUN=1 DND_FAKE_ZEN=9 sh "$FAKE/fusao/dnd.sh" on >/dev/null 2>&1
grep -q "dnd: FALHOU on" "$FAKE/fusao/fusao.log" && ok "dnd fail loud" || bad "dnd fail loud"
[ "$(M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/dnd.sh")" = "off" ] && ok "dnd state" || bad "dnd state"

# 11) display downscale em wm fake (stock 1080x2340)
cp "$STAGE/fusao/display.sh" "$FAKE/fusao/" 2>/dev/null
echo "1080x2340" > "$FAKE/wm.txt"
export WM_FAKE="$FAKE/wm.txt"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/display.sh" 720p | grep -q "720x1560" && ok "display 720p" || bad "display 720p"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/display.sh" reset >/dev/null
[ "$(cat "$FAKE/wm.txt")" = "1080x2340" ] && ok "display reset" || bad "display reset"
WM_FAKE="$FAKE/wm.txt" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" display show | grep -q "1080x2340" && ok "cli display" || bad "cli display"
unset WM_FAKE

# 13) higiene de órfãos temp-root (service/uninstall/cleanup) + preservação de gamelist
grep -q 'kill_stale()' "$STAGE/service.sh" && ok "orphan killfn" || bad "orphan killfn"
for _pt in "fusaod serve" "fusao.observer" "watch.sh" "thermal_guard.sh" "gos_killer.sh"; do
  grep -Fq "$_pt" "$STAGE/service.sh" && ok "orphan pat $_pt" || bad "orphan pat $_pt"
done
grep -q '"$$"' "$STAGE/service.sh" && ok "orphan self-guard" || bad "orphan self-guard"
grep -q '\[ -s "\$FUSAO_DIR/gamelist.txt" \]' "$STAGE/service.sh" && ok "service gamelist preserve" || bad "service gamelist preserve"
grep -q 'pkill -f "observer.sh"' "$STAGE/uninstall.sh" && ok "uninstall obs-sh" || bad "uninstall obs-sh"
grep -q '\*_pid' "$STAGE/uninstall.sh" && ok "uninstall pidfiles" || bad "uninstall pidfiles"
grep -q 'thermal_guard.sh' "$STAGE/cleanup.sh" && ok "cleanup guard" || bad "cleanup guard"
grep -q 'dnd.sh.*off' "$STAGE/cleanup.sh" && ok "cleanup dnd" || bad "cleanup dnd"

# 14) preload e2e (binário + fluxo sem jogo em primeiro plano via gamelist.txt)
BRAIN_DIR=""
if [ -d "$ROOT/fusao-brain" ]; then
  BRAIN_DIR="$ROOT/fusao-brain"
elif [ -d "$ROOT/_historico/fusao-brain" ]; then
  BRAIN_DIR="$ROOT/_historico/fusao-brain"
fi
if [ -n "$BRAIN_DIR" ] && command -v cargo >/dev/null 2>&1; then
  (cd "$BRAIN_DIR" && cargo build 2>/dev/null)
  mkdir -p "$FAKE/predata/data/com.jogo"
  echo conteudo > "$FAKE/predata/data/com.jogo/a.dat"
  mkdir -p "$FAKE/predata/media/0/Android/data/com.jogo"
  echo mais > "$FAKE/predata/media/0/Android/data/com.jogo/b.dat"
  "$BRAIN_DIR/target/debug/fusaod" preload com.jogo "$FAKE/predata" | grep -q "files=2" && ok "preload e2e" || bad "preload e2e"
  mkdir -p "$FAKE/packbase/preload"
  _sz=$(wc -c < "$FAKE/predata/data/com.jogo/a.dat")
  _mt=$(stat -c %Y "$FAKE/predata/data/com.jogo/a.dat" 2>/dev/null || stat -f %m "$FAKE/predata/data/com.jogo/a.dat")
  printf "%s %s %s\n" "$_mt" "$_sz" "$FAKE/predata/data/com.jogo/a.dat" > "$FAKE/packbase/preload/com_jogo.pack"
  FUSAO_PACK_BASE="$FAKE/packbase" "$BRAIN_DIR/target/debug/fusaod" preload com.jogo "$FAKE/predata" | grep -q "files=1" && ok "preload replay e2e" || bad "preload replay e2e"
else
  strings "$STAGE/system/bin/fusaod" | grep -q "preload ok pkg=" && ok "preload e2e (elf-sym)" || bad "preload e2e (elf-sym)"
  strings "$STAGE/system/bin/fusaod" | grep -q "preload_replay" && ok "preload replay e2e (elf-sym)" || bad "preload replay e2e (elf-sym)"
fi

M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload on | grep -q "preload=on" && ok "cli preload on" || bad "cli preload on"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload off | grep -q "preload=off" && ok "cli preload off" || bad "cli preload off"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "packs: nenhum" && ok "cli preload status" || bad "cli preload status"

# 14a) Preload sem jogo em primeiro plano (game-active.txt vazio, usando gamelist.txt como fonte de verdade)
: > "$FAKE/fusao/game-active.txt"
mkdir -p "$FAKE/predata/data/data/com.roblox.client" "$FAKE/predata/data/media/0/Android/obb/com.hottagames.nte"
echo "asset_roblox_1" > "$FAKE/predata/data/data/com.roblox.client/main.pak"
echo "asset_nte_1" > "$FAKE/predata/data/media/0/Android/obb/com.hottagames.nte/main.obb"
grep -Fxq "com.hottagames.nte" "$FAKE/fusao/gamelist.txt" || echo "com.hottagames.nte" >> "$FAKE/fusao/gamelist.txt"

PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "eligible_count=2" && ok "preload eligible discovery" || bad "preload eligible discovery"
# M3: elegíveis por builtins = mesma saída do printf|tr antigo para bordas, tab, CR e espaço INTERNO (tr junta os
# pedaços: "com.k l" -> com.kl; "com.m.n # jogo" -> com.m.n#jogo, sem dados). com.k/com.m.n/com.o.p são iscas.
mkdir -p "$FAKE/m3"
printf 'com.a.b\n  com.c.d\ncom.e.f  \n\tcom.g.h\t\ncom.i.j\r\ncom.k l\ncom.m.n # jogo\n  # c\n\n   \n#com.x\ncom.o.p\tx' > "$FAKE/m3/gamelist.txt"
for _p in com.a.b com.c.d com.e.f com.g.h com.i.j com.kl com.o.px com.k com.m.n com.o.p; do mkdir -p "$FAKE/m3data/data/data/$_p"; done
_m3=$(PRELOAD_DATA_ROOT="$FAKE/m3data" M54_DIR="$FAKE/m3" sh "$FAKE/fusao/fusao-cli" gamelist eligible | tr '\n' ' ')
[ "$_m3" = "com.a.b com.c.d com.e.f com.g.h com.i.j com.kl com.o.px " ] && ok "eligible builtins = printf|tr antigo" || bad "eligible builtins = printf|tr antigo [$_m3]"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload prepare | grep -q "preload-prepare ok prepared=2/2" && ok "preload prepare without fg game" || bad "preload prepare without fg game"
[ -s "$FAKE/fusao/preload/com_roblox_client.pack" ] && [ -s "$FAKE/fusao/preload/com_hottagames_nte.pack" ] && ok "preload packs created" || bad "preload packs created"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload now | grep -q "preload ok pkg=" && ok "preload now without fg game" || bad "preload now without fg game"
PRELOAD_DATA_ROOT="$FAKE/predata" M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload status | grep -q "preload_state=executed" && ok "preload state executed" || bad "preload state executed"
PRELOAD_DATA_ROOT="$FAKE/predata" PRELOAD_MEM_AVAIL_KB=400000 M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" preload now com.roblox.client | grep -q "reason=low_ram" && ok "preload low-ram gate" || bad "preload low-ram gate"

M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" status | grep -q "atividade dos ganchos" && ok "cli status hooks" || bad "cli status hooks"

# 14b) killer respeita pausa (marker = dorme sem matar)
cp "$STAGE/fusao/gos_killer.sh" "$FAKE/fusao/" 2>/dev/null
: > "$FAKE/fusao/gos_killer.paused"
M54_DIR="$FAKE/fusao" timeout 8 sh "$FAKE/fusao/gos_killer.sh" >/dev/null 2>&1
grep -q "gos killer: pausado" "$FAKE/fusao/fusao.log" && ok "killer pause" || bad "killer pause"
rm -f "$FAKE/fusao/gos_killer.paused"

# 15) dumpsys parser (formato real)
cat > "$FAKE/dump.txt" <<'EOF'
mCurrentFocus=Window{abc u0 com.roblox.client/com.roblox.client.Activity}
EOF
export DUMPSYS_FAKE="$FAKE/dump.txt"
unset FOCUS_FAKE
_got=$(grep -oE "[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+" "$FAKE/dump.txt" | head -1)
[ "$_got" = "com.roblox.client" ] && ok "watch dumpsys parse" || bad "watch dumpsys parse ($_got)"

# 16) FAS (fas-rs fork M54): lista da gamelist, alvo/modo, verify sem CPU, ordem no watch.
# fas-rs falso: processo vivo com "fas-rs run" na cmdline que loga a linha de controle do fork.
mkdir -p "$FAKE/bin" "$FAKE/sdcard"
cat > "$FAKE/bin/fas-rs" <<'FASEOF'
#!/bin/sh
trap 'exit 0' TERM
echo "[t] INFO: Config watcher started"
echo "[t] INFO: New fas buffer on: [com.roblox.client]"
echo "[t] INFO: FAS working on [com.roblox.client]"
while :; do sleep 1; done
FASEOF
chmod +x "$FAKE/bin/fas-rs"
export FAS_BIN="$FAKE/bin/fas-rs" FAS_NODE="$FAKE/fas_mode_node" FAS_USER_TOML="$FAKE/sdcard/games.toml"
: > "$FAS_NODE"
cp "$STAGE/fusao/fas.sh" "$FAKE/fusao/" && chmod +x "$FAKE/fusao/fas.sh"
cp "$STAGE/fusao/lib.sh" "$STAGE/fusao/tune.sh" "$STAGE/fusao/watch.sh" "$FAKE/fusao/"
cp "$STAGE/system/bin/fusao" "$FAKE/fusao/fusao-cli"
grep -Fxq "com.roblox.client" "$FAKE/fusao/gamelist.txt" || echo "com.roblox.client" >> "$FAKE/fusao/gamelist.txt"
fas() { M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" fas "$@"; }

M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fas.sh" gen | grep -Fq '"com.roblox.client" = [30, 45, 60, 90, 120]' && ok "fas gen auto" || bad "fas gen auto"
grep -Fq '"com.hottagames.nte" = [' "$FAKE/fusao/fas/games.toml" && ok "fas gen gamelist ssot" || bad "fas gen gamelist ssot"
fas target com.roblox.client 60 | grep -Fq "com.roblox.client=60" && ok "fas target set" || bad "fas target set"
M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fas.sh" gen | grep -Fq '"com.roblox.client" = [60]' && ok "fas target in toml" || bad "fas target in toml"
fas target com.roblox.client 90,60 >/dev/null 2>&1 && bad "fas target decrescente recusado" || ok "fas target decrescente recusado"
fas target com.roblox.client 5 >/dev/null 2>&1 && bad "fas target fora do dominio recusado" || ok "fas target fora do dominio recusado"
fas target com.roblox.client auto | grep -Fq "com.roblox.client=30, 45, 60, 90, 120" && ok "fas target auto" || bad "fas target auto"
fas mode fast | grep -q "fas_mode=fast" && ok "fas mode" || bad "fas mode"
fas mode turbo | grep -q "fas_mode=fast" && ok "fas mode invalido ignorado" || bad "fas mode invalido ignorado"
rm -f "$FAKE/fusao/fas.mode"

# fas_owns_cpu (lib.sh): ligado + binário + jogo da gamelist em foco; kill-switch, flag ou jogo fora = não
owns() { ( M54_DIR="$FAKE/fusao"; . "$FAKE/fusao/lib.sh"; fas_owns_cpu ); }
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
rm -f "$FAKE/fusao/fas.enabled"; owns && bad "fas owns sem flag" || ok "fas owns sem flag"
touch "$FAKE/fusao/fas.enabled"; owns && ok "fas owns ligado" || bad "fas owns ligado"
touch "$FAKE/fusao/disable"; owns && bad "fas owns kill-switch" || ok "fas owns kill-switch"; rm -f "$FAKE/fusao/disable"
( FAS_BIN="$FAKE/bin/nao-existe"; export FAS_BIN; owns ) && bad "fas owns sem binario" || ok "fas owns sem binario"
echo "com.fora.da.lista" > "$FAKE/fusao/game-active.txt"; owns && bad "fas owns jogo fora" || ok "fas owns jogo fora"

# verify do tune com o FAS dono da CPU: o fas-rs crava min=max (768000); com FAS não é divergência,
# sem FAS é (controle negativo no próprio teste)
FR="$FAKE/fasroot"
for p in policy0 policy4; do
  mkdir -p "$FR/sys/devices/system/cpu/cpufreq/$p"
  echo 533000 > "$FR/sys/devices/system/cpu/cpufreq/$p/cpuinfo_min_freq"
done
echo 2002000 > "$FR/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq"
echo 2400000 > "$FR/sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq"
echo "533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000" > "$FR/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
echo "533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2016000 2112000 2208000 2304000 2400000" > "$FR/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies"
for n in policy0/scaling_min_freq policy0/scaling_max_freq policy4/scaling_min_freq policy4/scaling_max_freq; do
  echo 768000 > "$FR/sys/devices/system/cpu/cpufreq/$n"
done
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
echo "battery" > "$FAKE/fusao/profile"
vfy() { M54_DIR="$FAKE/fusao" FS_ROOT="$FR" sh "$FAKE/fusao/tune.sh" verify; }
vfy | grep -q "CPU:FAS" && ok "fas verify resume CPU:FAS" || bad "fas verify resume CPU:FAS"
vfy | grep -Eq "profile_mismatch=.*p4_max" && bad "fas verify pula CPU" || ok "fas verify pula CPU"
rm -f "$FAKE/fusao/fas.enabled"
vfy | grep -Eq "profile_mismatch=.*p4_max" && ok "fas verify sem FAS confere CPU (controle)" || bad "fas verify sem FAS confere CPU (controle)"
touch "$FAKE/fusao/fas.enabled"
echo "balanced" > "$FAKE/fusao/profile"

# CLI on/status/off com o fas-rs falso
fas on | grep -q "jogo ativo: ligado agora" && ok "fas on em jogo" || bad "fas on em jogo"
sleep 1
st=$(M54_DIR="$FAKE/fusao" sh "$FAKE/fusao/fusao-cli" status)
echo "$st" | grep -q "^fas_state=working$" && ok "fas status working" || bad "fas status working"
echo "$st" | grep -q "^fas_game=com.roblox.client$" && ok "fas status game" || bad "fas status game"
[ -s "$FAS_USER_TOML" ] && cmp -s "$FAS_USER_TOML" "$FAKE/fusao/fas/games.toml" && ok "fas publica lista do usuario" || bad "fas publica lista do usuario"
fas off | grep -q "fas=off" && ok "fas off" || bad "fas off"
pgrep -f "[f]as-rs run" >/dev/null && bad "fas off mata o daemon" || ok "fas off mata o daemon"
fas status | grep -q "^fas_state=stopped$" && ok "fas status stopped" || bad "fas status stopped"

# watch: enter liga o FAS depois do tune; exit desliga ANTES do tune (o tune escreve a CPU por último)
touch "$FAKE/fusao/fas.enabled"
: > "$FAKE/fusao/fusao.log"
echo "com.roblox.client" > "$FAKE/fusao/game-active.txt"
( OBSERVER_FAKE_ALIVE=1 M54_DIR="$FAKE/fusao" timeout 10 sh "$FAKE/fusao/watch.sh" >/dev/null 2>&1 ) &
WPID=$!
sleep 4
: > "$FAKE/fusao/game-active.txt"
wait "$WPID" 2>/dev/null
grep -Eq "watch: hooks.* fas( |$)" "$FAKE/fusao/fusao.log" && ok "watch enter fas hook" || bad "watch enter fas hook"
awk '/watch: enter/ && !e {e=NR} e && /tune: apply/ && !t {t=NR} e && /fas: on/ && !f {f=NR} END {exit !(t && f && t < f)}' "$FAKE/fusao/fusao.log" && ok "watch enter tune antes do fas" || bad "watch enter tune antes do fas"
awk '/watch: exit/ && !x {x=NR} x && NR > x && /fas: off/ && !f {f=NR} x && NR > x && /tune: apply/ && !t {t=NR} END {exit !(f && t && f < t)}' "$FAKE/fusao/fusao.log" && ok "watch exit fas antes do tune" || bad "watch exit fas antes do tune"
pgrep -f "[f]as-rs run" >/dev/null && bad "watch exit mata o daemon" || ok "watch exit mata o daemon"
for _p in $(pgrep -f "[f]as-rs run"); do kill "$_p" 2>/dev/null; done
unset FAS_BIN FAS_NODE FAS_USER_TOML
rm -f "$FAKE/fusao/fas.enabled"

# 17) observer.sh: sem libnativeloader visível (root reativado após boot completo) entra no mount
# namespace do zygote64 antes do app_process; com a lib visível vai direto; nunca em laço.
OB="$FAKE/obsbin"; mkdir -p "$OB"
export OBS_TRACE="$FAKE/obs.trace"
cat > "$OB/pidof" <<'OBSEOF'
#!/bin/sh
[ "$1" = zygote64 ] && echo 4242
OBSEOF
cat > "$OB/nsenter" <<'OBSEOF'
#!/bin/sh
echo "nsenter $*" >> "$OBS_TRACE"
shift 4
exec "$@"
OBSEOF
cat > "$OB/app_process" <<'OBSEOF'
#!/bin/sh
echo "app_process hop=${FUSAO_NS_HOP:-0} $* | bcp=${BOOTCLASSPATH:-} sock=${ANDROID_SOCKET_zygote:-}" >> "$OBS_TRACE"
OBSEOF
chmod +x "$OB/pidof" "$OB/nsenter" "$OB/app_process"
cp "$STAGE/fusao/observer.sh" "$FAKE/fusao/observer.sh"
: > "$FAKE/fusao/observer.dex"
obs() { PATH="$OB:$PATH" M54_DIR="$FAKE/fusao" FUSAO_ART_LIB="$1" sh "$FAKE/fusao/observer.sh"; }
: > "$OBS_TRACE"; obs "$FAKE/nao-existe/libnativeloader.so"
grep -q "^nsenter -t 4242 -m -- sh " "$OBS_TRACE" && ok "observer sem lib entra no ns do zygote" || bad "observer sem lib entra no ns do zygote"
[ "$(grep -c "^app_process hop=1 /system/bin com.fusao.observer.ObserverMain" "$OBS_TRACE")" = 1 ] && ok "observer roda uma vez no ns novo (sem laco)" || bad "observer roda uma vez no ns novo (sem laco)"
: > "$FAKE/libnativeloader.so"; : > "$OBS_TRACE"; obs "$FAKE/libnativeloader.so"
grep -q "^nsenter" "$OBS_TRACE" && bad "observer com lib vai direto" || ok "observer com lib vai direto"
grep -q "^app_process hop=0 " "$OBS_TRACE" && ok "observer com lib chama app_process" || bad "observer com lib chama app_process"
# ambiente do KSU late-load sem BOOTCLASSPATH: herda o do zygote64, sem os sockets dele
printf 'BOOTCLASSPATH=/x/core-oj.jar\0ANDROID_SOCKET_zygote=9\0ANDROID_ART_ROOT=/apex/art\0' > "$FAKE/zyg.environ"
: > "$OBS_TRACE"
( unset BOOTCLASSPATH; FUSAO_ZYGOTE_ENV="$FAKE/zyg.environ"; export FUSAO_ZYGOTE_ENV; obs "$FAKE/libnativeloader.so" )
grep -q "| bcp=/x/core-oj.jar sock=$" "$OBS_TRACE" && ok "observer herda o ambiente do zygote sem sockets" || bad "observer herda o ambiente do zygote sem sockets"
: > "$OBS_TRACE"
( BOOTCLASSPATH=/meu/bcp; export BOOTCLASSPATH; FUSAO_ZYGOTE_ENV="$FAKE/zyg.environ"; export FUSAO_ZYGOTE_ENV; obs "$FAKE/libnativeloader.so" )
grep -q "| bcp=/meu/bcp " "$OBS_TRACE" && ok "observer com BOOTCLASSPATH nao importa" || bad "observer com BOOTCLASSPATH nao importa"
unset OBS_TRACE

# nap: o FIFO some com o daemon vivo -> a guarda [ -p ] cai para o sleep (sem ela o `<>` cria um arquivo regular e o
# read volta na hora: laço quente). Roda sob mksh (o shell do aparelho; o bash serve de reserva com KSH_VERSION falso).
NAPSH=$(command -v mksh || command -v bash)
cat > "$FAKE/nap_t.sh" <<'NAPEOF'
. "$M54_DIR/lib.sh"
nap_init t
[ -p "$NAP_FIFO" ] || { echo nofifo > "$NAP_OUT"; exit 0; }
rm -f "$NAP_FIFO"
i=0; while [ "$i" -lt 50 ]; do nap 1; i=$((i+1)); echo "$i" > "$NAP_OUT"; done
NAPEOF
: > "$FAKE/nap.out"
KSH_VERSION=x NAP_OUT="$FAKE/nap.out" M54_DIR="$FAKE/fusao" timeout 3 "$NAPSH" "$FAKE/nap_t.sh" 2>/dev/null
_nv=$(cat "$FAKE/nap.out")
case "$_nv" in [1-4]) ok "nap sem FIFO dorme ($_nv voltas em 3 s)";; *) bad "nap sem FIFO dorme ($_nv voltas em 3 s)";; esac
[ -e "$FAKE/fusao/.nap.t" ] && bad "nap sem FIFO nao cria arquivo regular" || ok "nap sem FIFO nao cria arquivo regular"

# M1: read_gamefile por builtins = printf|tr antigo (bordas, tab, CR, espaço interno); observer_alive com cache:
# o pgrep roda 1x enquanto o pid em cache vive, e o observer morto volta a 1 — inclusive ZUMBI (o pai do pid em
# cache é um exec sleep que nunca colhe; o stat continua com o mesmo início).
sed -n '/^read_gamefile() {/,/^}/p' "$STAGE/fusao/watch.sh" > "$FAKE/m1_rg.sh"
sed -n '/^OBS_PID=""/,/^}/p' "$STAGE/fusao/watch.sh" > "$FAKE/m1_oa.sh"
_m1=0
for _c in 'com.a.b' '  com.a.b' 'com.a.b  ' "$(printf '\tcom.a.b\r')" 'com.a b' 'com.a.b # jogo' '   ' ''; do
  printf '%s\n' "$_c" > "$FAKE/m1_gf"
  _old=$(printf "%s" "$_c" | tr -d '[:space:]')
  _new=$(GAMEFILE="$FAKE/m1_gf" sh -c '. "$1"; read_gamefile; printf "%s" "$CUR"' _ "$FAKE/m1_rg.sh")
  [ "$_old" = "$_new" ] || { _m1=1; echo "  read_gamefile [$_c]: antigo=[$_old] novo=[$_new]"; }
done
[ "$_m1" = 0 ] && ok "read_gamefile builtins = printf|tr antigo" || bad "read_gamefile builtins = printf|tr antigo"
mkdir -p "$FAKE/m1bin"
printf '#!/bin/sh\necho x >> "%s"\n[ -s "%s" ] && cat "%s"\n' "$FAKE/m1_pgn" "$FAKE/m1_pg" "$FAKE/m1_pg" > "$FAKE/m1bin/pgrep"; chmod +x "$FAKE/m1bin/pgrep"
: > "$FAKE/m1_pg"; : > "$FAKE/m1_pgn"
sh -c 'sleep 30 & echo $! > "$1"; exec sleep 60' _ "$FAKE/m1_pg" & _m1h=$!
_i=0; while [ ! -s "$FAKE/m1_pg" ] && [ $_i -lt 50 ]; do sleep 0.1; _i=$((_i+1)); done
_m1p=$(cat "$FAKE/m1_pg")
cat > "$FAKE/m1_t.sh" <<'M1EOF'
. "$1"
observer_alive; a=$?
: > "$2"
observer_alive; b=$?
kill "$3" 2>/dev/null; i=0
while ! grep -q '^State:.*Z' "/proc/$3/status" 2>/dev/null && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done
observer_alive; c=$?
echo "$a$b$c"
M1EOF
_m1r=$(PATH="$FAKE/m1bin:$PATH" OBSERVER_FAKE_ALIVE= sh "$FAKE/m1_t.sh" "$FAKE/m1_oa.sh" "$FAKE/m1_pg" "$_m1p")
kill "$_m1h" 2>/dev/null; wait "$_m1h" 2>/dev/null
[ "$_m1r" = "001" ] && [ "$(wc -l < "$FAKE/m1_pgn")" = 2 ] && ok "observer_alive cache (pgrep 1x vivo, morto=1)" || bad "observer_alive cache [$_m1r pgrep=$(wc -l < "$FAKE/m1_pgn")]"

# 18) tools: kill-list em cópias do stage (o stage limpo passa; cada injeção tem de ser acusada)
sweep_copy() { rm -rf "$FAKE/swp"; cp -r "$STAGE" "$FAKE/swp"; }
sweep_run() { python3 "$ROOT/tools/fusao-sweep.py" --stage "$FAKE/swp" > "$FAKE/swp.out" 2>&1; }
sweep_copy; sweep_run && ok "sweep stage limpo" || { bad "sweep stage limpo"; cat "$FAKE/swp.out"; }
sweep_copy; echo 'rm -rf /data/adb/fusao' >> "$FAKE/swp/fusao/watch.sh"
sweep_run && bad "sweep acusa rm -rf em dado real" || ok "sweep acusa rm -rf em dado real"
sweep_copy; echo '  # rm -rf /data/adb/fusao (exemplo)' >> "$FAKE/swp/fusao/watch.sh"
sweep_run && ok "sweep ignora rm -rf comentado" || bad "sweep ignora rm -rf comentado"
# o CLI não tem sufixo: era tratado como binário e pulava as checagens de texto
sweep_copy; echo 'X=202.155.143.189' >> "$FAKE/swp/system/bin/fusao"
sweep_run && bad "sweep confere o CLI sem sufixo" || ok "sweep confere o CLI sem sufixo"
# build: self-check antes de gravar (customize sem fas.sh = rc!=0 e nenhum zip no disco)
sweep_copy; sed -i 's#fusao/fas\.sh#fusao/FAS_X#g' "$FAKE/swp/customize.sh"; rm -f "$FAKE/b.zip"
python3 "$ROOT/tools/fusao-build.py" --stage "$FAKE/swp" --out "$FAKE/b.zip" > /dev/null 2>&1 && bad "build self-check falho nao grava zip" || {
  [ -e "$FAKE/b.zip" ] && bad "build self-check falho nao grava zip" || ok "build self-check falho nao grava zip"; }
sweep_copy; rm -f "$FAKE/b.zip"
python3 "$ROOT/tools/fusao-build.py" --stage "$FAKE/swp" --out "$FAKE/b.zip" > /dev/null 2>&1 && [ -s "$FAKE/b.zip" ] && ok "build stage bom grava zip" || bad "build stage bom grava zip"
rm -rf "$FAKE/swp" "$FAKE/b.zip"

echo "----"
echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
