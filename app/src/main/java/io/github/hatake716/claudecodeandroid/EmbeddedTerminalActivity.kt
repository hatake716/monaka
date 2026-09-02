package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

        /**
         * 通知タップからの復帰であることを示す。
         *
         * 「実行中のセッションを見に来ただけ」なので、セッションがすでに終了して
         * いた場合に新しいシェルを勝手に立ち上げてはいけない。メイン画面からの
         * 明示的な起動（このフラグが無い）とを区別するために使う。
         */
        const val EXTRA_FROM_NOTIFICATION = "from_notification"

        private const val PREFS = "terminal-ui"
        private const val PREF_FONT_SIZE_PX = "font-size-px"
        private const val PREF_NOTIFICATION_PROMPTED = "notification-prompted"
        private const val DEFAULT_FONT_DP = 15f
        private const val MIN_FONT_DP = 10f
        private const val MAX_FONT_DP = 28f

        // ヘッダー(上部メニューバー)の帯の高さ。ターミナルの表示領域を最大化するため、
        // Button 既定の 48dp より薄くする。ただしこれ以上詰めるとタップしづらくなるため、
        // 横画面でも 32dp を下限とし、幅の方を広めに取って押しやすさを確保する。
        private const val HEADER_HEIGHT_DP = 34
        private const val HEADER_HEIGHT_LAND_DP = 32

        // 補助キーの寸法。縦画面は 7 キー × 2 段、横画面は 14 キーを 1 段に並べる。
        private const val KEY_HEIGHT_DP = 44
        private const val KEY_HEIGHT_LAND_DP = 36
        private const val KEY_WIDTH_DP = 74

        // 等分割時の文字サイズ算出用。最長ラベルは "ENTER"/"PGUP" などの 5 文字、
        // sans-serif の 1 文字幅はフォントサイズのおよそ 0.62 倍。
        private const val KEY_LABEL_CHARS = 5f
        private const val KEY_LABEL_WIDTH_RATIO = 0.62f
        private const val MIN_KEY_TEXT_SP = 9f
        private const val MAX_KEY_TEXT_SP = 12f

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
    private var leaving = false
    private var launchMode = EmbeddedRuntimeManager.LaunchMode.SHELL

    /**
     * PTY セッションの所有者。Activity ではなく [TerminalSessionService] が持つ。
     * こうすることで、ホームに戻る・他アプリへ切り替えるなどでこの Activity が
     * 破棄されても Linux 側の処理は走り続ける。
     */
    private var sessionService: TerminalSessionService? = null
    private var serviceBound = false

    /** サービスへの接続。接続完了時にセッションを起動または復帰させる。 */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TerminalSessionService.LocalBinder)?.service ?: return
            sessionService = service
            service.attachUi(this@EmbeddedTerminalActivity)
            bindOrStartSession(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 参照を手放す前に UI クライアントを外し、破棄済み Activity へ
            // コールバックが配送され続けないようにする。
            sessionService?.attachUi(null)
            sessionService = null
        }
    }

    /** 現在のセッション（サービスが保持しているもの）。 */
    private val session: TerminalSession?
        get() = sessionService?.session

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

    // 回転時にヘッダーと補助キーバーだけを差し替えるための参照。
    // ターミナル本体は作り直さない(セッションの再アタッチや表示内容の消失を避ける)。
    private var contentRoot: LinearLayout? = null
    private var headerView: View? = null
    private var extraKeysView: View? = null

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
        ensureNotificationPermission()
        // セッションはサービスが持つ。サービスへ bind し、接続後に起動/復帰する。
        bindSessionService()
    }

    /**
     * 画面の向きが変わったときに、ヘッダーと補助キーバーだけを作り直す。
     *
     * Manifest の configChanges に orientation|screenSize があるため回転しても
     * Activity は再生成されず、buildView() も呼ばれない。そのままでは横画面用の
     * 1 段キー配置・薄型ヘッダーへ切り替わらないので、ここで該当部分だけ差し替える。
     *
     * ターミナル本体（TerminalView）は作り直さない。作り直すとセッションの
     * 再アタッチが要り、表示内容やスクロール位置が失われるため。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = contentRoot ?: return
        // この処理の間は、渡された最新の Configuration を基準にレイアウトを決める。
        layoutConfig = newConfig
        try {
            rebuildOrientationDependentViews(root)
        } finally {
            layoutConfig = null
        }
    }

    /** ヘッダーと補助キーバーを、現在の向きに合わせて作り直す。 */
    private fun rebuildOrientationDependentViews(root: LinearLayout) {

        // ヘッダーを同じ位置へ差し替える。
        headerView?.let { old ->
            val index = root.indexOfChild(old)
            if (index >= 0) {
                root.removeViewAt(index)
                val header = buildHeader()
                root.addView(
                    header,
                    index,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        headerHeightPx()
                    )
                )
                headerView = header
            }
        }

        // 補助キーバーを、向きに応じた段組みで差し替える。
        extraKeysView?.let { old ->
            val index = root.indexOfChild(old)
            if (index >= 0) {
                root.removeViewAt(index)
                val keys = buildExtraKeys()
                root.addView(keys, index)
                extraKeysView = keys
            }
        }

        // 修飾キーの押下状態はビューを作り直すと表示に反映されないため解除しておく
        // （見た目は非アクティブなのに内部では ON、というズレを防ぐ）。
        ctrlPressed = false
        altPressed = false
        shiftPressed = false

        // 子を差し替えたので、システムバー/IME のインセットを配り直させる。
        // これがないと padding が回転前の値のまま残り、ヘッダーがステータスバーへ
        // 潜り込んだり、IME 表示中にコンポーザーが隠れたりする。
        root.requestApplyInsets()
    }

    /**
     * 通知タップでの復帰や、別セッションの指定起動でここへ来る（singleTop）。
     * 既存の画面を使い回すため、intent を最新化してセッションを繋ぎ直す。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 画面を離れた際に立てた leaving を戻さないと、以降の「戻る」が効かなくなる。
        leaving = false
        sessionService?.let { bindOrStartSession(it) }
    }

    /**
     * セッションを保持するフォアグラウンドサービスを開始し、bind する。
     * startForegroundService してから bind することで、Activity が消えても
     * サービス（＝セッション）が生き残る。
     */
    private fun bindSessionService() {
        val intent = TerminalSessionService.intent(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 通知が無効なら、有効化を促す案内を一度だけ出す。
     *
     * monaka は targetSdk 29 のため、Android 13+ でも POST_NOTIFICATIONS の
     * 実行時ダイアログは表示されない（requestPermissions は no-op になる）。
     * 通知はインストール時に付与扱いだが、ユーザーが設定でオフにしていると
     * バックグラウンド実行中の表示も、そこからの復帰導線も失われる。
     * そこで権限要求ではなく、通知設定画面への導線を案内する。
     *
     * 毎回出すと煩わしいので、案内は一度きり（SharedPreferences に記録）。
     */
    private fun ensureNotificationPermission() {
        if (areNotificationsEnabled()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTIFICATION_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_NOTIFICATION_PROMPTED, true).apply()

        AlertDialog.Builder(this)
            .setTitle("通知が無効です")
            .setMessage(
                "monaka の通知がオフになっています。バックグラウンドで実行中の" +
                    "セッションの表示や、そこからターミナルへ戻る導線が使えません。" +
                    "設定で通知を有効にすることをおすすめします。"
            )
            .setPositiveButton("設定を開く") { _, _ -> openNotificationSettings() }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** このアプリの通知が有効か。 */
    private fun areNotificationsEnabled(): Boolean = runCatching {
        getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    }.getOrDefault(true)

    /** アプリの通知設定画面を開く（開けない端末ではアプリ詳細画面へ）。 */
    private fun openNotificationSettings() {
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
    }

    /** ヘッダー(上部メニューバー)の帯の高さ。横画面ではさらに詰める。 */
    private fun headerHeightPx(): Int =
        dp(if (isLandscape()) HEADER_HEIGHT_LAND_DP else HEADER_HEIGHT_DP)

    /**
     * ヘッダー(上部メニューバー)を組む。
     *
     * ここはターミナルの表示領域を削る一方なので、押せる最小限まで薄くする。
     * Button 既定の最小高さ(48dp)と内部パディングを捨て、[headerHeightPx] の帯に
     * ぴったり収める。回転時にも作り直せるよう関数に切り出してある。
     */
    private fun buildHeader(): View {
        val headerHeight = headerHeightPx()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(pageColor)
        }

        header.addView(
            headerButton("← 戻る") { leaveTerminal() },
            headerButtonParams(dp(64), headerHeight)
        )

        // 戻るの右に「履歴」ボタン。左端スワイプが出しづらいための固定導線。
        header.addView(
            headerButton("履歴") { openHistoryDrawer() },
            headerButtonParams(dp(52), headerHeight)
        )

        // コンテナ名表示は廃止。右側のフォントサイズ/A-/A+ を右端へ寄せるための
        // 伸縮スペーサー(weight=1・幅0)。高さは 0 に固定する。
        // (素の View に WRAP_CONTENT を与えると縦に全高まで広がり、ヘッダーが
        //  画面全体を占有してターミナルが隠れる不具合が出るため。)
        // titleView/statusView は他所からの代入があるため生成だけ残す(非表示)。
        titleView = TextView(this)
        statusView = TextView(this)
        header.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        fontSizeView = TextView(this).apply {
            textSize = 11f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(fontSizeView)
        // 高さを詰めたぶん、幅を広めに取ってタップ面積を確保する。
        header.addView(
            fontButton("A−") { changeFontSize(false) },
            headerButtonParams(dp(48), headerHeight)
        )
        header.addView(
            fontButton("A+") { changeFontSize(true) },
            headerButtonParams(dp(48), headerHeight)
        )
        updateFontSizeLabel()
        return header
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // edge-to-edge のインセット帯（ステータスバー裏・IME/ナビバー裏）に
            // 見える色。ヘッダーとコンポーザーの背景に合わせてページ色にする。
            setBackgroundColor(pageColor)
        }

        val header = buildHeader()
        root.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerHeightPx())
        )
        headerView = header

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

        val extraKeys = buildExtraKeys()
        root.addView(extraKeys)
        extraKeysView = extraKeys
        root.addView(buildMessageComposer())
        applyInsetsPadding(root, includeIme = true)
        contentRoot = root

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
        // 履歴の再開は新しいシェルを開く操作。実行中のセッションがあれば、
        // それを終了させることになるため確認する（バックグラウンドで走らせている
        // claude や長時間処理を、履歴タップで黙って落とさないようにする）。
        val running = sessionService?.runningSession()
        if (running != null) {
            AlertDialog.Builder(this)
                .setTitle("実行中のセッションがあります")
                .setMessage(
                    "「${r.displayName()}」を再開すると、現在バックグラウンドで実行中の" +
                        "セッションは終了します。よろしいですか？"
                )
                .setPositiveButton("終了して再開") { _, _ -> resumeHistory(r) }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        resumeHistory(r)
    }

    /**
     * 選んだ履歴のコンテナで新しいターミナルを開く。
     *
     * ここでは stopSession() を呼ばない。singleTop のため同じインスタンスの
     * onNewIntent に届き、[bindOrStartSession] が EXTRA_RESUME_ID を見て
     * 「既存セッションを畳んでから新規起動」を一箇所で行う。ここで先に
     * stopSession() すると、保留中の stopSelf() が新セッション生成後に効いて
     * 起動直後のセッションを殺してしまう競合になる。
     */
    private fun resumeHistory(r: TerminalHistoryManager.Record) {
        startActivity(EmbeddedTerminalActivity.resumeIntent(this, r.container, r.id))
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
        applyCompactButtonMetrics()
        setTextColor(textColor)
        setOnClickListener {
            action()
            terminalView.requestFocus()
        }
    }

    /**
     * Button 既定の最小サイズ(48dp)と内部パディングを外し、外から与えた寸法ちょうどに
     * 収まるようにする。背景は消さず、押下フィードバック(ripple)だけを残す
     * ——背景を null にすると「押しても反応がない」ように見えてしまうため。
     */
    private fun Button.applyCompactButtonMetrics() {
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        background = borderlessRipple()
    }

    /** 枠のない押下フィードバック(ripple)。取得できない端末では背景なしにする。 */
    private fun borderlessRipple(): android.graphics.drawable.Drawable? = runCatching {
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val typed = theme.obtainStyledAttributes(attrs)
        try {
            typed.getDrawable(0)
        } finally {
            typed.recycle()
        }
    }.getOrNull()

    /**
     * ヘッダー用の薄いボタン。
     *
     * Button は既定で 48dp の最小高さと内部パディングを持ち、そのままだとヘッダーが
     * 厚くなってターミナルの表示領域を削る。最小サイズと背景を外し、外側から与えた
     * 寸法ちょうどに収まるようにする。
     */
    private fun headerButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        applyCompactButtonMetrics()
        setTextColor(textColor)
        setOnClickListener { action() }
    }

    private fun headerButtonParams(width: Int, height: Int) =
        LinearLayout.LayoutParams(width, height)

    /**
     * レイアウト判断に使う Configuration。
     *
     * onConfigurationChanged では引数の newConfig が最も新しい値なので、その処理中だけ
     * これを差し替える。resources.configuration は更新済みであるのが通常だが、
     * マルチウィンドウや折りたたみ端末では古い値を返しうるため、契約どおり newConfig を使う。
     */
    private var layoutConfig: android.content.res.Configuration? = null

    private fun activeConfig(): android.content.res.Configuration =
        layoutConfig ?: resources.configuration

    /** 横画面か。補助キーの段組みとヘッダー高さの切り替えに使う。 */
    private fun isLandscape(): Boolean =
        activeConfig().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

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

    /** 補助キーの並び。前半 7 つが縦画面の 1 段目、後半 7 つが 2 段目になる。 */
    private fun extraKeySpecs(): List<KeySpec> = listOf(
        KeySpec("ESC", action = { send("\u001b") }),
        KeySpec("CTRL", modifier = ModifierKey.CTRL),
        KeySpec("ALT", modifier = ModifierKey.ALT),
        KeySpec("TAB", action = { send("\t") }),
        KeySpec("↑", action = { send("\u001b[A") }),
        KeySpec("HOME", action = { send("\u001b[H") }),
        KeySpec("END", action = { send("\u001b[F") }),
        KeySpec("PGUP", action = { send("\u001b[5~") }),
        KeySpec("←", action = { send("\u001b[D") }),
        KeySpec("↓", action = { send("\u001b[B") }),
        KeySpec("→", action = { send("\u001b[C") }),
        KeySpec("PGDN", action = { send("\u001b[6~") }),
        KeySpec("BKSP", action = { send("\u007f") }),
        KeySpec("ENTER", action = { send("\r") })
    )

    /**
     * 補助キーバーを組む。
     *
     * 横画面では画面高が乏しく、2 段だとターミナルの表示行数を大きく削るため、
     * 14 キーすべてを 1 段に並べる。横幅は十分にあるので、固定幅ではなく
     * weight で等分割して画面幅ちょうどに収める（横スクロール不要）。
     * 縦画面はこれまでどおり 7 キー × 2 段。
     */
    private fun buildExtraKeys(): View {
        val specs = extraKeySpecs()
        val landscape = isLandscape()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) setPadding(dp(2), dp(2), dp(2), dp(2))
            else setPadding(dp(4), dp(4), dp(4), dp(6))
            setBackgroundColor(Color.rgb(239, 237, 230))
        }

        if (landscape && canStretchAllKeys(specs.size)) {
            outer.addView(keyRow(specs, stretch = true))
        } else {
            outer.addView(keyRow(specs.take(7)))
            outer.addView(keyRow(specs.drop(7)))
        }
        return outer
    }

    /**
     * 全キーを 1 段に等分割しても、ラベルが省略されずに収まるか。
     *
     * 横画面でも分割画面などで幅が半分になることがあり、そこまで詰めると
     * 「ENTER」「PGUP」が見切れる。読めなくなるくらいなら 2 段 + 横スクロールの
     * 方がましなので、最小文字サイズで収まらない幅では等分割をやめる。
     */
    private fun canStretchAllKeys(keyCount: Int): Boolean =
        perKeyWidthDp(keyCount) >= MIN_KEY_TEXT_SP * KEY_LABEL_CHARS * KEY_LABEL_WIDTH_RATIO

    /** 等分割したときの 1 キーあたりの幅(dp)。 */
    private fun perKeyWidthDp(keyCount: Int): Float =
        (activeConfig().screenWidthDp.toFloat() - 4f) / keyCount - 2f

    private enum class ModifierKey { CTRL, ALT, SHIFT }

    private data class KeySpec(
        val label: String,
        val action: (() -> Unit)? = null,
        val modifier: ModifierKey? = null
    )

    /**
     * 補助キーの 1 段を組む。
     *
     * [stretch] が true なら各キーを weight で等分割し、行全体を画面幅に収める
     * （横画面用。全キーが一望でき、横スクロールが要らない）。
     * false なら固定幅で並べ、入りきらない分は横スクロールで見せる。
     */
    private fun keyRow(specs: List<KeySpec>, stretch: Boolean = false): View {
        val keyHeight = dp(if (isLandscape()) KEY_HEIGHT_LAND_DP else KEY_HEIGHT_DP)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        specs.forEach { spec ->
            val button = Button(this).apply {
                text = spec.label
                isAllCaps = false
                // 等分割時は 1 キーあたりの幅が画面幅に依存するので、幅から
                // 「ENTER」「PGUP」等の 5 文字が省略されない大きさを求める。
                textSize = if (stretch) stretchedKeyTextSize(specs.size) else 12f
                setTextColor(terminalText)
                setBackgroundColor(Color.rgb(255, 255, 255))
                // 既定の最小サイズを外し、与えた寸法どおりに収まるようにする。
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (spec.modifier != null) toggleModifier(spec.modifier, this)
                    else {
                        spec.action?.invoke()
                        terminalView.requestFocus()
                    }
                }
            }
            val params = if (stretch) {
                LinearLayout.LayoutParams(0, keyHeight, 1f).apply { marginEnd = dp(2) }
            } else {
                LinearLayout.LayoutParams(dp(KEY_WIDTH_DP), keyHeight).apply { marginEnd = dp(4) }
            }
            row.addView(button, params)
        }
        // 等分割時は画面幅に収まっているのでスクロールコンテナは不要。
        if (stretch) return row
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /**
     * 等分割した補助キーに収まる文字サイズを求める。
     *
     * 1 キーの幅は画面幅 ÷ キー数で決まるため端末によって変わる。最長ラベル
     * (ENTER / PGUP など 5 文字) が省略されない範囲で、できるだけ縦画面と同じ
     * 12sp に近づける。狭い端末では段階的に落とす。
     */
    private fun stretchedKeyTextSize(keyCount: Int): Float {
        // 最長ラベル(5 文字) × 文字幅比 が必要幅。左右に少し余白を見込む。
        val fit = perKeyWidthDp(keyCount) / (KEY_LABEL_CHARS * KEY_LABEL_WIDTH_RATIO * 1.25f)
        return fit.coerceIn(MIN_KEY_TEXT_SP, MAX_KEY_TEXT_SP)
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

    /**
     * サービス接続後の入口。
     *
     * すでにサービスが生きたセッションを持っていれば（＝バックグラウンドから戻ってきた、
     * あるいは通知タップで復帰した場合）、新しく起動せずにその画面へ再アタッチする。
     * これがバックグラウンド継続の実際の効き目になる部分で、claude の対話や
     * 走行中のビルドがそのまま画面に戻ってくる。
     */
    private fun bindOrStartSession(service: TerminalSessionService) {
        // 空文字は「指定なし」と同じ扱いにする。古い通知などから空の値が届いても、
        // 存在しないコンテナの指定起動と誤解釈して生きたセッションを巻き添えにしない。
        val requestedContainer = intent.getStringExtra(EXTRA_CONTAINER)?.takeIf { it.isNotBlank() }
        val resumeId = intent.getStringExtra(EXTRA_RESUME_ID)
        val running = service.runningSession()

        // 「実行すべきコマンドを持った起動」は、必ず新しいシェルで実行しなければ
        // ならない。既存セッションへ復帰させてしまうと、Claude Code のインストールや
        // 基本CLIの更新が黙って実行されないまま、元のシェルが表示されるだけになる。
        // 履歴からの再開（resumeId 付き）も「新しいシェルを開く」操作なので同じ扱い。
        if (requiresFreshSession() || !resumeId.isNullOrBlank()) {
            // サービスは畳まずセッションだけ終了する（畳むと直後の新規起動と競合する）。
            if (running != null) service.endSession()
            startRequestedSession(service)
            return
        }

        // 生存セッションがあり、別コンテナを明示指定されたわけでもなければ復帰する。
        if (running != null && (requestedContainer == null || requestedContainer == service.container)) {
            reattachSession(service, running)
            return
        }

        // 通知から復帰しに来たのにセッションが終了済みだった場合は、勝手に新しい
        // シェルを立ち上げず、終了した旨だけを示す（メイン画面からの明示的な起動には
        // このフラグが付かないので、通常の新規起動は妨げない）。
        if (running == null && intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
            statusView.text = "セッションは終了しています · 戻るボタンでメイン画面へ"
            Toast.makeText(this, "セッションは終了しています", Toast.LENGTH_LONG).show()
            // 用済みの終了通知とサービスを片付ける。
            service.stopSession()
            return
        }

        startRequestedSession(service)
    }

    /**
     * この起動が「新しいシェルで実行しなければならない」ものか。
     *
     * COMMAND / SETUP モード（＝実行すべきコマンドを伴う起動）は、既存セッションへ
     * 復帰させると指定コマンドが実行されないまま終わってしまうため、常に新規で起動する。
     */
    private fun requiresFreshSession(): Boolean {
        if (!intent.getStringExtra(EXTRA_COMMAND).isNullOrBlank()) return true
        val mode = runCatching {
            EmbeddedRuntimeManager.LaunchMode.valueOf(
                intent.getStringExtra(EXTRA_MODE) ?: EmbeddedRuntimeManager.LaunchMode.SHELL.name
            )
        }.getOrDefault(EmbeddedRuntimeManager.LaunchMode.SHELL)
        return mode != EmbeddedRuntimeManager.LaunchMode.SHELL
    }

    /** 生存中のセッションを画面へ繋ぎ直す（バックグラウンドからの復帰）。 */
    private fun reattachSession(service: TerminalSessionService, running: TerminalSession) {
        launchMode = EmbeddedRuntimeManager.LaunchMode.SHELL
        updateComposerHint()
        titleView.text = service.sessionTitle
        statusView.text = "アプリ内PTY · ${service.container}"
        historyContainer = service.container
        historyId = service.historyId
        // 復帰時は emulator がすでに生きているため、配色を適用し直す必要がある。
        terminalColorsApplied = false

        // attachSession は内部で mEmulator を捨てて updateSize() から取り直すが、
        // ビューの幅・高さが 0（= レイアウト前）だと取り直しに失敗して黒画面のまま
        // 残る。onServiceConnected は onCreate 直後（レイアウト前）に来ることが
        // あるため、必ずレイアウト後に実行する。
        terminalView.post {
            terminalView.attachSession(running)
            applyLightTerminalColors(running)
            terminalView.onScreenUpdated()
            imeInput.requestFocus()
            imeInput.postDelayed({ showComposerKeyboard() }, 300)
        }
    }

    private fun startRequestedSession(service: TerminalSessionService) {
        val container = intent.getStringExtra(EXTRA_CONTAINER)?.takeIf { it.isNotBlank() }
            ?: EmbeddedRuntimeManager.activeContainer(this)
        if (container == null) {
            statusView.text = "No Linux container is installed."
            Toast.makeText(this, "先にLinuxコンテナを作成してください", Toast.LENGTH_LONG).show()
            // セッションを一つも持たないままサービスが常駐し、実行中通知だけが
            // residue として残らないよう畳む。
            service.stopSession()
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
                // 起動できなかったので、実行中通知を残さずサービスを畳む。
                service.stopSession()
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

        // セッションの生成と保持はサービスが行う（Activity が消えても生き残らせるため）。
        // コマンドを伴う起動は既存セッションを再利用させない（指定コマンドが
        // 実行されないまま既存シェルが返るのを防ぐ）。
        val newSession = service.startSession(
            spec,
            container,
            historyId,
            displayTitle,
            reuseExisting = !requiresFreshSession()
        )
        // 既存セッションが再利用された場合、記録先はサービス側の ID が正。
        // 採番したての ID を使い続けると、Activity とサービスで保存先が食い違う。
        historyId = service.historyId
        historyContainer = service.container
        terminalColorsApplied = false
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
        // サービスがセッションを持っていれば、そちらのトランスクリプトが正。
        val service = sessionService
        if (service != null && service.historyId.isNotBlank()) {
            service.saveHistory()
            return
        }
        // サービスがセッションを持たない（起動に失敗した等）場合は、
        // Activity 側で拾えている分だけでも保存する。
        val transcript = latestTranscript
        if (historyId.isBlank() || historyContainer.isBlank()) return
        runCatching {
            TerminalHistoryManager.save(applicationContext, historyId, historyContainer, transcript)
        }
    }

    /**
     * ターミナル画面を閉じてメイン画面へ戻る。
     *
     * セッションは終了させない。サービスが保持したまま実行を続けるため、
     * claude や長時間処理を走らせたまま安全にこの画面を離れられる。
     * 明示的に終わらせたいときは通知の「停止」、またはメイン画面の
     * 「実行中のセッションを停止」から止める。
     */
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
        // 画面が消えるだけではセッションを終了しない（バックグラウンド継続の核心）。
        // UI クライアントの参照だけ外し、以降の PTY 出力はサービスが受け取り続ける。
        if (!leaving) saveHistory()
        sessionService?.attachUi(null)
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        sessionService = null
        super.onDestroy()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        // emulator が初期化されたら(最初の出力時)ライト配色を適用する。
        applyLightTerminalColors(changedSession)
        terminalView.onScreenUpdated()
        // トランスクリプトの記録はサービス側が行う（画面が無い間の出力も残すため）。
        // サービスが記録対象を持っていない場合だけ、こちらで保持しておく。
        if (sessionService?.historyId.isNullOrBlank()) {
            runCatching {
                changedSession.emulator?.screen?.transcriptText?.let { latestTranscript = it }
            }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // ヘッダーからステータス欄を廃してターミナル領域へ回したため、終了は Toast で伝える。
        // statusView は画面に出ていないので、ここへ書くだけではユーザーに届かない。
        val message = "プロセスが終了しました (exit ${finishedSession.exitStatus}) · 戻るボタンでメイン画面へ"
        statusView.text = message
        runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
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
