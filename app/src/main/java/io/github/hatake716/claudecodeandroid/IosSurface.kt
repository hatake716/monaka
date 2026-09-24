package io.github.hatake716.claudecodeandroid

import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.content.res.ColorStateList

/**
 * iOS 27 風の面（サーフェス）を View ベースの UI で描くための共通素材。
 *
 * monaka は XML レイアウトを使わず Kotlin で View を組み立てているため、
 * Compose の Modifier 相当を [Drawable] として用意する。
 *
 * 【iOS 27 の面を構成する 4 層】
 * 1. 塗り  … 半透明の地色
 * 2. 拡散  … 上端から柔らかく消える白い光（ガラス内部の拡散）
 * 3. 暗い縁 … 背景と分離するための外周の陰り（iOS 27 で加わった）
 * 4. スペキュラ … 左上が明るく右下へ抜ける内側の光の縁
 *
 * 角丸は円弧ではなく**連続曲率**（スクイークル）を使う。iOS の角丸は角が辺へ
 * なだらかに接続し、円弧の角丸より「ふくらみ」が少ない。係数は iOS 7 以降の
 * UIBezierPath(roundedRect:cornerRadius:) の実測値として知られているもの。
 */
object IosSurface {

    // 連続曲率の係数（角の曲線は各辺から radius×1.52866 まで伸びる）
    const val CONTINUOUS_EXTENT = 1.52866483f
    private const val C_B = 1.08849323f
    private const val C_C = 0.86840689f
    private const val C_D = 0.63149399f
    private const val C_E = 0.07491100f
    private const val C_F = 0.36994867f
    private const val C_G = 0.17964654f

    /**
     * (0,0)-(width,height) に連続曲率の角丸矩形を追加する。
     * 角の曲線が辺の長さを超えないよう、短辺の半分に収まるよう radius を丸める。
     */
    fun Path.addContinuousRoundedRect(width: Float, height: Float, radius: Float) {
        if (width <= 0f || height <= 0f) return
        val limit = minOf(width, height) / 2f / CONTINUOUS_EXTENT
        val r = radius.coerceIn(0f, limit)
        if (r <= 0f) {
            addRect(0f, 0f, width, height, Path.Direction.CW)
            return
        }
        val w = width
        val h = height
        moveTo(CONTINUOUS_EXTENT * r, 0f)
        lineTo(w - CONTINUOUS_EXTENT * r, 0f)
        cubicTo(w - C_B * r, 0f, w - C_C * r, 0f, w - C_D * r, C_E * r)
        cubicTo(w - C_F * r, C_G * r, w - C_G * r, C_F * r, w - C_E * r, C_D * r)
        cubicTo(w, C_C * r, w, C_B * r, w, CONTINUOUS_EXTENT * r)
        lineTo(w, h - CONTINUOUS_EXTENT * r)
        cubicTo(w, h - C_B * r, w, h - C_C * r, w - C_E * r, h - C_D * r)
        cubicTo(w - C_G * r, h - C_F * r, w - C_F * r, h - C_G * r, w - C_D * r, h - C_E * r)
        cubicTo(w - C_C * r, h, w - C_B * r, h, w - CONTINUOUS_EXTENT * r, h)
        lineTo(CONTINUOUS_EXTENT * r, h)
        cubicTo(C_B * r, h, C_C * r, h, C_D * r, h - C_E * r)
        cubicTo(C_F * r, h - C_G * r, C_G * r, h - C_F * r, C_E * r, h - C_D * r)
        cubicTo(0f, h - C_C * r, 0f, h - C_B * r, 0f, h - CONTINUOUS_EXTENT * r)
        lineTo(0f, CONTINUOUS_EXTENT * r)
        cubicTo(0f, C_B * r, 0f, C_C * r, C_E * r, C_D * r)
        cubicTo(C_G * r, C_F * r, C_F * r, C_G * r, C_D * r, C_E * r)
        cubicTo(C_C * r, 0f, C_B * r, 0f, CONTINUOUS_EXTENT * r, 0f)
        close()
    }

    /**
     * iOS 27 風の面を描く Drawable。
     *
     * @param fill        地色（不透明色を渡す。半透明にしたい場合は呼び出し側で alpha 込みの色を渡す）
     * @param radiusPx    角丸の半径
     * @param strokePx    外周の線の太さ。0 なら線なし
     * @param strokeColor 外周の線の色
     * @param sheen       内部の拡散光とスペキュラを描くか（押せる面・カードでは true）
     */
    class SurfaceDrawable(
        private val fill: Int,
        private val radiusPx: Float,
        private val strokePx: Float = 0f,
        private val strokeColor: Int = 0,
        private val sheen: Boolean = true,
    ) : Drawable() {

        private val path = Path()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fill
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = strokeColor
        }
        private var sheenShader: Shader? = null

        override fun onBoundsChange(bounds: Rect) {
            rebuild(bounds)
        }

        private fun rebuild(bounds: Rect) {
            path.reset()
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            // 線は輪郭の中心に乗るため、線幅の半分だけ内側に寄せて描く。
            val inset = strokePx / 2f
            val sub = Path()
            sub.addContinuousRoundedRect(w - inset * 2f, h - inset * 2f, radiusPx)
            sub.offset(bounds.left + inset, bounds.top + inset)
            path.set(sub)

            sheenShader = if (sheen && h > 0f) {
                // 上端の拡散光 → 中ほどで消える → 下端でごく淡い陰り。
                LinearGradient(
                    0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                    intArrayOf(0x24FFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x0C000000),
                    floatArrayOf(0f, 0.34f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        }

        override fun draw(canvas: Canvas) {
            if (path.isEmpty) return
            canvas.drawPath(path, fillPaint)
            sheenShader?.let {
                sheenPaint.shader = it
                canvas.drawPath(path, sheenPaint)
            }
            if (strokePx > 0f) canvas.drawPath(path, edgePaint)
        }

        override fun getOutline(outline: android.graphics.Outline) {
            // 影は実際に描いた形と一致させる。円弧の角丸で近似すると、角では
            // 円弧のほうが外側に張り出すため、面の外へ影がはみ出して
            // 角に三日月状の影が見えてしまう。
            //
            // API 30+ は凸パスをそのまま渡せる。それ未満では角丸矩形にするが、
            // 面より内側に収まるよう半径を少し小さく取り、はみ出しを防ぐ。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !path.isEmpty) {
                runCatching { outline.setPath(path) }.onFailure {
                    outline.setRoundRect(bounds, radiusPx * LEGACY_SHADOW_RADIUS_SCALE)
                }
            } else {
                outline.setRoundRect(bounds, radiusPx * LEGACY_SHADOW_RADIUS_SCALE)
            }
        }

        override fun setAlpha(alpha: Int) {
            // RippleDrawable のフェードや View の alpha アニメーションは content 層の
            // setAlpha を呼ぶ。塗りだけ薄くすると、縁と拡散光が不透明のまま残って
            // 輪郭が浮いて見えるため、3 層すべてに同じ倍率を掛ける。
            val scale = alpha / 255f
            fillPaint.alpha = (Color.alpha(fill) * scale).toInt()
            edgePaint.alpha = (Color.alpha(strokeColor) * scale).toInt()
            sheenPaint.alpha = alpha
        }

        override fun getAlpha(): Int = sheenPaint.alpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            edgePaint.colorFilter = colorFilter
            sheenPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android API; required override")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * API 30 未満で影を角丸矩形に落とすときの半径の倍率。
     * 円弧は角で連続曲率より外へ張り出すため、少し小さく取って面の内側に収める。
     */
    private const val LEGACY_SHADOW_RADIUS_SCALE = 0.92f

    /**
     * 押せる面。[SurfaceDrawable] に iOS 風の淡い押下フィードバック（ripple）を重ねる。
     *
     * iOS のボタンは押すと全体がわずかに暗く（明るい面なら暗く、濃い面なら明るく）
     * なる。Android の ripple で近似し、マスクに同じ形状を渡して角からはみ出さない
     * ようにする。
     */
    fun pressable(
        fill: Int,
        radiusPx: Float,
        strokePx: Float = 0f,
        strokeColor: Int = 0,
        rippleColor: Int,
        sheen: Boolean = true,
    ): Drawable {
        val content = SurfaceDrawable(fill, radiusPx, strokePx, strokeColor, sheen)
        // マスクも同じ連続曲率で切る。円弧の角丸で代用すると、角で円弧のほうが
        // 外へ張り出すぶん ripple の色が面からはみ出して見える。
        val mask = SurfaceDrawable(
            fill = 0xFF000000.toInt(),
            radiusPx = radiusPx,
            sheen = false,
        )
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    /**
     * 円形の押せる面（iOS の送信ボタンなど）。
     *
     * [SurfaceDrawable] の半径は「角の曲線が辺を食い尽くさない」ように
     * 短辺の 1/2/1.52866 までに丸められるため、連続曲率のパスでは真円にできない。
     * 円が欲しい場所はこちらを使う。
     */
    fun pressableCircle(fill: Int, rippleColor: Int): Drawable {
        val content = ShapeDrawable(OvalShape()).apply { paint.color = fill }
        val mask = ShapeDrawable(OvalShape()).apply { paint.color = 0xFF000000.toInt() }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    /** dp → px。 */
    fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density
}
