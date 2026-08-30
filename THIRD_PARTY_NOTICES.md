# CCFA third-party notices

CCFA is an independent project. It is not endorsed by, affiliated with, or sponsored by Anthropic PBC, Canonical Ltd., Termux, the Samba Team, the Apache Software Foundation, or other third-party licensors named below.

## CCFA application code

- Project name: CCFA (日本語入力対応Linuxコンテナ)
- License: Apache License 2.0
- The Google Play build uses the `io.github.hatake716.ccfa` applicationId; the sideload build retains the historical `io.github.hatake716.claudecodeandroid` id, and the source package/namespace keeps the historical name in both builds.

## Termux PRoot runtime

CCFA embeds the Android/Bionic PRoot binary distributed by the Termux stable package repository.

- Component: PRoot
- Embedded version: `5.1.107.92`
- Source: `https://github.com/termux/proot/tree/v5.1.107.92`
- Termux package recipe identifies the package as `GPL-2.0`.
- PRoot source-file notices state GNU GPL version 2 or, at the recipient's option, any later version.

CCFA modifies only packaged ELF metadata needed to resolve APK-bundled shared libraries. The CCFA repackaging script is included with the corresponding-source bundle.

The exact PRoot source archive and build/repackaging materials are embedded in every distribution APK under `assets/legal/sources/`.

## libandroid-shmem

- Version: `0.7`
- Source: `https://github.com/termux/libandroid-shmem/tree/v0.7`
- License: BSD 3-Clause
- Copyright notices and BSD terms are reproduced at `assets/legal/licenses/BSD-3-Clause-libandroid-shmem.txt`.

The exact source archive is also embedded under `assets/legal/sources/`.

## libtalloc

- Version: `2.4.3`
- Upstream: Samba Team / talloc
- Source: `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz`
- The shared `libtalloc` library is provided under LGPLv3 terms upstream; the Termux package metadata labels the package as GPL-3.0.

For conservative redistribution compliance, CCFA includes both LGPLv3 and GPLv3 license texts and the exact talloc 2.4.3 source archive in every distribution APK. Because `libtalloc.so` is dynamically loaded by PRoot, CCFA's source/rebuild materials remain available so recipients can rebuild the APK with a modified compatible library.

## Termux terminal-view / terminal-emulator

- Version: `0.118.0`
- Source: `https://github.com/termux/termux-app/tree/v0.118.0`
- Used modules: `terminal-view`, `terminal-emulator`
- License: Apache License 2.0 exception identified by the Termux project for these terminal modules.

The Termux v0.118.0 license/exception notice is reproduced at `assets/legal/licenses/TERMUX-TERMINAL-LICENSE.md`.

The external Termux application is not bundled, installed, launched, or contacted by CCFA.

## Apache Commons runtime libraries

CCFA directly depends on Apache Commons Compress `1.27.1` to extract the Linux Base archive. Its published POM declares the following non-optional runtime dependencies:

- Apache Commons Codec `1.17.1`
- Apache Commons IO `2.16.1`
- Apache Commons Lang `3.16.0`

These components are Apache License 2.0 software. CCFA distribution APKs contain the Apache License 2.0 text and each relevant ASF NOTICE:

```text
assets/legal/licenses/COMMONS-COMPRESS-NOTICE.txt
assets/legal/licenses/COMMONS-CODEC-NOTICE.txt
assets/legal/licenses/COMMONS-IO-NOTICE.txt
assets/legal/licenses/COMMONS-LANG3-NOTICE.txt
```

## Linux Base / Ubuntu Base

Ubuntu Base is **not bundled or redistributed inside the CCFA APK**. When the user asks CCFA to create a Linux environment, the archive is downloaded directly from Canonical's official server to the user's device.

`Ubuntu` is a Canonical trademark. CCFA does not use the Ubuntu name or logo as its product name, launcher icon, or endorsement branding. References in source/documentation are descriptive references to the upstream Linux base image.

## Optional third-party AI CLI software

No proprietary third-party AI CLI is bundled in the repository or APK, and CCFA v1.0.0 does not automatically download, install, repair, log in to, or broker credentials for such software.

If a user wants a third-party AI CLI, the user must install and authenticate it manually from the ordinary Linux shell according to that provider's current documentation, terms, privacy policy, supported-region rules, authentication requirements, and other applicable conditions.

CCFA does not provide or proxy third-party subscription credentials, OAuth tokens, API keys, account entitlements, or rate limits. CCFA does not use third-party AI-provider logos and does not present itself as an official client of those providers.

Names such as `Claude`, `Claude Code`, and `Anthropic` may appear only in compatibility history, implementation history, legal analysis, or technical documentation where identification is necessary.

## Distribution rule

Do not distribute an APK from which `assets/legal/` has been removed. Distribution builds are expected to contain:

- full Apache-2.0, GPL-2.0, GPL-3.0, LGPL-3.0 and BSD-3-Clause license texts;
- the Termux terminal-module license/exception notice;
- Apache Commons Compress/Codec/IO/Lang NOTICE files;
- exact corresponding-source archives for PRoot, libandroid-shmem and talloc;
- the CCFA runtime repackaging script;
- pinned Termux package build recipes used for the Android package family;
- SHA-256 manifests for bundled legal/source material.

See `docs/DISTRIBUTION.md` and `docs/PROOT-SOURCE-OFFER.md`.
