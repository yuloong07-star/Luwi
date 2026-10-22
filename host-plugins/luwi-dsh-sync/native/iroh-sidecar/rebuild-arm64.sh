#!/bin/bash
# 从仓库里这份源码编 aarch64-linux-musl 静态 sidecar, 产物直接落 jniLibs
# 必须用 cargo zigbuild: 手写 zig cc 包装器会和 ring 自带的 --target 打架
# 路径写死 D:\apk (WSL 里是 /mnt/d/apk), 换机器要改 SRC_DIR / OUT
set -euo pipefail
export PATH="$HOME/.cargo/bin:$HOME/zig:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

SRC_DIR="/mnt/d/apk/Luwi/host-plugins/luwi-dsh-sync/native/iroh-sidecar"
OUT="/mnt/d/apk/Luwi/app/src/main/jniLibs/arm64-v8a/liblwiroh.so"
WORK="$HOME/iroh-sidecar-arm64"

mkdir -p "$WORK/src"
cp "$SRC_DIR/Cargo.toml" "$SRC_DIR/Cargo.lock" "$WORK/"
cp "$SRC_DIR/src/main.rs" "$WORK/src/main.rs"
cd "$WORK"
cargo zigbuild --release --target aarch64-unknown-linux-musl 2>&1 | tail -20
cp "$WORK/Cargo.lock" "$SRC_DIR/Cargo.lock"
mkdir -p "$(dirname "$OUT")"
cp target/aarch64-unknown-linux-musl/release/iroh-sidecar "$OUT"
ls -la "$OUT"
sha256sum "$OUT"
echo DONE
