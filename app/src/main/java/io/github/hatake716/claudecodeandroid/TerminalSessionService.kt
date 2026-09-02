package io.github.hatake716.claudecodeandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * ターミナルの PTY セッションを Activity の寿命から切り離して保持するフォアグラウンドサービス。
 *
 * 【この Service が必要な理由】
 * TerminalSession（= PRoot 配下の Linux プロセス群）を Activity が直接持っていると、
 * ホームに戻る・他アプリへ切り替えるなどでアプリがバックグラウンドへ回った瞬間、
 * Android はそのプロセスを「キャッシュ済みプロセス」に落とす。キャッシュ済みプロセスは
 *
 *   - Android 12+ の phantom process killer に子プロセスを刈られる
 *   - Doze / App Standby で CPU を止められる
 *   - メモリ逼迫時に真っ先に kill される
 *
 * ため、claude や長時間ビルドのような「見ていない間も走り続けてほしい処理」が停止する。
 * フォアグラウンドサービスを実行中のプロセスは可視プロセス相当に格上げされ、上記の
 * いずれの対象からも外れる。そこでセッションの所有者を Activity から本サービスへ移し、
 * [EmbeddedTerminalActivity] は「表示と入力の窓口」だけを担当する構成にした。
 *
 * 【セッションの寿命】
 * - Activity が bind して画面を描画する。画面回転・バックグラウンド化では終了しない。
 * - ターミナルを「戻る」で閉じてもセッションは終了しない（そのまま走り続ける）。
 * - 終了するのは、通知の「停止」／メイン画面の「実行中のセッションを停止」／
 *   コマンド実行のための作り直し（[endSession]）／プロセスの自然終了のときだけ。
 */
class TerminalSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "monaka-terminal"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "monaka:terminal-session"

        /** 通知の「停止」から送られる、セッションを終了してサービスを畳むアクション。 */
        const val ACTION_STOP = "io.github.hatake716.claudecodeandroid.STOP_TERMINAL"

        /**
         * バックグラウンドで走っているセッションがあるか。
         *
         * Activity を跨いで参照できるよう、サービス実行中フラグとして静的に持つ。
         * bind していない画面（メイン画面）からも「実行中かどうか」を知るために使う。
         */
        @Volatile
        var isSessionRunning: Boolean = false
            private set

        /** 実行中セッションのコンテナ名（実行中でなければ空）。 */
        @Volatile
        var runningContainer: String = ""
            private set

        fun intent(context: Context): Intent =
            Intent(context, TerminalSessionService::class.java)

        /** メイン画面などから、bind せずにセッションを停止するためのリクエスト。 */
        fun requestStop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TerminalSessionService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    /** Activity から Service 本体へアクセスするためのローカルバインダ。 */
    inner class LocalBinder : Binder() {
        val service: TerminalSessionService get() = this@TerminalSessionService
    }

    private val binder = LocalBinder()

    /** 保持中の PTY セッション。null なら未起動または終了済み。 */
    var session: TerminalSession? = null
        private set

    /** セッションを起動したコンテナ名（履歴の保存先を Activity と揃えるために持つ）。 */
    var container: String = ""
        private set

    /** 履歴記録 ID。Activity 再作成後も同じ記録へ追記できるよう Service 側で保持する。 */
    var historyId: String = ""
        private set

    /** 最新のトランスクリプト。Activity が居ない間も Service が更新し続ける。 */
    @Volatile
    var latestTranscript: String = ""
        private set

    /** 画面表示用のタイトル。 */
    var sessionTitle: String = "monaka Terminal"
        private set

    /**
     * 画面に出ている Activity のクライアント。バックグラウンド時は null になり、
     * その間の PTY 出力は [forwardingClient] が握りつぶさずトランスクリプトへ反映する。
     */
    private var uiClient: TerminalSessionClient? = null

    /**
     * Doze 中も PTY 配下の処理が止まらないようにする wake lock。
     * セッション実行中だけ保持し、終了時に必ず解放する。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 画面（Activity）が離れたときに呼ばれる。
     *
     * セッションが生きていれば、それを保持し続けるのがこのサービスの役目なので畳まない。
     * すでに終了しているなら、通知だけが残った空のサービスになるので片付ける。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        uiClient = null
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        // セッションはプロセスと不可分（再生成しても同じ Linux プロセスには戻れない）ため、
        // システムによる自動再起動は行わない。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        session?.finishIfRunning()
        session = null
        isSessionRunning = false
        runningContainer = ""
        super.onDestroy()
    }

    /**
     * PTY セッションを起動する。
     *
     * [reuseExisting] が true で同じコンテナのセッションが生きていれば、それを再利用する
     * （バックグラウンドから戻ったときに作業を引き継ぐための要）。コマンドを伴う起動では
     * false を渡し、必ず新しいシェルで実行させる。
     *
     * onStartCommand を経ずに bind だけで生成された場合（アプリがバックグラウンドに
     * ある状態から startForegroundService が拒否された等）でも、ここで必ず
     * foreground 化するため、通知なしの通常サービスとして取り残されることはない。
     */
    fun startSession(
        spec: EmbeddedRuntimeManager.LaunchSpec,
        container: String,
        historyId: String,
        title: String,
        reuseExisting: Boolean = true
    ): TerminalSession {
        // reuseExisting=false は「このコマンドを新しいシェルで実行する」という要求。
        // コンテナ名だけで再利用してしまうと、Claude Code のインストール等が
        // 実行されないまま既存シェルが返ってしまう。
        if (reuseExisting) {
            session?.let { existing ->
                if (existing.isRunning && this.container == container) return existing
            }
        }
        session?.finishIfRunning()

        this.container = container
        this.historyId = historyId
        this.sessionTitle = title

        val created = TerminalSession(
            spec.executable,
            spec.cwd,
            spec.args,
            spec.env,
            5000,
            forwardingClient
        )
        created.mSessionName = title
        session = created
        isSessionRunning = true
        runningContainer = container

        // 直前のセッション終了時に stopSelf() を出していると、bind が切れた瞬間に
        // このサービスごと破棄されてしまう。新しい start を発行して保留中の停止要求を
        // 打ち消し、今起動したセッションが巻き添えにならないようにする。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent(this))
            } else {
                startService(intent(this))
            }
        }
        startForegroundWithNotification()
        acquireWakeLock()
        return created
    }

    /** 既存セッションが生きていればそれを返す（Activity 再作成時の復帰用）。 */
    fun runningSession(): TerminalSession? = session?.takeIf { it.isRunning }

    /** 画面が前面にある間だけ、UI 側のクライアントを繋ぐ。 */
    fun attachUi(client: TerminalSessionClient?) {
        uiClient = client
    }

    /**
     * セッションを終了し、履歴を保存してサービスを畳む。
     * ユーザーが明示的にターミナルを閉じたとき、または通知の「停止」から呼ばれる。
     */
    fun stopSession() {
        endSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * セッションだけを終了する（サービスは畳まない）。
     *
     * 履歴からの再開やコマンド実行のように「今のセッションを終わらせて、続けて
     * 新しいセッションを始める」場合に使う。ここで stopSelf() まで行うと、保留中の
     * 停止処理が新セッション生成のあとに効いて、起動直後のセッションを殺してしまう。
     */
    fun endSession() {
        saveHistory()
        releaseWakeLock()
        session?.finishIfRunning()
        session = null
        isSessionRunning = false
        runningContainer = ""
        historyId = ""
        container = ""
        latestTranscript = ""
    }

    /** 現時点のトランスクリプトを履歴へ保存する。 */
    fun saveHistory() {
        if (historyId.isBlank() || container.isBlank()) return
        runCatching {
            TerminalHistoryManager.save(applicationContext, historyId, container, latestTranscript)
        }
    }

    /**
     * PTY からのコールバックの実体。UI が居ても居なくても Service が必ず受け取り、
     * トランスクリプトを更新したうえで、居るときだけ UI へ中継する。
     * これにより、バックグラウンド中の出力も履歴に残る。
     */
    private val forwardingClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            runCatching {
                changedSession.emulator?.screen?.transcriptText?.let { latestTranscript = it }
            }
            uiClient?.onTextChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            uiClient?.onTitleChanged(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // プロセスが自然終了したときの後始末。
            saveHistory()
            releaseWakeLock()
            isSessionRunning = false
            runningContainer = ""
            // 終了済みインスタンスを持ち続けない。履歴は保存済みなので記録先もクリアし、
            // 以降の stopSession() などで二重保存にならないようにする。
            // 通知の文面・復帰先に使うコンテナ名は、クリアする前に退避しておく
            // （空文字を焼き込むと、その通知をタップしたときに「存在しないコンテナの
            //   指定起動」と解釈され、生きたセッションを巻き添えに終了させてしまう）。
            val finishedContainer = container
            session = null
            historyId = ""
            container = ""
            uiClient?.onSessionFinished(finishedSession)

            if (uiClient != null) {
                // 画面が出ている間は、終了した旨を通知に残して結果を読ませる
                // （フォアグラウンドは解除し、ユーザーが通知を消せるようにする）。
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification(finished = true, containerName = finishedContainer)
                // bind 中の stopSelf() はサービスを即破棄せず、最後の unbind 時に
                // 破棄されるだけなので、終了通知を読ませたまま確実に畳める。
                stopSelf()
            } else {
                // 誰も見ていないなら通知を残す意味がないので、サービスごと畳む。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            uiClient?.onCopyTextToClipboard(session, text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            uiClient?.onPasteTextFromClipboard(session)
        }

        override fun onBell(session: TerminalSession) {
            uiClient?.onBell(session)
        }

        override fun onColorsChanged(session: TerminalSession) {
            uiClient?.onColorsChanged(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            uiClient?.onTerminalCursorStateChange(state)
        }

        override fun getTerminalCursorStyle(): Int? = uiClient?.terminalCursorStyle

        override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            android.util.Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) {
            android.util.Log.e(tag, e.message.orEmpty(), e)
        }
    }

    // ---------------------------------------------------------------------
    // 通知（フォアグラウンドサービスの必須要件）
    // ---------------------------------------------------------------------

    private fun startForegroundWithNotification() {
        ensureChannel()
        val notification = buildNotification(finished = false)
        // 端末や状態によっては startForeground が拒否されて例外になる。
        // その場合でもセッション自体は動かし続けたいので、落とさず握る。
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 10+ はサービス種別の宣言が必要。ターミナルは「データ同期」ではなく
                // ユーザーが開始した継続的な処理なので dataSync を用いる（Manifest と対応）。
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            android.util.Log.w("monaka", "startForeground failed: ${it.message}")
        }
    }

    private fun updateNotification(finished: Boolean, containerName: String = container) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(finished, containerName))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ターミナルセッション",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "バックグラウンドで実行中の Linux ターミナルを表示します。"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(
        finished: Boolean,
        containerName: String = container
    ): Notification {
        // タップでターミナル画面へ戻る。既存の Activity があれば再利用し（singleTop）、
        // 破棄済みなら実行中セッションのコンテナ名を持たせて作り直させる。
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, EmbeddedTerminalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EmbeddedTerminalActivity.EXTRA_CONTAINER, containerName)
                putExtra(
                    EmbeddedTerminalActivity.EXTRA_MODE,
                    EmbeddedRuntimeManager.LaunchMode.SHELL.name
                )
                // 「実行中のものを見に来た」ことを示す。セッションが終了済みだった場合に
                // 勝手な新規起動をさせないための目印。
                putExtra(EmbeddedTerminalActivity.EXTRA_FROM_NOTIFICATION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(if (finished) "monaka — セッション終了" else "monaka — 実行中")
            .setContentText(
                if (finished) "$containerName のプロセスは終了しました"
                else "$containerName のターミナルがバックグラウンドで動作中です"
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    // ---------------------------------------------------------------------
    // Wake lock（Doze 中の CPU 停止を防ぐ）
    // ---------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
