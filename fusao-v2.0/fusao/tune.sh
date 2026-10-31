#!/system/bin/sh
# fusao-tune.sh — Motor unificado de perfis de hardware (CPU/GPU/MIF/INT/DISP/VM/IO/TCP)
# com restauração limpa entre transições e verificação real por readback.
# Alvo: Samsung Galaxy M54 (SM-M546B), Exynos 1380 (s5e8835), kernel 5.15.189.
# FS_ROOT prefixa /proc e /sys para testes offline determinísticos.
#
# 2.9.7: tabelas de degraus LIDAS do próprio aparelho (scaling_available_frequencies / gpu_freq_table),
# todo alvo de min/max passa por snap p/ um degrau que existe, w() relê e não mente rc 0 quando o
# driver clampa em silêncio, e verify_profile confere exatamente o que o perfil pede depois do snap.
# 2.9.8: piso/teto de CPU segurado pelo QoS da Samsung (boost de boot, hint de launch, térmico) logo
# após um apply vira "pending" (não "partial"), a descida para no primeiro degrau que não pega, e
# reverificações só de leitura dão o veredito final fora da janela (EXT_WINDOW_S).
# 2.9.9: auto/verify usam o perfil EM VIGOR (lib.sh effective_profile: battery + jogo da gamelist = game,
# balanced fica balanced); piso da GPU também é trava externa.
# 2.10.0: read_ahead saiu dos perfis (nó errado: sda; no dm certo subir piora) e o battery não capa a GPU.
# 2.11.0: com o FAS (fas-rs) dono da CPU em jogo, o verify não confere piso/teto de CPU (lib.sh fas_owns_cpu).
# 2.11.3: game sem piso de GPU (o Hyper da Samsung reescreve o gpu_min_clock em toda troca de app).
# 2.12.0: game sem teto no P4 (2400000 = cpuinfo_max): o 1824000 vinha do falso "teto rígido" (nodes-m54.md §10).
DIR=$(dirname "$0")
. "$DIR/lib.sh"

R="${FS_ROOT:-}"
STOCK="$M54_DIR/tune.stock"
STATE_F="$M54_DIR/profile.state"
MODE="${1:-auto}"
GAME_ACTIVE="$M54_DIR/game-active.txt"

# Nós de CPU / GPU / Devfreq do Exynos 1380 (validados em nodes-m54.md)
P0_MIN="$R/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
P0_MAX="$R/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
P4_MIN="$R/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
P4_MAX="$R/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
GPU_MIN="$R/sys/kernel/gpu/gpu_min_clock"
GPU_MAX="$R/sys/kernel/gpu/gpu_max_clock"
MIF_MIN="$R/sys/class/devfreq/17000010.devfreq_mif/min_freq"
MIF_MAX="$R/sys/class/devfreq/17000010.devfreq_mif/max_freq"
INT_MIN="$R/sys/class/devfreq/17000020.devfreq_int/min_freq"
DISP_MIN="$R/sys/class/devfreq/17000040.devfreq_disp/min_freq"

# Tabelas de degraus e limites físicos (a verdade é o aparelho; ver load_domains).
P0_AVAIL="$R/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
P0_CPUINFO_MIN="$R/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"
P0_CPUINFO_MAX="$R/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq"
P4_AVAIL="$R/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies"
P4_CPUINFO_MIN="$R/sys/devices/system/cpu/cpufreq/policy4/cpuinfo_min_freq"
P4_CPUINFO_MAX="$R/sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq"
GPU_TABLE="$R/sys/kernel/gpu/gpu_freq_table"

# FALLBACK, só se o nó não existir/ilegível: tabelas reais do M54, lidas ao vivo em 2026-10-18.
# As tabelas antigas (1512000 1320000 1104000 999000 889000 400000...) eram de OUTRO SoC e não
# existem no Exynos 1380. O driver clampa em silêncio (400000 em policy0/scaling_min_freq fica 533000).
P0_TABLE_FB="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2002000"
P4_TABLE_FB="533000 672000 768000 864000 960000 1056000 1152000 1248000 1344000 1440000 1536000 1632000 1728000 1824000 1920000 2016000 2112000 2208000 2304000 2400000"
GPU_TABLE_FB="221000 351000 455000 552000 650000 754000 845000 949000"

hw_nodes() {
  for _n in "$P0_MIN" "$P0_MAX" "$P4_MIN" "$P4_MAX" "$GPU_MIN" "$GPU_MAX" "$MIF_MIN" "$MIF_MAX" "$INT_MIN" "$DISP_MIN"; do
    [ -f "$_n" ] && echo "$_n"
  done
}

vm_nodes() {
  echo "$R/proc/sys/vm/swappiness"
  echo "$R/proc/sys/vm/vfs_cache_pressure"
  echo "$R/proc/sys/vm/stat_interval"
  echo "$R/proc/sys/vm/page-cluster"
}

readahead_nodes() {
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/read_ahead_kb" ] && echo "$dev/queue/read_ahead_kb"
  done
}

io_queue_nodes() {
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && echo "$dev/queue/nomerges"
    [ -f "$dev/queue/add_random" ] && echo "$dev/queue/add_random"
  done
}

tcp_nodes() {
  echo "$R/proc/sys/net/ipv4/tcp_congestion_control"
  echo "$R/proc/sys/net/ipv4/tcp_fastopen"
}

snapshot_stock() {
  [ -s "$STOCK" ] && return 0
  {
    for n in $(hw_nodes) $(vm_nodes) $(readahead_nodes) $(io_queue_nodes) $(tcp_nodes); do
      [ -f "$n" ] && echo "$n $(cat "$n" 2>/dev/null | tr -d '\r')"
    done
  } | atomic_write "$STOCK" 0600
}

stock_of() {
  grep -F "$1 " "$STOCK" 2>/dev/null | cut -d' ' -f2- | tail -1
}

# rval <nó> — o conteúdo do nó sem nenhum espaço/CR/quebra (= cat|tr -d '[:space:]'), rc 1 se ilegível.
# Só builtins no caso comum (cada exec custa ~27 ms no aparelho e o verify lê ~25 nós); o pipeline antigo
# fica para espaço interno e para valor com "-" no início ou "\", em que o echo do mksh não é literal.
# /proc fica sempre no cat: o read do mksh lê byte a byte e o sysctl só responde na posição 0 (aparelho
# 2026-10-23: /proc/sys/vm/swappiness lido "6" em vez de "60"); os nós de /sys deram leitura idêntica.
rval() {
  local _l _v=""
  [ -r "$1" ] || return 1
  case "$1" in */proc/*) cat "$1" 2>/dev/null | tr -d '[:space:]'; return;; esac
  { while IFS= read -r _l || [ -n "$_l" ]; do _v="$_v$_l"; done; } 2>/dev/null < "$1"
  case "$_v" in
    *[[:space:]]*|-*|*\\*) printf '%s' "$_v" | tr -d '[:space:]';;
    *) echo "$_v";;
  esac
}

# Primitivo de escrita isolado: o teste offline o troca por um driver falso que clampa
# (TUNE_SOURCE_ONLY=1 carrega só as funções; produção nunca redefine).
sysfs_put() {
  { echo "$2" > "$1"; } 2>/dev/null
}

# w <nó> <valor> — escreve e RELÊ. rc 0 só quando o nó guardou o que foi pedido.
# O driver do Exynos 1380 clampa em silêncio (pedir 400000 em policy0/scaling_min_freq deixa 533000,
# sem errno, e o echo devolve 0): relido != pedido => WARN no log + rc 1, nunca rc 0 mentindo.
# Nó ausente = no-op rc 0 (teste non-root). Só garante 0644; NUNCA chmod 444 (nodes-m54.md §10).
w() {
  local want got rc
  [ -e "$1" ] || return 0
  chmod 0644 "$1" 2>/dev/null
  if [ ! -w "$1" ]; then
    log "WARN tune: sem escrita em $1 pedido=$2"
    return 1
  fi
  sysfs_put "$1" "$2"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    log "WARN tune: falha de escrita $1 pedido=$2 rc=$rc"
    return "$rc"
  fi
  [ -r "$1" ] || return 0
  want=$2
  case "$want" in *[[:space:]]*) want=$(printf '%s' "$want" | tr -d '[:space:]');; esac
  got=$(rval "$1")
  if [ "$got" != "$want" ]; then
    log "WARN tune: clamp $1 pedido=$want lido=$got"
    return 1
  fi
  return 0
}

# num_or <valor> <padrão> — o valor se for inteiro, senão o padrão.
num_or() {
  case "$1" in
    ''|*[!0-9]*) echo "$2";;
    *) echo "$1";;
  esac
}

# freq_table <nó> <fallback> — tabela de degraus ASCENDENTE, só números, sem repetidos, separada por
# espaço (aceita ordem qualquer/CRLF do nó). Nó ausente/ilegível/sem número => fallback e rc 1.
freq_table() {
  local raw="" f lista="" out=""
  [ -r "$1" ] && raw=$(cat "$1" 2>/dev/null | tr '\r\n\t' '   ')
  for f in $raw; do
    case "$f" in
      ''|*[!0-9]*) ;;
      *) lista="$lista$f
";;
    esac
  done
  [ -n "$lista" ] && out=$(printf '%s' "$lista" | sort -n -u | tr '\n' ' ')
  out=$(echo $out)
  if [ -n "$out" ]; then
    echo "$out"
    return 0
  fi
  echo "$2"
  return 1
}

# load_domains — lê UMA vez por execução a tabela e os limites físicos de P0, P4 e GPU.
# Limites: cpuinfo_min/max_freq (CPU) ou a ponta da própria tabela (GPU, ou nó ausente).
load_domains() {
  [ -n "$DOM_READY" ] && return 0
  local rc
  T_P0=$(freq_table "$P0_AVAIL" "$P0_TABLE_FB"); rc=$?
  [ "$rc" -ne 0 ] && [ -f "$P0_MIN" ] && log "WARN tune: p0 sem tabela legível em $P0_AVAIL, usando fallback do Exynos 1380"
  LO_P0=$(num_or "$(rval "$P0_CPUINFO_MIN")" "${T_P0%% *}")
  HI_P0=$(num_or "$(rval "$P0_CPUINFO_MAX")" "${T_P0##* }")
  T_P4=$(freq_table "$P4_AVAIL" "$P4_TABLE_FB"); rc=$?
  [ "$rc" -ne 0 ] && [ -f "$P4_MIN" ] && log "WARN tune: p4 sem tabela legível em $P4_AVAIL, usando fallback do Exynos 1380"
  LO_P4=$(num_or "$(rval "$P4_CPUINFO_MIN")" "${T_P4%% *}")
  HI_P4=$(num_or "$(rval "$P4_CPUINFO_MAX")" "${T_P4##* }")
  T_GPU=$(freq_table "$GPU_TABLE" "$GPU_TABLE_FB"); rc=$?
  [ "$rc" -ne 0 ] && [ -f "$GPU_MIN" ] && log "WARN tune: gpu sem tabela legível em $GPU_TABLE, usando fallback do Mali-G68"
  LO_GPU="${T_GPU%% *}"
  HI_GPU="${T_GPU##* }"
  DOM_READY=1
}

# dom_select <p0|p4|gpu> — expõe tabela e limites do domínio em D_TBL / D_LO / D_HI.
dom_select() {
  load_domains
  case "$1" in
    p0) D_TBL="$T_P0"; D_LO="$LO_P0"; D_HI="$HI_P0";;
    p4) D_TBL="$T_P4"; D_LO="$LO_P4"; D_HI="$HI_P4";;
    gpu) D_TBL="$T_GPU"; D_LO="$LO_GPU"; D_HI="$HI_GPU";;
    *) return 1;;
  esac
}

# snap_log <msg> — log do snap, mudo com SNAP_QUIET=1 (o verify só confere; quem pediu já logou).
snap_log() {
  [ -n "$SNAP_QUIET" ] || log "$*"
}

# snap_domain <min|max> <p0|p4|gpu> <alvo> — imprime o valor EFETIVO: um degrau que existe na tabela
# do domínio, dentro de [mínimo, máximo] físico.
#   min: menor degrau >= alvo (como o kernel arredonda o piso); max: maior degrau <= alvo.
# Alvo fora da faixa vira o limite físico com WARN explícito no log; alvo inválido idem.
# Fail-loud no log, nunca aborta o fluxo. Arredondar dentro da faixa loga "snap" (informativo).
snap_domain() {
  local kind="$1" dom="$2" tgt="$3" f first="" last="" best="" res="" num=1
  case "$tgt" in ''|*[!0-9]*) num=0;; esac
  if ! dom_select "$dom"; then
    snap_log "WARN tune: snap domínio desconhecido '$dom'"
    echo "$tgt"
    return 1
  fi
  for f in $D_TBL; do
    { [ "$f" -ge "$D_LO" ] && [ "$f" -le "$D_HI" ]; } || continue
    [ -z "$first" ] && first="$f"
    last="$f"
    [ "$num" = 1 ] || continue
    if [ "$kind" = "min" ]; then
      [ -z "$best" ] && [ "$f" -ge "$tgt" ] && best="$f"
    else
      [ "$f" -le "$tgt" ] && best="$f"
    fi
  done
  if [ -z "$first" ]; then
    # nenhum degrau da tabela cabe na faixa física: só resta o limite
    if [ "$kind" = "min" ]; then res="$D_LO"; else res="$D_HI"; fi
    snap_log "WARN tune: snap $dom/$kind sem degrau em [$D_LO,$D_HI] (pedido=$tgt), usando $res"
  elif [ "$num" = 0 ]; then
    if [ "$kind" = "min" ]; then res="$first"; else res="$last"; fi
    snap_log "WARN tune: snap $dom/$kind alvo inválido '$tgt', usando $res"
  elif [ "$tgt" -lt "$D_LO" ]; then
    res="$first"
    snap_log "WARN tune: snap $dom/$kind pedido=$tgt abaixo do mínimo físico $D_LO, usando $res"
  elif [ "$tgt" -gt "$D_HI" ]; then
    res="$last"
    snap_log "WARN tune: snap $dom/$kind pedido=$tgt acima do máximo físico $D_HI, usando $res"
  else
    if [ "$kind" = "min" ]; then res="${best:-$last}"; else res="${best:-$first}"; fi
    [ "$res" = "$tgt" ] || snap_log "tune: snap $dom/$kind pedido=$tgt -> $res"
  fi
  echo "$res"
}

# set_cpu_domain <p0|p4> <nó min> <nó max> <alvo min> <alvo max>
# Ordem segura (min <= max o tempo todo) e descida do piso por degraus REAIS da tabela
# (nodes-m54.md §10: policy0/min só desce via intermediário). rc 1 se algum nó final não guardou o pedido.
set_cpu_domain() {
  local dom="$1" n_min="$2" n_max="$3" t_min t_max c_min c_max _s rev="" rc=0
  [ -f "$n_min" ] || [ -f "$n_max" ] || return 0
  load_domains
  t_min=$(snap_domain min "$dom" "$4")
  t_max=$(snap_domain max "$dom" "$5")
  dom_select "$dom"
  c_min=$(num_or "$(rval "$n_min")" "$D_LO")
  c_max=$(num_or "$(rval "$n_max")" "$D_HI")
  # Se o novo max é maior que o atual, sobe o max primeiro p/ não rejeitar o min
  if [ "$t_max" -gt "$c_max" ]; then
    w "$n_max" "$t_max"
  fi
  if [ "$t_min" -lt "$c_min" ]; then
    # degraus da tabela do aparelho, do maior p/ o menor, estritamente entre o alvo e o piso atual.
    # Degrau que não pega = piso EXTERNO (boost de boot / hint de launch da Samsung segura o QoS de min):
    # os degraus abaixo falhariam igual, então para no primeiro (no aparelho, 2026-10-18 15:29: 15 WARN
    # seguidos com lido=2002000). O pedido final abaixo ainda é gravado e vale quando o piso externo solta.
    for _s in $D_TBL; do rev="$_s $rev"; done
    for _s in $rev; do
      if [ "$_s" -lt "$c_min" ] && [ "$_s" -gt "$t_min" ]; then
        w "$n_min" "$_s" || break
      fi
    done
  fi
  w "$n_min" "$t_min" || rc=1
  w "$n_max" "$t_max" || rc=1
  return "$rc"
}

# Exynos 1380 policy0 (Cortex-A55): tabela em scaling_available_frequencies (533000..2002000)
set_policy0() {
  set_cpu_domain p0 "$P0_MIN" "$P0_MAX" "$1" "$2"
}

# Exynos 1380 policy4 (Cortex-A78): tabela em scaling_available_frequencies (533000..2400000)
set_policy4() {
  set_cpu_domain p4 "$P4_MIN" "$P4_MAX" "$1" "$2"
}

# Mali-G68 MP5: tabela em /sys/kernel/gpu/gpu_freq_table (221000..949000)
set_gpu() {
  local t_min t_max c_max rc=0
  [ -f "$GPU_MIN" ] || [ -f "$GPU_MAX" ] || return 0
  load_domains
  t_min=$(snap_domain min gpu "$1")
  t_max=$(snap_domain max gpu "$2")
  c_max=$(num_or "$(rval "$GPU_MAX")" "$HI_GPU")
  if [ "$t_max" -gt "$c_max" ]; then
    w "$GPU_MAX" "$t_max" || rc=1
    w "$GPU_MIN" "$t_min" || rc=1
  else
    w "$GPU_MIN" "$t_min" || rc=1
    w "$GPU_MAX" "$t_max" || rc=1
  fi
  return "$rc"
}

restore_one() {
  local v
  v=$(stock_of "$1")
  [ -n "$v" ] && w "$1" "$v"
}

# read_ahead NÃO é perfil (2.10.0, kernlab launch-readahead, YouTube frio de disco, 6 blocos): o da sda
# (readahead_nodes = sd*/mmcblk*/nvme*) não chega na leitura de arquivo (sda 2048 = +0,2%, equivalente) —
# o /data é dm-60 e a raiz dm-6, e neles subir PIORA a abertura (512 +5,9%, 2048 +9,2%). Os perfis só
# devolvem ao stock o que versões antigas deixaram na sda (game 512 / balanced 256).
restore_readahead() {
  local n
  for n in $(readahead_nodes); do restore_one "$n"; done
}

restore_stock() {
  [ -s "$STOCK" ] || return 0
  # Primeiro desce policy0/policy4/gpu com segurança
  local s_p0min s_p0max s_p4min s_p4max s_gmin s_gmax
  s_p0min=$(stock_of "$P0_MIN"); [ -z "$s_p0min" ] && s_p0min=533000
  s_p0max=$(stock_of "$P0_MAX"); [ -z "$s_p0max" ] && s_p0max=2002000
  s_p4min=$(stock_of "$P4_MIN"); [ -z "$s_p4min" ] && s_p4min=533000
  s_p4max=$(stock_of "$P4_MAX"); [ -z "$s_p4max" ] && s_p4max=2400000
  s_gmin=$(stock_of "$GPU_MIN"); [ -z "$s_gmin" ] && s_gmin=221000
  s_gmax=$(stock_of "$GPU_MAX"); [ -z "$s_gmax" ] && s_gmax=949000
  set_policy0 "$s_p0min" "$s_p0max"
  set_policy4 "$s_p4min" "$s_p4max"
  set_gpu "$s_gmin" "$s_gmax"
  while read -r p v; do
    case "$p" in ""|\#*) continue;; esac
    case "$p" in
      "$P0_MIN"|"$P0_MAX"|"$P4_MIN"|"$P4_MAX"|"$GPU_MIN"|"$GPU_MAX") continue;;
    esac
    [ -f "$p" ] && w "$p" "$v"
  done <"$STOCK"
  log "tune: restore stock"
}

bbr_if_avail() {
  local avail="$R/proc/sys/net/ipv4/tcp_available_congestion_control"
  if grep -qw "bbr" "$avail" 2>/dev/null; then
    echo "bbr"
  else
    stock_of "$R/proc/sys/net/ipv4/tcp_congestion_control"
  fi
}

# profile_targets <game|balanced|battery> — SSOT dos alvos de cada perfil. CPU/GPU saem JÁ com snap
# (T_P0_MIN T_P0_MAX T_P4_MIN T_P4_MAX T_GMIN T_GMAX): o valor que existe na tabela do aparelho.
# apply_* escrevem estes valores e verify_profile confere EXATAMENTE estes, então N/N significa
# "o aparelho aceitou o que o perfil realmente pede" (e não "o arquivo falso engoliu qualquer número").
# Alvos já são degraus reais do Exynos 1380; o snap fica de rede de proteção (loga se algum desviar).
# Mantém a intenção de cada perfil. game-active.txt não vazio sobe pisos e MIF/INT (jogo em foco).
profile_targets() {
  local p="$1" act=0 r_p0min r_p0max r_p4min r_p4max r_gmin r_gmax
  [ -s "$GAME_ACTIVE" ] && act=1
  load_domains
  case "$p" in
    game)
      # P0 piso era 999000 (1104000 com jogo) e P4 889000 (999000): de outro SoC, agora degraus reais.
      # Teto do P4 = máximo físico 2400000: o 1824000 era o falso "teto rígido" (o game-tetos mediu 2400000
      # segurando) e capava os A78 em 24% justo no perfil que é dono da CPU em jogo fora do FAS (Roblox). Quem
      # baixa o teto com calor é o térmico da Samsung (QoS), e o energy_aware só sobe o clock com carga.
      r_p0min=1056000; r_p0max=2002000
      r_p4min=960000;  r_p4max=2400000
      # GPU sem piso (= padrão da Samsung): o Hyper/EPIC é dono do gpu_min_clock (recurso GPUMinFreq,
      # padrão 0# = 221000) e o reescreve a cada abertura/troca de app (AMS_ACT_START/AMS_RESUME...).
      # Medido 2026-10-19: 351000 escrito -> abrir um app -> 455000 -> 351000 -> 221000 em ~2 s, sem o
      # FUSAO agir; em jogo o verify ficava 4/5. Ganho do piso nunca medido; reescrever = briga com a ROM.
      r_gmin=221000;   r_gmax=949000
      T_MIF_MIN=845000; T_INT_MIN=266000; T_DISP_MIN=200000
      T_SWAP=60; T_VFS=50
      if [ "$act" = 1 ]; then
        r_p0min=1152000; r_p4min=1056000
        T_MIF_MIN=1014000; T_INT_MIN=333000
      fi
      ;;
    battery)
      # Piso do P0 = 533000: o mínimo físico e o stock. NÃO subir p/ 672000 (gasta mais em ocioso) e
      # 400000 não existe (o driver clampa p/ 533000 em silêncio e o verify nunca fechava 10/10).
      # Teto da GPU = máximo físico (sem cap): o 650000 nunca segurava — a Samsung sobrescreve o
      # gpu_max_clock no boot e o térmico ao liberar (2026-10-18: 650000 -> 949000 e ficou), e o verify
      # vivia em 9/10. Escrever o máximo também solta o cap deixado por outro perfil.
      r_p0min=533000; r_p0max=1728000
      r_p4min=533000; r_p4max=1440000
      r_gmin=221000;  r_gmax=949000
      T_MIF_MIN=421000; T_INT_MIN=50000; T_DISP_MIN=66000
      T_SWAP=160; T_VFS=150
      ;;
    *)
      # teto da GPU era 858000 (não existe na tabela); 845000 é o degrau logo abaixo do máximo 949000.
      # Teto do P4 1824000 aqui é escolha de eficiência do perfil casual (não limite de hardware): de 1824000 a
      # 2400000 o A78 ganha 32% de capacidade por 2,2x a potência dinâmica (598 -> 1323, kernlab energy_table).
      r_p0min=533000; r_p0max=2002000
      r_p4min=533000; r_p4max=1824000
      r_gmin=221000;  r_gmax=845000
      T_MIF_MIN=421000; T_INT_MIN=133000; T_DISP_MIN=133000
      T_SWAP=120; T_VFS=100
      if [ "$act" = 1 ]; then
        r_p0min=672000; r_p4min=672000
        T_MIF_MIN=676000
      fi
      ;;
  esac
  T_P0_MIN=$(snap_domain min p0 "$r_p0min")
  T_P0_MAX=$(snap_domain max p0 "$r_p0max")
  T_P4_MIN=$(snap_domain min p4 "$r_p4min")
  T_P4_MAX=$(snap_domain max p4 "$r_p4max")
  T_GMIN=$(snap_domain min gpu "$r_gmin")
  T_GMAX=$(snap_domain max gpu "$r_gmax")
}

# Janela após um apply em que piso/teto de CPU EXTERNO conta como "pending" e não "partial".
# No Exynos 1380 scaling_min/max_freq leem o efetivo do QoS: o boost de boot e os hints de launch da
# Samsung seguram o min acima do pedido (e o térmico, o max abaixo) por segundos a minutos, com o nosso
# pedido gravado — vale quando eles soltam (aparelho 2026-10-18: apply 15:29 8/10, mesmo nó 533000 em
# 15:47 sem reescrita). Passada a janela, divergência volta a ser falha real (driver que clampa de verdade).
EXT_WINDOW_S=180
# Reverificações SÓ DE LEITURA depois de um apply pending (s, cumulativo 30/90/185: a última cai fora da
# janela e dá o veredito final). Nunca reescreve: loop de reescrita contra a ROM é proibido (ver apply_battery).
REVERIFY_STEPS="${REVERIFY_STEPS:-30 60 95}"
# Teto de CPU lido ACIMA do pedido não é QoS (o QoS só abaixa teto): é o NOSSO pedido regravado por outro
# escritor. Aparelho 2026-10-20 19:53 (saída do Roblox com a tela apagando): P0/P4 max 2002000/2400000 com
# battery pedindo 1728000/1440000, e ficou assim 9+ min. Uma regravação, uma vez, REASSERT_DELAY_S depois do
# apply; nunca agenda outra (loop de reescrita contra a ROM continua proibido).
REASSERT_DELAY_S="${REASSERT_DELAY_S:-30}"

# now_s — segundos inteiros de uptime (= cut -d. -f1 /proc/uptime, 0 se ilegível), por builtins.
now_s() {
  local _l=""
  if [ -n "${TUNE_NOW:-}" ]; then echo "$TUNE_NOW"; return; fi
  [ -r /proc/uptime ] || { echo 0; return; }
  { IFS= read -r _l || :; } 2>/dev/null < /proc/uptime
  echo "${_l%%.*}"
}

# state_field <campo> — valor do campo no profile.state atual (vazio se não houver; a última linha vence).
# = grep -E "^campo=" | tail -n 1 | cut -d= -f2-, por builtins quando o campo é uma palavra simples.
state_field() {
  local _l _v=""
  case "$1" in ''|*[!A-Za-z0-9_]*) grep -E "^$1=" "$STATE_F" 2>/dev/null | tail -n 1 | cut -d= -f2-; return;; esac
  { while IFS= read -r _l || [ -n "$_l" ]; do case "$_l" in "$1="*) _v=${_l#*=};; esac; done; } 2>/dev/null < "$STATE_F"
  case "$_v" in -*|*\\*) printf '%s\n' "$_v";; *) echo "$_v";; esac
}

# Verifica por readback se os nós presentes no sistema refletem o perfil alvo
# (alvo = profile_targets, já com snap). APPLIED_AT (uptime do apply) marca a janela EXT_WINDOW_S;
# sem ele (verify do status, reverify) herda o applied_at do state se o perfil for o mesmo.
verify_profile() {
  local target="$1" ok_cnt=0 tot_cnt=0 mis="" ext="" applied_at in_win=0
  case "$target" in
    game|battery) ;;
    *) target="balanced";;
  esac
  # recalcula quieto: o apply já logou qualquer desvio do snap; o verify só confere
  SNAP_QUIET=1
  profile_targets "$target"
  SNAP_QUIET=""

  applied_at="${APPLIED_AT:-}"
  if [ -z "$applied_at" ] && [ "$(state_field profile)" = "$target" ]; then
    applied_at=$(state_field applied_at)
  fi
  case "$applied_at" in
    ''|*[!0-9]*) applied_at="";;
    *) [ $(( $(now_s) - applied_at )) -lt "$EXT_WINDOW_S" ] && in_win=1;;
  esac

  # dir: floor = piso de CPU ou da GPU (lido ACIMA do alvo é trava externa); ceil = só teto de CPU (lido
  # ABAIXO é QoS externo). Piso da GPU medido 2026-10-18: boot 16:50 lido 455000 com alvo 221000, 221000
  # depois sem reescrita (trava, como o da CPU). Teto da GPU NÃO entra: é sobrescrito (650000 virou 949000
  # e ficou). MIF/VM sem medida, não entram.
  check_eq() {
    local path="$1" expected="$2" label="$3" dir="${4:-}" cur
    [ -f "$path" ] || return 0
    tot_cnt=$((tot_cnt + 1))
    cur=$(rval "$path")
    if [ "$cur" = "$expected" ]; then
      ok_cnt=$((ok_cnt + 1))
      return 0
    fi
    if [ "$in_win" = 1 ] && [ -n "$dir" ]; then
      case "$cur" in ''|*[!0-9]*) ;; *)
        if { [ "$dir" = floor ] && [ "$cur" -gt "$expected" ]; } || { [ "$dir" = ceil ] && [ "$cur" -lt "$expected" ]; }; then
          ext="${ext}${ext:+,}${label}:${cur}!=${expected}"
          return 0
        fi;;
      esac
    fi
    mis="${mis}${mis:+,}${label}:${cur}!=${expected}"
  }

  check_ge() {
    local path="$1" expected="$2" label="$3" cur
    [ -f "$path" ] || return 0
    tot_cnt=$((tot_cnt + 1))
    cur=$(rval "$path")
    case "$cur" in ''|*[!0-9]*) mis="${mis}${mis:+,}${label}:?!=ge${expected}"; return 0;; esac
    if [ "$cur" -ge "$expected" ] 2>/dev/null; then
      ok_cnt=$((ok_cnt + 1))
    else
      mis="${mis}${mis:+,}${label}:${cur}<${expected}"
    fi
  }

  # FAS dono da CPU (lib.sh fas_owns_cpu): o fas-rs reescreve piso/teto por frame, então conferir a CPU
  # contra o perfil daria divergência falsa. O apply do enter ainda escreve o perfil (cobre o aquecimento
  # do fas-rs, ~10 s); daí em diante só ele escreve.
  local fas_cpu=0
  fas_owns_cpu && fas_cpu=1
  if [ "$target" = "battery" ]; then
    [ "$fas_cpu" = 1 ] || check_eq "$P0_MIN" "$T_P0_MIN" "p0_min" floor
    [ "$fas_cpu" = 1 ] || check_eq "$P4_MIN" "$T_P4_MIN" "p4_min" floor
    check_eq "$GPU_MIN" "$T_GMIN" "gpu_min" floor
    check_eq "$MIF_MIN" "$T_MIF_MIN" "mif_min"
  else
    [ "$fas_cpu" = 1 ] || check_ge "$P0_MIN" "$T_P0_MIN" "p0_min"
    [ "$fas_cpu" = 1 ] || check_ge "$P4_MIN" "$T_P4_MIN" "p4_min"
    check_ge "$GPU_MIN" "$T_GMIN" "gpu_min"
    check_ge "$MIF_MIN" "$T_MIF_MIN" "mif_min"
  fi
  [ "$fas_cpu" = 1 ] || check_eq "$P0_MAX" "$T_P0_MAX" "p0_max" ceil
  [ "$fas_cpu" = 1 ] || check_eq "$P4_MAX" "$T_P4_MAX" "p4_max" ceil
  check_eq "$GPU_MAX" "$T_GMAX" "gpu_max"
  check_eq "$R/proc/sys/vm/swappiness" "$T_SWAP" "swappiness"
  check_eq "$R/proc/sys/vm/vfs_cache_pressure" "$T_VFS" "vfs_pressure"

  # read_ahead saiu dos perfis (2.10.0): fica só no sysfs_summary como leitura informativa
  local _ra_node
  _ra_node=$(readahead_nodes | head -n 1)

  # tetos de CPU acima do pedido (rótulos p0_max/p4_max) p/ o reassert único
  VERIFY_OVER=""
  local _m _m_cur
  for _m in $(echo "$mis" | tr ',' ' '); do
    case "$_m" in
      p0_max:*|p4_max:*)
        _m_cur=${_m#*:}; _m_cur=${_m_cur%%!=*}
        case "$_m_cur" in ''|*[!0-9]*) ;; *)
          [ "$_m_cur" -gt "${_m##*!=}" ] && VERIFY_OVER="${VERIFY_OVER}${VERIFY_OVER:+ }${_m%%:*}";;
        esac;;
    esac
  done

  local state="applied"
  if [ "$tot_cnt" -eq 0 ]; then
    # Ambiente sem sysfs/procfs mapeado: valida se o perfil em vigor é o conferido
    if [ "$(effective_profile)" = "$target" ]; then
      state="applied"
    else
      state="failed"
    fi
  elif [ "$ok_cnt" -eq "$tot_cnt" ]; then
    state="applied"
  elif [ -z "$mis" ]; then
    # só piso/teto de CPU externo dentro da janela: pedido gravado, aguardando o QoS da Samsung soltar
    state="pending"
  elif [ "$ok_cnt" -gt 0 ]; then
    state="partial"
  else
    state="failed"
  fi

  local p0_s p4_s gpu_s mif_s vm_s io_s summary
  p0_s="$(rval "$P0_MIN")-$(rval "$P0_MAX")"
  p4_s="$(rval "$P4_MIN")-$(rval "$P4_MAX")"
  gpu_s="$(rval "$GPU_MIN")-$(rval "$GPU_MAX")"
  mif_s="$(rval "$MIF_MIN")"
  vm_s="$(rval "$R/proc/sys/vm/swappiness")/$(rval "$R/proc/sys/vm/vfs_cache_pressure")"
  io_s="$( [ -n "$_ra_node" ] && rval "$_ra_node" || echo "-" )k"
  summary="P0:${p0_s} P4:${p4_s} GPU:${gpu_s} MIF:${mif_s} VM:${vm_s} IO:${io_s}"
  [ "$fas_cpu" = 1 ] && summary="$summary CPU:FAS"

  {
    echo "profile=$target"
    echo "profile_state=$state"
    echo "profile_verify=${ok_cnt}/${tot_cnt}"
    echo "sysfs_summary=$summary"
    [ -n "$mis" ] && echo "profile_mismatch=$mis"
    [ -n "$ext" ] && echo "profile_external=$ext"
    [ -n "$applied_at" ] && echo "applied_at=$applied_at"
  } | atomic_write "$STATE_F" 0644

  VERIFY_STATE="$state"
  [ "$state" != "failed" ]
}

# notify_app <perfil> — avisa o app FUSAO do perfil em vigor (Smart Pixels liga sozinho no battery). O
# receptor exige DUMP (root/shell têm, app de terceiro não); -f 0x20 alcança o app parado à força.
notify_app() {
  command -v am >/dev/null 2>&1 || return 0
  am broadcast -a com.orkgabb.fusao.action.PROFILE -p com.orkgabb.fusao -f 0x20 --es profile "$1" >/dev/null 2>&1 &
}

# verify_after_apply <perfil> — o verify do fim de um apply_*. Se deu pending, agenda as reverificações
# só de leitura (TUNE_NO_DEFER=1 desliga: teste offline). Cada job confere o applied_at: um apply novo
# (outro perfil ou o mesmo de novo) torna o job antigo obsoleto e ele sai sem tocar no state.
verify_after_apply() {
  APPLIED_AT=$(now_s)
  verify_profile "$1"
  notify_app "$1"
  if [ "$VERIFY_STATE" = "pending" ]; then
    log "tune: $1 pending (piso/teto de CPU externo: $(state_field profile_external)); reverifica em +30/+90/+185 s"
    [ "${TUNE_NO_DEFER:-0}" = "1" ] || nohup sh "$DIR/tune.sh" reverify "$1" "$APPLIED_AT" >/dev/null 2>&1 &
  fi
  if [ -n "$VERIFY_OVER" ]; then
    log "tune: $1 teto de CPU acima do pedido ($VERIFY_OVER); regrava 1x em +${REASSERT_DELAY_S} s se seguir assim"
    [ "${TUNE_NO_DEFER:-0}" = "1" ] || nohup sh "$DIR/tune.sh" reassert "$1" "$APPLIED_AT" >/dev/null 2>&1 &
  fi
}

# reassert <perfil> <applied_at> — UMA regravação dos tetos de CPU que seguem acima do pedido (VERIFY_OVER).
# Sai sem tocar em nada se outro apply veio depois ou se o teto já voltou. Nunca agenda outra.
reassert_once() {
  local _l _over
  sleep "$REASSERT_DELAY_S"
  [ "$(state_field applied_at)" = "$2" ] || return 0
  verify_profile "$1"
  _over="$VERIFY_OVER"
  [ -n "$_over" ] || return 0
  for _l in $_over; do
    case "$_l" in
      p0_max) w "$P0_MAX" "$T_P0_MAX";;
      p4_max) w "$P4_MAX" "$T_P4_MAX";;
    esac
  done
  verify_profile "$1"
  log "tune: reassert $1 ($_over regravado 1x) -> $VERIFY_STATE $(state_field profile_verify)"
}

# reverify_loop <perfil> <applied_at> — só leitura; sai assim que o state deixa de ser pending.
reverify_loop() {
  local _d
  for _d in $REVERIFY_STEPS; do
    sleep "$_d"
    [ "$(state_field applied_at)" = "$2" ] || return 0
    [ "$(state_field profile_state)" = "pending" ] || return 0
    verify_profile "$1"
    [ "$VERIFY_STATE" = "pending" ] || { log "tune: reverify $1 -> $VERIFY_STATE $(state_field profile_verify)"; return 0; }
  done
}

apply_game() {
  snapshot_stock
  profile_targets game
  set_policy0 "$T_P0_MIN" "$T_P0_MAX"
  set_policy4 "$T_P4_MIN" "$T_P4_MAX"
  set_gpu "$T_GMIN" "$T_GMAX"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "$T_MIF_MIN"
  w "$INT_MIN" "$T_INT_MIN"
  w "$DISP_MIN" "$T_DISP_MIN"

  w "$R/proc/sys/vm/swappiness" "$T_SWAP"
  w "$R/proc/sys/vm/vfs_cache_pressure" "$T_VFS"
  w "$R/proc/sys/vm/stat_interval" "10"
  w "$R/proc/sys/vm/page-cluster" "0"

  restore_readahead
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "1"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  local b
  b=$(bbr_if_avail)
  [ -n "$b" ] && w "$R/proc/sys/net/ipv4/tcp_congestion_control" "$b"
  w "$R/proc/sys/net/ipv4/tcp_fastopen" "3"

  verify_after_apply game
  log "tune: apply game"
}

apply_balanced() {
  snapshot_stock
  profile_targets balanced
  set_policy0 "$T_P0_MIN" "$T_P0_MAX"
  set_policy4 "$T_P4_MIN" "$T_P4_MAX"
  set_gpu "$T_GMIN" "$T_GMAX"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "$T_MIF_MIN"
  w "$INT_MIN" "$T_INT_MIN"
  w "$DISP_MIN" "$T_DISP_MIN"

  w "$R/proc/sys/vm/swappiness" "$T_SWAP"
  w "$R/proc/sys/vm/vfs_cache_pressure" "$T_VFS"
  w "$R/proc/sys/vm/stat_interval" "5"
  w "$R/proc/sys/vm/page-cluster" "0"

  restore_readahead
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "0"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  restore_one "$R/proc/sys/net/ipv4/tcp_congestion_control"
  w "$R/proc/sys/net/ipv4/tcp_fastopen" "3"

  verify_after_apply balanced
  log "tune: apply balanced"
}

# ATENÇÃO (medido ao vivo em 2026-10-18): os tetos de CPU do battery (P0 max 1728000 / P4 max 1440000)
# SOMEM sozinhos quando a tela entra em Dozing — a Samsung reescreve os nós (presentes até uptime ~3194s,
# ausentes em ~3709s). Isso NÃO se resolve com loop de reescrita (briga com o framework da ROM, gasta
# bateria e esconde o sintoma). Se um dia for preciso reaplicar, que seja por EVENTO de tela-acesa, UMA
# vez, com releitura (w/verify_profile) — não implementado.
# GPU: o battery NÃO capa mais o teto (2.10.0). O cap 650000 era sobrescrito no boot e pelo térmico ao
# liberar (kernlab game-tetos: 650000 -> 845000/949000/754000); em jogo o battery vira game (effective_profile).
apply_battery() {
  snapshot_stock
  profile_targets battery
  set_policy0 "$T_P0_MIN" "$T_P0_MAX"
  set_policy4 "$T_P4_MIN" "$T_P4_MAX"
  set_gpu "$T_GMIN" "$T_GMAX"
  w "$MIF_MAX" "2093000"
  w "$MIF_MIN" "$T_MIF_MIN"
  w "$INT_MIN" "$T_INT_MIN"
  w "$DISP_MIN" "$T_DISP_MIN"

  w "$R/proc/sys/vm/swappiness" "$T_SWAP"
  w "$R/proc/sys/vm/vfs_cache_pressure" "$T_VFS"
  w "$R/proc/sys/vm/stat_interval" "15"
  w "$R/proc/sys/vm/page-cluster" "0"

  restore_readahead
  for dev in "$R"/sys/block/mmcblk* "$R"/sys/block/sd* "$R"/sys/block/nvme*; do
    [ -f "$dev/queue/nomerges" ] && w "$dev/queue/nomerges" "0"
    [ -f "$dev/queue/add_random" ] && w "$dev/queue/add_random" "0"
  done

  restore_one "$R/proc/sys/net/ipv4/tcp_congestion_control"
  restore_one "$R/proc/sys/net/ipv4/tcp_fastopen"

  verify_after_apply battery
  log "tune: apply battery"
}

resolve_mode() {
  local _m="$MODE"
  if [ "$_m" = "auto" ] || [ "$_m" = "verify" ] || [ "$_m" = "effective" ]; then
    effective_profile
  else
    echo "$_m"
  fi
}

# Teste offline: TUNE_SOURCE_ONLY=1 carrega só as funções (permite exercitar snap/w/verify com um
# driver falso que clampa). Em produção a variável nunca existe.
[ "${TUNE_SOURCE_ONLY:-0}" = "1" ] && return 0

case "$MODE" in
  idle|restore)
    snapshot_stock
    restore_stock
    exit 0
    ;;
  game)
    apply_game
    exit 0
    ;;
  balanced)
    apply_balanced
    exit 0
    ;;
  battery)
    apply_battery
    exit 0
    ;;
  verify)
    _t="${2:-$(resolve_mode)}"
    verify_profile "$_t"
    cat "$STATE_F" 2>/dev/null
    exit 0
    ;;
  reverify)
    reverify_loop "$2" "$3"
    exit 0
    ;;
  reassert)
    reassert_once "$2" "$3"
    exit 0
    ;;
  effective)
    effective_profile
    exit 0
    ;;
  auto)
    M=$(resolve_mode)
    case "$M" in
      game) apply_game;;
      battery) apply_battery;;
      *) apply_balanced;;
    esac
    exit 0
    ;;
  *)
    echo "uso: tune.sh [game|balanced|battery|idle|verify|reverify|reassert|auto]" >&2
    exit 1
    ;;
esac
