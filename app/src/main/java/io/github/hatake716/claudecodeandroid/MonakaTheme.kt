package io.github.hatake716.claudecodeandroid

import android.graphics.Color

/**
 * monaka の配色・寸法（iOS 27 風）。
 *
 * ベースは monaka のブランドである小豆色（和菓子の最中）。そこへ iOS 27 の
 * 質感を重ねる:
 *
 * - 面は「グループ化された背景の上にカードが浮く」iOS の二層構造
 * - 角丸は大きめ、かつ連続曲率（[IosSurface]）
 * - 区切りは線ではなく余白と面の段差で作る（線は極細・低コントラスト）
 * - 文字は太さでしっかり階層を作る（iOS のタイトルは重め）
 *
 * 全 Activity・ターミナル UI で共通利用する。
 */
object MonakaTheme {

    // ---- 面 -------------------------------------------------------------
    // iOS の systemGroupedBackground 相当。カードより一段暗い地。
    val page = Color.rgb(242, 241, 236)      // #F2F1EC 温かいオフホワイト
    val card = Color.rgb(255, 255, 255)      // #FFFFFF カード/サーフェス
    val soft = Color.rgb(236, 234, 227)      // #ECEAE3 淡いサブ面(バッジ・副ボタン)
    val border = Color.rgb(226, 222, 212)    // #E2DED4 罫線(極細・低コントラスト)

    // ---- 文字 -----------------------------------------------------------
    val text = Color.rgb(28, 27, 24)         // #1C1B18 primary(iOS label 相当)
    val muted = Color.rgb(132, 126, 116)     // #847E74 secondaryLabel
    val faint = Color.rgb(168, 162, 151)     // #A8A297 tertiaryLabel

    // ---- アクセント（小豆/クレイ） ---------------------------------------
    val accent = Color.rgb(193, 95, 60)      // #C15F3C クレイ・テラコッタ
    val accentDark = Color.rgb(167, 78, 48)  // #A74E30 濃いアクセント(押下)
    val accentSoft = Color.rgb(246, 233, 226) // #F6E9E2 アクセントの淡い面
    val onAccent = Color.WHITE               // アクセント面上の文字

    // ---- 状態色 ---------------------------------------------------------
    val danger = Color.rgb(199, 74, 58)      // #C74A3A 削除・警告(iOS systemRed 寄り)
    val dangerSoft = Color.rgb(250, 233, 230) // #FAE9E6
    val success = Color.rgb(58, 138, 91)     // #3A8A5B 許可済みバッジ等

    // ---- ターミナル -----------------------------------------------------
    val terminalBg = Color.rgb(251, 250, 247)   // #FBFAF7 ほぼ白の暖色
    val terminalText = Color.rgb(45, 42, 38)    // #2D2A26 端末文字
    val terminalHint = Color.rgb(160, 153, 142) // 入力欄プレースホルダ

    // ---- 補助キーバー ---------------------------------------------------
    val keyBar = Color.rgb(233, 231, 224)    // #E9E7E0 バーの地
    val keyFace = Color.rgb(255, 255, 255)   // キーの面
    val keyText = Color.rgb(44, 41, 36)      // キー文字
    val statusBar = page

    // ---- ripple（押下フィードバック） -----------------------------------
    /** 明るい面を押したときの陰り。 */
    val rippleOnLight = Color.argb(36, 0, 0, 0)
    /** アクセントなど濃い面を押したときの光。 */
    val rippleOnAccent = Color.argb(56, 255, 255, 255)

    // ---- 寸法（iOS 27 は角丸が大きく、余白が広い） -----------------------
    /** カード・シートの角丸。 */
    const val RADIUS_CARD_DP = 22f
    /** ボタン・入力欄の角丸。 */
    const val RADIUS_CONTROL_DP = 14f
    /** バッジ・小さな面の角丸。 */
    const val RADIUS_CHIP_DP = 11f
    /** 罫線の太さ（iOS のセパレータは 1px 相当の極細）。 */
    const val HAIRLINE_DP = 0.66f
}
