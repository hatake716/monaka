# CCFA Google Play リリース向け ProGuard/R8 ルール
#
# 現在の release ビルドは isMinifyEnabled = false のため R8 は縮小・難読化を
# 行わない。将来 minify を有効化する場合に備え、PRoot ランタイム連携・Termux
# ターミナルモジュール・Apache Commons を保持する保守的なルールを置いておく。

# Termux terminal-view / terminal-emulator（JNI から参照されるクラス・メソッド）
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# Apache Commons Compress（リフレクション経由のアーカイバ/コンプレッサー解決）
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# JNI で呼ばれる native メソッドを持つクラスは保持する
-keepclasseswithmembernames class * {
    native <methods>;
}

# アプリ本体（Activity は AndroidManifest から参照されるので R8 が保持するが明示）
-keep class io.github.hatake716.claudecodeandroid.** { *; }
