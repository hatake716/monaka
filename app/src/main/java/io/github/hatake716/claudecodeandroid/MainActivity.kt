package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Main launcher for CCFA's fully embedded Linux + PTY architecture. */
class MainActivity : Activity() {
    companion object {
        /** ターミナルから戻ったとき、メニューを表示させ自動ターミナル遷移を抑止する。 */
        const val EXTRA_SHOW_MENU = "show_menu"

        /**
         * このプロセスで一度ターミナルへ自動遷移したか。
         * Activity の再生成をまたいで一回きりにするため、プロセス単位で保持する。
         */
        private var processJumpedToTerminal = false

        private const val STATE_USER_WANTS_MENU = "user-wants-menu"

        private const val BASE_DEV_SETUP =
            "apt-get -o Acquire::Retries=3 update && " +
                "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 install -y ca-certificates curl git ripgrep locales"

        // Claude Code 自動インストール。
        // 1. 依存(curl/ca-certificates/git/ripgrep)を用意
        // 2. Anthropic 公式インストーラ(glibc arm64 バイナリ)を guest 内へ導入
        //    - guest rootfs 内に導入し guest パスから起動するため #86798 の無限再起動を回避
        // 3. PATH に ~/.local/bin を通し、次回以降 claude で起動できるようにする
        // 4. バージョンを表示して導入完了を確認
        private const val INSTALL_CLAUDE_CODE =
            "set -e; " +
                "echo '== 依存パッケージを準備しています =='; " +
                "apt-get -o Acquire::Retries=3 update; " +
                "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 install -y ca-certificates curl git ripgrep; " +
                "echo; echo '== Claude Code 公式インストーラを実行しています =='; " +
                "curl -fsSL https://claude.ai/install.sh | bash; " +
                "grep -q '.local/bin' ~/.bashrc 2>/dev/null || " +
                "echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc; " +
                "export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
                "echo; echo '== 導入結果 =='; " +
                "( claude --version && echo && " +
                "echo 'インストール完了。次回からターミナルで claude を実行できます。' && " +
                "echo '初回は claude を起動し、画面の案内に従ってご自身のアカウントで認証してください。' ) || " +
                "( echo 'claude の起動確認に失敗しました。ターミナルで PATH を確認してください。'; exit 1 )"
    }

    // monaka配色: 和菓子・最中(もなか)。焦げ茶のダーク地に小豆色(あずき)のアクセント。
    // page=焦げ茶 #1A1412 / accent=小豆 #9C4A3C / text=最中種の皮クリーム #EDE0D6。
    private val page = Color.rgb(245, 244, 239)
    private val card = Color.rgb(255, 255, 255)
    private val text = Color.rgb(38, 36, 32)
    private val muted = Color.rgb(122, 115, 104)
    private val border = Color.rgb(228, 224, 214)
    private val soft = Color.rgb(238, 236, 229)
    private val accent = Color.rgb(193, 95, 60)
    private val accentDark = Color.rgb(167, 78, 48)
    private val danger = Color.rgb(180, 72, 54)
    private val terminal = Color.rgb(251, 250, 247)

    private lateinit var setupProgress: ProgressBar
    private lateinit var setupOperationText: TextView
    private lateinit var setupButton: Button

    /** バックグラウンド実行中のセッションを止めるボタン（実行中のみ表示）。 */
    private lateinit var stopSessionButton: Button

    /** 電池の最適化からの除外を案内するボタン（除外済みなら文言を変える）。 */
    private lateinit var batteryButton: Button

    // このプロセスで一度ターミナルへ自動遷移したか（起動時ジャンプの一回制御）。
    //
    // インスタンス変数にすると、メモリ逼迫や回転で MainActivity が再生成された際に
    // false へ戻り、onCreate の maybeJumpToTerminal が再び発火してメニューを
    // 見られなくなる。プロセス単位の状態として companion object に持つ。
    private var jumpedToTerminal: Boolean
        get() = processJumpedToTerminal
        set(value) { processJumpedToTerminal = value }

    /**
     * ユーザーが明示的にメニューを開いている状態か。
     *
     * ターミナルの「戻る」で来たときに立て、以後この画面にいる間は自動遷移しない。
     *
     * intent の種類では「アイコンで開き直した」のか「タスクスイッチャーで戻ってきた」
     * のかを区別できない（Android はどちらでも ACTION_MAIN + CATEGORY_LAUNCHER を
     * 配送しうるうえ、Activity が最前面なら onNewIntent 自体が配送されないこともある）。
     * そこで intent には頼らず、『ユーザーがメニューを見たいと示したか』を状態として
     * 持ち、これを優先して評価する。
     *
     * Activity が回収されて作り直されても意思が消えないよう、保存・復元する。
     */
    private var userWantsMenu = false

    /**
     * この画面が一度でも表示されたか（[onStart] で立つ）。
     *
     * onCreate → onStart の順なので、新規作成時の判定では false、
     * 表示後に届く onNewIntent での判定では true になる。これを使って
     * 「表示済みの画面へ intent が来た」場合だけ往復を抑止する。
     *
     * 保存・復元はしない（作り直した画面は「未表示」が正しい）。
     */
    private var hasBeenShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        setContentView(buildView().also { it.applyEdgeToEdgeInsets() })
        EmbeddedRuntimeManager.ensureHostRuntime(this)
        refreshStorageStatus()
        // 画面を作り直した場合（回転・メモリ回収からの復帰）は、ユーザーの意思を復元する。
        // ここで復元しないと「戻る」で開いたメニューが、回収を挟んだ途端に
        // ターミナルへ奪われてしまう。
        // hasBeenShown は復元しない。これは「このインスタンスが一度表示されたか」を
        // 表すもので、画面を作り直した時点では未表示が正しい。復元してしまうと、
        // プロセス単位の jumpedToTerminal と寿命が食い違い、下の往復防止ガードが
        // 「復帰したのにターミナルへ飛べない」方向に誤作動する。
        if (savedInstanceState != null) {
            userWantsMenu = savedInstanceState.getBoolean(STATE_USER_WANTS_MENU, false)
        }

        // セットアップ完了以降（アクティブなコンテナがある）は、アプリ起動時に
        // 直接エージェントターミナルを開く。ターミナルから「戻る」で来たときは
        // EXTRA_SHOW_MENU が付くので飛ばさず、このメニューを表示する。
        maybeJumpToTerminal(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_USER_WANTS_MENU, userWantsMenu)
    }

    override fun onStart() {
        super.onStart()
        hasBeenShown = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop なので、戻る/再タップでの再表示はここに来る。Intent を最新化する。
        setIntent(intent)
        maybeJumpToTerminal(intent)
    }

    override fun onResume() {
        super.onResume()
        // 権限画面から戻ってきたときに全ファイルアクセスの状態表示を更新する。
        refreshStorageStatus()
        // ターミナルから戻ってきたときに、バックグラウンド実行中かどうかを反映する。
        refreshSessionStatus()
    }

    /** バックグラウンド実行中のセッションの有無をボタン表示に反映する。 */
    private fun refreshSessionStatus() {
        if (!::stopSessionButton.isInitialized) return
        val running = TerminalSessionService.isSessionRunning
        stopSessionButton.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            stopSessionButton.text =
                "実行中のセッションを停止（${TerminalSessionService.runningContainer}）"
        }
        if (::batteryButton.isInitialized) {
            batteryButton.text =
                if (isIgnoringBatteryOptimizations()) "✓ バックグラウンド実行: 許可済み"
                else "バックグラウンド実行を許可（電池の最適化）"
        }
    }

    /**
     * 「電池の最適化」の除外設定へ誘導する。
     *
     * フォアグラウンドサービスでプロセスの優先度は確保できるが、端末メーカーの
     * 省電力機能や Doze は別枠で効くため、長時間処理を確実に走らせるには
     * ユーザーによる除外が要る。除外の可否はアプリ側では決められないので、
     * 状態を表示したうえでシステム設定を開くだけにとどめる。
     */
    private fun openBatteryOptimizationSettings() {
        if (isIgnoringBatteryOptimizations()) {
            AlertDialog.Builder(this)
                .setTitle("設定済みです")
                .setMessage(
                    "monaka はすでに電池の最適化から除外されています。" +
                        "バックグラウンドでの実行が維持されます。"
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("設定を開く") { _, _ -> launchBatterySettings() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("バックグラウンド実行を許可")
            .setMessage(
                "設定画面で monaka を「最適化しない / 制限なし」に変更すると、" +
                    "画面消灯中や他アプリ使用中も Linux 側の処理が止まらなくなります。\n\n" +
                    "設定画面を開きますか？"
            )
            .setPositiveButton("設定を開く") { _, _ -> launchBatterySettings() }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** 端末の電池最適化設定画面を開く（機種差があるため段階的にフォールバックする）。 */
    private fun launchBatterySettings() {
        val candidates = listOf(
            // アプリごとの詳細画面（ここから「電池」→「制限なし」を選べる機種が多い）
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
            // 最適化対象アプリの一覧
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
        showError("電池の最適化設定画面を開けませんでした。端末の「設定 > アプリ > monaka」から変更してください。")
    }

    /** 電池の最適化から除外されているか。 */
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /** バックグラウンドで走っているセッションを、確認のうえ停止する。 */
    private fun confirmStopSession() {
        AlertDialog.Builder(this)
            .setTitle("セッションを停止")
            .setMessage(
                "バックグラウンドで実行中の Linux セッションを終了します。" +
                    "実行途中の処理は中断されます。よろしいですか？"
            )
            .setPositiveButton("停止") { _, _ ->
                TerminalSessionService.requestStop(this)
                stopSessionButton.postDelayed({ refreshSessionStatus() }, 500)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun buildView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(36))
            setBackgroundColor(page)
        }

        content.addView(TextView(this).apply {
            text = "monaka"
            textSize = 32f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "全ファイルアクセス · Claude Code 対応 Linux コンテナ"
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(18))
        })

        // 最上段はエージェントターミナル（最重要）。以降は初回セットアップ →
        // Linuxコンテナ → スマートフォンストレージ → Claude Code の順。
        content.addView(terminalCard())
        content.addView(setupCard(), top(dp(14)))
        content.addView(containerCard(), top(dp(14)))
        content.addView(storageCard(), top(dp(14)))
        content.addView(claudeCard(), top(dp(14)))
        content.addView(legalCard(), top(dp(14)))

        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
    }

    private fun terminalCard(): View {
        val section = section("エージェントターミナル", "日本語IMEコンポーザー + PCキー + アプリ内PTY")
        section.addView(primary("エージェントターミナルを開く") {
            launch(EmbeddedRuntimeManager.LaunchMode.SHELL)
        }, top(dp(14)))
        section.addView(button("ターミナル履歴を見る") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }, top(dp(8)))
        // バックグラウンドで走り続けているセッションを、ここからも止められるようにする。
        stopSessionButton = button("実行中のセッションを停止") { confirmStopSession() }.apply {
            visibility = View.GONE
        }
        section.addView(stopSessionButton, top(dp(8)))
        // バックグラウンド実行を確実にするための端末側設定（電池の最適化の除外）へ誘導する。
        batteryButton = button("バックグラウンド実行を許可（電池の最適化）") {
            openBatteryOptimizationSettings()
        }
        section.addView(batteryButton, top(dp(8)))
        section.addView(help(
            "アクティブなLinuxコンテナのシェルをアプリ内ターミナルで開きます。" +
                "claude を導入済みなら、ここで claude を起動して使えます。" +
                "ターミナルを閉じたりホームに戻ったりしても処理は動き続け、" +
                "通知またはこの画面から再開・停止できます。" +
                "過去のやりとりは「ターミナル履歴」から見返して再開できます。"
        ))
        section.addView(help(
            "長時間の処理を確実に走らせ続けるには、「電池の最適化」からmonakaを除外して" +
                "ください。除外しないと、画面消灯後に端末側の省電力機能でLinux側の処理が" +
                "止められることがあります。"
        ))
        section.addView(TextView(this).apply {
            text = "ESC   CTRL   ALT   TAB   ↑   HOME   END\nPGUP   ←   ↓   →   PGDN   BKSP   ENTER"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(38, 36, 32))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(terminal, terminal, 10)
        }, top(dp(10)))
        return section
    }

    private fun claudeCard(): View {
        val section = section("Claude Code", "公式インストーラでLinuxコンテナへ自動導入")
        section.addView(primary("Claude Code をインストール") { installClaudeCode() }, top(dp(14)))
        section.addView(help(
            "「インストール」を押すと、Linuxコンテナ内で Anthropic 公式インストーラ" +
                "（claude.ai/install.sh）を実行し、Claude Code を導入します。導入後は" +
                "「エージェントターミナル」で claude を起動し、画面の案内に従って各自の" +
                "アカウントで認証してください（monaka は認証情報を代理取得・保存しません）。"
        ))
        return section
    }

    private fun containerCard(): View {
        val section = section("Linux コンテナ", "アプリ専用領域にLinux環境を複数保持")
        section.addView(primary("Linux コンテナ管理") {
            startActivity(Intent(this, ContainerManagerActivity::class.java))
        }, top(dp(12)))
        section.addView(help(
            "Linux rootfsはアプリ内部のprivate storageに保存します。外部Termux・PRoot-Distroアプリ・root権限は不要です。"
        ))
        return section
    }

    private lateinit var storageStatusBadge: TextView

    private fun storageCard(): View {
        val section = section("スマートフォンストレージ", "全ユーザーファイルをLinuxへ直接バインド（リアルタイム）")
        storageStatusBadge = badge("")
        section.addView(storageStatusBadge, top(dp(12)))
        section.addView(badge("端末全体  →  Linux側 /sdcard（コピーなし・即時反映）"), top(dp(6)))
        section.addView(primary("全ファイルアクセス権限を設定") { openStorageSharing() }, top(dp(10)))
        section.addView(help(
            "「すべてのファイルへのアクセス」を許可すると、Android のユーザー権限で読み書きできる" +
                "全ファイル（内部ストレージ・SDカード・他アプリの共有領域）が Linux 側の /sdcard に" +
                "そのままマウントされます。SAF のようなコピー同期ではなく、両側が同じ実ファイルを" +
                "参照するため変更は即座に反映されます。"
        ))
        return section
    }

    private fun refreshStorageStatus() {
        if (!::storageStatusBadge.isInitialized) return
        val granted = AllFilesAccessManager.hasAccess(this)
        storageStatusBadge.text =
            if (granted) "✓ 全ファイルアクセス: 許可 — /sdcard をバインド中"
            else "× 全ファイルアクセス: 未許可 — タップして許可"
        storageStatusBadge.setTextColor(if (granted) accent else danger)
    }

    private fun setupCard(): View {
        val section = section("初回セットアップ", "monakaのLinuxコンテナ実行環境をこのAPK内に構築")
        setupButton = primary("初期Linux環境を作成") { createInitialRuntime() }
        section.addView(setupButton, top(dp(8)))

        setupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            progress = 0
        }
        section.addView(setupProgress, top(dp(10)))

        setupOperationText = TextView(this).apply {
            visibility = View.GONE
            text = "未開始"
            textSize = 13f
            setTextColor(this@MainActivity.text)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(soft, border, 10)
        }
        section.addView(setupOperationText, top(dp(8)))
        section.addView(help(
            "Linux Baseイメージは提供元公式サーバーから端末へ直接取得します。" +
                "monakaは第三者AI CLI、第三者アカウント認証、APIキーを配布・代理取得しません。"
        ))
        section.addView(help(
            "各種AIエージェントの実装は、それぞれの公式サイトを確認したうえで、インストール手順に従ってください。"
        ))
        return section
    }

    private fun legalCard(): View {
        val section = section("配布・ライセンス", "第三者ライセンス、対応ソース、商標・非提携情報")
        section.addView(button("ライセンス・法的情報を表示") {
            startActivity(Intent(this, LegalActivity::class.java))
        }, top(dp(12)))
        section.addView(help("配布APKにはGPL/LGPL対象コンポーネントの対応ソースとライセンス本文を同梱します。"))
        return section
    }

    private fun createInitialRuntime() {
        val existing = EmbeddedRuntimeManager.listContainers(this)
        if (existing.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Linux環境は作成済みです")
                .setMessage("現在 ${existing.size} 個のコンテナがあります。管理画面を開きますか？")
                .setPositiveButton("開く") { _, _ ->
                    startActivity(Intent(this, ContainerManagerActivity::class.java))
                }
                .setNegativeButton("閉じる", null)
                .show()
            return
        }

        setupButton.isEnabled = false
        setupProgress.visibility = View.VISIBLE
        setupOperationText.visibility = View.VISIBLE
        setupProgress.isIndeterminate = true
        setupOperationText.text = "Linux環境のセットアップを開始しています…"

        EmbeddedRuntimeManager.installUbuntuContainer(
            this,
            EmbeddedRuntimeManager.DEFAULT_CONTAINER,
            onProgress = { updateInstallProgress(it) },
            onComplete = { result ->
                setupButton.isEnabled = true
                result.onSuccess {
                    setupProgress.isIndeterminate = false
                    setupProgress.progress = 100
                    setupOperationText.text = "Linux環境の準備が完了しました。エージェントターミナルを開きます。"
                    // 基本CLIを流したうえで、Enter 待ちせずそのままエージェントターミナル
                    // （ログインシェル）に入る。戻るボタンでこのメイン画面に戻る。
                    // 自動遷移と同じく「もう飛ばした」扱いにして、二重に開かないようにする。
                    jumpedToTerminal = true
                    startActivity(
                        EmbeddedTerminalActivity.intent(
                            this,
                            EmbeddedRuntimeManager.DEFAULT_CONTAINER,
                            EmbeddedRuntimeManager.LaunchMode.SETUP,
                            BASE_DEV_SETUP
                        )
                    )
                }.onFailure {
                    setupProgress.visibility = View.GONE
                    setupOperationText.visibility = View.VISIBLE
                    setupOperationText.text = "失敗: ${it.message ?: it.javaClass.simpleName}"
                    showError(it.message ?: "Linux環境の作成に失敗しました。")
                }
            }
        )
    }

    private fun updateInstallProgress(value: EmbeddedRuntimeManager.InstallProgress) {
        setupOperationText.visibility = View.VISIBLE
        setupOperationText.text = "${value.phase}: ${value.message}"
        setupProgress.visibility = View.VISIBLE
        if (value.percent == null) {
            setupProgress.isIndeterminate = true
        } else {
            setupProgress.isIndeterminate = false
            setupProgress.progress = value.percent.coerceIn(0, 100)
        }
    }

    /**
     * アプリ起動時、セットアップ完了済み（アクティブコンテナあり）なら直接
     * エージェントターミナルを開く。ターミナルから戻ってきたとき
     * （EXTRA_SHOW_MENU 付き）は飛ばさず、このメニューを表示する。
     *
     * 判定の優先順位:
     *   1. ターミナルからの「戻る」なら、以後この画面に留まる意思として記録し留まる
     *   2. その意思がある間は飛ばない（タスクスイッチャーからの復帰を含む）
     *   3. それ以外は飛ぶ。ランチャー起動は毎回、内部遷移は往復防止のため一度きり
     */
    private fun maybeJumpToTerminal(fromIntent: Intent) {
        // ターミナルから「戻る」で来たとき。ユーザーはこの画面を見に来たので、
        // 以後この画面にいる間は自動遷移しない（飛ばすとメニューに戻れなくなる）。
        if (fromIntent.getBooleanExtra(EXTRA_SHOW_MENU, false)) {
            userWantsMenu = true
            return
        }
        // 明示的にメニューを開いている間は飛ばない。
        // この意思は「戻る」でメニューへ来てから、メニューを閉じる（この Activity が
        // 終わる）まで続く。アプリを離れて戻っただけでは解除しない。
        if (userWantsMenu) return
        // 同じインスタンスで何度も飛ばない（内部遷移からの復帰での往復を防ぐ）。
        if (hasBeenShown && jumpedToTerminal) return
        val active = EmbeddedRuntimeManager.activeContainer(this) ?: return
        jumpedToTerminal = true
        startActivity(
            EmbeddedTerminalActivity.intent(this, active, EmbeddedRuntimeManager.LaunchMode.SHELL)
        )
    }

    private fun launch(mode: EmbeddedRuntimeManager.LaunchMode) {
        val active = EmbeddedRuntimeManager.activeContainer(this)
        if (active == null) {
            AlertDialog.Builder(this)
                .setTitle("Linux環境がありません")
                .setMessage("先に「初期Linux環境を作成」を実行してください。")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        startActivity(EmbeddedTerminalActivity.intent(this, active, mode))
    }

    /**
     * Claude Code を Linux コンテナへ自動インストールする。
     * コンテナが無ければ先に作成を促し、あれば公式インストーラを COMMAND モードの
     * ターミナルで実行する（進捗と結果はターミナルに表示される）。
     */
    private fun installClaudeCode() {
        val active = EmbeddedRuntimeManager.activeContainer(this)
        if (active == null) {
            AlertDialog.Builder(this)
                .setTitle("Linux環境がありません")
                .setMessage("Claude Code を入れる前に、先に「初期Linux環境を作成」を実行してください。")
                .setPositiveButton("環境を作成") { _, _ -> createInitialRuntime() }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Claude Code をインストール")
            .setMessage(
                "Linuxコンテナ「$active」内で Anthropic 公式インストーラ" +
                    "（claude.ai/install.sh）を実行します。ネットワーク通信が発生し、" +
                    "数分かかることがあります。続行しますか？"
            )
            .setPositiveButton("インストール") { _, _ ->
                startActivity(
                    EmbeddedTerminalActivity.intent(
                        this,
                        active,
                        EmbeddedRuntimeManager.LaunchMode.COMMAND,
                        INSTALL_CLAUDE_CODE
                    )
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // monaka（sideload 専用）は全ファイルアクセス権限を使う。
    // 権限の許可/取消と /sdcard バインドの説明画面へ誘導する。
    private fun openStorageSharing() {
        startActivity(Intent(this, StorageSettingsActivity::class.java))
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("エラー")
        .setMessage(message)
        .setPositiveButton("閉じる", null)
        .show()

    private fun section(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(card, border, 18)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 20f
            setTextColor(this@MainActivity.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun help(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(muted)
        setPadding(dp(2), dp(10), dp(2), 0)
    }

    private fun badge(value: String) = TextView(this).apply {
        text = value
        textSize = 12.5f
        setTextColor(this@MainActivity.text)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(soft, border, 10)
    }

    private fun primary(value: String, click: () -> Unit) = styled(value, accent, Color.WHITE, click)
    private fun button(value: String, click: () -> Unit) = styled(value, soft, text, click)

    private fun styled(value: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(bg, if (bg == accent) accentDark else border, 12)
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun full() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun top(v: Int) = full().apply { topMargin = v }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
