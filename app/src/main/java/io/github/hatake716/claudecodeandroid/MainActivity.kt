package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
        private const val BASE_DEV_SETUP =
            "apt-get -o Acquire::Retries=3 update && " +
                "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 install -y ca-certificates curl git ripgrep locales"
    }

    // 裏CCFA配色: CCFA の暖色ライト(#F4F1EA / テラコッタ #C96442)の反対。
    // 寒色ダークネイビー地 + 補色シアン(#42A7C9)アクセント。
    private val page = Color.rgb(11, 14, 21)
    private val card = Color.rgb(21, 26, 36)
    private val text = Color.rgb(210, 228, 235)
    private val muted = Color.rgb(132, 148, 166)
    private val border = Color.rgb(40, 50, 63)
    private val soft = Color.rgb(28, 36, 48)
    private val accent = Color.rgb(66, 167, 201)
    private val accentDark = Color.rgb(45, 130, 160)
    private val danger = Color.rgb(224, 122, 95)
    private val terminal = Color.rgb(8, 11, 17)

    private lateinit var setupProgress: ProgressBar
    private lateinit var setupOperationText: TextView
    private lateinit var setupButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        setContentView(buildView().also { it.applyEdgeToEdgeInsets() })
        EmbeddedRuntimeManager.ensureHostRuntime(this)
        refreshStorageStatus()
    }

    override fun onResume() {
        super.onResume()
        // 権限画面から戻ってきたときに全ファイルアクセスの状態表示を更新する。
        refreshStorageStatus()
    }

    private fun buildView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(36))
            setBackgroundColor(page)
        }

        content.addView(TextView(this).apply {
            text = "裏CCFA"
            textSize = 32f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "全ファイルアクセス版 · 日本語入力対応Linuxコンテナ"
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(18))
        })

        content.addView(agentCard())
        content.addView(containerCard(), top(dp(14)))
        content.addView(storageCard(), top(dp(14)))
        content.addView(setupCard(), top(dp(14)))
        content.addView(legalCard(), top(dp(14)))

        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
    }

    private fun agentCard(): View {
        val section = section("エージェント環境", "日本語IMEコンポーザー + PCキー + アプリ内PTY")
        section.addView(primary("エージェントターミナルを開く") {
            launch(EmbeddedRuntimeManager.LaunchMode.SHELL)
        }, top(dp(14)))
        section.addView(help(
            "裏CCFA配布版は特定ベンダーのAI CLIを自動インストール・自動ログインしません。" +
                "利用したいCLIはLinuxシェル内で、各提供元の条件を確認したうえでユーザー自身が導入・認証してください。"
        ))
        section.addView(TextView(this).apply {
            text = "ESC   CTRL   ALT   TAB   ↑   HOME   END\nPGUP   ←   ↓   →   PGDN   BKSP   ENTER"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(198, 224, 232))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(terminal, terminal, 10)
        }, top(dp(10)))
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
        val section = section("初回セットアップ", "裏CCFAのLinuxコンテナ実行環境をこのAPK内に構築")
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
                "裏CCFAは第三者AI CLI、第三者アカウント認証、APIキーを配布・代理取得しません。"
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
                    setupOperationText.text = "Linux rootfsとPRootセルフテストが完了しました。基本CLIをセットアップします。"
                    startActivity(
                        EmbeddedTerminalActivity.intent(
                            this,
                            EmbeddedRuntimeManager.DEFAULT_CONTAINER,
                            EmbeddedRuntimeManager.LaunchMode.COMMAND,
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

    // 裏CCFA（sideload 専用）は全ファイルアクセス権限を使う。
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
