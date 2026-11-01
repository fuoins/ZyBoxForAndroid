package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Liquid Glass · 窗口级背景（底色 + 流体光斑 + 可选背景图）。
 *
 * 为什么不做成 View：
 * AppCompat 的 subDecor（`FitWindowsLinearLayout`）自带 `fitsSystemWindows="true"`，
 * 会给内容区加一层状态栏/导航栏高度的 padding。任何铺在内容区里的 View
 * 都只能覆盖「状态栏下沿 → 导航栏上沿」这一块矩形，而窗口背景是铺满整窗的。
 * 两者的光斑/亮度对不上，屏幕边缘就会露出一圈更暗的边 —— 也就是用户看到的
 * 「主页上有个长方形，长方形里有顶部栏」。
 *
 * 把光斑画进 `Window.setBackgroundDrawable()` 之后，它由 DecorView 绘制，
 * 天生覆盖整个窗口（含系统栏区域），不存在任何内容区边界。
 *
 * 代价是光斑不再逐帧流动（窗口背景每帧重绘会拖垮整屏），
 * 换来的是彻底没有矩形接缝 —— 这个取舍是值得的。
 */
class LiquidGlassWindowBackground(
    private val baseColor: Int,
    glowColors: IntArray,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowShaders: List<RadialGradient> = glowColors.map { color ->
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(color, color and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private var photo: Bitmap? = null
    private var photoDim = 0

    /**
     * 设置自定义背景图。[dim] 是 0..80 的暗化百分比，压在照片之上，
     * 保证前景玻璃和文字在亮图上也读得清。
     */
    fun setPhoto(bitmap: Bitmap?, dim: Int) {
        photo = bitmap
        photoDim = dim.coerceIn(0, 80)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(baseColor)

        // 与 LiquidGlassBackdrop 完全一致的四盏灯：左上主光、右侧冷光、底部反光、右上补光
        drawGlow(canvas, 0, w * 0.08f, -h * 0.06f, w * 0.92f)
        drawGlow(canvas, 1, w * 0.98f, h * 0.42f, w * 0.78f)
        drawGlow(canvas, 2, w * 0.46f, h * 1.02f, w * 1.05f)
        drawGlow(canvas, 3, w * 0.88f, -h * 0.02f, w * 0.52f)

        val bitmap = photo ?: return
        if (bitmap.isRecycled) return
        paint.shader = null
        paint.alpha = 255
        canvas.drawBitmap(bitmap, null, Rect(b), paint)
        if (photoDim > 0) {
            canvas.drawColor((photoDim * 255 / 100) shl 24)
        }
    }

    private fun drawGlow(canvas: Canvas, index: Int, cx: Float, cy: Float, radius: Float) {
        if (radius <= 1f) return
        val shader = glowShaders.getOrNull(index) ?: return
        paint.shader = shader
        paint.alpha = 255
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        // 窗口级背景不需要整体透明度
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
