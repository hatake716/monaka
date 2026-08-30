package io.github.hatake716.claudecodeandroid

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * targetSdk 35 以降では Android 15+ が edge-to-edge を強制し、ウィンドウが
 * ステータスバー・ナビゲーションバー・IME の背面まで広がる（SOFT_INPUT_ADJUST_RESIZE
 * も IME ぶんの縮小を行わなくなる）。ルートビューへシステムバー（+IME）の
 * インセットをパディングとして反映し、上部バーがステータスバーに重なる問題と、
 * キーボード表示時に入力欄・特殊キーが隠れる問題を防ぐ。
 * Android 14 以前は従来どおり OS 側が余白を確保するため何もしない。
 */
fun View.applyEdgeToEdgeInsets(includeIme: Boolean = false) {
    if (Build.VERSION.SDK_INT < 35) return
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val imeBottom =
            if (includeIme) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, imeBottom))
        WindowInsets.CONSUMED
    }
}
