#!/system/bin/sh
# kernlab agent — runtime do aparelho. O plano gerado no PC (plan.sh) dá `.` neste arquivo e
# chama as funções kl_*. O agente é um executor sem opinião: aplica, relê, mede e grava.
# Toda validação (o nó segurou? o estado derivou? o efeito é real?) é feita no PC, sobre o
# que ficou gravado aqui. A única lógica que fica no aparelho é a de segurança: portão
# térmico, aborto e restauração — elas precisam funcionar com o PC desconectado.
#
# Layout: $KL_HOME/bin (nativos), $KL_HOME/runs/<id>/{plan.sh,knobs.tsv,log,status,t<N>/}

KL_HOME=/data/local/tmp/kernlab
KL_BIN=$KL_HOME/bin
KL_EMS_TUNER=/sys/devices/platform/ems/emstune/aio_tuner
KL_EMS_CURSET=/sys/devices/platform/ems/emstune/cur_set
KL_BATT=/sys/class/power_supply/battery
KL_SPID=""
KL_T=""
KL_HAS_EMS=0

kl_log() { echo "$(date +%H:%M:%S) $*" >> "$KL_RUN/log"; }

kl_status() {
  echo "$*" > "$KL_RUN/status.tmp" && mv -f "$KL_RUN/status.tmp" "$KL_RUN/status"
}

kl_zone() {
  # caminho do temp de uma zona térmica pelo tipo (BIG, LITTLE, G3D, battery ...)
  for _z in /sys/class/thermal/thermal_zone*; do
    [ "$(cat "$_z/type" 2>/dev/null)" = "$1" ] && { echo "$_z/temp"; return 0; }
  done
  return 1
}

kl_begin() {
  KL_ID="$1"
  KL_NTRIALS="$2"
  KL_RUN="$KL_HOME/runs/$KL_ID"
  mkdir -p "$KL_RUN"
  if [ -f "$KL_HOME/lock" ]; then
    _lp=$(cut -d' ' -f2 "$KL_HOME/lock" 2>/dev/null)
    if [ -n "$_lp" ] && kill -0 "$_lp" 2>/dev/null; then
      echo "reason=locked_by $(cat "$KL_HOME/lock")" > "$KL_RUN/ABORTED"
      exit 3
    fi
  fi
  if [ -f "$KL_HOME/STOP" ]; then
    # STOP só é apagado pelo PC (kernlab.py stop --clear): apagar aqui fazia o próximo run de
    # uma fila engolir o pedido de parada (2026-10-18)
    echo "reason=stop_pending" > "$KL_RUN/ABORTED"
    exit 3
  fi
  echo "$KL_ID $$" > "$KL_HOME/lock"
  rm -f "$KL_RUN/DONE" "$KL_RUN/ABORTED" "$KL_RUN/knobs.tsv"
  : > "$KL_RUN/log"
  KL_TZ_BIG=$(kl_zone BIG)
  KL_TZ_LIT=$(kl_zone LITTLE)
  KL_TZ_G3D=$(kl_zone G3D)
  KL_FLOOR0=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq)
  KL_FLOOR4=$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq)
  trap 'kl_on_exit' EXIT
  trap 'kl_abort signal' INT TERM HUP
  kl_env > "$KL_RUN/env_begin"
  kl_log "begin $KL_ID trials=$KL_NTRIALS pid=$$ floor_start=$KL_FLOOR0/$KL_FLOOR4"
  kl_status "begin"
}

# kl_knob <id> <access> <target> <valor-de-escrita-base> <leitura-crua-base>
kl_knob() {
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" >> "$KL_RUN/knobs.tsv"
  [ "$2" = "ems" ] && KL_HAS_EMS=1
  return 0
}

kl_line() { awk -F'\t' -v id="$1" '$1==id{print; exit}' "$KL_RUN/knobs.tsv"; }

kl_read_raw() {
  # <access> <target> -> leitura crua (primeira linha)
  case "$1" in
    file) head -n 1 "$2" 2>/dev/null | tr -d '\r' ;;
    setting) settings get $2 2>/dev/null ;;
    ems) echo "@curset" ;;
  esac
}

kl_write_raw() {
  # <access> <target> <valor>
  case "$1" in
    file) echo "$3" > "$2" 2>/dev/null ;;
    setting) settings put $2 "$3" >/dev/null 2>&1 ;;
    ems) echo "$2 $3" > "$KL_EMS_TUNER" 2>/dev/null ;;
  esac
}

kl_set() {
  _line=$(kl_line "$1")
  [ -n "$_line" ] || { kl_log "set: nó não declarado $1"; return 1; }
  _acc=$(echo "$_line" | cut -f2)
  _tgt=$(echo "$_line" | cut -f3)
  kl_write_raw "$_acc" "$_tgt" "$2"
  printf '%s\t%s\t%s\n' "$1" "$2" "$(kl_read_raw "$_acc" "$_tgt")" >> "$KL_T/set.tsv"
}

kl_readback_all() {
  : > "$1"
  while IFS='	' read -r _id _acc _tgt _bw _braw; do
    printf '%s\t%s\n' "$_id" "$(kl_read_raw "$_acc" "$_tgt")" >> "$1"
  done < "$KL_RUN/knobs.tsv"
}

kl_curset() {
  [ "$KL_HAS_EMS" = 1 ] || return 0
  cat "$KL_EMS_CURSET" > "$KL_T/curset_$1.txt" 2>/dev/null
}

kl_verify_baseline() {
  # o estado lido agora tem de ser o mesmo que o PC leu no preflight (exceto EMS, que o
  # PC confere pelo cur_set). Se mudou, outro escritor está ativo: aborta antes de tocar.
  _bad=""
  while IFS='	' read -r _id _acc _tgt _bw _braw; do
    [ "$_acc" = "ems" ] && continue
    _now=$(kl_read_raw "$_acc" "$_tgt")
    [ "$_now" = "$_braw" ] || _bad="$_bad $_id=[$_now]!=[$_braw]"
  done < "$KL_RUN/knobs.tsv"
  if [ "$KL_HAS_EMS" = 1 ]; then
    cat "$KL_EMS_CURSET" > "$KL_RUN/curset_begin.txt" 2>/dev/null
  fi
  if [ -n "$_bad" ]; then
    kl_log "baseline mudou:$_bad"
    kl_abort "baseline_changed"
  fi
  return 0
}

kl_restore() {
  [ -f "$KL_RUN/knobs.tsv" ] || return 0
  : > "$KL_RUN/restore.tsv"
  while IFS='	' read -r _id _acc _tgt _bw _braw; do
    kl_write_raw "$_acc" "$_tgt" "$_bw"
    printf '%s\t%s\t%s\n' "$_id" "$_bw" "$(kl_read_raw "$_acc" "$_tgt")" >> "$KL_RUN/restore.tsv"
  done < "$KL_RUN/knobs.tsv"
  [ "$KL_HAS_EMS" = 1 ] && cat "$KL_EMS_CURSET" > "$KL_RUN/curset_restored.txt" 2>/dev/null
  kl_log "restore feito"
}

kl_sampler_stop() {
  if [ -n "$KL_SPID" ]; then
    kill -TERM "$KL_SPID" 2>/dev/null
    wait "$KL_SPID" 2>/dev/null
    KL_SPID=""
  fi
}

kl_abort() {
  trap - EXIT INT TERM HUP
  kl_sampler_stop
  kl_restore
  echo "reason=$1" > "$KL_RUN/ABORTED"
  kl_log "ABORT $1"
  kl_status "aborted $1"
  rm -f "$KL_HOME/lock"
  exit 1
}

kl_on_exit() {
  if [ ! -f "$KL_RUN/DONE" ] && [ ! -f "$KL_RUN/ABORTED" ]; then
    kl_sampler_stop
    kl_restore
    echo "reason=exit_unexpected" > "$KL_RUN/ABORTED"
    kl_status "aborted exit_unexpected"
  fi
  rm -f "$KL_HOME/lock"
}

kl_env() {
  echo "uptime=$(cut -d' ' -f1 /proc/uptime)"
  echo "boot_id=$(cat /proc/sys/kernel/random/boot_id)"
  echo "batt_level=$(cat $KL_BATT/capacity)"
  echo "batt_status=$(cat $KL_BATT/status)"
  echo "batt_temp=$(cat $KL_BATT/temp)"
  echo "t_big=$(cat "$KL_TZ_BIG" 2>/dev/null)"
  echo "t_little=$(cat "$KL_TZ_LIT" 2>/dev/null)"
  echo "t_g3d=$(cat "$KL_TZ_G3D" 2>/dev/null)"
  echo "wakefulness=$(dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*' | cut -d= -f2)"
  echo "focus=$(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -o 'u0 [^ ]*' | cut -d' ' -f2)"
  echo "emstune=$(head -n 1 "$KL_EMS_CURSET" 2>/dev/null)"
  echo "cpu_max=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq)/$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq)"
  echo "cpu_min=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq)/$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq)"
  echo "gpu_max=$(cat /sys/kernel/gpu/gpu_max_clock)"
  echo "gpu_min=$(cat /sys/kernel/gpu/gpu_min_clock)"
  echo "mif_min=$(cat /sys/class/devfreq/17000010.devfreq_mif/min_freq)"
}

kl_psi() {
  for _r in cpu memory io; do
    awk -v r="$_r" '{for(i=1;i<=NF;i++) if($i ~ /^total=/){split($i,a,"="); print r"_"$1"_total="a[2]}}' \
      "/proc/pressure/$_r" 2>/dev/null
  done
}

kl_trial() {
  KL_T="$KL_RUN/t$1"
  mkdir -p "$KL_T"
  : > "$KL_T/set.tsv"
  [ -f "$KL_HOME/STOP" ] && kl_abort "stop_requested"
  printf 'trial=%s\narm=%s\nblock=%s\nstart_uptime=%s\n' "$1" "$2" "$3" "$(cut -d' ' -f1 /proc/uptime)" \
    > "$KL_T/meta"
  kl_status "trial $1/$KL_NTRIALS arm=$2 block=$3"
  kl_log "trial $1 arm=$2 block=$3"
}

kl_settle() { sleep "$1"; }

kl_gate() {
  # <tmax_cpu_gpu_C> <tmax_bateria_C> <timeout_s> [min|start]: espera esfriar; bateria >= 42 C aborta.
  _waited=0
  while :; do
    _tb=$(cat "$KL_TZ_BIG" 2>/dev/null); _tl=$(cat "$KL_TZ_LIT" 2>/dev/null)
    _tg=$(cat "$KL_TZ_G3D" 2>/dev/null); _tbat=$(cat $KL_BATT/temp)
    _cap=$(cat $KL_BATT/capacity)
    _tmax=$_tb
    [ "${_tl:-0}" -gt "$_tmax" ] && _tmax=$_tl
    [ "${_tg:-0}" -gt "$_tmax" ] && _tmax=$_tg
    [ "$_tbat" -ge 420 ] && kl_abort "battery_hot_$_tbat"
    [ "$_cap" -lt 15 ] && kl_abort "battery_low_$_cap"
    if [ "$_tmax" -le $(($1 * 1000)) ] && [ "$_tbat" -le $(($2 * 10)) ]; then break; fi
    if [ "$_waited" -ge "$3" ]; then
      echo "gate_timeout=1" >> "$KL_T/meta"
      break
    fi
    kl_status "gate: soc=$_tmax bat=$_tbat esperando ${_waited}s"
    sleep 5
    _waited=$((_waited + 5))
  done
  printf 'gate_wait_s=%s\ngate_tmax=%s\ngate_tbat=%s\n' "$_waited" "$_tmax" "$_tbat" >> "$KL_T/meta"
  # Piso quieto: hints do Hyper (LISTVIEW_SCROLL segura CPUMinFreq 40% por 10 s; AMS_RESUME até
  # ser liberado) sobem scaling_min_freq. Trial só começa com os pisos no mínimo físico, senão
  # mede o boost da Samsung e não o nó (2026-10-18: base do burst 83% -> 2% de perda por isso).
  # Modo "start" (jogo in-situ): o jogo segura um piso o run inteiro (NTE: P0 min 1152000), que
  # é o regime medido; a referência passa a ser o piso lido no kl_begin e só um hint ACIMA dele
  # segura o trial. No modo "min" todo trial do jogo esperava 30 s e saía inválido.
  _fw=0
  _p0=/sys/devices/system/cpu/cpufreq/policy0
  _p4=/sys/devices/system/cpu/cpufreq/policy4
  if [ "$4" = start ]; then
    _r0=$KL_FLOOR0; _r4=$KL_FLOOR4
  else
    _r0=$(cat $_p0/cpuinfo_min_freq); _r4=$(cat $_p4/cpuinfo_min_freq)
  fi
  while :; do
    _m0=$(cat $_p0/scaling_min_freq); _m4=$(cat $_p4/scaling_min_freq)
    [ "$_m0" -le "$_r0" ] && [ "$_m4" -le "$_r4" ] && break
    if [ "$_fw" -ge 30 ]; then
      echo "gate_floor_timeout=1" >> "$KL_T/meta"
      break
    fi
    sleep 1
    _fw=$((_fw + 1))
  done
  printf 'gate_floor_wait_s=%s\ngate_floor=%s/%s\ngate_floor_ref=%s/%s\n' "$_fw" "$_m0" "$_m4" "$_r0" "$_r4" \
    >> "$KL_T/meta"
}

kl_sampler_start() {
  _cs=""
  [ "$KL_HAS_EMS" = 1 ] && _cs="--curset $KL_EMS_CURSET $KL_T"
  "$KL_BIN/kl_sample" -i 250 -d 3600 --power ibat vbat $_cs \
    ibat=$KL_BATT/current_now vbat=$KL_BATT/voltage_now \
    f0=/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq \
    f4=/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq \
    gpu=/sys/kernel/gpu/gpu_clock mif=/sys/class/devfreq/17000010.devfreq_mif/cur_freq \
    tbig="$KL_TZ_BIG" tlit="$KL_TZ_LIT" tg3d="$KL_TZ_G3D" tbat=$KL_BATT/temp \
    > "$KL_T/telemetry" 2>&1 &
  KL_SPID=$!
}

kl_measure() {
  _wl="$1"
  shift
  kl_readback_all "$KL_T/knobs_pre.tsv"
  kl_curset pre
  kl_env > "$KL_T/env_pre"
  kl_psi > "$KL_T/psi_pre"
  kl_sampler_start
  "wl_$_wl" "$@" > "$KL_T/metrics" 2> "$KL_T/wl.err"
  _rc=$?
  kl_sampler_stop
  kl_psi > "$KL_T/psi_post"
  kl_env > "$KL_T/env_post"
  kl_readback_all "$KL_T/knobs_post.tsv"
  kl_curset post
  echo "rc=$_rc" >> "$KL_T/meta"
  echo "end_uptime=$(cut -d' ' -f1 /proc/uptime)" >> "$KL_T/meta"
}

kl_trial_end() { touch "$KL_T/END"; }

kl_end() {
  kl_restore
  kl_env > "$KL_RUN/env_end"
  trap - EXIT INT TERM HUP
  touch "$KL_RUN/DONE"
  kl_status "done"
  kl_log "done"
  rm -f "$KL_HOME/lock"
}

kl_focused() {
  dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -q "u0 $1/"
}

kl_wake() {
  if ! dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; then
    input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    sleep 1
  fi
}

# ------------------------------------------------------------------ posse do nó
# Depois de cada evento, o estado de todos os nós declarados vai para state_<tag>.tsv (+ cur_set).
kl_state() {
  kl_readback_all "$KL_RUN/state_$1.tsv"
  [ "$KL_HAS_EMS" = 1 ] && cat "$KL_EMS_CURSET" > "$KL_RUN/curset_$1.txt" 2>/dev/null
  kl_env > "$KL_RUN/env_$1"
  kl_log "estado $1"
}

ev_wait() { sleep "${1:-30}"; }

ev_launch() {
  am force-stop com.android.settings
  am start -W -a android.settings.APPLICATION_SETTINGS >/dev/null 2>&1
  sleep 3
}

ev_scroll() {
  "$KL_BIN/kl_touch" "$KL_RUN/gesture.txt" >/dev/null 2>&1
  sleep 2
}

ev_home() {
  am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
  sleep 3
}

ev_other_app() {
  _c=$(cmd package resolve-activity --brief com.sec.android.app.popupcalculator 2>/dev/null | tail -n 1)
  case "$_c" in */*) am start -W -n "$_c" >/dev/null 2>&1 ;; esac
  sleep 3
  am start -W -a android.settings.APPLICATION_SETTINGS >/dev/null 2>&1
  sleep 3
}

# ------------------------------------------------------------------ workloads
# Cada wl_* imprime métricas key=value no stdout e grava artefatos em $KL_T.

wl_burst() {
  "$KL_BIN/kl_burst" run "$@" --raw "$KL_T/raw.csv"
}

wl_idle() {
  sleep "$1"
  echo "idle=1"
  echo "seconds=$1"
}

wl_io() {
  "$KL_BIN/kl_io" "$@"
}

ts_reset() {
  dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1
  dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1
  dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1
}

ts_dump() {
  dumpsys SurfaceFlinger --timestats -dump > "$1" 2>&1
  dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1
}

# wl_scroll <pkg> <roteiro> <settle_s> <relaunch 0|1> <args do am start...>
# relaunch=1 reabre o app do zero a cada trial (mesma posição inicial garantida); relaunch=0 só
# garante o foco — necessário para nós que a Samsung reescreve no launch (top-app uclamp.min).
wl_scroll() {
  _pkg="$1"; _gest="$2"; _settle="$3"; _relaunch="$4"
  shift 4
  kl_wake
  if [ "$_relaunch" = 1 ] || ! kl_focused "$_pkg"; then
    am force-stop "$_pkg"
    am start -W "$@" > "$KL_T/launch.txt" 2>&1
    echo "relaunched=1"
  fi
  sleep "$_settle"
  if ! kl_focused "$_pkg"; then
    echo "error=not_focused"
    return 1
  fi
  dumpsys gfxinfo "$_pkg" reset >/dev/null 2>&1
  ts_reset
  "$KL_BIN/kl_touch" "$_gest" > "$KL_T/touch.txt" 2>&1
  dumpsys gfxinfo "$_pkg" > "$KL_T/gfxinfo.txt" 2>&1
  ts_dump "$KL_T/timestats.txt"
  echo "scroll=1"
}

# wl_launch <pkg> <componente> <repetições>
wl_launch() {
  _pkg="$1"; _comp="$2"; _reps="$3"
  kl_wake
  echo "launch=1"
  _i=1
  while [ "$_i" -le "$_reps" ]; do
    am force-stop "$_pkg"
    sleep 1
    am start -W -n "$_comp" > "$KL_T/launch_$_i.txt" 2>&1
    awk -v i="$_i" '/^TotalTime:/{print "r"i"_total_ms="$2} /^WaitTime:/{print "r"i"_wait_ms="$2}
                    /^LaunchState:/{print "r"i"_state="$2}' "$KL_T/launch_$_i.txt"
    am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
    sleep 2
    _i=$((_i + 1))
  done
}

# wl_appswitch <componente> [<componente>...]: abre todos, depois revisita todos. Todo trial
# parte do mesmo estado (force-stop de todos antes), senão a retenção herdaria o trial anterior.
wl_appswitch() {
  kl_wake
  echo "appswitch=1"
  for _c in "$@"; do am force-stop "${_c%%/*}"; done
  sleep 3
  _pass=1
  while [ "$_pass" -le 2 ]; do
    _i=1
    for _c in "$@"; do
      am start -W -n "$_c" > "$KL_T/sw_${_pass}_$_i.txt" 2>&1
      awk -v k="p${_pass}_${_i}" '/^TotalTime:/{print k"_total_ms="$2} /^LaunchState:/{print k"_state="$2}' \
        "$KL_T/sw_${_pass}_$_i.txt"
      sleep 2
      _i=$((_i + 1))
    done
    _pass=$((_pass + 1))
  done
  am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
}

# wl_game <pkg> <segundos>: mede o jogo que o dono deixou aberto numa cena estável.
wl_game() {
  if ! kl_focused "$1"; then
    echo "error=not_focused"
    return 1
  fi
  ts_reset
  sleep "$2"
  ts_dump "$KL_T/timestats.txt"
  kl_focused "$1" || echo "error=focus_lost"
  echo "game=1"
}
