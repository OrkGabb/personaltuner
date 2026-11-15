#!/system/bin/sh
# fusao-perf.sh — Executor processo+IO consciente do perfil e seguro para UE5/NTE.
# Regras críticas (validadas na investigação do Neverness to Everness / UE5):
# 1. NUNCA escrever o PID inteiro em /dev/cpuset/top-app/cgroup.procs ou
#    /dev/stune/top-app/cgroup.procs — isso puxa todos os worker pools de
#    descompressão/streaming assíncrono (FAsyncLoadingThread / TaskGraphBP)
#    para os 4 núcleos Cortex-A78 (policy4), causando contenção direta com a
#    GameThread durante estados de streaming/voo. Usar apenas /tasks por TID.
# 2. Priorizar GameThread/UnityMain com nice moderado (-6 no Game, -2 no Balanced,
#    0 no Battery) e executar uma 2ª passagem atrasada (3s) quando a GameThread
#    ainda não nasceu no instante do launch.
# 3. Salvar e restaurar os valores originais de nomerges/add_random ao sair.
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
DEV_R="${FS_ROOT:+$FS_ROOT}"
PROC_R="${FS_ROOT:+$FS_ROOT}"
# Sem uclamp/stune no kernel M54 (/dev/stune ausente): usa apenas /dev/cpuset e /dev/cpuctl por TID.
GAMEFILE="$M54_DIR/game-active.txt"
IO_STOCK="$M54_DIR/perf.io.stock"
AUDIO_STATE="$M54_DIR/perf.audio.state"
AUDIO_PIDF="$M54_DIR/perf.audio.pid"
PROC_STATE="$M54_DIR/perf.proc.state"
MODE="${1:-enter}"
PKG="${2:-}"
NL='
'

# tid_nice <pid> <tid> — nice (campo 19 do stat) em _TID_NICE e política de escalonamento (campo 41) em
# _TID_POL, só com builtins: o awk por thread custava ~27 ms de exec cada e a proteção de áudio passa por ~90
# threads (aparelho 2026-11-13: 12-15 s dentro do game-enter). rc 1 se a thread sumiu ou o campo não é número.
tid_nice() {
  local _f="$PROC_R/proc/$1/task/$2/stat" _l=""
  _TID_NICE=""
  _TID_POL=0
  [ -r "$_f" ] || _f="$PROC_R/proc/$2/stat"
  [ -r "$_f" ] || return 1
  IFS= read -r _l < "$_f" 2>/dev/null
  [ -n "$_l" ] || return 1
  _l=${_l##*) }
  set -- $_l
  case "${17:-}" in ''|-|*[!0-9-]*) return 1;; esac
  _TID_NICE=${17}
  case "${39:-}" in ''|*[!0-9]*) ;; *) _TID_POL=${39};; esac
  return 0
}

# tid_cgroups <pid> <tid> — cgroup v1 da thread: cpuset em _TID_CSET e cpu em _TID_CCTL (builtins; mesma regra
# do read_tid_cgroup: arquivo da thread, senão o do processo, senão /foreground).
tid_cgroups() {
  local _f="$PROC_R/proc/$1/task/$2/cgroup" _h _c _p
  _TID_CSET=""
  _TID_CCTL=""
  [ -r "$_f" ] || _f="$PROC_R/proc/$1/cgroup"
  if [ -r "$_f" ]; then
    while IFS=: read -r _h _c _p || [ -n "$_h" ]; do
      case "$_c" in
        cpuset) [ -n "$_TID_CSET" ] || _TID_CSET=$_p;;
        cpu) [ -n "$_TID_CCTL" ] || _TID_CCTL=$_p;;
      esac
    done < "$_f" 2>/dev/null
  fi
  [ -n "$_TID_CSET" ] || _TID_CSET="/foreground"
  [ -n "$_TID_CCTL" ] || _TID_CCTL="/foreground"
}

# rec_has <prefixo de linha> — o registro em memória (_rec_buf) já tem uma linha que começa assim (sem grep).
rec_has() {
  case "$NL$_rec_buf" in *"$NL$1"*) return 0;; esac
  return 1
}

# rec_line <prefixo de linha> — o resto da primeira linha do registro que começa com o prefixo.
rec_line() {
  local _t
  _t="$NL$_rec_buf"
  _t=${_t#*"$NL$1"}
  echo "${_t%%"$NL"*}"
}

# read_tid_nice <pid> <tid> — campo 19 de /proc/<pid>/task/<tid>/stat (17º após ') ').
read_tid_nice() {
  tid_nice "$1" "$2" || return 1
  echo "$_TID_NICE"
}

# set_exact_nice <pid> <tid> <target_nice> — Toybox renice aplica incremento relativo (+delta);
# renice -n 0 é no-op. Calcula delta exacto e só invoca renice quando delta != 0.
set_exact_nice() {
  local _p="$1" _t="$2" _tgt="$3" _delta
  tid_nice "$_p" "$_t" || return 1
  _delta=$((_tgt - _TID_NICE))
  [ "$_delta" -eq 0 ] && return 0
  renice -n "$_delta" -p "$_t" >/dev/null 2>&1
}

read_ionice_pair() {
  local _out="" _cls=2 _lvl=4 _num=""
  if command -v ionice >/dev/null 2>&1; then
    _out=$(ionice -p "$1" 2>/dev/null)
  fi
  case "$_out" in
    Realtime*)
      _cls=1
      _num=${_out##*prio }
      case "$_num" in [0-7]*) _lvl="${_num%%[!0-9]*}";; *) _lvl=4;; esac
      ;;
    Best-effort*)
      _cls=2
      _num=${_out##*prio }
      case "$_num" in [0-7]*) _lvl="${_num%%[!0-9]*}";; *) _lvl=4;; esac
      ;;
    Idle*|idle*|None*|none*)
      _cls=3; _lvl=7
      ;;
  esac
  echo "$_cls $_lvl"
}

read_tid_cgroup() {
  local _pid="$1" _tid="$2" _ctrl="$3" _cf _val=""
  _cf="$PROC_R/proc/$_pid/task/$_tid/cgroup"
  [ -r "$_cf" ] || _cf="$PROC_R/proc/$_pid/cgroup"
  if [ -r "$_cf" ]; then
    _val=$(awk -F: -v c="$_ctrl" '$2 == c { print $3; exit }' "$_cf" 2>/dev/null)
  fi
  [ -n "$_val" ] || _val="/foreground"
  echo "$_val"
}

cg_tasks_path() {
  local _base="$1" _sub="${2%/}"
  echo "${_base}${_sub}/tasks"
}

write_task() {
  [ -w "$1" ] || return 0
  if [ -n "$FS_ROOT" ]; then
    echo "$2" >> "$1" 2>/dev/null
  else
    echo "$2" > "$1" 2>/dev/null
  fi
}

pick_pkg() {
  if [ -n "$PKG" ]; then echo "$PKG"; return; fi
  tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null
}

snapshot_io() {
  [ -s "$IO_STOCK" ] && return 0
  {
    for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
      [ ! -d "$dev/queue" ] && continue
      [ -f "$dev/queue/nomerges" ] && echo "$dev/queue/nomerges $(cat "$dev/queue/nomerges" 2>/dev/null)"
      [ -f "$dev/queue/add_random" ] && echo "$dev/queue/add_random $(cat "$dev/queue/add_random" 2>/dev/null)"
    done
  } | atomic_write "$IO_STOCK" 0600
}

optimize_io() {
  local prof
  prof=$(effective_profile)
  snapshot_io
  local target_nomerges="1"
  [ "$prof" != "game" ] && target_nomerges="0"
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ ! -d "$dev/queue" ] && continue
    [ -w "$dev/queue/add_random" ] && echo 0 > "$dev/queue/add_random" 2>/dev/null
    [ -w "$dev/queue/nomerges" ] && echo "$target_nomerges" > "$dev/queue/nomerges" 2>/dev/null
  done
  for u in "$R"/sys/devices/platform/*.ufs; do
    [ -d "$u" ] || continue
    echo 1 > "$u/clkgate_enable" 2>/dev/null
  done
  log "perf: io aplicado (profile=$prof nomerges=$target_nomerges)"
}

restore_io() {
  if [ -s "$IO_STOCK" ]; then
    while read -r p v; do
      case "$p" in ""|\#*) continue;; esac
      [ -w "$p" ] && echo "$v" > "$p" 2>/dev/null
    done < "$IO_STOCK"
  else
    for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
      [ ! -d "$dev/queue" ] && continue
      [ -w "$dev/queue/nomerges" ] && echo 0 > "$dev/queue/nomerges" 2>/dev/null
      [ -w "$dev/queue/add_random" ] && echo 0 > "$dev/queue/add_random" 2>/dev/null
    done
  fi
}

apply_threads_once() {
  local _pkg="$1" _prof="$2" _found_engine=0
  local _main_nice="-4" _game_nice="-6" _render_nice="-4" _oom="-500"
  case "$_prof" in
    battery)
      # Em PowerSaving, preserva hierarquia stock sem elevar nice/stune
      return 0
      ;;
    balanced)
      _main_nice="-2"
      _game_nice="-4"
      _render_nice="-2"
      _oom="-300"
      ;;
    game|*)
      _main_nice="-4"
      _game_nice="-6"
      _render_nice="-4"
      _oom="-500"
      ;;
  esac

  local _pids pid _tdir _tcomm _tid _ni _cg _oom_cur _io_pair _io_cls _io_lvl _new_rec=""
  _pids=$(pidof "$_pkg" 2>/dev/null)
  [ -z "$_pids" ] && return 1
  if [ -s "$PROC_STATE" ]; then
    _new_rec=$(cat "$PROC_STATE" 2>/dev/null)
  fi
  for pid in $_pids; do
    if ! printf '%s\n' "$_new_rec" | grep -q "^p $pid "; then
      _oom_cur=$(cat "$PROC_R/proc/$pid/oom_score_adj" 2>/dev/null | tr -d '[:space:]')
      case "$_oom_cur" in ''|*[!0-9-]*) _oom_cur=0;; esac
      _io_pair=$(read_ionice_pair "$pid")
      _io_cls=${_io_pair%% *}; _io_lvl=${_io_pair##* }
      _new_rec="${_new_rec}${_new_rec:+
}p $pid $_io_cls $_io_lvl $_oom_cur"
    fi
    if ! printf '%s\n' "$_new_rec" | grep -q "^t $pid $pid "; then
      _ni=$(read_tid_nice "$pid" "$pid"); [ -n "$_ni" ] || _ni=0
      _cg=$(read_tid_cgroup "$pid" "$pid" cpuset)
      _new_rec="${_new_rec}${_new_rec:+
}t $pid $pid $_ni $_cg"
    fi
    set_exact_nice "$pid" "$pid" "$_main_nice"
    if command -v ionice >/dev/null 2>&1; then
      ionice -c 2 -n 2 -p "$pid" >/dev/null 2>&1
    fi
    # Escreve APENAS o TID principal em /tasks (NUNCA em cgroup.procs!)
    write_task "$DEV_R/dev/cpuset/top-app/tasks" "$pid"
    echo "$_oom" > "$PROC_R/proc/$pid/oom_score_adj" 2>/dev/null

    for _tdir in "$PROC_R/proc/$pid/task/"*; do
      [ -r "$_tdir/comm" ] || continue
      IFS= read -r _tcomm < "$_tdir/comm" 2>/dev/null
      _tid="${_tdir##*/}"
      case "$_tcomm" in
        GameThread*|UnityMain*)
          _found_engine=1
          if ! printf '%s\n' "$_new_rec" | grep -q "^t $pid $_tid "; then
            _ni=$(read_tid_nice "$pid" "$_tid"); [ -n "$_ni" ] || _ni=0
            _cg=$(read_tid_cgroup "$pid" "$_tid" cpuset)
            _new_rec="${_new_rec}${_new_rec:+
}t $pid $_tid $_ni $_cg"
          fi
          set_exact_nice "$pid" "$_tid" "$_game_nice"
          write_task "$DEV_R/dev/cpuset/top-app/tasks" "$_tid"
          ;;
        RenderThread*|RHIThread*|UnityGfx*|GLThread*|Vulkan*)
          _found_engine=1
          if ! printf '%s\n' "$_new_rec" | grep -q "^t $pid $_tid "; then
            _ni=$(read_tid_nice "$pid" "$_tid"); [ -n "$_ni" ] || _ni=0
            _cg=$(read_tid_cgroup "$pid" "$_tid" cpuset)
            _new_rec="${_new_rec}${_new_rec:+
}t $pid $_tid $_ni $_cg"
          fi
          set_exact_nice "$pid" "$_tid" "$_render_nice"
          write_task "$DEV_R/dev/cpuset/top-app/tasks" "$_tid"
          ;;
      esac
    done
  done
  printf '%s\n' "$_new_rec" | awk 'NF' | atomic_write "$PROC_STATE" 0600
  [ "$_found_engine" -eq 1 ] && return 0 || return 2
}

ensure_audio_cgroup() {
  local _cpus _mems
  if [ -d "$DEV_R/dev/cpuset" ]; then
    mkdir -p "$DEV_R/dev/cpuset/fusao_audio" 2>/dev/null
    _cpus=$(cat "$DEV_R/dev/cpuset/top-app/cpus" 2>/dev/null | tr -d '[:space:]')
    [ -n "$_cpus" ] || _cpus="0-7"
    _mems=$(cat "$DEV_R/dev/cpuset/top-app/mems" 2>/dev/null | tr -d '[:space:]')
    [ -n "$_mems" ] || _mems="0"
    [ -w "$DEV_R/dev/cpuset/fusao_audio/cpus" ] && echo "$_cpus" > "$DEV_R/dev/cpuset/fusao_audio/cpus" 2>/dev/null
    [ -w "$DEV_R/dev/cpuset/fusao_audio/mems" ] && echo "$_mems" > "$DEV_R/dev/cpuset/fusao_audio/mems" 2>/dev/null
  fi
  if [ -d "$DEV_R/dev/cpuctl" ]; then
    mkdir -p "$DEV_R/dev/cpuctl/fusao_audio" 2>/dev/null
    [ -w "$DEV_R/dev/cpuctl/fusao_audio/cpu.shares" ] && echo "2048" > "$DEV_R/dev/cpuctl/fusao_audio/cpu.shares" 2>/dev/null
    [ -w "$DEV_R/dev/cpuctl/fusao_audio/cpu.uclamp.min" ] && echo "15" > "$DEV_R/dev/cpuctl/fusao_audio/cpu.uclamp.min" 2>/dev/null
    [ -w "$DEV_R/dev/cpuctl/fusao_audio/cpu.uclamp.latency_sensitive" ] && echo "1" > "$DEV_R/dev/cpuctl/fusao_audio/cpu.uclamp.latency_sensitive" 2>/dev/null
  fi
}

list_audio_daemons() {
  local _d _p
  for _d in audioserver android.hardware.audio.service android.hardware.audio.service-aidl secaudiohalaidl mediaserver; do
    for _p in $(pidof "$_d" 2>/dev/null); do
      echo "$_p"
    done
  done
}

list_active_media_pkgs() {
  local _game_pkg="$1" _da _ms _pkgs="" _apids="" _apid _cmd
  if command -v dumpsys >/dev/null 2>&1; then
    _da=$(dumpsys audio 2>/dev/null)
    if [ -n "$_da" ]; then
      # 1) PIDs diretos de players ativos (AudioPlaybackConfiguration ... u/pid:<uid>/<pid> ... state:started)
      _apids=$(printf '%s\n' "$_da" | awk '
        /AudioPlaybackConfiguration .*state:started/ {
          if ($0 ~ /USAGE_ASSISTANCE_SONIFICATION/ || $0 ~ /USAGE_NOTIFICATION/) next
          for (i=1; i<=NF; i++) {
            if ($i ~ /^u\/pid:[0-9]+\/[0-9]+$/) {
              p=$i; sub(/^.*\//, "", p)
              if (p ~ /^[0-9]+$/ && p > 0) print p
            }
          }
        }
      ')
      for _apid in $_apids; do
        [ -r "$PROC_R/proc/$_apid/cmdline" ] || continue
        _cmd=$(head -c 256 "$PROC_R/proc/$_apid/cmdline" 2>/dev/null | tr '\0' ' ' | awk '{print $1}')
        _cmd=${_cmd%%:*}
        case "$_cmd" in
          ""|"$_game_pkg"|android|com.android.systemui|com.samsung.android.honeyboard|system_server) ;;
          *.*) _pkgs="$_pkgs $_cmd";;
        esac
      done
      # 2) Fallback por mapeamento piid -> package nos eventos recentes de audio
      _pkgs="$_pkgs $(printf '%s\n' "$_da" | awk -v g="$_game_pkg" '
        /new player piid:/ {
          piid=""; pkg=""
          for (i=1; i<=NF; i++) {
            if ($i ~ /^piid:[0-9]+$/) piid=$i
            if ($i ~ /^package:/) { pkg=$i; sub(/^package:/, "", pkg) }
          }
          if (piid != "" && pkg != "") map[piid]=pkg
        }
        /AudioPlaybackConfiguration .*state:started/ {
          if ($0 ~ /USAGE_ASSISTANCE_SONIFICATION/ || $0 ~ /USAGE_NOTIFICATION/) next
          piid=""
          for (i=1; i<=NF; i++) if ($i ~ /^piid:[0-9]+$/) piid=$i
          p=map[piid]
          if (p != "" && p != g && p !~ /^(android|com\.android\.systemui|com\.samsung\.android\.honeyboard)$/) print p
        }
      ')"
    fi
    _ms=$(dumpsys media_session 2>/dev/null)
    if [ -n "$_ms" ]; then
      _pkgs="$_pkgs $(printf '%s\n' "$_ms" | awk -v g="$_game_pkg" '
        /package=/ { p=$0; sub(/.*package=/, "", p); sub(/[[:space:]].*/, "", p); cur=p }
        /state=PlaybackState \{state=PLAYING/ {
          if (cur != "" && cur != g && cur !~ /^(android|com\.android\.systemui|com\.samsung\.android\.honeyboard)$/) print cur
        }
      ')"
    fi
  fi
  if [ -n "$PERF_AUDIO_MEDIA_PKGS" ]; then
    _pkgs="$_pkgs $PERF_AUDIO_MEDIA_PKGS"
  fi
  printf '%s\n' $_pkgs | awk 'NF && !seen[$0]++'
}

# audio_prio_thread — a thread lida por último pelo tid_nice já é do caminho de áudio: prioridade de áudio
# (nice <= -10: THREAD_PRIORITY_AUDIO -16 / URGENT_AUDIO -19) ou tempo real (SCHED_FIFO 1 / SCHED_RR 2).
audio_prio_thread() {
  [ "$_TID_NICE" -le -10 ] 2>/dev/null && return 0
  [ "$_TID_POL" = 1 ] || [ "$_TID_POL" = 2 ]
}

# protect_pid_audio <pid> <is_media 0|1> <kind> <pkg>
# Duas fases por processo, para o exit poder interromper o job a qualquer momento sem perder o original:
#   1) REGISTRA (nice + cgroups de cada thread que vai ser tocada) e grava o perf.audio.state;
#   2) só então aplica.
# Daemon de áudio (audioserver/HAL): todas as threads vão para o fusao_audio e as não-binder sobem a nice -8,
# como antes. App de mídia (o player em segundo plano): só as threads que JÁ são de áudio (audio_prio_thread)
# entram no grupo, e nenhuma é renice. Antes o app inteiro subia a -8 dentro de um grupo com cpu.shares 2048,
# uclamp.min e latency_sensitive: as threads que esse renice de fato mexia eram as que NÃO são de áudio
# (interface, rede, GC, JIT — as de áudio já estão em -16/-19), e elas passavam à frente da SystemUI (thread
# principal em 0, RenderThread em -4) justo na rajada de troca de faixa. O processo segue protegido contra
# congelamento/morte (freezer, oom_score_adj, ionice, bucket active).
protect_pid_audio() {
  local _pid="$1" _is_media="${2:-0}" _kind="$3" _pkg="${4:--}"
  local _td _tid _tcomm _io_pair _io_cls _io_lvl _oom_cur _rest
  [ -d "$PROC_R/proc/$_pid" ] || return 1

  # fase 1: registro
  if rec_has "proc $_kind $_pid "; then
    _rest=$(rec_line "proc $_kind $_pid ")
    set -- $_rest
    _io_cls=${2:-2}
  else
    _io_pair=$(read_ionice_pair "$_pid")
    _io_cls=${_io_pair%% *}; _io_lvl=${_io_pair##* }
    _oom_cur=$(cat "$PROC_R/proc/$_pid/oom_score_adj" 2>/dev/null | tr -d '[:space:]')
    case "$_oom_cur" in ''|*[!0-9-]*) _oom_cur=0;; esac
    _rec_buf="${_rec_buf}${_rec_buf:+$NL}proc $_kind $_pid $_pkg $_io_cls $_io_lvl $_oom_cur"
  fi
  for _td in "$PROC_R/proc/$_pid/task/"*; do
    [ -d "$_td" ] || continue
    _tid="${_td##*/}"
    rec_has "tid $_pid $_tid " && continue
    tid_nice "$_pid" "$_tid" || { _TID_NICE=0; _TID_POL=0; }
    if [ "$_is_media" = "1" ] && ! audio_prio_thread; then
      continue
    fi
    tid_cgroups "$_pid" "$_tid"
    _rec_buf="${_rec_buf}${_rec_buf:+$NL}tid $_pid $_tid $_TID_NICE $_TID_CSET $_TID_CCTL"
  done
  printf '%s\n' "$_rec_buf" | atomic_write "$AUDIO_STATE" 0600

  # fase 2: aplicação (só o que está registrado)
  if command -v ionice >/dev/null 2>&1 && [ "${_io_cls:-2}" != "1" ]; then
    ionice -c 2 -n 0 -p "$_pid" >/dev/null 2>&1
  fi
  # Retira processo de freezer Samsung (ex.: SoundAlive/EQ ou player pausando em transição)
  [ -w "$DEV_R/dev/freezer/tasks" ] && echo "$_pid" > "$DEV_R/dev/freezer/tasks" 2>/dev/null
  if [ "$_is_media" = "1" ]; then
    echo "0" > "$PROC_R/proc/$_pid/oom_score_adj" 2>/dev/null
  fi
  for _td in "$PROC_R/proc/$_pid/task/"*; do
    [ -d "$_td" ] || continue
    _tid="${_td##*/}"
    rec_has "tid $_pid $_tid " || continue
    if [ "$_is_media" != "1" ]; then
      _tcomm=""
      [ -r "$_td/comm" ] && IFS= read -r _tcomm < "$_td/comm" 2>/dev/null
      case "$_tcomm" in
        binder:*|HwBinder:*|vndbinder:*) ;;
        *)
          if tid_nice "$_pid" "$_tid" && [ "$_TID_NICE" -gt -8 ] 2>/dev/null; then
            renice -n "$((-8 - _TID_NICE))" -p "$_tid" >/dev/null 2>&1
          fi
          ;;
      esac
    fi
    write_task "$DEV_R/dev/cpuset/fusao_audio/tasks" "$_tid"
    write_task "$DEV_R/dev/cpuctl/fusao_audio/tasks" "$_tid"
  done
  return 0
}

# audio_guard_ok — com AUDIO_GUARD_PKG (job em segundo plano) a proteção só segue enquanto o jogo dela continua
# em foco e o módulo ligado: um exit que chegou antes de o job publicar o PID não pode deixar proteção para trás.
audio_guard_ok() {
  local _g=""
  [ -z "${AUDIO_GUARD_PKG:-}" ] && return 0
  [ -f "$M54_DIR/disable" ] && return 1
  [ -r "$GAMEFILE" ] && IFS= read -r _g < "$GAMEFILE" 2>/dev/null
  _g=${_g%%[[:space:]]*}
  [ "$_g" = "$AUDIO_GUARD_PKG" ]
}

# protect_audio <jogo> — rc 1 se a guarda interrompeu (o chamador desfaz com restore_audio).
protect_audio() {
  local _game_pkg="$1" _gpids _dpids _mpkgs _mp _mpids _p _dcnt=0 _mcnt=0 _rec_buf="" _stop=0
  [ -s "$AUDIO_STATE" ] && _rec_buf=$(cat "$AUDIO_STATE" 2>/dev/null)
  _gpids=" $(pidof "$_game_pkg" 2>/dev/null) "
  ensure_audio_cgroup
  _dpids="${PERF_AUDIO_PIDS:-$(list_audio_daemons)}"
  _mpkgs=$(list_active_media_pkgs "$_game_pkg")
  [ -n "$_mpkgs" ] && _mpkgs="$_mpkgs com.sec.android.app.soundalive"
  _mpkgs=$(printf '%s\n' $_mpkgs | awk 'NF && !seen[$0]++')

  for _p in $_dpids; do
    audio_guard_ok || { _stop=1; break; }
    case "$_gpids" in *" $_p "*) continue;; esac
    if protect_pid_audio "$_p" 0 daemon "-"; then
      _dcnt=$((_dcnt + 1))
    fi
  done

  for _mp in $_mpkgs; do
    [ "$_stop" = 1 ] && break
    [ "$_mp" = "$_game_pkg" ] && continue
    if [ -z "$FS_ROOT" ] && command -v am >/dev/null 2>&1; then
      am set-standby-bucket "$_mp" active >/dev/null 2>&1
    fi
    _mpids=$(pidof "$_mp" 2>/dev/null)
    for _p in $_mpids; do
      audio_guard_ok || { _stop=1; break; }
      case "$_gpids" in *" $_p "*) continue;; esac
      if protect_pid_audio "$_p" 1 media "$_mp"; then
        _mcnt=$((_mcnt + 1))
      fi
    done
  done

  if [ "$_stop" = 1 ] || ! audio_guard_ok; then
    log "perf: audio interrompido (jogo saiu de foco; daemons=$_dcnt media=$_mcnt)"
    return 1
  fi
  log "perf: audio protegido (daemons=$_dcnt media=$_mcnt)"
  return 0
}

# audio_job_stop — encerra o job de proteção em voo (identidade conferida pelo registro de PID) antes de um
# restore. O job grava o estado ANTES de tocar em cada processo, então o restore desfaz tudo o que ele fez.
audio_job_stop() {
  local _p _i=0
  if pid_record_alive "$AUDIO_PIDF" "audio-bg"; then
    _p=$(pid_record_pid "$AUDIO_PIDF")
    kill -TERM "$_p" 2>/dev/null
    while [ "$_i" -lt 30 ] && kill -0 "$_p" 2>/dev/null; do
      sleep 0.1 2>/dev/null || sleep 1
      _i=$((_i + 1))
    done
    kill -0 "$_p" 2>/dev/null && kill -KILL "$_p" 2>/dev/null
  fi
  rm -f "$AUDIO_PIDF" 2>/dev/null
  return 0
}

# audio_job_run <jogo> — corpo do modo audio-bg: um job por vez, guardado pelo jogo em foco; se a guarda
# interromper, desfaz o que já tinha aplicado.
audio_job_run() {
  local _pkg="$1"
  [ -n "$_pkg" ] || return 0
  pid_record_alive "$AUDIO_PIDF" "audio-bg" && return 0
  rm -f "$AUDIO_PIDF" 2>/dev/null
  pid_record_write "$AUDIO_PIDF" "audio-bg" || return 1
  AUDIO_GUARD_PKG="$_pkg"
  protect_audio "$_pkg" || restore_audio
  AUDIO_GUARD_PKG=""
  [ "$(pid_record_pid "$AUDIO_PIDF")" = "$$" ] && rm -f "$AUDIO_PIDF" 2>/dev/null
  return 0
}

restore_audio() {
  local _tag _f1 _f2 _f3 _f4 _f5 _f6 _cset_p _cctl_p _tid _moved=0
  if [ -s "$AUDIO_STATE" ]; then
    while read -r _tag _f1 _f2 _f3 _f4 _f5 _f6; do
      case "$_tag" in
        proc)
          # proc <kind> <pid> <pkg> <io_cls> <io_lvl> <oom>
          if [ -d "$PROC_R/proc/$_f2" ]; then
            if command -v ionice >/dev/null 2>&1; then
              case "$_f4:$_f5" in
                [1-3]:[0-7]) ionice -c "$_f4" -n "$_f5" -p "$_f2" >/dev/null 2>&1;;
                *) ionice -c 2 -n 4 -p "$_f2" >/dev/null 2>&1;;
              esac
            fi
            if [ "$_f1" = "media" ]; then
              case "$_f6" in ''|*[!0-9-]*) ;; *) echo "$_f6" > "$PROC_R/proc/$_f2/oom_score_adj" 2>/dev/null;; esac
            fi
          fi
          ;;
        tid)
          # tid <pid> <tid> <orig_nice> <orig_cpuset> <orig_cpuctl>
          if [ -d "$PROC_R/proc/$_f1/task/$_f2" ] || [ -d "$PROC_R/proc/$_f1" ]; then
            case "$_f3" in ''|*[!0-9-]*) ;; *) set_exact_nice "$_f1" "$_f2" "$_f3";; esac
            _cset_p=$(cg_tasks_path "$DEV_R/dev/cpuset" "${_f4:-/foreground}")
            [ -w "$_cset_p" ] || _cset_p="$DEV_R/dev/cpuset/foreground/tasks"
            write_task "$_cset_p" "$_f2"
            _cctl_p=$(cg_tasks_path "$DEV_R/dev/cpuctl" "${_f5:-/foreground}")
            [ -w "$_cctl_p" ] || _cctl_p="$DEV_R/dev/cpuctl/foreground/tasks"
            write_task "$_cctl_p" "$_f2"
            _moved=$((_moved + 1))
          fi
          ;;
      esac
    done < "$AUDIO_STATE"
    rm -f "$AUDIO_STATE"
  fi
  for _tid in $(cat "$DEV_R/dev/cpuset/fusao_audio/tasks" 2>/dev/null); do
    write_task "$DEV_R/dev/cpuset/foreground/tasks" "$_tid"
  done
  for _tid in $(cat "$DEV_R/dev/cpuctl/fusao_audio/tasks" 2>/dev/null); do
    write_task "$DEV_R/dev/cpuctl/foreground/tasks" "$_tid"
  done
  rmdir "$DEV_R/dev/cpuset/fusao_audio" "$DEV_R/dev/cpuctl/fusao_audio" 2>/dev/null
  [ "$_moved" -gt 0 ] && log "perf: audio restaurado (tids=$_moved)"
}

optimize_proc() {
  local _pkg="$1" _prof _rc
  [ -z "$_pkg" ] && return 1
  _prof=$(effective_profile)
  apply_threads_once "$_pkg" "$_prof"
  _rc=$?
  # Proteção de áudio FORA do caminho do game-enter: ela varre ~90 threads dos daemons de áudio e segurava o
  # watch por 12-15 s antes do tune e do FAS (aparelho 2026-11-13: enter -> hooks em 16-21 s). Vira um job
  # próprio (perf.sh audio-bg, um por vez, PID registrado); o exit o encerra antes de restaurar.
  # PERF_AUDIO_SYNC=1 roda na hora (teste offline determinístico).
  if [ -n "${PERF_AUDIO_SYNC:-}" ]; then
    protect_audio "$_pkg"
  else
    M54_DIR="$M54_DIR" FS_ROOT="${FS_ROOT:-}" nohup sh "$0" audio-bg "$_pkg" >/dev/null 2>&1 &
  fi
  # Se a GameThread/UnityMain ainda não nasceu no t=0s do launch (caso típico de UE5/NTE),
  # agenda uma segunda passagem leve após 3s em background (só as threads do jogo: o áudio não depende dela).
  if [ "$_rc" -eq 2 ] && [ -z "$PERF_NO_DELAY" ] && [ "$_prof" != "battery" ]; then
    (
      sleep 3
      [ "$(tr -d '[:space:]' < "$GAMEFILE" 2>/dev/null)" = "$_pkg" ] && apply_threads_once "$_pkg" "$_prof"
    ) >/dev/null 2>&1 &
  fi
  log "perf: proc $_pkg aplicado (profile=$_prof)"
}

case "$MODE" in
  enter)
    T=$(pick_pkg)
    [ -z "$T" ] && exit 0
    optimize_io
    optimize_proc "$T"
    ;;
  io-only) optimize_io;;
  audio)
    protect_audio "$(pick_pkg)"
    ;;
  audio-bg)
    audio_job_run "$(pick_pkg)"
    ;;
  exit)
    restore_io
    audio_job_stop
    restore_audio
    if [ -s "$PROC_STATE" ]; then
      while read -r _ptag _pf1 _pf2 _pf3 _pf4; do
        case "$_ptag" in
          p)
            if [ -d "$PROC_R/proc/$_pf1" ]; then
              command -v ionice >/dev/null 2>&1 && case "$_pf2:$_pf3" in
                [1-3]:[0-7]) ionice -c "$_pf2" -n "$_pf3" -p "$_pf1" >/dev/null 2>&1;;
              esac
              case "$_pf4" in ''|*[!0-9-]*) ;; *) echo "$_pf4" > "$PROC_R/proc/$_pf1/oom_score_adj" 2>/dev/null;; esac
            fi
            ;;
          t)
            if [ -d "$PROC_R/proc/$_pf1/task/$_pf2" ] || [ -d "$PROC_R/proc/$_pf1" ]; then
              case "$_pf3" in ''|*[!0-9-]*) ;; *) set_exact_nice "$_pf1" "$_pf2" "$_pf3";; esac
              _pcg=$(cg_tasks_path "$DEV_R/dev/cpuset" "${_pf4:-/top-app}")
              write_task "$_pcg" "$_pf2"
            fi
            ;;
        esac
      done < "$PROC_STATE"
      rm -f "$PROC_STATE"
    elif [ -n "$PKG" ]; then
      for _p in $(pidof "$PKG" 2>/dev/null); do
        echo "0" > "$PROC_R/proc/$_p/oom_score_adj" 2>/dev/null
        set_exact_nice "$_p" "$_p" 0
      done
    fi
    log "perf: exit (io restaurado)"
    ;;
  *) echo "uso: perf.sh [enter|io-only|audio|audio-bg|exit] [pkg]" >&2; exit 1;;
esac
