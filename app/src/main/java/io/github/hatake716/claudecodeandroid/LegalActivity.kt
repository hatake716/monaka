package io.github.hatake716.claudecodeandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Displays legal notices and bundled license texts directly from APK assets. */
class LegalActivity : Activity() {
    private val page = Color.rgb(244, 241, 234)
    private val text = Color.rgb(45, 42, 38)
    private val muted = Color.rgb(110, 103, 94)

    private data class LegalDocument(val label: String, val assetPath: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page

        val notice = readAsset("legal/NOTICE.txt")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
            setBackgroundColor(page)
        }
        content.addView(Button(this).apply {
            this.text = "← 戻る"
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            this.text = "裏CCFA 法的情報"
            textSize = 28f
            setTextColor(this@LegalActivity.text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(8))
        })
        content.addView(TextView(this).apply {
            this.text = notice
            textSize = 13.5f
            setTextColor(this@LegalActivity.text)
            setLineSpacing(0f, 1.15f)
        })

        content.addView(TextView(this).apply {
            this.text = "プライバシーポリシー"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@LegalActivity.text)
            setPadding(0, dp(20), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            this.text =
                "裏CCFA自体が解析・広告・テレメトリー・アカウント等によるデータ収集を行うことはありません。" +
                    "詳細は公開中のプライバシーポリシーを参照してください。"
            textSize = 13.5f
            setTextColor(this@LegalActivity.text)
            setLineSpacing(0f, 1.15f)
        })
        content.addView(Button(this).apply {
            this.text = "プライバシーポリシーを開く（Web）"
            isAllCaps = false
            setOnClickListener { openPrivacyPolicy() }
        }, top(dp(6)))

        content.addView(TextView(this).apply {
            this.text = "同梱ライセンス / NOTICE"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@LegalActivity.text)
            setPadding(0, dp(20), 0, dp(6))
        })

        val documents = listOf(
            LegalDocument("Apache License 2.0", "legal/licenses/APACHE-2.0.txt"),
            LegalDocument("GNU GPL v2", "legal/licenses/GPL-2.0.txt"),
            LegalDocument("GNU GPL v3", "legal/licenses/GPL-3.0.txt"),
            LegalDocument("GNU LGPL v3", "legal/licenses/LGPL-3.0.txt"),
            LegalDocument(
                "libandroid-shmem BSD 3-Clause",
                "legal/licenses/BSD-3-Clause-libandroid-shmem.txt"
            ),
            LegalDocument(
                "Termux terminal modules license notice",
                "legal/licenses/TERMUX-TERMINAL-LICENSE.md"
            ),
            LegalDocument(
                "Apache Commons Compress NOTICE",
                "legal/licenses/COMMONS-COMPRESS-NOTICE.txt"
            ),
            LegalDocument(
                "Apache Commons Codec NOTICE",
                "legal/licenses/COMMONS-CODEC-NOTICE.txt"
            ),
            LegalDocument(
                "Apache Commons IO NOTICE",
                "legal/licenses/COMMONS-IO-NOTICE.txt"
            ),
            LegalDocument(
                "Apache Commons Lang NOTICE",
                "legal/licenses/COMMONS-LANG3-NOTICE.txt"
            ),
            LegalDocument("対応ソースについて", "legal/sources/README.txt"),
            LegalDocument(
                "対応ソース / ライセンス SHA-256",
                "legal/SOURCE-AND-LICENSE-MANIFEST.sha256"
            )
        )

        documents.forEach { document ->
            content.addView(Button(this).apply {
                this.text = document.label
                isAllCaps = false
                setOnClickListener { showDocument(document) }
            }, top(dp(6)))
        }

        content.addView(TextView(this).apply {
            this.text =
                "GPL/LGPL対象ネイティブ部品の対応ソースアーカイブと裏CCFA再パッケージスクリプトも、" +
                    "このAPKの assets/legal/sources/ に同梱されています。"
            textSize = 12.5f
            setTextColor(muted)
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(page)
            addView(content)
            applyEdgeToEdgeInsets()
        })
    }

    private fun openPrivacyPolicy() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }.onFailure {
            Toast.makeText(this, "ブラウザを起動できませんでした: $PRIVACY_POLICY_URL", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDocument(document: LegalDocument) {
        val body = readAsset(document.assetPath)
        val textView = TextView(this).apply {
            text = body
            textSize = 12.5f
            setTextColor(this@LegalActivity.text)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle(document.label)
            .setView(scroll)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun readAsset(path: String): String = runCatching {
        assets.open(path).bufferedReader().use { it.readText() }
    }.getOrElse { "ファイルを読み込めませんでした: $path\n${it.message}" }

    private fun top(value: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = value }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        // 裏CCFA は sideload 専用のため、GitHub Pages ではなくリポジトリ内 PRIVACY.md を指す。
        const val PRIVACY_POLICY_URL = "https://github.com/hatake716/-CCFA/blob/main/PRIVACY.md"
    }
}
