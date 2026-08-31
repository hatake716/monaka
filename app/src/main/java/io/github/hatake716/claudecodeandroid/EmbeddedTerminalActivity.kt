package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/** CCFA in-app PTY terminal with a Japanese-IME-friendly Android composer. */
class EmbeddedTerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    companion object {
        const val EXTRA_CONTAINER = "container"
        const val EXTRA_MODE = "mode"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_RESUME_ID = "resume_id"

        private const val PREFS = "terminal-ui"
        private const val PREF_FONT_SIZE_PX = "font-size-px"
        private const val DEFAULT_FONT_DP = 15f
        private const val MIN_FONT_DP = 10f
        private const val MAX_FONT_DP = 28f

        fun intent(
            context: Context,
            container: String,
            mode: EmbeddedRuntimeManager.LaunchMode,
            command: String? = null
        ) = Intent(context, EmbeddedTerminalActivity::class.java).apply {
            putExtra(EXTRA_CONTAINER, container)
            putExtra(EXTRA_MODE, mode.name)
            if (command != null) putExtra(EXTRA_COMMAND, command)
        }

        /**
         * 過去の記録を再開する intent。保存済みログを画面上部に表示し、
         * 同じコンテナで新しいシェル(SHELL)を開いて作業を続ける。
         */
        fun resumeIntent(context: Context, container: String, resumeId: String) =
            Intent(context, EmbeddedTerminalActivity::class.java).apply {
                putExtra(EXTRA_CONTAINER, container)
                putExtra(EXTRA_MODE, EmbeddedRuntimeManager.LaunchMode.SHELL.name)
                putExtra(EXTRA_RESUME_ID, resumeId)
            }
    }

    private lateinit var terminalView: TerminalView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var fontSizeView: TextView
    private lateinit var imeInput: EditText
    private var session: TerminalSession? = null
    private var leaving = false
    private var launchMode = EmbeddedRuntimeManager.LaunchMode.SHELL

    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false
    private var fontSizePx = 0

    // 履歴記録用: このセッションの記録ID・コンテナ名・最新トランスクリプト。
    private var historyId: String = ""
    private var historyContainer: String = ""
    @Volatile private var latestTranscript: String = ""

    // ライトカラースキームを適用済みか（emulator 初期化後に一度だけ適用する）。
    private var terminalColorsApplied = false

    // 履歴サイドペイン(左からスライドするオーバーレイ)。
    private var overlayRoot: FrameLayout? = null
    private var historyOverlay: FrameLayout? = null
    private var historyPanel: LinearLayout? = null
    private var historyListHost: LinearLayout? = null

    // monaka配色（Claude ライトモード風。温かいオフホワイト地 × クレイ/小豆アクセント）
    private val pageColor = Color.rgb(245, 244, 239)
    private val cardColor = Color.rgb(255, 255, 255)
    private val borderColor = Color.rgb(228, 224, 214)
    private val textColor = Color.rgb(38, 36, 32)
    private val mutedColor = Color.rgb(122, 115, 104)
    private val terminalColor = Color.rgb(251, 250, 247)
    private val terminalText = Color.rgb(38, 36, 32)
    private val accent = Color.rgb(193, 95, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = terminalColor
        window.navigationBarColor = pageColor
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fontSizePx = loadFontSizePx()
        // edge-to-edge のインセットはメインコンテンツ(root)側で処理する(buildView 内)。
        // DrawerLayout 自体に padding を付けるとドロワーがずれるため。
        setContentView(buildView())
        startRequestedSession()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // edge-to-edge のインセット帯（ステータスバー裏・IME/ナビバー裏）に
            // 見える色。ヘッダーとコンポーザーの背景に合わせてページ色にする。
            setBackgroundColor(pageColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(pageColor)
        }

        val backButton = Button(this).apply {
            text = "← 戻る"
            isAllCaps = false
            textSize = 13f
            setTextColor(textColor)
            minWidth = dp(76)
            setOnClickListener { leaveTerminal() }
        }
        header.addView(
            backButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 戻るの右に「履歴」ボタン。左端スワイプが出しづらいための固定導線。
        val historyButton = Button(this).apply {
            text = "履歴"
            isAllCaps = false
            textSize = 13f
            setTextColor(textColor)
            minWidth = dp(56)
            setOnClickListener { openHistoryDrawer() }
        }
        header.addView(
            historyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // コンテナ名表示は廃止。右側のフォントサイズ/A-/A+ を右端へ寄せるための
        // 伸縮スペーサー(weight=1・幅0)。高さは 0 に固定する。
        // (素の View に WRAP_CONTENT を与えると縦に全高まで広がり、ヘッダーが
        //  画面全体を占有してターミナルが隠れる不具合が出るため。)
        // titleView/statusView は他所からの代入があるため生成だけ残す(非表示)。
        titleView = TextView(this)
        statusView = TextView(this)
        val spacer = View(this)
        header.addView(
            spacer,
            LinearLayout.LayoutParams(0, 0, 1f)
        )

        fontSizeView = TextView(this).apply {
            textSize = 11f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(fontSizeView)
        header.addView(fontButton("A−") { changeFontSize(false) })
        header.addView(fontButton("A+") { changeFontSize(true) })
        updateFontSizeLabel()

        root.addView(
            header,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@EmbeddedTerminalActivity)
            setTextSize(fontSizePx)
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(terminalColor)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(
            terminalView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(buildExtraKeys())
        root.addView(buildMessageComposer())
        applyInsetsPadding(root, includeIme = true)

        // 履歴サイドペインは、メインUIの上に重ねる FrameLayout オーバーレイ方式。
        // 左端(幅24dp)からの右スワイプで開く。ヘッダーにはボタンを足さない
        // (ボタンを増やすとタイトルの weight 幅が潰れて崩壊するため)。
        overlayRoot = FrameLayout(this).apply {
            addView(
                root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                buildEdgeSwipeCatcher(),
                FrameLayout.LayoutParams(dp(24), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            )
            addView(buildHistoryOverlay())
        }
        return overlayRoot!!
    }

    /** 左端からの右スワイプを検出して履歴サイドペインを開く透明ビュー。 */
    private fun buildEdgeSwipeCatcher(): View {
        val catcher = View(this)
        var downX = 0f
        var downY = 0f
        catcher.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    // 右方向に十分スワイプ(縦ブレは小さめ)なら開く。
                    if (dx > dp(40) && kotlin.math.abs(dy) < dp(80)) openHistoryDrawer()
                    true
                }
                else -> true
            }
        }
        return catcher
    }

    /**
     * 履歴サイドペインのオーバーレイ(半透明の暗幕 + 左からスライドするパネル)を構築する。
     * 既定は非表示。openHistoryDrawer / closeHistoryDrawer で表示を切り替える。
     */
    private fun buildHistoryOverlay(): View {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            // 暗幕。タップで閉じる。
            setBackgroundColor(0x66000000)
            setOnClickListener { closeHistoryDrawer() }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
            setPadding(dp(16), dp(24), dp(16), dp(16))
            // パネル内タップは暗幕に伝播させない。
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = "ターミナル履歴"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
        })
        panel.addView(TextView(this).apply {
            text = "選ぶとログを表示して、このコンテナで再開します。"
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(12))
        })
        historyListHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(historyListHost)
        }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        applyInsetsPadding(panel)

        overlay.addView(
            panel,
            FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        historyPanel = panel
        historyOverlay = overlay
        return overlay
    }

    private fun openHistoryDrawer() {
        renderHistoryList()
        val overlay = historyOverlay ?: return
        val panel = historyPanel ?: return
        overlay.visibility = View.VISIBLE
        // 左からスライドイン。
        panel.translationX = -dp(300).toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
    }

    private fun closeHistoryDrawer() {
        val overlay = historyOverlay ?: return
        val panel = historyPanel ?: return
        panel.animate().translationX(-dp(300).toFloat()).setDuration(160)
            .withEndAction { overlay.visibility = View.GONE }.start()
    }

    private fun isHistoryDrawerOpen(): Boolean = historyOverlay?.visibility == View.VISIBLE

    /** ドロワー内に履歴一覧を新しい順で描画する。 */
    private fun renderHistoryList() {
        val host = historyListHost ?: return
        host.removeAllViews()
        val records = TerminalHistoryManager.load(this)
        if (records.isEmpty()) {
            host.addView(TextView(this).apply {
                text = "まだ履歴はありません。"
                textSize = 13f
                setTextColor(mutedColor)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        records.forEachIndexed { index, r ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(cardColor, borderColor, 12)
                setOnClickListener { onHistoryPicked(r) }
                setOnLongClickListener { onHistoryLongPressed(r); true }
                isClickable = true
                isLongClickable = true
            }
            item.addView(TextView(this).apply {
                text = r.displayName()
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
            })
            if (r.preview.isNotBlank()) {
                item.addView(TextView(this).apply {
                    text = r.preview
                    textSize = 11.5f
                    setTextColor(mutedColor)
                    maxLines = 2
                    setPadding(0, dp(3), 0, 0)
                })
            }
            host.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(8) }
            )
        }
    }

    /** サイドペインで履歴を選んだとき。ログを表示し、そのコンテナで再開する。 */
    private fun onHistoryPicked(r: TerminalHistoryManager.Record) {
        closeHistoryDrawer()
        if (r.container !in EmbeddedRuntimeManager.listContainers(this)) {
            Toast.makeText(this, "コンテナ「${r.container}」は存在しません", Toast.LENGTH_LONG).show()
            return
        }
        // 現在のセッションを保存してから、選んだ履歴で新しいターミナルを開き直す。
        saveHistory()
        startActivity(EmbeddedTerminalActivity.resumeIntent(this, r.container, r.id))
        finish()
    }

    /** サイドペインで履歴を長押ししたとき。名前変更 / 削除を選べる。 */
    private fun onHistoryLongPressed(r: TerminalHistoryManager.Record) {
        AlertDialog.Builder(this)
            .setTitle(r.displayName())
            .setItems(arrayOf("名前を変更", "削除")) { _, which ->
                when (which) {
                    0 -> renameHistoryDialog(r)
                    1 -> confirmDeleteHistory(r)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 履歴に名前を付ける/変更するダイアログ。 */
    private fun renameHistoryDialog(r: TerminalHistoryManager.Record) {
        val input = EditText(this).apply {
            setText(r.name)
            hint = "名前(空にすると日時に戻ります)"
            setSingleLine(true)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("履歴の名前")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                TerminalHistoryManager.rename(this, r.id, input.text.toString())
                renderHistoryList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 履歴の削除確認。 */
    private fun confirmDeleteHistory(r: TerminalHistoryManager.Record) {
        AlertDialog.Builder(this)
            .setTitle("履歴を削除")
            .setMessage("「${r.displayName()}」の記録を削除します。よろしいですか？")
            .setPositiveButton("削除") { _, _ ->
                TerminalHistoryManager.delete(this, r.id)
                renderHistoryList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun fontButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = dp(48)
        minHeight = dp(38)
        setPadding(dp(5), 0, dp(5), 0)
        setTextColor(textColor)
        setOnClickListener {
            action()
            terminalView.requestFocus()
        }
    }

    private fun buildMessageComposer(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(pageColor)
        }

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(cardColor, borderColor, 20)
        }

        imeInput = EditText(this).apply {
            hint = "メッセージ / コマンドを入力…"
            textSize = 16f
            setTextColor(textColor)
            setHintTextColor(Color.rgb(150, 143, 132))
            background = null
            gravity = Gravity.TOP or Gravity.START
            minLines = 1
            maxLines = 4
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, dp(6), dp(8), dp(5))
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    enterPressed
                ) {
                    sendComposerMessage()
                    true
                } else {
                    false
                }
            }
        }
        composer.addView(
            imeInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val sendButton = TextView(this).apply {
            text = "↑"
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, accent, 16)
            setOnClickListener { sendComposerMessage() }
            contentDescription = "送信"
        }
        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) }
        )
        outer.addView(
            composer,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return outer
    }

    private fun updateComposerHint() {
        if (!::imeInput.isInitialized) return
        imeInput.hint = when (launchMode) {
            EmbeddedRuntimeManager.LaunchMode.SHELL -> "メッセージ / コマンドを入力…"
            EmbeddedRuntimeManager.LaunchMode.SETUP -> "メッセージ / コマンドを入力…"
            EmbeddedRuntimeManager.LaunchMode.COMMAND -> "セットアップターミナルへ入力…"
        }
    }

    private fun sendComposerMessage() {
        if (!::imeInput.isInitialized) return
        val editable = imeInput.text
        BaseInputConnection.removeComposingSpans(editable)
        val text = editable.toString()
        if (text.isBlank()) return

        send(text)
        send("\r")
        editable.clear()
        imeInput.requestFocus()
        showComposerKeyboard()
    }

    private fun buildExtraKeys(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            setBackgroundColor(Color.rgb(239, 237, 230))
        }

        outer.addView(keyRow(listOf(
            KeySpec("ESC", action = { send("\u001b") }),
            KeySpec("CTRL", modifier = ModifierKey.CTRL),
            KeySpec("ALT", modifier = ModifierKey.ALT),
            KeySpec("TAB", action = { send("\t") }),
            KeySpec("↑", action = { send("\u001b[A") }),
            KeySpec("HOME", action = { send("\u001b[H") }),
            KeySpec("END", action = { send("\u001b[F") })
        )))
        outer.addView(keyRow(listOf(
            KeySpec("PGUP", action = { send("\u001b[5~") }),
            KeySpec("←", action = { send("\u001b[D") }),
            KeySpec("↓", action = { send("\u001b[B") }),
            KeySpec("→", action = { send("\u001b[C") }),
            KeySpec("PGDN", action = { send("\u001b[6~") }),
            KeySpec("BKSP", action = { send("\u007f") }),
            KeySpec("ENTER", action = { send("\r") })
        )))
        return outer
    }

    private enum class ModifierKey { CTRL, ALT, SHIFT }

    private data class KeySpec(
        val label: String,
        val action: (() -> Unit)? = null,
        val modifier: ModifierKey? = null
    )

    private fun keyRow(specs: List<KeySpec>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        specs.forEach { spec ->
            val button = Button(this).apply {
                text = spec.label
                isAllCaps = false
                textSize = 12f
                setTextColor(terminalText)
                setBackgroundColor(Color.rgb(255, 255, 255))
                minWidth = dp(66)
                minHeight = dp(42)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    if (spec.modifier != null) toggleModifier(spec.modifier, this)
                    else {
                        spec.action?.invoke()
                        terminalView.requestFocus()
                    }
                }
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(dp(74), dp(46)).apply { marginEnd = dp(4) }
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun toggleModifier(key: ModifierKey, button: Button) {
        val enabled = when (key) {
            ModifierKey.CTRL -> (!ctrlPressed).also { ctrlPressed = it }
            ModifierKey.ALT -> (!altPressed).also { altPressed = it }
            ModifierKey.SHIFT -> (!shiftPressed).also { shiftPressed = it }
        }
        button.setBackgroundColor(if (enabled) accent else Color.rgb(255, 255, 255))
        terminalView.requestFocus()
    }

    private fun startRequestedSession() {
        val container = intent.getStringExtra(EXTRA_CONTAINER)
            ?: EmbeddedRuntimeManager.activeContainer(this)
        if (container == null) {
            statusView.text = "No Linux container is installed."
            Toast.makeText(this, "先にLinuxコンテナを作成してください", Toast.LENGTH_LONG).show()
            return
        }

        launchMode = runCatching {
            EmbeddedRuntimeManager.LaunchMode.valueOf(
                intent.getStringExtra(EXTRA_MODE)
                    ?: EmbeddedRuntimeManager.LaunchMode.SHELL.name
            )
        }.getOrDefault(EmbeddedRuntimeManager.LaunchMode.SHELL)
        updateComposerHint()
        val command = intent.getStringExtra(EXTRA_COMMAND)

        val spec = EmbeddedRuntimeManager.buildLaunchSpec(this, container, launchMode, command)
            .getOrElse {
                statusView.text = it.message ?: "Runtime launch error"
                Toast.makeText(this, statusView.text, Toast.LENGTH_LONG).show()
                return
            }

        val displayTitle = when (launchMode) {
            EmbeddedRuntimeManager.LaunchMode.SHELL -> "$container — monaka Terminal"
            EmbeddedRuntimeManager.LaunchMode.SETUP -> "$container — monaka Terminal"
            EmbeddedRuntimeManager.LaunchMode.COMMAND -> "$container — monaka Task"
        }
        titleView.text = displayTitle
        statusView.text = "アプリ内PTY · $container"

        // 履歴記録の準備。このセッション用の新しい記録IDを採番する。
        historyContainer = container
        historyId = TerminalHistoryManager.newId(System.currentTimeMillis())

        // 再開の場合、過去ログを画面上部の折りたたみビューに表示する。
        val resumeId = intent.getStringExtra(EXTRA_RESUME_ID)
        if (!resumeId.isNullOrBlank()) {
            showResumeLog(resumeId)
        }

        val newSession = TerminalSession(
            spec.executable,
            spec.cwd,
            spec.args,
            spec.env,
            5000,
            this
        )
        newSession.mSessionName = displayTitle
        session = newSession
        terminalView.attachSession(newSession)
        // ライトモードのカラースキームを適用（emulator 初期化後に色を書き換える）。
        applyLightTerminalColors(newSession)

        imeInput.requestFocus()
        imeInput.postDelayed({ showComposerKeyboard() }, 300)
    }

    private fun send(text: String) {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        session?.write(data, 0, data.size)
    }

    private fun showTerminalKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.restartInput(terminalView)
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showComposerKeyboard() {
        if (!::imeInput.isInitialized) return
        imeInput.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.restartInput(imeInput)
        imm.showSoftInput(imeInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun changeFontSize(increase: Boolean) {
        val step = (2f * resources.displayMetrics.density).roundToInt().coerceAtLeast(2)
        val min = (MIN_FONT_DP * resources.displayMetrics.density).roundToInt()
        val max = (MAX_FONT_DP * resources.displayMetrics.density).roundToInt()
        fontSizePx = (fontSizePx + if (increase) step else -step).coerceIn(min, max)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PREF_FONT_SIZE_PX, fontSizePx)
            .apply()
        terminalView.setTextSize(fontSizePx)
        updateFontSizeLabel()
    }

    private fun loadFontSizePx(): Int {
        val density = resources.displayMetrics.density
        val defaultPx = (DEFAULT_FONT_DP * density).roundToInt()
        val min = (MIN_FONT_DP * density).roundToInt()
        val max = (MAX_FONT_DP * density).roundToInt()
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_FONT_SIZE_PX, defaultPx)
            .coerceIn(min, max)
    }

    private fun updateFontSizeLabel() {
        if (!::fontSizeView.isInitialized) return
        val dpSize = fontSizePx / resources.displayMetrics.density
        fontSizeView.text = "%.0f".format(dpSize)
    }

    /** ターミナルにライトモードのカラースキーム（背景/前景/カーソル）を適用する。 */
    /**
     * ターミナルにライトモードのカラースキームを適用する。
     *
     * emulator は attachSession 直後はまだ null で、PTY 起動後(最初の出力時)に
     * 初期化される。かつ初期化時に TerminalColors.reset() が走りデフォルト(黒背景)へ
     * 戻るため、初期化前に書いても無効になる。そこで onTextChanged / onEmulatorSet の
     * たびに呼び、emulator が現れたら一度だけ適用する（terminalColorsApplied で制御）。
     */
    private fun applyLightTerminalColors(session: TerminalSession?) {
        if (terminalColorsApplied) return
        val colors = session?.emulator?.mColors?.mCurrentColors ?: return
        runCatching {
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFFFBFAF7.toInt() // 端末背景(ほぼ白の暖色)
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFF2D2A26.toInt() // 端末文字(濃いグレー)
            colors[TextStyle.COLOR_INDEX_CURSOR] = 0xFFC15F3C.toInt()     // カーソル(クレイ)
            // 明るい背景で読みにくい標準色(明色系)を暗く寄せて可読性を確保する。
            colors[7] = 0xFF5C5955.toInt()    // 通常の白 → 濃いグレー
            colors[15] = 0xFF2D2A26.toInt()   // 明るい白 → ほぼ本文色
            colors[10] = 0xFF3F7A3F.toInt()   // 明るい緑 → 濃い緑(プロンプト等)
            colors[11] = 0xFF8A6D00.toInt()   // 明るい黄 → 濃い山吹
            colors[14] = 0xFF2A6E8F.toInt()   // 明るいシアン → 濃い青
            terminalColorsApplied = true
            terminalView.invalidate()
        }
    }

    /** 再開時、過去ログを画面上部の折りたたみ帯に表示する。 */
    private fun showResumeLog(resumeId: String) {
        val log = TerminalHistoryManager.readTranscript(this, resumeId) ?: return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("前回のログ")
                .setMessage(log.takeLast(6000))
                .setPositiveButton("閉じて続ける", null)
                .show()
        }
    }

    /** このセッションのやりとりを履歴として保存する。 */
    private fun saveHistory() {
        val transcript = latestTranscript
        if (historyId.isBlank() || historyContainer.isBlank()) return
        runCatching {
            TerminalHistoryManager.save(applicationContext, historyId, historyContainer, transcript)
        }
    }

    private fun leaveTerminal() {
        if (leaving) return
        leaving = true
        saveHistory()
        runCatching {
            val token = if (::imeInput.isInitialized && imeInput.hasFocus()) {
                imeInput.windowToken
            } else {
                terminalView.windowToken
            }
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(token, 0)
        }
        session?.finishIfRunning()
        session = null
        // 戻ると必ずメイン画面（メニュー）を表示する。どの経路（起動時の自動遷移や
        // セットアップ後の自動遷移を含む）からターミナルを開いても、既存の MainActivity を
        // 再利用してその上のターミナルを畳む。EXTRA_SHOW_MENU で、メニュー側の
        // 起動時自動ターミナル遷移を抑止し、確実にメニューを表示させる。
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_SHOW_MENU, true)
                }
            )
        }
        finish()
    }

    @Deprecated("Deprecated in Android API; retained for targetSdk 28 compatibility")
    override fun onBackPressed() {
        // 履歴サイドペインが開いていれば、まず閉じる。
        if (isHistoryDrawerOpen()) {
            closeHistoryDrawer()
            return
        }
        leaveTerminal()
    }

    override fun onDestroy() {
        if (isFinishing) {
            // 戻るボタン以外(タスク終了・回転以外の破棄)でも履歴を保存しておく。
            if (!leaving) saveHistory()
            session?.finishIfRunning()
        }
        super.onDestroy()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        // emulator が初期化されたら(最初の出力時)ライト配色を適用する。
        applyLightTerminalColors(changedSession)
        terminalView.onScreenUpdated()
        // 画面が変わるたびに最新トランスクリプト(スクロールバック込み・プレーンテキスト)を
        // 保持しておく。leaveTerminal / onDestroy で履歴として保存する。
        runCatching {
            changedSession.emulator?.screen?.transcriptText?.let { latestTranscript = it }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        statusView.text =
            "Process completed (${finishedSession.exitStatus}) · 戻るボタンでメイン画面へ戻れます"
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) send(text)
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { terminalView.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) { terminalView.invalidate() }
    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1f)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) { showTerminalKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = terminalView.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = ctrlPressed
    override fun readAltKey(): Boolean = altPressed
    override fun readShiftKey(): Boolean = shiftPressed
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession
    ): Boolean = false
    override fun onEmulatorSet() {
        // emulator が用意されたタイミングでもライト配色を適用する。
        applyLightTerminalColors(session)
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * システムバー(+任意でIME)のインセットを padding として反映する。
     * WindowInsets は消費せずそのまま返すため、親のレイアウト測定を壊さない。
     * EdgeToEdge の CONSUMED 版と違い、ヘッダーの weight レイアウトが潰れない。
     */
    private fun applyInsetsPadding(view: View, includeIme: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            val ime = if (includeIme) insets.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
            insets
        }
        view.requestApplyInsets()
    }
}
