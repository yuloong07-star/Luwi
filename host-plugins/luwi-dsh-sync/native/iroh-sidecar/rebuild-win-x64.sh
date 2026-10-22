#!/bin/bash
# 从仓库里这份源码编 Windows x64 sidecar (lwiroh.exe), 产物落 native/win32-x64/
# 构建在 WSL 里做, 用 cargo zigbuild 的 mingw 工具链 —— Windows 上没有 MSVC link.exe
# 路径写死 D:\apk (WSL 里是 /mnt/d/apk), 换机器要改 SRC_DIR
set -euo pipefail
export PATH="$HOME/.cargo/bin:$HOME/zig:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

SRC_DIR="/mnt/d/apk/Luwi/host-plugins/luwi-dsh-sync/native/iroh-sidecar"
OUT_DIR="$SRC_DIR/../win32-x64"
WORK="$HOME/iroh-sidecar-win"

mkdir -p "$WORK/src" "$OUT_DIR"
cp "$SRC_DIR/Cargo.toml" "$SRC_DIR/Cargo.lock" "$WORK/"
cp "$SRC_DIR/src/main.rs" "$WORK/src/main.rs"
cd "$WORK"
cargo zigbuild --release --target x86_64-pc-windows-gnu 2>&1 | tail -20
cp target/x86_64-pc-windows-gnu/release/iroh-sidecar.exe "$OUT_DIR/lwiroh.exe"
# 锁文件以这里为准回写仓库 (新增依赖时它会变)
cp "$WORK/Cargo.lock" "$SRC_DIR/Cargo.lock"
ls -la "$OUT_DIR/lwiroh.exe"
sha256sum "$OUT_DIR/lwiroh.exe"
file "$OUT_DIR/lwiroh.exe" || true
echo DONE
