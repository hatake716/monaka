# Release status — monaka

monaka（全ファイルアクセス版 / Claude Code 自動導入）は
[CCFA](https://github.com/hatake716/CCFA) の派生で、**sideload 専用**（Google Play 非公開）です。

## v1.0.0-monaka

- Branch: `monaka`
- Version: `1.0.0-monaka`（`versionCode` 1）
- applicationId: `io.github.hatake716.monaka`（CCFA と共存可能）
- APK: `monaka-v1.0.0-monaka-debug.apk`
- Build workflow: `.github/workflows/publish-release.yml`（`v*` タグ push で起動）

### CCFA からの主な変更点

- `MANAGE_EXTERNAL_STORAGE`（全ファイルアクセス）を追加し、共有ストレージ全体を
  Linux 側 `/sdcard`・`/storage` へ**直接 bind mount**（リアルタイム反映）。
- SAF フォルダ選択 + 手動ミラー同期（`StorageShareManager` / `SafSyncManager`）を廃止。
- ストレージ設定画面を「全ファイルアクセス権限の許可 / 許可しない」トグルに置換。
- **Claude Code をワンタップで自動インストールする UI と実装を追加**
  （メイン画面「Claude Code をインストール」→ 公式 `claude.ai/install.sh` を
  コンテナ内で実行）。
- targetSdk を 36 → 29 に変更（Play 要件に縛られない sideload 専用構成）。
- UI 配色を**ダーク地の焦げ茶 × 小豆色（あずき #9C4A3C）**に変更し、
  ランチャーアイコンを最中（もなか）モチーフに再デザイン。
- Google Play 専用ファイル（`docs/PLAY-RELEASE.md`、`store-assets/`、
  `play-release.yml`、公開サイト HTML）を削除。

### 公開手順

1. `app/build.gradle.kts` の `versionName` / `versionCode` を確認する。
2. `monaka` ブランチをリモートへ push する。
3. リリースする場合は `v1.0.0-monaka` 等のタグを push すると `Publish Release`
   ワークフローが APK をビルド・添付する（ランタイム/ライセンスは CI が準備）。

APK は sideload（提供元不明アプリの許可）で導入する。arm64-v8a / Android 8.0+。
