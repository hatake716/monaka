package io.github.hatake716.claudecodeandroid

import android.graphics.Color

/**
 * monaka のライトモード配色（Claude デスクトップアプリのライトモード風）。
 *
 * Claude/Anthropic のライトモードの特徴である温かいオフホワイト(paper)地と、
 * クレイ/テラコッタ系のアクセントをベースにしつつ、monaka のブランドである
 * 小豆色に寄せている。全 Activity・ターミナル UI で共通利用する。
 */
object MonakaTheme {
    // 面
    val page = Color.rgb(245, 244, 239)      // #F5F4EF 温かいオフホワイト(paper)
    val card = Color.rgb(255, 255, 255)      // #FFFFFF カード/サーフェス
    val soft = Color.rgb(238, 236, 229)      // #EEECE5 淡いサブ面(バッジ等)
    val border = Color.rgb(228, 224, 214)    // #E4E0D6 ボーダー

    // 文字
    val text = Color.rgb(38, 36, 32)         // #262420 primary text(ダークグレー)
    val muted = Color.rgb(122, 115, 104)     // #7A7368 secondary/muted

    // アクセント（Claude のクレイ/テラコッタ寄りの小豆色）
    val accent = Color.rgb(193, 95, 60)      // #C15F3C クレイ・テラコッタ
    val accentDark = Color.rgb(167, 78, 48)  // #A74E30 濃いアクセント(枠・押下)
    val accentSoft = Color.rgb(245, 232, 224) // #F5E8E0 アクセントの淡い面
    val onAccent = Color.WHITE               // アクセント面上の文字

    // 状態色
    val danger = Color.rgb(180, 72, 54)      // #B44836 削除・警告
    val dangerSoft = Color.rgb(247, 231, 227) // #F7E7E3

    // ターミナル面（ライトの中でもコードは少し落ち着いた明色にして目に優しく）
    val terminalBg = Color.rgb(251, 250, 247)   // #FBFAF7 ほぼ白の暖色
    val terminalText = Color.rgb(45, 42, 38)    // #2D2A26 端末文字(濃いグレー)
    val terminalHint = Color.rgb(150, 143, 132) // 入力欄プレースホルダ

    // 補助キーバー（ライトの淡いグレー面 + 濃い文字）
    val keyBar = Color.rgb(239, 237, 230)    // #EFEDE6
    val keyFace = Color.rgb(255, 255, 255)   // キーの面
    val keyText = Color.rgb(60, 56, 50)      // キー文字
    val statusBar = page                     // ステータスバー背景（ライトなので暗いアイコン）
}
