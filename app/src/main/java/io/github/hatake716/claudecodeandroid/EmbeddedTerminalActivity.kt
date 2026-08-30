package io.github.hatake716.claudecodeandroid

import android.app.Activity
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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

    // 裏CCFA配色（CCFA 暖色ライトの反対 = 寒色ダーク）
    private val pageColor = Color.rgb(11, 14, 21)
    private val cardColor = Color.rgb(21, 26, 36)
    private val borderColor = Color.rgb(40, 50, 63)
    private val textColor = Color.rgb(210, 228, 235)
    private val mutedColor = Color.rgb(132, 148, 166)
    private val terminalColor = Color.rgb(8, 11, 17)
    private val terminalText = Color.rgb(198, 224, 232)
    private val accent = Color.rgb(66, 167, 201)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = terminalColor
        window.navigationBarColor = pageColor
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fontSizePx = loadFontSizePx()
        setContentView(buildView().also { it.applyEdgeToEdgeInsets(includeIme = true) })
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

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            text = "裏CCFA Terminal"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
        }
        statusView = TextView(this).apply {
            text = "Starting…"
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(0, dp(1), 0, 0)
        }
        headerText.addView(titleView)
        headerText.addView(statusView)
        header.addView(
            headerText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        return root
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
            setHintTextColor(Color.rgb(108, 122, 138))
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
            setBackgroundColor(Color.rgb(14, 18, 26))
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
                setBackgroundColor(Color.rgb(34, 43, 56))
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
        button.setBackgroundColor(if (enabled) accent else Color.rgb(34, 43, 56))
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
            EmbeddedRuntimeManager.LaunchMode.SHELL -> "$container — 裏CCFA Terminal"
            EmbeddedRuntimeManager.LaunchMode.COMMAND -> "$container — 裏CCFA Task"
        }
        titleView.text = displayTitle
        statusView.text = "アプリ内PTY · $container"

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

    private fun leaveTerminal() {
        if (leaving) return
        leaving = true
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
        finish()
    }

    @Deprecated("Deprecated in Android API; retained for targetSdk 28 compatibility")
    override fun onBackPressed() {
        leaveTerminal()
    }

    override fun onDestroy() {
        if (isFinishing) session?.finishIfRunning()
        super.onDestroy()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
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
    override fun onEmulatorSet() = Unit

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
}
