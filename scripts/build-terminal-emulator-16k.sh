#!/usr/bin/env bash
# Rebuilds the JNI part (libtermux.so, arm64-v8a) of com.termux.termux-app:terminal-emulator
# v0.118.0 with 16KiB ELF page alignment and repackages it into
# app/libs/terminal-emulator-0.118.0-16k.aar, which the app builds against instead of the
# Maven artifact (see app/build.gradle.kts).
#
# Why: Google Play requires every arm64-v8a .so in an upload to have PT_LOAD p_align >= 16384
# starting with updates submitted on/after 2025-11-01. The Maven terminal-emulator AAR was built
# with 4K alignment (p_align=0x1000), which Play's bundle validation rejects. Everything else in
# the upstream v0.118.0 sources is unchanged; only the linker page-size flags differ:
#   -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
#
# Inputs (all pinned by SHA-256 below):
#   - upstream source: termux/termux-app tag v0.118.0, terminal-emulator/src/main/jni/termux.c
#   - base AAR:        com.termux.termux-app:terminal-emulator:0.118.0 from JitPack
#
# Requirements: git, curl, python3, Android NDK r28 (aarch64-linux-android21-clang + llvm-readelf).
#   CCFA_NDK may point at a different NDK root; the default is the r28 install used for testing.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${CCFA_NDK:-$HOME/android-sdk/ndk/28.2.13676358}"
TERMUX_TAG="v0.118.0"
# terminal-* 系は Maven Central ではなく JitPack に公開されている（app の repositories も同様）。
AAR_URL="https://jitpack.io/com/termux/termux-app/terminal-emulator/0.118.0/terminal-emulator-0.118.0.aar"
AAR_SHA256="fd0d262041b42713a5685625711a01f87d86c559eaa9cc663f82bc73520d08b5"
TERMUX_C_SHA256="729112f2e66cdddb7e7311ddf8a89ad54037a54bddbf4d5291f2e1fff5b97373"

CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$CC" || { echo "NDK clang not found: $CC (set CCFA_NDK)" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src" "$WORK/aar"

echo "Cloning termux/termux-app $TERMUX_TAG (shallow)..."
git clone --depth 1 --branch "$TERMUX_TAG" https://github.com/termux/termux-app.git "$WORK/src" >/dev/null
TERMUX_C="$WORK/src/terminal-emulator/src/main/jni/termux.c"

echo "Verifying termux.c SHA-256..."
( cd "$WORK/src" && echo "$TERMUX_C_SHA256  terminal-emulator/src/main/jni/termux.c" | sha256sum -c - )

echo "Fetching base AAR $AAR_URL ..."
curl -fL --connect-timeout 20 --max-time 300 --retry 4 --retry-delay 3 "$AAR_URL" -o "$WORK/aar/terminal-emulator.aar"
( cd "$WORK/aar" && echo "$AAR_SHA256  terminal-emulator.aar" | sha256sum -c - )

echo "Compiling libtermux.so (arm64-v8a, 16KiB alignment)..."
"$CC" -shared -fPIC -O2 \
    "$TERMUX_C" \
    -o "$WORK/libtermux.so" \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -lm -ldl

echo "Verifying PT_LOAD p_align == 0x4000..."
mapfile -t aligns < <("$READELF" -lW "$WORK/libtermux.so" | awk '/LOAD/ { print $NF }')
test "${#aligns[@]}" -ge 2 || { echo "no PT_LOAD segments found in libtermux.so" >&2; exit 1; }
for align in "${aligns[@]}"; do
    [ "$align" = "0x4000" ] || { echo "unexpected p_align: $align" >&2; exit 1; }
done

echo "Patching AAR (jni/arm64-v8a/libtermux.so only)..."
mkdir -p "$ROOT/app/libs"
OUT_AAR="$ROOT/app/libs/terminal-emulator-0.118.0-16k.aar"
python3 - "$WORK/aar/terminal-emulator.aar" "$WORK/libtermux.so" "$OUT_AAR" <<'PYEOF'
import sys, zipfile

src_aar, new_so, out_aar = sys.argv[1], sys.argv[2], sys.argv[3]
so_bytes = open(new_so, "rb").read()
replaced = False
with zipfile.ZipFile(src_aar) as zin, zipfile.ZipFile(out_aar, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "jni/arm64-v8a/libtermux.so":
            data = so_bytes
            replaced = True
        zout.writestr(item, data)
if not replaced:
    raise SystemExit("jni/arm64-v8a/libtermux.so not found in base AAR")
PYEOF

echo "Verifying written AAR..."
python3 - "$OUT_AAR" "$WORK/libtermux.so" <<'PYEOF'
import sys, zipfile

out_aar, so = sys.argv[1], sys.argv[2]
want = open(so, "rb").read()
with zipfile.ZipFile(out_aar) as z:
    got = z.read("jni/arm64-v8a/libtermux.so")
assert got == want, "libtermux.so inside the AAR differs from the rebuilt one"
print(f"AAR OK; embedded libtermux.so sha256={__import__('hashlib').sha256(got).hexdigest()}")
PYEOF

echo "Wrote $OUT_AAR"
