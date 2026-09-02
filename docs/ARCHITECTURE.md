# CCFA Architecture — v1.0.0

## Overview

CCFA is an Android application that combines an in-app PTY terminal with an app-private Linux rootfs. The external Termux application is not required.

```text
Android / CCFA APK
        │
        ├─ MainActivity / container & storage UI
        ├─ Japanese IME composer
        ├─ TerminalView / TerminalSession
        │       │
        │       ▼
        │   libtermux.so -> /dev/ptmx
        │
        └─ Android/Bionic PRoot runtime
                │
                ▼
           Linux rootfs
             ├─ /workspace
             └─ /phone/*
```

## Background execution

The PTY session (and therefore every Linux process under PRoot) is owned by
`TerminalSessionService`, a foreground service — **not** by the Activity.

When an Activity owns the session, moving the app to the background drops its
process to *cached*, where Android may freeze it under Doze, reap its child
processes with the phantom process killer (Android 12+), or kill it outright
under memory pressure. Long-running work — an interactive `claude` session, a
build — therefore stopped as soon as the user left the screen.

Running a foreground service raises the process back to visible-equivalent
priority, which removes it from all three paths. The Activity keeps only the
view and the input composer:

```text
TerminalSessionService (foreground service, owns the session)
        │  binder
        ▼
EmbeddedTerminalActivity (view + IME composer, disposable)
```

- Session output is delivered to the service first, which records the
  transcript before forwarding to the UI client — so output produced while no
  Activity exists still reaches the terminal history.
- A `PARTIAL_WAKE_LOCK` is held for the lifetime of a running session.
- Leaving the terminal screen does **not** end the session. The user ends it
  explicitly from the notification's *stop* action or from the main screen.
- Reopening the terminal re-attaches the live session instead of spawning a
  new one. A launch that carries a command (COMMAND / SETUP mode) always starts
  a fresh shell, so the command cannot be silently swallowed by an existing one.

Manufacturer battery savers act independently of the above, so the main screen
links to the system's battery-optimization exclusion for the app.

## Landscape layout

Screen space spent on chrome is screen space taken from the terminal, and in
landscape the height budget is small. The terminal screen therefore adapts:

- **Extra keys collapse to one row.** Portrait keeps 7 keys × 2 rows at a fixed
  width (horizontally scrollable). Landscape has the width to spare, so all 14
  keys are laid out in a single row using layout weights — every key visible, no
  scrolling. If the width cannot fit readable labels (split-screen, for
  instance), it falls back to the two-row scrolling form.
- **The header is slimmed** from the platform `Button` minimum (48dp plus
  padding) to a 32–34dp band, with the default minimum size and padding cleared.

Together these return roughly 92dp — about 22% of the height on a 923 × 411dp
landscape screen, or ~5 text rows at a 15dp font.

Because the manifest declares `orientation|screenSize` in `configChanges`, the
Activity is not recreated on rotation; `onConfigurationChanged` swaps just the
header and the key bar. The `TerminalView` is deliberately left in place so the
session stays attached and the visible buffer survives.

## PTY and Japanese input

`TerminalSession` opens a PTY through the terminal-emulator JNI layer. `TerminalView` renders terminal output and handles terminal-focused keyboard events.

Japanese composition is intentionally handled by a normal Android `EditText`. Android IMEs such as Gboard perform kana/kanji conversion, and CCFA writes the committed Unicode text to the PTY as UTF-8.

## Linux runtime

At build time, `scripts/prepare-termux-android-proot.sh` obtains the Android/Bionic PRoot package family and packages:

- `libproot.so`
- `libproot-loader.so`
- `libandroid-shmem.so`
- `libtalloc.so`

Runtime environment variables include `PROOT_LOADER`, `PROOT_NO_SECCOMP`, `LD_LIBRARY_PATH`, and `PROOT_L2S_DIR`.

The app currently targets ARM64 (`arm64-v8a`).

## Linux rootfs

CCFA downloads Ubuntu Base 24.04 ARM64 at first setup from the upstream provider. The Base archive is not bundled in the APK.

Rootfs containers are stored under app-private storage:

```text
filesDir/embedded-runtime/containers/<name>/rootfs
```

A shared workspace is stored at:

```text
filesDir/workspace
```

and bound to `/workspace` in every container.

## Multiple containers

CCFA discovers installed containers from their rootfs marker, stores the active container name, and supports create/select/delete operations. Deleting a container removes that rootfs only; the shared workspace and Android storage remain separate.

## Configurable Android storage mounts

`StorageShareManager` persists Android-folder to Linux-folder mappings. Defaults are:

```text
Android Download   -> /phone/Downloads
Android Documents  -> /phone/Documents
```

Users can enable/disable mappings, change Android paths, change `/phone/...` guest targets, add/remove mappings, test read/write access, and restore defaults. Up to eight mappings are supported.

When a terminal session starts, enabled and accessible mappings are converted into PRoot bind arguments:

```text
--bind=<Android path>:<Linux /phone/... path>
```

Changes therefore apply from the next terminal launch.

## Android executable restriction and storage

The Linux userland lives in app-private writable storage. monaka
(the all-files-access / sideload-only build) intentionally uses:

```text
compileSdk 36
targetSdk  29
minSdk     26
```

This is a technical choice for the direct/sideload distribution architecture
and is **not** a Google Play configuration — monaka is not published on Play.

`targetSdk 29` keeps the app just below the W^X restriction that Android
enforces on the app data directory at targetSdk 29+, so PRoot continues to run
from the exec-capable native library dir (`libproot.so`). Just as importantly,
it lets the app hold the **all-files-access** permission and bind the device's
shared storage directly into the container:

```text
--bind=/storage/emulated/0:/sdcard
--bind=/storage:/storage
```

Because these are bind mounts (not copies), Android and the Linux container see
the same real files and changes are reflected in real time. This is the key
difference from CCFA's Google Play build, which cannot hold all-files access and
instead uses SAF mirror **copy** sync into the app-private `/workspace/phone/`.

> **Upstream CCFA (Google Play build)**: raises `targetSdk` to 36, drops the
> `MANAGE_EXTERNAL_STORAGE` permission, and replaces the direct bind mount with
> SAF mirror sync to satisfy Play's scoped-storage and restricted-permission
> policies. monaka reverses those two constraints for personal, sideload use.

## AI-agent policy

CCFA is vendor-neutral. It does not bundle or automatically install proprietary AI-agent CLIs and does not broker vendor authentication. Users can manually install terminal-based tools inside the Linux environment by following each provider's current official documentation.

## Distribution and licenses

Distribution builds generate `assets/legal/` containing required license texts, NOTICE files, and corresponding source for bundled GPL/LGPL native components. See `THIRD_PARTY_NOTICES.md`, `docs/DISTRIBUTION.md`, and `docs/PROOT-SOURCE-OFFER.md`.
