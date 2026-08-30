#!/usr/bin/env bash
set -euo pipefail

PROOT_VERSION="5.3.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for tool in aarch64-linux-gnu-gcc aarch64-linux-gnu-strip aarch64-linux-gnu-objcopy aarch64-linux-gnu-objdump; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Missing $tool. Install gcc-aarch64-linux-gnu and binutils-aarch64-linux-gnu." >&2
    exit 1
  }
done

curl -fL --retry 3 --retry-delay 2 \
  "https://github.com/proot-me/proot/archive/refs/tags/v${PROOT_VERSION}.tar.gz" \
  -o "$WORK/proot.tar.gz"

tar -xzf "$WORK/proot.tar.gz" -C "$WORK"
SRC="$WORK/proot-${PROOT_VERSION}"

make -C "$SRC/src" loader/loader \
  CC=aarch64-linux-gnu-gcc \
  LD=aarch64-linux-gnu-gcc \
  STRIP=aarch64-linux-gnu-strip \
  OBJCOPY=aarch64-linux-gnu-objcopy \
  OBJDUMP=aarch64-linux-gnu-objdump \
  V=1

mkdir -p "$(dirname "$OUT")"
cp "$SRC/src/loader/loader" "$OUT"
chmod 755 "$OUT"

echo "Built PRoot loader: $OUT"
file "$OUT"
sha256sum "$OUT"
