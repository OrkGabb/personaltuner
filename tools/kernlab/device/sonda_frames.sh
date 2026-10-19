#!/system/bin/sh
# sonda_frames.sh <pacote> [segundos] — prova a sonda eBPF do fas-rs (fork M54) no jogo em foco.
#   1. fa-upstream (frame-analyzer SEM a correção) tem de FALHAR ao anexar: controle negativo,
#      o libgui do Android 16 não tem os dois símbolos de queueBuffer de antes.
#   2. fa-patched (hook_queueBuffer) conta os frames do processo durante N s enquanto o TimeStats
#      mede a mesma janela por camada. Contagem ~2x a do TimeStats = mais de uma superfície no
#      mesmo fluxo (o analisador não separa), e aí o frametime que o fas-rs vê está errado.
# Saída em $KL_HOME/sonda/: upstream.txt, patched.txt, timestats.txt, resumo.txt.
. /data/local/tmp/kernlab/agent.sh
PKG="$1"
SECS="${2:-20}"
OUT=$KL_HOME/sonda
rm -rf "$OUT"
mkdir -p "$OUT"
PID=$(pidof "$PKG" | cut -d' ' -f1)
if [ -z "$PID" ]; then
  echo "erro=jogo_nao_rodando $PKG" | tee "$OUT/resumo.txt"
  exit 1
fi

timeout -s INT 5 "$KL_OPT/fa-upstream" --pid "$PID" > "$OUT/upstream.txt" 2>&1
echo "upstream_rc=$?" > "$OUT/resumo.txt"
echo "upstream_frames=$(grep -c '^frametime' "$OUT/upstream.txt")" >> "$OUT/resumo.txt"

ts_reset
timeout -s INT "$SECS" "$KL_OPT/fa-patched" --pid "$PID" > "$OUT/patched.txt" 2>&1
echo "patched_rc=$?" >> "$OUT/resumo.txt"
ts_dump "$OUT/timestats.txt"
kl_focused "$PKG" || echo "erro=foco_perdido" >> "$OUT/resumo.txt"
echo "patched_frames=$(grep -c '^frametime' "$OUT/patched.txt")" >> "$OUT/resumo.txt"
echo "secs=$SECS pid=$PID pkg=$PKG" >> "$OUT/resumo.txt"
cat "$OUT/resumo.txt"
