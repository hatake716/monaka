# CCFA distribution checklist

This checklist is designed to reduce licensing, trademark, and third-party-service risk when redistributing CCFA APKs. It is not a substitute for legal advice in a particular jurisdiction.

## Required for every APK

1. Build with `scripts/prepare-termux-android-proot.sh`.
2. Build with `scripts/prepare-distribution-legal.sh`.
3. Do not remove `assets/legal/` from the APK.
4. Confirm the APK contains required license texts, attribution notices, and corresponding-source archives.
5. Publish the unmodified APK produced by CI or an equivalent reproducible build.
6. Keep the source/legal manifest associated with that APK.
7. Keep the release commit, APK SHA-256, and corresponding-source materials together.

## Branding

Use the product name:

```text
CCFA(日本語入力対応Linuxコンテナ)
```

Do not present CCFA as an official product of Anthropic, Canonical, Termux, Samba, Apache, or any other third party. Do not use third-party logos in the launcher icon, screenshots, store artwork, or promotional material unless separately authorized.

## Third-party AI CLI software

CCFA v1.0.0 does not automatically download, install, repair, authenticate, log in to, or broker credentials for proprietary third-party AI CLI software.

Users who choose to use a third-party AI CLI install and authenticate it manually from the ordinary Linux shell under that provider's current terms and documentation.

## Linux Base

Ubuntu Base is downloaded directly by the user's device and is not part of the distributed APK. Do not add Ubuntu logos or rename CCFA to include the Ubuntu trademark.

## Copyleft native components

The APK contains PRoot and libtalloc-related native code. Distribution builds therefore include exact corresponding-source archives and build/repackaging materials under `assets/legal/sources/`.

Do not distribute only extracted native libraries without their corresponding license/source materials.

## Attribution notices

Every distribution APK includes Apache/GPL/LGPL/BSD license texts, the Termux terminal-module license/exception notice, Apache Commons NOTICE files, CCFA third-party notices, and the corresponding-source SHA-256 manifest.

## Privacy

See `PRIVACY.md`. If a distributor adds analytics, crash reporting, advertising, cloud sync, authentication, telemetry, or another network service, update the privacy documentation before distribution.

## Release retention

For each public binary release retain the APK, SHA-256, Git commit, source/legal manifest, corresponding-source archives, and build/repackaging scripts. Keep GPL source-offer material available for at least three years after the last distribution of the applicable binary release.
