<div align="center">

# monaka 🍡

**Androidスマートフォン上のユーザー権限で扱える全ファイルを、Linux 環境の Claude Code から直接読み書き。**

[CCFA](https://github.com/hatake716/CCFA) の派生。Google Play 版CCFAが Play ポリシー対応のために制限していた「全ファイルアクセス」を復活させ、共有ストレージ全体を Linux コンテナへ**リアルタイムに**バインドマウントし、**Claude Code をワンタップで自動インストール**できる **sideload 専用**ビルドです。

![Version](https://img.shields.io/badge/version-v1.0.0--monaka-9C4A3C)
&nbsp;![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84)
&nbsp;![ABI](https://img.shields.io/badge/ABI-arm64--v8a-555)
&nbsp;![Distribution](https://img.shields.io/badge/配布-sideload%20専用-9C4A3C)

</div>

> [!WARNING]
> monaka は **Google Play には公開しません**。「すべてのファイルへのアクセス（MANAGE_EXTERNAL_STORAGE）」という強力な権限を使用し、端末の共有ストレージ全体を Linux 側へ晒します。個人利用・自己責任での使用を前提とします。

---

## 名前とデザイン

**monaka（最中）** は、Claude Code をスマホ上の Linux（餡）で包む和菓子のようなアプリ、という見立てです。UI は**焦げ茶のダーク地に小豆色（あずき）**を組み合わせた配色で、アイコンも最中種（皮）と餡をモチーフにしています。

---

## CCFA との違い

monaka は CCFA のアーキテクチャ（アプリ内蔵 PRoot + Ubuntu rootfs + 日本語IME対応 PTY ターミナル）をそのまま引き継ぎ、**ストレージ共有方式・Claude Code 導入・配色**を変更したものです。

| 項目 | CCFA（Google Play 版） | monaka |
|---|---|---|
| 配布 | Google Play + sideload | **sideload のみ** |
| targetSdk | 36 | **29**（Play 要件に縛られない） |
| ストレージ権限 | なし（scoped storage） | **MANAGE_EXTERNAL_STORAGE（全ファイルアクセス）** |
| Android 側ファイルの共有 | SAF で選んだフォルダを `/workspace/phone/` へ**手動コピー同期** | 共有ストレージ全体を `/sdcard` へ**直接 bind mount** |
| 反映のタイミング | 「今すぐ同期」ボタンで都度コピー | **リアルタイム**（同じ実ファイルを両側が参照） |
| Claude Code | ユーザーが手動導入 | **ワンタップで自動インストール**（公式 install.sh） |
| UI 配色 | 暖色ライト（クリーム × テラコッタ） | **ダーク × 小豆色**（焦げ茶 × あずき #9C4A3C） |
| applicationId | `io.github.hatake716.ccfa` | `io.github.hatake716.monaka`（**共存可能**） |

---

## 仕組み — リアルタイム同期の実体

CCFA（Play 版）は scoped storage の制約から、PRoot が `/storage/emulated/0/...` を直接 open できず、SAF 経由でアプリ専用領域へ**ファイルをコピー**していました（同期＝コピー往復）。

monaka は全ファイルアクセス権限を持つため、PRoot 起動時に共有ストレージを直接バインドします（[`EmbeddedRuntimeManager.sharedStorageBindArgs`](app/src/main/java/io/github/hatake716/claudecodeandroid/EmbeddedRuntimeManager.kt)）:

```
--bind=/storage/emulated/0:/sdcard     # 内部ストレージ全体 → Linux 側 /sdcard
--bind=/storage:/storage               # SD カード等のセカンダリボリューム
```

bind mount は**同じ実ファイルを両側が参照する**ため、Android 側での変更も Linux 側（Claude Code）での変更も即座に相手へ反映されます（コピーではないので「同期」の待ち時間も容量の二重消費もありません）。

### アクセス範囲

「全ファイル」とは **ユーザー権限で読み書きできる共有ストレージ全体**（内部ストレージ + SD カード）を指します。Android の仕様上、root なしでは他アプリの専用データ領域（`/data/data/<pkg>` や `/Android/data/<pkg>` 配下）はどのアプリからもアクセスできません。monaka もそこは触れません。

---

## 使い方

1. **初期Linux環境を作成** — メイン画面のボタンから Ubuntu ベースの Linux コンテナを構築。
2. **Claude Code をインストール** — 「Claude Code」カードの「Claude Code をインストール」を押すと、コンテナ内で Anthropic 公式インストーラ（`claude.ai/install.sh`）が自動実行されます。
3. **全ファイルアクセスを許可** — 「スマートフォンストレージ」→「全ファイルアクセス権限を設定」から許可（Android 11+ はシステム画面、Android 10 以下は実行時許可）。
4. **ターミナルで `claude`** — `cd /sdcard` で端末の全ファイルへ。初回は画面の案内に従って各自のアカウントで認証してください。

> [!NOTE]
> monaka は Claude Code のインストーラ実行を補助しますが、**認証情報（OAuth トークン・API キー等）は代理取得・保存しません**。認証はターミナル内でユーザー自身が行います。[Anthropic の Legal / 商標条件](https://code.claude.com/docs/en/legal-and-compliance)に従ってご利用ください。

---

## インストール

monaka は sideload 専用です。GitHub Releases の APK を端末に導入してください（提供元不明のアプリのインストールを許可する必要があります）。

- 対応: **arm64-v8a / Android 8.0（API 26）以上**
- CCFA と applicationId が異なるため、CCFA と**同一端末に共存**できます。

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

monaka は [CCFA](https://github.com/hatake716/CCFA)（Apache-2.0）の派生であり、同一のライセンス・第三者コンポーネント（PRoot / libandroid-shmem / talloc / Termux terminal-emulator 等）を引き継ぎます。詳細は [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)、アプリ内「ライセンス・法的情報」を参照してください。

monaka は Anthropic 社および Claude / Claude Code とは提携していません。アプリ名・ロゴに Claude / Anthropic の名称は使用していません（"Claude Code" はアプリ内で対応ツール名として記述的に参照するのみ）。
