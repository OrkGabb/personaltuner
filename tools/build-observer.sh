#!/bin/bash
# build-observer.sh — compila fusao-observer (javac + d8, offline) e instala
# o dex no stage. O stub android/app/IProcessObserver.java serve SÓ ao javac
# (classe @hide ausente no android.jar público); NÃO entra no dex — no aparelho
# a delegação parent-first resolve para o framework real.
# Roda no WSL: bash tools/build-observer.sh [--stage fusao-v2.0]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/fusao-v2.0"
for i in "$@"; do case "$i" in --stage=*) STAGE="${i#--stage=}";; esac; done
SDK=~/android-sdk
AJAR="$SDK/platforms/android-37.0/android.jar"
D8="$SDK/build-tools/36.0.0/d8"
OUT="$ROOT/.observer-build"
rm -rf "$OUT"; mkdir -p "$OUT/stub" "$OUT/our" "$OUT/dex"
[ -f "$AJAR" ] || { echo "sem android.jar"; exit 1; }
javac -cp "$AJAR" -d "$OUT/stub" "$ROOT/fusao-observer/stub/android/app/IProcessObserver.java" || exit 1
javac -cp "$AJAR:$OUT/stub" -d "$OUT/our" "$ROOT/fusao-observer/src/com/fusao/observer/ObserverMain.java" || exit 1
"$D8" --lib "$AJAR" --min-api 29 --output "$OUT/dex" "$OUT/our/com/fusao/observer/ObserverMain.class" "$OUT/our/com/fusao/observer/ObserverMain\$Obs.class" || exit 1
cp "$OUT/dex/classes.dex" "$STAGE/fusao/observer.dex"
echo "dex: $(stat -c%s "$STAGE/fusao/observer.dex") bytes -> $STAGE/fusao/observer.dex"
