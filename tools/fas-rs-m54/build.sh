#!/usr/bin/env bash
# Build do fas-rs (fork M54) que vai em fusao-v2.0/system/bin/fas-rs. Fonte correspondente do
# binário GPL-3.0: upstream nos commits abaixo + patches/ deste diretório.
#   bash tools/fas-rs-m54/build.sh            (WSL/Linux; NDK em ~/android-ndk-r28)
# Precisa: rustup (nightly + rust-src + aarch64-linux-android), cargo-ndk e bpf-linker 0.12.0
# OFICIAL (o bpf-linker do cargo install linka no LLVM do sistema, e o nightly usa LLVM 23).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/fas-m54-build}"
FAS_BASE=09ba876
FAE_BASE=3dfd11f
export ANDROID_NDK_HOME="${NDK:-$HOME/android-ndk-r28}"
export RUSTFLAGS="-C default-linker-libraries"
export PATH="$HOME/.cargo/bin:$PATH"

rustup toolchain install nightly --profile minimal -c rust-src
rustup target add aarch64-linux-android --toolchain nightly
command -v cargo-ndk >/dev/null || cargo +nightly install cargo-ndk --locked
command -v bpf-linker >/dev/null || { echo "bpf-linker ausente: instale o release oficial 0.12.0 (aya-rs)"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"
[ -d fas-rs ] || git clone https://github.com/shadow3aaa/fas-rs
[ -d frame-analyzer-ebpf ] || git clone https://github.com/shadow3aaa/frame-analyzer-ebpf
git -C fas-rs checkout -q -B m54 "$FAS_BASE"
git -C frame-analyzer-ebpf checkout -q -B m54 "$FAE_BASE"
git -C frame-analyzer-ebpf am -q "$HERE"/patches/frame-analyzer-ebpf/*.patch
git -C fas-rs am -q "$HERE"/patches/fas-rs/*.patch

cd fas-rs
cargo +nightly ndk --platform 31 -t arm64-v8a build --target aarch64-linux-android -Z build-std --release
ls -l target/aarch64-linux-android/release/fas-rs
sha256sum target/aarch64-linux-android/release/fas-rs
