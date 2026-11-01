package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import kotlin.math.min
import kotlin.random.Random

/**
 * Liquid Glass 的核心材质。
 *
 * 不是「半透明白色圆角矩形」那么简单，而是按真实玻璃的光学层次逐层绘制：
 *
 *  1. 折射体      —— 垂直渐变（顶部亮、底部略暗），模拟光线穿过玻璃后的衰减
 *  2. 体积噪点    —— 极弱的颗粒，消除大面积纯色的「塑料感」与色带
 *  3. 顶部镜面    —— 上半部的高光衰减，是玻璃「厚度」的来源
 *  4. 底部内反射  —— 底缘的一圈微光，模拟光在玻璃内部的回弹
 *  5. 边缘高光    —— 左上亮、右下暗的渐变描边，形成真实棱边
 *  6. 环境遮蔽    —— 贴着边缘的极淡暗角，让玻璃「嵌」进背景而不是浮在上面
 *
 * 所有颜色都来自主题的 glass_* token，因此日/夜自动切换，且**不含任何品牌色**。
 */
class GlassSurfaceDrawable(
    private val context: Context,
    private var cornerRadius: Float = 22f * context.resources.displayMetrics.density,
    @ColorInt private var fillTop: Int = Color.WHITE,
    @ColorInt private var fillBottom: Int = Color.WHITE,
    @ColorInt private var strokeColor: Int = Color.WHITE,
    @ColorInt private var highlightColor: Int = Color.WHITE,
    private var strokeWidth: Float = 1f * context.resources.displayMetrics.density,
    private var accentMode: Boolean = false,
    private var textured: Boolean = true
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val path = Path()
    private val boundsF = RectF()

    private var fillShader: Shader? = null
    private var strokeShader: Shader? = null
    private var glossShader: Shader? = null
    private var shadeShader: Shader? = null

    private var drawAlpha = 255
    private var paintFilter: ColorFilter? = null
    private var pressed = false
    private var checked = false

    init {
        strokePaint.strokeWidth = strokeWidth
        if (textured) {
            grainPaint.shader = grainShader()
            grainPaint.alpha = if (accentMode) 10 else 14
        }
    }

    fun setCornerRadius(radius: Float) {
        if (cornerRadius != radius) {
            cornerRadius = radius
            rebuildGeometry()
            invalidateSelf()
        }
    }

    fun setFillColors(@ColorInt top: Int, @ColorInt bottom: Int) {
        fillTop = top
        fillBottom = bottom
        rebuildGeometry()
        invalidateSelf()
    }

    fun setStrokeColor(@ColorInt color: Int) {
        strokeColor = color
        rebuildGeometry()
        invalidateSelf()
    }

    fun setStrokeWidth(width: Float) {
        if (strokePaint.strokeWidth != width) {
            strokePaint.strokeWidth = width
            invalidateSelf()
        }
    }

    fun setHighlightColor(@ColorInt color: Int) {
        highlightColor = color
        rebuildGeometry()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val w = boundsF.width()
        val h = boundsF.height()
        if (w <= 0f || h <= 0f) return

        val r = min(cornerRadius, min(w, h) / 2f)
        path.reset()
        path.addRoundRect(boundsF, r, r, Path.Direction.CW)

        // 1. 折射体：顶部比底部亮 12%，形成玻璃的「厚度」
        fillShader = LinearGradient(
            0f, boundsF.top, 0f, boundsF.bottom,
            fillTop, fillBottom, Shader.TileMode.CLAMP
        )

        // 3. 顶部镜面：前 55% 高度内从高光衰减到透明
        glossShader = LinearGradient(
            0f, boundsF.top, 0f, boundsF.top + h * 0.55f,
            intArrayOf(highlightColor, withAlpha(highlightColor, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )

        // 4. 底部内反射 + 环境遮蔽
        shadeShader = LinearGradient(
            0f, boundsF.bottom - h * 0.28f, 0f, boundsF.bottom,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, if (accentMode) 34 else 14)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )

        // 5. 边缘高光：左上亮 → 右下暗，模拟光照方向（左上 135°）
        strokeShader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.right, boundsF.bottom,
            intArrayOf(highlightColor, withAlpha(strokeColor, 120), strokeColor),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (boundsF.isEmpty) return
        val save = canvas.save()
        if (drawAlpha < 255) {
            canvas.saveLayerAlpha(boundsF, drawAlpha)
        }

        // 折射体
        fillPaint.shader = fillShader
        fillPaint.colorFilter = paintFilter
        canvas.drawPath(path, fillPaint)

        // 体积噪点
        if (textured) {
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(boundsF, grainPaint)
            canvas.restore()
        }

        // 顶部镜面
        glossPaint.shader = glossShader
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(boundsF, glossPaint)
        canvas.restore()

        // 底部内反射
        shadePaint.shader = shadeShader
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(boundsF, shadePaint)
        canvas.restore()

        // 边缘高光
        strokePaint.shader = strokeShader
        strokePaint.alpha = if (pressed) 255 else 226
        canvas.drawPath(path, strokePaint)
        strokePaint.shader = null

        if (drawAlpha < 255) {
            canvas.restore()
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        if (drawAlpha != alpha) {
            drawAlpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paintFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val newPressed = state.contains(android.R.attr.state_pressed)
        val newChecked = state.contains(android.R.attr.state_checked) ||
                state.contains(android.R.attr.state_selected)
        if (newPressed != pressed || newChecked != checked) {
            pressed = newPressed
            checked = newChecked
            invalidateSelf()
            return true
        }
        return false
    }

    override fun getOutline(outline: Outline) {
        super.getOutline(outline)
        val r = min(cornerRadius, min(boundsF.width(), boundsF.height()) / 2f)
        outline.setRoundRect(bounds, r)
    }

    private fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    )

    companion object {

        private var grain: android.graphics.Bitmap? = null

        /** 64×64 的微弱噪点纹理，重复平铺后可消除渐变断层。 */
        fun grainShader(): Shader {
            val bitmap = grain ?: run {
                val size = 64
                val bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888
                )
                val random = Random(20261101)
                val pixels = IntArray(size * size)
                for (i in pixels.indices) {
                    val v = random.nextInt(0, 22)
                    pixels[i] = Color.argb(v, 255, 255, 255)
                }
                bmp.setPixels(pixels, 0, size, 0, 0, size, size)
                grain = bmp
                bmp
            }
            return android.graphics.BitmapShader(
                bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
            )
        }

        /** 从当前主题读取一整套玻璃参数，保证全局材质完全一致。 */
        fun fromTheme(
            context: Context,
            accent: Boolean = false,
            soft: Boolean = false,
            cornerDp: Float = 22f
        ): GlassSurfaceDrawable {
            val attrs = context.obtainStyledAttributes(
                intArrayOf(
                    R.attr.glassSurfaceStrongColor,
                    R.attr.glassSurfaceSoftColor,
                    R.attr.glassSurfaceColor,
                    R.attr.glassStrokeColor,
                    R.attr.glassHighlightColor,
                    R.attr.glassAccentColor,
                    R.attr.glassOnAccentColor
                )
            )
            val strong = attrs.getColor(0, Color.WHITE)
            val softColor = attrs.getColor(1, Color.WHITE)
            val surface = attrs.getColor(2, Color.WHITE)
            val stroke = attrs.getColor(3, Color.WHITE)
            val highlight = attrs.getColor(4, Color.WHITE)
            val accentColor = attrs.getColor(5, Color.BLACK)
            attrs.recycle()

            return if (accent) {
                GlassSurfaceDrawable(
                    context = context,
                    cornerRadius = dpStatic(context, cornerDp),
                    fillTop = accentColor,
                    fillBottom = accentColor,
                    strokeColor = highlight,
                    highlightColor = highlight,
                    accentMode = true
                )
            } else {
                GlassSurfaceDrawable(
                    context = context,
                    cornerRadius = dpStatic(context, cornerDp),
                    fillTop = if (soft) surface else strong,
                    fillBottom = if (soft) softColor else surface,
                    strokeColor = stroke,
                    highlightColor = highlight
                )
            }
        }

        fun dpStatic(context: Context, value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )

        fun color(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)
    }
}
