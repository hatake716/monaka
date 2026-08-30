package io.github.hatake716.claudecodeandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * monaka（sideload 専用）の「全ファイルアクセス」権限を管理する。
 *
 * CCFA(Google Play 版)は scoped storage の制約から SAF で選んだフォルダだけを
 * filesDir 内へコピー同期していた。monaka は Play に公開しない前提で、
 * Android 11+ の [MANAGE_EXTERNAL_STORAGE]（すべてのファイルへのアクセス）を使い、
 * ユーザー権限で読み書きできる共有ストレージ全体を Linux 側 /sdcard へ
 * 直接 bind mount する（[EmbeddedRuntimeManager.baseProotArgs] 参照）。
 *
 * この権限は Play の制限付き権限だが、GitHub/直配布のみなので使用できる。
 * 許可は「設定」アプリ内のシステム画面でユーザーが行う（アプリ側では付与できない）。
 *
 * 注意: MANAGE_EXTERNAL_STORAGE をもってしても、他アプリの private データ
 * (/data/data/<pkg> や /Android/data/<pkg>) は Android の仕様上アクセスできない。
 * ここで言う「全ファイル」は共有ストレージ全体（内部ストレージ + SD カード）を指す。
 */
object AllFilesAccessManager {

    /**
     * Linux ゲストから見えるマウント先。ホスト側 /storage/emulated/0 をここへ bind する。
     * 一般的な Termux 慣習に合わせて /sdcard とする。
     */
    const val GUEST_MOUNT = "/sdcard"

    /**
     * ホスト側の共有ストレージのルート。emulated/0（プライマリ外部ストレージ）。
     * SD カード等のセカンダリボリュームは /storage 全体を別途 bind して到達させる。
     */
    fun primaryStorageRoot(): String =
        Environment.getExternalStorageDirectory().absolutePath

    /** 全ファイルアクセスが許可されているか。 */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 以下は従来の READ/WRITE_EXTERNAL_STORAGE で代替（Manifest 側で宣言）。
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * 権限付与画面を開くための Intent。Android 11+ は本アプリ専用の
     * 「すべてのファイルへのアクセス」設定画面へ、失敗時は一覧画面へフォールバックする。
     * Android 10 以下は runtime permission 要求で扱うため null を返す。
     */
    fun requestAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val packageUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        // 一部端末では上の直接 Intent を解決できないため、一覧画面を用意しておく。
        return if (direct.resolveActivity(context.packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /** Android 10 以下で runtime 要求する権限（11+ では空）。 */
    fun legacyRuntimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            emptyArray()
        }
}
