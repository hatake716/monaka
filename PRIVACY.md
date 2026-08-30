# 裏CCFA privacy disclosure

Effective date: 2026-08-30
Application: 裏CCFA (全ファイルアクセス版, package `io.github.hatake716.ccfa.ura`)
Distribution: GitHub / sideload only（**not published on Google Play**）
Derived from: [CCFA](https://github.com/hatake716/CCFA)

裏CCFA itself does not operate an analytics, telemetry, advertising, account, or cloud-sync server. 裏CCFA does not collect, transmit, sell, or share personal data with the developer or any third party. All processing is local to the device.

## All-files access (key difference from CCFA)

裏CCFA requests Android's **all-files access** permission (`MANAGE_EXTERNAL_STORAGE` on Android 11+, or `READ`/`WRITE_EXTERNAL_STORAGE` with `requestLegacyExternalStorage` on Android 10 and below). This is a powerful permission and is the reason 裏CCFA is **sideload-only and not distributed on Google Play**.

When the permission is granted, 裏CCFA binds the device's shared storage directly into the Linux container at launch:

- `/storage/emulated/0` (internal shared storage) → Linux `/sdcard`
- `/storage` (SD cards and other secondary volumes) → Linux `/storage`

These are **bind mounts, not copies**: the Linux container and Android see the same real files, so changes are reflected in real time and no data is duplicated. Programs the user runs inside the Linux container (for example Claude Code) can therefore read and write the user's shared-storage files while the permission is granted.

"All files" here means the **shared storage the user can normally read/write** (internal storage + SD cards). By Android design, no app can access another app's private data directories (`/data/data/<pkg>` or files under `/Android/data/<pkg>`) without root, and 裏CCFA does not access those either.

The permission can be revoked by the user at any time from the system "all files access" settings screen (Android 11+) or app permissions (Android 10 and below). When revoked, the bind mount is not applied on the next launch.

## Other data 裏CCFA accesses locally

- App-private Linux container files and `/workspace` (inside `Context.filesDir`).
- Local terminal input/output needed to operate the in-app PTY.

## Network access performed by 裏CCFA

裏CCFA uses network access only for Linux-environment setup started by the user: downloading the Linux Base image from its upstream provider (Canonical's `cdimage.ubuntu.com`) and installing ordinary Linux packages the user requests (for example via `apt`).

裏CCFA does **not** automatically download, install, repair, log in to, or authenticate a proprietary third-party AI CLI. It does not obtain or proxy a third-party provider's OAuth token, API key, subscription credential, account entitlement, or rate limit.

## Third-party software installed by the user

If the user manually installs a third-party AI CLI or other networked program inside the 裏CCFA Linux environment, that software may communicate directly with its own provider. Such traffic is governed by that third party's terms, privacy policy, and other applicable conditions. Because 裏CCFA grants all-files access, such software can also read and write the user's shared storage; the user is responsible for what they run.

## Local credentials

Credentials created or stored by software the user manually installs remain inside the Linux filesystem (or shared storage, if that software writes there) unless that software itself transmits or exports them. 裏CCFA does not intentionally inspect, upload, sell, or broker those credentials.

## Data deletion

- Removing a Linux container deletes that container rootfs.
- `/workspace` is app-private and is deleted when the app is uninstalled.
- Files on the device's shared storage live outside the app and remain under the user's control; 裏CCFA changes them only when software the user runs inside the container writes to `/sdcard` or `/storage`.

## Children

裏CCFA does not collect personal data from any user, including children.

## Changes and contact

- Developer: hatake716 (individual developer)
- Contact: https://github.com/hatake716/-CCFA/issues

## Distribution note

裏CCFA is a personal-use, sideload-only build. A distributor that modifies 裏CCFA to add analytics, crash reporting, advertising, cloud synchronization, authentication, telemetry, or credential brokering must update this disclosure before distribution.
