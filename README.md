<div align="center">

# 裏CCFA — 全ファイルアクセス版

**Androidスマートフォン上のユーザー権限で扱える全ファイルを、Linux環境（Claude Code等）から直接読み書き。**

[CCFA](https://github.com/hatake716/CCFA) の派生。Google Play 版CCFAが Play ポリシー対応のために制限していた「全ファイルアクセス」を復活させ、共有ストレージ全体を Linux コンテナへ**リアルタイムに**バインドマウントする **sideload 専用**ビルドです。

![Version](https://img.shields.io/badge/version-v1.0.0--ura-42A7C9)
&nbsp;![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84)
&nbsp;![ABI](https://img.shields.io/badge/ABI-arm64--v8a-555)
&nbsp;![Distribution](https://img.shields.io/badge/配布-GitHub%20%2F%20sideload%20専用-E07A5F)

</div>

> [!WARNING]
> 裏CCFA は **Google Play には公開しません**。「すべてのファイルへのアクセス（MANAGE_EXTERNAL_STORAGE）」という強力な権限を使用し、端末の共有ストレージ全体を Linux 側へ晒します。個人利用・自己責任での使用を前提とします。

---

## CCFA との違い

裏CCFA は CCFA のアーキテクチャ（アプリ内蔵 PRoot + Ubuntu rootfs + 日本語IME対応 PTY ターミナル）をそのまま引き継ぎ、**ストレージ共有の方式だけ**を置き換えたものです。

| 項目 | CCFA（Google Play 版） | 裏CCFA（本ブランチ） |
|---|---|---|
| 配布 | Google Play + sideload | **GitHub / sideload のみ** |
| targetSdk | 36 | **29**（Play 要件に縛られない） |
| ストレージ権限 | なし（scoped storage） | **MANAGE_EXTERNAL_STORAGE（全ファイルアクセス）** |
| Android 側ファイルの共有 | SAF で選んだフォルダを `/workspace/phone/` へ**手動コピー同期** | 共有ストレージ全体を `/sdcard` へ**直接 bind mount** |
| 反映のタイミング | 「今すぐ同期」ボタンで都度コピー | **リアルタイム**（同じ実ファイルを両側が参照） |
| 容量 | コピーのため二重に消費 | 二重消費なし |
| UI 配色 | 暖色ライト（クリーム × テラコッタ） | **寒色ダーク（ネイビー × シアン）** = CCFA の反対色 |
| applicationId | `io.github.hatake716.ccfa` | `io.github.hatake716.ccfa.ura`（**共存可能**） |

---

## 仕組み — リアルタイム同期の実体

CCFA（Play 版）は scoped storage の制約から、PRoot が `/storage/emulated/0/...` を直接 open できず、SAF 経由でアプリ専用領域へ**ファイルをコピー**していました（同期＝コピー往復）。

裏CCFA は全ファイルアクセス権限を持つため、PRoot 起動時に共有ストレージを直接バインドします（[`EmbeddedRuntimeManager.sharedStorageBindArgs`](app/src/main/java/io/github/hatake716/claudecodeandroid/EmbeddedRuntimeManager.kt)）:

```
--bind=/storage/emulated/0:/sdcard     # 内部ストレージ全体 → Linux 側 /sdcard
--bind=/storage:/storage               # SD カード等のセカンダリボリューム
```

bind mount は**同じ実ファイルを両側が参照する**ため、Android 側での変更も Linux 側での変更も即座に相手へ反映されます（コピーではないので「同期」の待ち時間も容量の二重消費もありません）。これが要件の「全ファイルをリアルタイム同期」です。

### アクセス範囲

「全ファイル」とは **ユーザー権限で読み書きできる共有ストレージ全体**（内部ストレージ + SD カード）を指します。Android の仕様上、root なしでは他アプリの専用データ領域（`/data/data/<pkg>` や `/Android/data/<pkg>` 配下）はどのアプリからもアクセスできません。裏CCFA もそこは触れません。

---

## 権限の許可 / 許可しない

メイン画面「スマートフォンストレージ」→「全ファイルアクセス権限を設定」から、[StorageSettingsActivity](app/src/main/java/io/github/hatake716/claudecodeandroid/StorageSettingsActivity.kt) の許可トグルへ進みます。

- **Android 11+**: システムの「すべてのファイルへのアクセス」画面で裏CCFAをオンにします。取り消しも同じ画面から。
- **Android 10 以下**: 実行時パーミッション（READ/WRITE_EXTERNAL_STORAGE + `requestLegacyExternalStorage`）で許可します。

許可すると次回のターミナル起動から `/sdcard` がマウントされ、Claude Code 等のツールから端末全ファイルを操作できます。

---

## インストール

裏CCFA は sideload 専用です。GitHub Releases の APK を端末に導入してください（提供元不明のアプリのインストールを許可する必要があります）。

- 対応: **arm64-v8a / Android 8.0（API 26）以上**
- CCFA と applicationId が異なるため、CCFA と**同一端末に共存**できます。

---

## 使い方（Claude Code を例に）

1. 初回セットアップで Ubuntu ベースの Linux 環境を構築（メイン画面「初期Linux環境を作成」）。
2. 「スマートフォンストレージ」で全ファイルアクセスを許可。
3. ターミナルを開き、公式手順に従って Claude Code を導入・認証（各提供元の条件を確認のうえユーザー自身が実施）。
4. `cd /sdcard` で端末の全ファイルへ。Claude Code から読み書き・編集ができます。

> [!NOTE]
> 裏CCFA は特定ベンダーの AI CLI を同梱・自動インストール・自動ログインしません。Claude Code 等の導入・認証はユーザー自身が行ってください（[Anthropic の Legal / 商標条件](https://code.claude.com/docs/en/legal-and-compliance)に従うこと）。

---

## ソースからビルド

```bash
# PRoot ランタイムと配布用ライセンス/対応ソースを準備（CI と同じ）
bash scripts/prepare-termux-android-proot.sh
bash scripts/prepare-distribution-legal.sh
# デバッグ APK をビルド
./gradlew :app:assembleDebug
```

Gradle 9.5.0 / AGP 9.3.0（Kotlin 組み込み）/ JDK 17 / compileSdk 36 が必要です。

---

## 由来・ライセンス・非提携

裏CCFA は [CCFA](https://github.com/hatake716/CCFA)（Apache-2.0）の派生であり、同一のライセンス・第三者コンポーネント（PRoot / libandroid-shmem / talloc / Termux terminal-emulator 等）を引き継ぎます。詳細は [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)、アプリ内「ライセンス・法的情報」を参照してください。

裏CCFA は Anthropic 社および Claude / Claude Code とは提携していません。アプリ名・ロゴに Claude / Anthropic の名称は使用していません。
