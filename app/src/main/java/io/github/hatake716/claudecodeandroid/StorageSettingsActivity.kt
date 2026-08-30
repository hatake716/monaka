package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * スマートフォンストレージ設定（monaka / sideload 専用）。
 *
 * CCFA(Google Play 版)の「SAF でフォルダを選んで手動ミラー同期」を廃止し、
 * 「全ユーザーファイルの読み込み・書き込みを可能にする権限」を許可 / 許可しない
 * だけで切り替える画面へ置き換える。
 *
 * 許可すると [EmbeddedRuntimeManager] が共有ストレージ全体を Linux 側 /sdcard へ
 * 直接 bind mount し、Android 側・Linux 側どちらの変更も即座に反映される
 * （コピー同期ではないため容量も二重にならない）。
 */
class StorageSettingsActivity : Activity() {
    // monaka配色（ダーク地の焦げ茶 × 小豆色アクセント）
    private val page = Color.rgb(26, 20, 18)
    private val card = Color.rgb(38, 28, 25)
    private val text = Color.rgb(237, 224, 214)
    private val muted = Color.rgb(176, 150, 138)
    private val border = Color.rgb(74, 52, 45)
    private val soft = Color.rgb(48, 35, 31)
    private val accent = Color.rgb(156, 74, 60)
    private val accentDark = Color.rgb(122, 59, 46)
    private val danger = Color.rgb(210, 140, 90)

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        setContentView(buildView().also { it.applyEdgeToEdgeInsets(includeIme = true) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // システムの権限画面から戻ったときに状態を更新する。
        refresh()
    }

    private fun buildView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(36))
            setBackgroundColor(page)
        }

        content.addView(Button(this).apply {
            text = "← 戻る"
            isAllCaps = false
            setTextColor(this@StorageSettingsActivity.text)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        })

        content.addView(TextView(this).apply {
            text = "スマートフォンストレージ"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@StorageSettingsActivity.text)
            setPadding(0, dp(14), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "全ユーザーファイルの読み込み・書き込みを Claude Code から行えるようにします。"
            textSize = 13.5f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(16))
        })

        // 現在の権限状態カード
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(card, border, 16)
        }
        statusView = TextView(this).apply {
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusCard.addView(statusView)
        statusCard.addView(TextView(this).apply {
            text = "許可すると、内部ストレージと SD カードの全ファイルが Linux 側 /sdcard に" +
                "そのままマウントされます。SAF のようなコピーではなく同じ実ファイルを両側が" +
                "参照するため、変更はリアルタイムに反映され、容量も二重に消費しません。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(statusCard)

        // 許可 / 許可しない トグル
        toggleButton = primary("") { onToggleClicked() }
        content.addView(toggleButton, top(dp(14)))

        content.addView(TextView(this).apply {
            text = "Linux 側マウント先"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@StorageSettingsActivity.text)
            setPadding(dp(2), dp(20), 0, dp(6))
        })
        content.addView(mountBadge("/sdcard  ←  内部ストレージ (/storage/emulated/0)"))
        content.addView(mountBadge("/storage  ←  SD カード等のセカンダリボリューム"), top(dp(6)))

        content.addView(TextView(this).apply {
            text = "注意: この権限を許可しても、他アプリの専用データ領域（/data/data や " +
                "/Android/data 配下）は Android の仕様上アクセスできません。ここでの" +
                "「全ファイル」は、ユーザー権限で読み書きできる共有ストレージ全体を指します。\n\n" +
                "monaka は Google Play に公開しない sideload 専用構成です。強力な権限のため、" +
                "信頼できる用途にのみ使用してください。"
            textSize = 12.5f
            setTextColor(muted)
            setPadding(dp(2), dp(20), dp(2), 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
    }

    private fun refresh() {
        val granted = AllFilesAccessManager.hasAccess(this)
        if (granted) {
            statusView.text = "✓ 全ファイルアクセス: 許可済み"
            statusView.setTextColor(accent)
            toggleButton.text = "権限設定を開く（取り消す場合）"
            toggleButton.background = rounded(soft, border, 12)
            toggleButton.setTextColor(text)
        } else {
            statusView.text = "× 全ファイルアクセス: 未許可"
            statusView.setTextColor(danger)
            toggleButton.text = "全ファイルアクセスを許可する"
            toggleButton.background = rounded(accent, accentDark, 12)
            toggleButton.setTextColor(Color.WHITE)
        }
    }

    private fun onToggleClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: システムの「すべてのファイルへのアクセス」画面へ誘導する。
            // 許可・取り消しはどちらもこの画面でユーザーが行う。
            val intent = AllFilesAccessManager.requestAccessIntent(this)
            if (intent == null) {
                Toast.makeText(this, "権限設定画面を開けませんでした", Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("設定画面を開きます")
                .setMessage(
                    if (AllFilesAccessManager.hasAccess(this))
                        "「すべてのファイルへのアクセス」を無効にすると、Linux 側 /sdcard の" +
                            "マウントが次回起動から外れます。"
                    else
                        "次の画面で「monaka」の「すべてのファイルへのアクセス」を" +
                            "オンにしてから戻ってください。"
                )
                .setPositiveButton("開く") { _, _ -> runCatching { startActivity(intent) } }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            // Android 10 以下: runtime permission を要求する。
            requestPermissions(AllFilesAccessManager.legacyRuntimePermissions(), REQUEST_LEGACY)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LEGACY) {
            val ok = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (ok) "全ファイルアクセスを許可しました" else "権限が許可されませんでした",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    private fun mountBadge(value: String) = TextView(this).apply {
        text = value
        textSize = 12.5f
        setTextColor(this@StorageSettingsActivity.text)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(soft, border, 10)
    }

    private fun primary(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(accent, accentDark, 12)
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun top(value: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = value }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_LEGACY = 4301
    }
}
