package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
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
    private var photoBlur = 0

    /**
     * 柔化用的小图缓存。窗口背景每帧都会重绘，模糊不能现算，
     * 只在「换了图 / 改了模糊强度」时重建一次。
     */
    private var softPhoto: Bitmap? = null
    private var softKey: Long = 0L

    /**
     * 设置自定义背景图。[dim] 是 0..80 的暗化百分比，压在照片之上，
     * 保证前景玻璃和文字在亮图上也读得清。
     *
     * [blur] 是 0..40 的模糊强度。这里没法用 `RenderEffect` ——
     * 那是 View 才有的 API，`Drawable` 上没有；而窗口背景一旦改成 View
     * 就会重新出现「只盖住内容区」的长方形接缝（就是当初要躲开的坑）。
     * 所以改成先在后台把图逐级减半降采样，绘制时再用双线性插值放大：
     * 缩得越狠，放大后越糊，观感与高斯模糊接近，且 minSdk 21 都能用。
     */
    fun setPhoto(bitmap: Bitmap?, dim: Int, blur: Int = 0) {
        val nextBlur = blur.coerceIn(0, 40)
        if (photo === bitmap && photoDim == dim.coerceIn(0, 80) && photoBlur == nextBlur) {
            invalidateSelf()
            return
        }
        photo = bitmap
        photoDim = dim.coerceIn(0, 80)
        photoBlur = nextBlur
        dropSoftPhoto()
        invalidateSelf()
    }

    private fun dropSoftPhoto() {
        val old = softPhoto ?: return
        softPhoto = null
        softKey = 0L
        // 极端情况（1×1 的图）Bitmap.createScaledBitmap 会原样返回入参，
        // 此时软图就是原图本身，绝不能 recycle，否则下一次 draw 直接崩。
        if (old !== photo && !old.isRecycled) old.recycle()
    }

    /**
     * 取实际要画的那张图：模糊强度为 0 时直接返回原图，
     * 否则返回预降采样过的柔化版（带缓存）。
     */
    private fun displayPhoto(): Bitmap? {
        val source = photo ?: return null
        if (source.isRecycled) return null
        if (photoBlur <= 0) return source

        val key = (System.identityHashCode(source).toLong() shl 8) or photoBlur.toLong()
        val cached = softPhoto
        if (cached != null && softKey == key && !cached.isRecycled) return cached

        val built = buildSoftPhoto(source, photoBlur)
        if (built == null || built === source) return source
        dropSoftPhoto()
        softPhoto = built
        softKey = key
        return built
    }

    /**
     * 逐级 1/2 缩小。一次缩到底会丢掉太多细节、边缘发硬；
     * 两级以上的连续插值降采样更接近高斯核，放大后过渡更顺。
     */
    private fun buildSoftPhoto(source: Bitmap, blur: Int): Bitmap? {
        // blur 40 → 约 1/13 边长，放大回全屏就是很重的毛玻璃
        val targetFactor = 1f + blur * 0.30f
        return try {
            var current = source
            var owned = false
            var factor = 1f
            while (factor * 2f <= targetFactor) {
                val w = (current.width / 2).coerceAtLeast(1)
                val h = (current.height / 2).coerceAtLeast(1)
                if (w <= 1 && h <= 1) break
                val next = Bitmap.createScaledBitmap(current, w, h, true)
                if (owned && current !== next) current.recycle()
                current = next
                owned = true
                factor *= 2f
            }
            if (!owned) {
                // 强度很低、连一级都降不下去，就复制一份 1/2 的
                val w = (source.width / 2).coerceAtLeast(1)
                val h = (source.height / 2).coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, w, h, true)
            } else {
                current
            }
        } catch (_: Throwable) {
            null
        }
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

        val bitmap = displayPhoto() ?: return
        if (bitmap.isRecycled) return
        paint.shader = null
        paint.alpha = 255
        // 柔化版是几十像素的小图，放大时靠双线性插值把过渡抹开；
        // 关掉滤波就会看到硬边的色块（马赛克），不是模糊。
        paint.isFilterBitmap = true
        drawPhotoCenterCrop(canvas, bitmap)
        if (photoDim > 0) {
            canvas.drawColor((photoDim * 255 / 100) shl 24)
        }
    }

    /**
     * 和 ImageView 的 `scaleType="centerCrop"` 对齐：等比放大到刚好盖满，
     * 多余部分裁掉。直接 `drawBitmap(..., Rect(bounds))` 会按窗口比例拉伸，
     * 竖图会被压扁。
     */
    private fun drawPhotoCenterCrop(canvas: Canvas, bitmap: Bitmap) {
        val b = bounds
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val scale = maxOf(b.width() / bw, b.height() / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = b.left + (b.width() - dw) / 2f
        val top = b.top + (b.height() - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), paint)
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
