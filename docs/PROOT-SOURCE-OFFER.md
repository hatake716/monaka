# PRoot corresponding source and source offer

CCFA distribution APKs embed the Android/Bionic PRoot runtime from the Termux stable package family.

Current embedded version:

```text
proot 5.1.107.92
```

The Termux package recipe identifies PRoot as GPL-2.0, while the PRoot source-file notices permit redistribution under GPL version 2 or, at the recipient's option, any later version.

## Source accompanies the APK

CCFA does not rely only on an external URL for GPL source availability. A distribution build embeds the exact verified source archive inside the APK:

```text
assets/legal/sources/proot-v5.1.107.92.zip
```

It also embeds:

```text
assets/legal/sources/ccfa-prepare-termux-android-proot.sh
assets/legal/sources/termux-build-recipes/proot-build.sh
assets/legal/SOURCE-AND-LICENSE-MANIFEST.sha256
```

The CCFA script is included because the APK build alters ELF dependency metadata/RPATH while repackaging the Termux binary for Android `nativeLibraryDir`.

The source archive SHA-256 is verified against the value published by the Termux package recipe before packaging:

```text
29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135
```

## Other native dependencies

CCFA also includes corresponding source archives for the native libraries packaged alongside PRoot:

```text
assets/legal/sources/libandroid-shmem-v0.7.tar.gz.source
assets/legal/sources/talloc-2.4.3.tar.gz.source
```

Android AAPT may omit files ending directly in `.gz`, so these two exact gzip byte streams are stored with a trailing `.source` suffix. After extracting them from the APK, remove only that trailing suffix to restore the original `.tar.gz` filenames.

## Three-year fallback offer

For at least three years after distribution of a CCFA APK containing these copyleft components, the project maintainer should retain a copy of the exact corresponding source and provide it on request for no more than the reasonable physical cost of source distribution if the in-APK source or upstream locations become unavailable.

## Distribution requirement

Do not publish a stripped APK that omits `assets/legal/`. The CI build fails if required license texts or corresponding-source archives are absent.
