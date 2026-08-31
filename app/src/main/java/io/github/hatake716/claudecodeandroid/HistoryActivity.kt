package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 過去のターミナルのやりとり（記録）を新しい順に一覧し、選んで再開できる画面。
 *
 * 「再開」は、保存済みのログを画面上部に表示しつつ、同じコンテナで新しいシェルを
 * 開いて作業を続ける形（[EmbeddedTerminalActivity] の resume 経路）。
 */
class HistoryActivity : Activity() {
    // Claude ライトモード風の配色（[MonakaTheme]）
    private val page = MonakaTheme.page
    private val card = MonakaTheme.card
    private val text = MonakaTheme.text
    private val muted = MonakaTheme.muted
    private val border = MonakaTheme.border
    private val soft = MonakaTheme.soft
    private val accent = MonakaTheme.accent
    private val accentDark = MonakaTheme.accentDark
    private val danger = MonakaTheme.danger

    private lateinit var listHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        setContentView(buildView().also { it.applyEdgeToEdgeInsets() })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::listHost.isInitialized) render()
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
            setTextColor(this@HistoryActivity.text)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            text = "ターミナル履歴"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@HistoryActivity.text)
            setPadding(0, dp(14), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "過去のやりとりを選ぶと、内容を表示しつつ同じコンテナで作業を再開できます。"
            textSize = 13.5f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(16))
        })

        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listHost)

        content.addView(button("すべての履歴を削除") { confirmClear() }, top(dp(16)))

        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
    }

    private fun render() {
        listHost.removeAllViews()
        val records = TerminalHistoryManager.load(this)
        if (records.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "まだ履歴はありません。ターミナルでの操作を終えて戻ると、ここに保存されます。"
                textSize = 13.5f
                setTextColor(muted)
                setPadding(dp(14), dp(16), dp(14), dp(16))
                background = rounded(soft, border, 12)
            })
            return
        }
        records.forEachIndexed { index, r ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(card, border, 14)
            }
            box.addView(TextView(this).apply {
                text = r.displayName()
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(this@HistoryActivity.text)
            })
            // 自動タイトル(コンテナ名・日時)。ユーザー名がある時だけ補助表示する。
            if (r.name.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = r.title
                    textSize = 11.5f
                    setTextColor(muted)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            if (r.preview.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = r.preview
                    textSize = 12.5f
                    setTextColor(muted)
                    maxLines = 2
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(primary("再開") { resume(r) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) })
            row.addView(button("名前") { renameDialog(r) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) })
            row.addView(button("ログ") { showLog(r) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) })
            row.addView(dangerButton("削除") { confirmDelete(r) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            listHost.addView(box, if (index == 0) top(dp(4)) else top(dp(10)))
        }
    }

    /** 記録に名前を付ける/変更するダイアログ。 */
    private fun renameDialog(r: TerminalHistoryManager.Record) {
        val input = android.widget.EditText(this).apply {
            setText(r.name)
            hint = "名前(空にすると日時に戻ります)"
            setSingleLine(true)
            setSelection(text.length)
        }
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("履歴の名前")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                TerminalHistoryManager.rename(this, r.id, input.text.toString())
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun resume(r: TerminalHistoryManager.Record) {
        // コンテナが存在するか確認してから再開する。
        if (r.container !in EmbeddedRuntimeManager.listContainers(this)) {
            Toast.makeText(this, "コンテナ「${r.container}」は存在しません", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(EmbeddedTerminalActivity.resumeIntent(this, r.container, r.id))
    }

    private fun showLog(r: TerminalHistoryManager.Record) {
        val log = TerminalHistoryManager.readTranscript(this, r.id) ?: "(ログを読み込めませんでした)"
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = log
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(this@HistoryActivity.text)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(r.displayName())
            .setView(scroll)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("このログで再開") { _, _ -> resume(r) }
            .show()
    }

    private fun confirmDelete(r: TerminalHistoryManager.Record) {
        AlertDialog.Builder(this)
            .setTitle("履歴を削除")
            .setMessage("「${r.title}」の記録を削除します。よろしいですか？")
            .setPositiveButton("削除") { _, _ ->
                TerminalHistoryManager.delete(this, r.id)
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmClear() {
        if (TerminalHistoryManager.load(this).isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("すべての履歴を削除")
            .setMessage("保存されているすべてのターミナル履歴を削除します。よろしいですか？")
            .setPositiveButton("すべて削除") { _, _ ->
                TerminalHistoryManager.clear(this)
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- UI ヘルパー（他 Activity と同じ流儀） ----
    private fun primary(value: String, click: () -> Unit) = styled(value, accent, Color.WHITE, click)
    private fun button(value: String, click: () -> Unit) = styled(value, soft, text, click)
    private fun dangerButton(value: String, click: () -> Unit) =
        styled(value, MonakaTheme.dangerSoft, danger, click)

    private fun styled(value: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13.5f
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

    private fun top(value: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = value }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
