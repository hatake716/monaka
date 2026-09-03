# Release status — monaka

monaka（全ファイルアクセス版 / Claude Code 自動導入）は
[CCFA](https://github.com/hatake716/CCFA) の派生で、**sideload 専用**（Google Play 非公開）です。

## v1.2.0（最新）

- Branch: `monaka`
- Version: `1.2.0`（`versionCode` 4）
- APK: `monaka-v1.2.0-debug.apk`

### v1.1.1 からの変更点

- **補助キー（ESC / CTRL / TAB など 14 個）を常に 1 段に配置**。従来は縦画面で
  7 キー × 2 段だったものを 1 行にまとめ、幅に収まらない分は横スクロールで選ぶ。
  2 段ぶんの高さをターミナルに回すのが目的で、縦画面で約 47dp
  （フォント 15dp で約 2.6 行）増える。横画面は従来どおり等分割で全キーを一望できる。
- 横スクロール時のキー幅を 74dp → 52dp に変更（一度に見えるキーが約 5 個から
  約 7 個に増え、スクロール量も減る）。端末のフォントサイズ設定が大きい場合は
  ラベルの実測幅から自動で広げ、「ENTER」等が省略されないようにした。

---

## v1.1.1

- Branch: `monaka`
- Version: `1.1.1`（`versionCode` 3）
- APK: `monaka-v1.1.1-debug.apk`

### v1.1.0 からの変更点

- **アプリ起動時にエージェントターミナルが開かないことがある不具合を修正**。
  v1.1.0 で遷移済みフラグをプロセス単位にした際に壊れており、プロセスが生きたまま
  アプリを開き直すとメニューが表示されていた。
  判定を intent の種類に依存しない方式へ変更（Android はアイコンのタップと
  タスクスイッチャーからの復帰のどちらでも ACTION_MAIN + CATEGORY_LAUNCHER を
  配送しうるうえ、最前面なら onNewIntent 自体が来ないこともあるため）。
  代わりに『ユーザーがメニューを見たいと示したか』を状態として持ち優先評価する。
  - 起動時: ターミナルが開く（コンテナ未作成ならメニュー）
  - ターミナルで「戻る」: メニューを表示し、その画面にいる間は自動遷移しない
  - 回転・メモリ回収からの復帰: メニューのまま（意思を復元）
  - タスクを消して起動し直し: ターミナル
- 初回セットアップ完了時の直接遷移でも `jumpedToTerminal` を立てるようにした
  （二重にターミナルが開く防壁が片方しか効いていなかった）。

---

## v1.1.0

- Branch: `monaka`
- Version: `1.1.0`（`versionCode` 2）
- applicationId: `io.github.hatake716.monaka`（CCFA と共存可能）
- APK: `monaka-v1.1.0-debug.apk`
- Build workflow: `.github/workflows/publish-release.yml`（`v*` タグ push で起動）

### v1.0.0-monaka からの変更点

**バックグラウンド実行の維持（不具合修正）**

- PTY セッションの所有者を Activity から新設の `TerminalSessionService`
  （フォアグラウンドサービス）へ移した。従来はアプリが背面に回るとプロセスが
  cached へ落ち、Doze・phantom process killer・メモリ回収のいずれかで
  Linux 側の処理が停止していた。
- 画面が無い間の PTY 出力もサービスが受け取り、ターミナル履歴に残る。
- 実行中は `PARTIAL_WAKE_LOCK` を保持する。
- ターミナルを「戻る」で閉じてもセッションは終了しない。通知タップで復帰、
  通知の「停止」またはメイン画面の「実行中のセッションを停止」で明示的に終了。
- コマンドを伴う起動（Claude Code のインストール等）は、既存セッションへ
  復帰させず必ず新しいシェルで実行する（コマンドが黙って実行されない不具合を防ぐ）。
- メイン画面から「電池の最適化」の除外設定へ誘導する導線を追加。
- 追加権限: `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` /
  `POST_NOTIFICATIONS` / `WAKE_LOCK`。

**横画面のレイアウト最適化**

- 補助キーを横画面では 14 個すべて 1 段に等分割（縦画面は従来どおり 7 個 × 2 段）。
  幅が足りない場合（分割画面など）は自動的に 2 段 + 横スクロールへ戻す。
- 上部メニューバーを薄型化（`Button` 既定の 48dp → 32〜34dp の帯）。
- 回転時は `onConfigurationChanged` でヘッダーと補助キーバーのみ差し替え、
  `TerminalView` は作り直さない（セッションと表示内容を保つ）。
- Pixel 10a の横画面で約 92dp（画面高の 22%・フォント 15dp で約 5 行）を回収。

**新機能**

- ターミナル履歴（`TerminalHistoryManager`）: トランスクリプトを最大 100 件保存し、
  左端スワイプまたはヘッダーの「履歴」から閲覧・再開。長押しで名前変更・削除。
- UI を Claude デスクトップアプリのライトモード風に刷新
  （温かいオフホワイト #F5F4EF × クレイ/小豆 #C15F3C、`MonakaTheme`）。
- アプリ起動時に、セットアップ済みならメニューを介さず直接ターミナルを開く。
- ランチャーアイコンを白背景の抽象的な最中（顔なし）に刷新。

---

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
- UI 配色を変更し、ランチャーアイコンを最中（もなか）モチーフに再デザイン。
  （配色はその後 v1.1.0 で Claude ライトモード風へ刷新。下記参照）
- Google Play 専用ファイル（`docs/PLAY-RELEASE.md`、`store-assets/`、
  `play-release.yml`、公開サイト HTML）を削除。

### 公開手順

1. `app/build.gradle.kts` の `versionName` / `versionCode` を確認する。
2. `monaka` ブランチをリモートへ push する。
3. リリースする場合は `v1.1.0` 等のタグを push すると `Publish Release`
   ワークフローが APK をビルド・添付する（ランタイム/ライセンスは CI が準備）。
   タグ名から `v` を除いた文字列が `versionName` と一致している必要がある。
   GitHub Actions が使えない場合は、`scripts/prepare-*.sh` を実行してから
   `./gradlew :app:assembleDebug` でローカルビルドし、生成 APK を
   `gh release create` で添付する。

APK は sideload（提供元不明アプリの許可）で導入する。arm64-v8a / Android 8.0+。
