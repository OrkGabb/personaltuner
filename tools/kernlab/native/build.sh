#!/usr/bin/env bash
# Compila os binários nativos do kernlab para arm64 (rodar no WSL, com o NDK).
#   NDK=~/android-ndk-r28 bash tools/kernlab/native/build.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../device/bin"
NDK=${NDK:-$HOME/android-ndk-r28}
API=${API:-33}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "compilador não encontrado: $CC" >&2; exit 1; }
mkdir -p "$OUT"
for src in kl_burst kl_touch kl_io kl_sample; do
  "$CC" -O2 -Wall -Wextra -Werror -Wno-unused-parameter -fPIE -pie -pthread \
    -o "$OUT/$src" "$HERE/$src.c"
  echo "ok $src $(stat -c %s "$OUT/$src") bytes"
done
