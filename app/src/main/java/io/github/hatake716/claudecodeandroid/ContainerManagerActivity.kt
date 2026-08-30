package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Native manager for app-private Linux containers. */
class ContainerManagerActivity : Activity() {
    companion object {
        private const val BASE_DEV_SETUP =
            "apt-get -o Acquire::Retries=3 update && " +
                "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 install -y ca-certificates curl git ripgrep locales"
    }

    // monaka配色（ダーク地の焦げ茶 × 小豆色アクセント）
    private val page = Color.rgb(26, 20, 18)
    private val card = Color.rgb(38, 28, 25)
    private val text = Color.rgb(237, 224, 214)
    private val muted = Color.rgb(176, 150, 138)
    private val border = Color.rgb(74, 52, 45)
    private val soft = Color.rgb(48, 35, 31)
    private val accent = Color.rgb(156, 74, 60)
    private val danger = Color.rgb(210, 140, 90)

    private lateinit var activeText: TextView
    private lateinit var listHost: LinearLayout
    private lateinit var operationText: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        setContentView(buildView().also { it.applyEdgeToEdgeInsets() })
    }

    override fun onResume() {
        super.onResume()
        if (::listHost.isInitialized) refresh()
    }

    private fun buildView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(36))
            setBackgroundColor(page)
        }
        content.addView(TextView(this).apply {
            this.text = "Linux コンテナ"
            textSize = 29f
            setTextColor(this@ContainerManagerActivity.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            this.text = "monakaのLinux環境をアプリ内に複数保持"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(16))
        })

        val activeCard = section("アクティブコンテナ", "monakaのターミナルが使用する環境")
        activeText = badge("確認中…")
        activeCard.addView(activeText, top(dp(12)))
        addRow(
            activeCard,
            button("シェルを開く") { launchActive(EmbeddedRuntimeManager.LaunchMode.SHELL) },
            button("基本CLIを更新") { updateBaseTools() }
        )
        content.addView(activeCard)

        val create = section("新しいLinux環境", "Linux Baseを端末へ直接取得して構築")
        create.addView(primary("コンテナを追加") { showCreateDialog() }, top(dp(12)))
        create.addView(help(
            "Baseイメージのダウンロード、rootfs展開、PRootセルフテストまで進捗を表示します。" +
                "第三者AI CLIのインストール・認証は自動実行しません。"
        ))
        content.addView(create, top(dp(14)))

        val list = section("保持中のコンテナ", "環境の切替・削除")
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(listHost, top(dp(8)))
        content.addView(list, top(dp(14)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            progress = 0
        }
        content.addView(progress, top(dp(14)))
        operationText = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(soft, border, 12)
            text = "ランタイムを確認しています…"
        }
        content.addView(operationText, top(dp(8)))
        content.addView(help(
            "コンテナのrootfsはアプリ専用領域に保存されます。削除しても共有ワークスペースとDownload/Documentsは削除しません。"
        ))

        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
    }

    private fun refresh() {
        val runtime = EmbeddedRuntimeManager.ensureHostRuntime(this)
        if (runtime.isFailure) {
            operationText.text = runtime.exceptionOrNull()?.message ?: "内蔵Linuxランタイムを準備できません。"
        }
        val active = EmbeddedRuntimeManager.activeContainer(this)
        activeText.text = if (active == null) "アクティブ: なし" else "アクティブ: $active"
        renderRows(active)
    }

    private fun renderRows(active: String?) {
        listHost.removeAllViews()
        val containers = EmbeddedRuntimeManager.listContainers(this)
        if (containers.isEmpty()) {
            listHost.addView(help("まだコンテナがありません。上の「コンテナを追加」から作成できます。"))
            return
        }
        containers.forEachIndexed { index, name ->
            val selected = name == active
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(
                    if (selected) Color.rgb(58, 34, 28) else soft,
                    if (selected) accent else border,
                    12
                )
            }
            box.addView(TextView(this).apply {
                text = if (selected) "● $name" else name
                textSize = 15f
                setTextColor(this@ContainerManagerActivity.text)
                setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = if (selected) "現在の実行先" else "保存済みLinux rootfs"
                textSize = 12.5f
                setTextColor(muted)
            })
            addRow(
                box,
                button(if (selected) "選択中" else "この環境を使う") {
                    if (!selected) {
                        EmbeddedRuntimeManager.setActiveContainer(this, name)
                            .onSuccess { operationText.text = "$name に切り替えました。" }
                            .onFailure { showError(it.message ?: "切替に失敗しました。") }
                        refresh()
                    }
                }.apply { isEnabled = !selected },
                dangerButton("削除") { showDeleteDialog(name) }
            )
            listHost.addView(box, if (index == 0) top(dp(4)) else top(dp(8)))
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            setText(nextName())
            hint = "ccfa-linux-2"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Linuxコンテナを追加")
            .setMessage("Linux Base ${EmbeddedRuntimeManager.UBUNTU_RELEASE} ARM64をアプリ内に構築します。")
            .setView(input)
            .setPositiveButton("作成", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!EmbeddedRuntimeManager.isValidContainerName(name)) {
                    input.error = "英数字で開始し、英数字・. _ - のみ使用できます"
                    return@setOnClickListener
                }
                if (name in EmbeddedRuntimeManager.listContainers(this)) {
                    input.error = "同名コンテナがすでにあります"
                    return@setOnClickListener
                }
                dialog.dismiss()
                createContainer(name)
            }
        }
        dialog.show()
    }

    private fun createContainer(name: String) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        operationText.text = "$name を準備しています…"
        EmbeddedRuntimeManager.installUbuntuContainer(
            this,
            name,
            onProgress = { value ->
                operationText.text = "${value.phase}: ${value.message}"
                if (value.percent == null) {
                    progress.isIndeterminate = true
                } else {
                    progress.isIndeterminate = false
                    progress.progress = value.percent.coerceIn(0, 100)
                }
            },
            onComplete = { result ->
                result.onSuccess {
                    progress.isIndeterminate = false
                    progress.progress = 100
                    operationText.text = "$name のrootfs作成・起動テストが完了しました。基本CLIをセットアップします。"
                    refresh()
                    startActivity(
                        EmbeddedTerminalActivity.intent(
                            this,
                            name,
                            EmbeddedRuntimeManager.LaunchMode.COMMAND,
                            BASE_DEV_SETUP
                        )
                    )
                }.onFailure { showError(it.message ?: "コンテナ作成に失敗しました。") }
            }
        )
    }

    private fun updateBaseTools() {
        val active = EmbeddedRuntimeManager.activeContainer(this)
        if (active == null) {
            Toast.makeText(this, "先にコンテナを作成してください", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            EmbeddedTerminalActivity.intent(
                this,
                active,
                EmbeddedRuntimeManager.LaunchMode.COMMAND,
                BASE_DEV_SETUP
            )
        )
    }

    private fun launchActive(mode: EmbeddedRuntimeManager.LaunchMode) {
        val active = EmbeddedRuntimeManager.activeContainer(this)
        if (active == null) {
            Toast.makeText(this, "先にコンテナを作成してください", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(EmbeddedTerminalActivity.intent(this, active, mode))
    }

    private fun showDeleteDialog(name: String) {
        val input = EditText(this).apply {
            hint = name
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("コンテナを削除")
            .setMessage("'$name' のLinux rootfsを永久削除します。確認のためコンテナ名を入力してください。")
            .setView(input)
            .setPositiveButton("削除", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setTextColor(danger)
            positive.setOnClickListener {
                if (input.text.toString().trim() != name) {
                    input.error = "'$name' と入力してください"
                    return@setOnClickListener
                }
                dialog.dismiss()
                EmbeddedRuntimeManager.deleteContainer(this, name)
                    .onSuccess { operationText.text = "$name を削除しました。" }
                    .onFailure { showError(it.message ?: "削除に失敗しました。") }
                refresh()
            }
        }
        dialog.show()
    }

    private fun nextName(): String {
        val names = EmbeddedRuntimeManager.listContainers(this)
        if (EmbeddedRuntimeManager.DEFAULT_CONTAINER !in names) return EmbeddedRuntimeManager.DEFAULT_CONTAINER
        var i = 2
        while ("${EmbeddedRuntimeManager.DEFAULT_CONTAINER}-$i" in names) i++
        return "${EmbeddedRuntimeManager.DEFAULT_CONTAINER}-$i"
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        operationText.text = message
        AlertDialog.Builder(this)
            .setTitle("エラー")
            .setMessage(message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun section(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(card, border, 18)
        addView(TextView(this@ContainerManagerActivity).apply {
            text = title
            textSize = 20f
            setTextColor(this@ContainerManagerActivity.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@ContainerManagerActivity).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(muted)
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
        textSize = 13.5f
        setTextColor(this@ContainerManagerActivity.text)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(soft, border, 10)
    }

    private fun primary(value: String, click: () -> Unit) = styled(value, accent, Color.WHITE, click)
    private fun button(value: String, click: () -> Unit) = styled(value, soft, text, click)
    private fun dangerButton(value: String, click: () -> Unit) =
        styled(value, Color.rgb(56, 32, 24), danger, click)

    private fun styled(value: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13.5f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(bg, border, 12)
        setOnClickListener { click() }
    }

    private fun addRow(parent: LinearLayout, left: Button, right: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            }
        )
        row.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        )
        parent.addView(row, top(dp(8)))
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
