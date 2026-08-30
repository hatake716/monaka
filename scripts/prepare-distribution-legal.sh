#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEGAL="$ROOT/app/src/main/assets/legal"
LICENSES="$LEGAL/licenses"
SOURCES="$LEGAL/sources"
RECIPES="$SOURCES/termux-build-recipes"
TERMUX_PACKAGES_COMMIT="08b49b3ce00b1e14a3a0365200f30e50f8dfafe1"
# scripts/prepare-termux-android-proot.sh の PROOT_VERSION と必ず一致させること
# （同梱バイナリと対応ソースの GPL 整合性）。
PROOT_SOURCE_VERSION="5.1.107.92"
mkdir -p "$LICENSES" "$SOURCES" "$RECIPES"

# 過去バージョンの対応ソース zip が残っていると、ローカルの増分ビルドで
# 新旧両方の zip が APK に同梱されてしまうため、対象版以外を削除する。
find "$SOURCES" -maxdepth 1 -name 'proot-v*.zip' ! -name "proot-v${PROOT_SOURCE_VERSION}.zip" -delete

fetch() {
  local url="$1" out="$2"
  echo "Fetching $url"
  # 接続確立まで20秒で打ち切り、失敗接続もリトライ対象に含めて多めに再試行する。
  # 外部ホスト(例: www.gnu.org)が断続的に不調でも、133秒待ちで固まらず素早く再試行できる。
  curl -fL \
    --connect-timeout 20 \
    --max-time 180 \
    --retry 6 \
    --retry-delay 3 \
    --retry-connrefused \
    --retry-all-errors \
    "$url" -o "$out"
  test -s "$out"
}

verify_sha256() {
  local expected="$1" file="$2"
  echo "$expected  $file" | sha256sum -c -
}

fetch "https://www.apache.org/licenses/LICENSE-2.0.txt" "$LICENSES/APACHE-2.0.txt"
fetch "https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt" "$LICENSES/GPL-2.0.txt"
fetch "https://www.gnu.org/licenses/gpl-3.0.txt" "$LICENSES/GPL-3.0.txt"
fetch "https://www.gnu.org/licenses/lgpl-3.0.txt" "$LICENSES/LGPL-3.0.txt"
fetch "https://raw.githubusercontent.com/termux/libandroid-shmem/v0.7/LICENSE" "$LICENSES/BSD-3-Clause-libandroid-shmem.txt"
fetch "https://raw.githubusercontent.com/termux/termux-app/v0.118.0/LICENSE.md" "$LICENSES/TERMUX-TERMINAL-LICENSE.md"
fetch "https://raw.githubusercontent.com/apache/commons-compress/rel/commons-compress-1.27.1/NOTICE.txt" "$LICENSES/COMMONS-COMPRESS-NOTICE.txt"
fetch "https://raw.githubusercontent.com/apache/commons-codec/rel/commons-codec-1.17.1/NOTICE.txt" "$LICENSES/COMMONS-CODEC-NOTICE.txt"
fetch "https://raw.githubusercontent.com/apache/commons-io/rel/commons-io-2.16.1/NOTICE.txt" "$LICENSES/COMMONS-IO-NOTICE.txt"
fetch "https://raw.githubusercontent.com/apache/commons-lang/rel/commons-lang-3.16.0/NOTICE.txt" "$LICENSES/COMMONS-LANG3-NOTICE.txt"

fetch "https://github.com/termux/proot/archive/v${PROOT_SOURCE_VERSION}.zip" "$SOURCES/proot-v${PROOT_SOURCE_VERSION}.zip"
verify_sha256 "29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135" "$SOURCES/proot-v${PROOT_SOURCE_VERSION}.zip"

fetch "https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz" "$SOURCES/libandroid-shmem-v0.7.tar.gz.source"
verify_sha256 "1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867" "$SOURCES/libandroid-shmem-v0.7.tar.gz.source"

fetch "https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz" "$SOURCES/talloc-2.4.3.tar.gz.source"
verify_sha256 "dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd" "$SOURCES/talloc-2.4.3.tar.gz.source"

# terminal-emulator v0.118.0 の JNI ソース。app/libs/terminal-emulator-0.118.0-16k.aar 内蔵の
# libtermux.so (arm64-v8a) はこの termux.c を scripts/build-terminal-emulator-16k.sh が
# 16KiB page alignment でコンパイルした再ビルド版である。
fetch "https://raw.githubusercontent.com/termux/termux-app/v0.118.0/terminal-emulator/src/main/jni/termux.c" "$SOURCES/termux-terminal-emulator-v0.118.0-termux.c"
verify_sha256 "729112f2e66cdddb7e7311ddf8a89ad54037a54bddbf4d5291f2e1fff5b97373" "$SOURCES/termux-terminal-emulator-v0.118.0-termux.c"

BASE="https://raw.githubusercontent.com/termux/termux-packages/$TERMUX_PACKAGES_COMMIT/packages"
fetch "$BASE/proot/build.sh" "$RECIPES/proot-build.sh"
fetch "$BASE/libandroid-shmem/build.sh" "$RECIPES/libandroid-shmem-build.sh"
fetch "$BASE/libtalloc/build.sh" "$RECIPES/libtalloc-build.sh"
cp "$ROOT/scripts/prepare-termux-android-proot.sh" "$SOURCES/ccfa-prepare-termux-android-proot.sh"
cp "$ROOT/scripts/build-terminal-emulator-16k.sh" "$SOURCES/ccfa-build-terminal-emulator-16k.sh"

cat > "$SOURCES/README.txt" <<EOF
CCFA corresponding-source bundle

This directory is intentionally embedded in distributed CCFA APKs so recipients of the
native GPL/LGPL components receive the corresponding upstream source archives alongside
the object code. The CCFA packaging script used to select, verify and alter ELF metadata
is included as ccfa-prepare-termux-android-proot.sh.

The terminal-emulator JNI library (libtermux.so, arm64-v8a) shipped inside
terminal-emulator-0.118.0-16k.aar is a rebuild of the included
termux-terminal-emulator-v0.118.0-termux.c with 16KiB ELF page alignment (required by
Google Play); the build script is included as ccfa-build-terminal-emulator-16k.sh and no
other part of the upstream v0.118.0 terminal-emulator sources was modified.

Android AAPT can ignore assets ending in .gz. For that reason these exact gzip archives
are stored with a trailing .source suffix inside the APK:

  libandroid-shmem-v0.7.tar.gz.source  -> rename to libandroid-shmem-v0.7.tar.gz
  talloc-2.4.3.tar.gz.source           -> rename to talloc-2.4.3.tar.gz

Renaming does not alter the bytes; SHA-256 is verified before APK packaging.

Pinned Termux package recipe commit:
$TERMUX_PACKAGES_COMMIT

The application itself is distributed in source form in the CCFA repository under the
Apache License 2.0. PRoot remains a separate subprocess/native executable component and
retains its GPL terms. libtalloc and libandroid-shmem retain their respective licenses.
The terminal-module exception notice and Apache Commons family NOTICE files required by
the runtime dependency graph are stored under ../licenses/.

Do not remove this directory from distribution builds.
EOF

(
  cd "$LEGAL"
  find licenses sources -type f -print0 | sort -z | xargs -0 sha256sum > SOURCE-AND-LICENSE-MANIFEST.sha256
)

echo "Prepared distribution legal/source assets: $LEGAL"
