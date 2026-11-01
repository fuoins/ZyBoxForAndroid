package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Liquid Glass 的光源层。
 *
 * 整个界面漂浮在一层缓慢流动的柔光之上：左上主光、右侧冷光、底部反光。
 * 玻璃面板的半透明材质会把这一层「透」出来，形成真正的液态玻璃观感。
 *
 * 性能设计：
 * - 光斑 Shader 使用「单位半径」并在绘制时缩放，只在尺寸变化时创建一次，
 *   动画期间零对象分配，中低端机也能稳定 30fps。
 * - 纯 Canvas 径向渐变，API 21 起可用，不依赖 RenderEffect / RenderScript。
 * - 离屏 / 不可见时自动停止。
 */
class LiquidGlassBackdrop @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var running = false
    private var animate = true

    private val glowColors = intArrayOf(
        ContextCompat.getColor(context, R.color.glass_glow_a),
        ContextCompat.getColor(context, R.color.glass_glow_b),
        ContextCompat.getColor(context, R.color.glass_glow_c),
        ContextCompat.getColor(context, R.color.glass_glow_d)
    )
    private val glowShaders = arrayOfNulls<RadialGradient>(4)

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassBackdrop)
            animate = ta.getBoolean(R.styleable.LiquidGlassBackdrop_glassAnimate, true)
            ta.recycle()
        }
        rebuildShaders()
    }

    fun setAnimated(enabled: Boolean) {
        if (animate == enabled) return
        animate = enabled
        if (enabled && isAttachedToWindow) start() else stop()
    }

    private fun rebuildShaders() {
        for (i in glowColors.indices) {
            val color = glowColors[i]
            glowShaders[i] = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(color, color and 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow && animate) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        postOnAnimation(ticker)
    }

    private fun stop() {
        running = false
        removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.0021f
            if (phase > 1f) phase -= 1f
            invalidate()
            postOnAnimationDelayed(this, 33L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        /*
         * 这里刻意不 canvas.drawColor(baseColor)：
         * 底色交给窗口背景（glass_window_background）铺满整块屏幕，
         * 本层只叠加光斑。自己再铺一层不透明色，会让内容区被画成
         * 一块独立的矩形板 —— 在状态栏/导航栏旁边就能看到它的边界。
         */
        val t = phase * 2f * Math.PI.toFloat()
        val breath = 1f + 0.06f * sin(t)
        val driftX = w * 0.06f * sin(t * 0.75f)
        val driftY = h * 0.04f * cos(t * 0.9f)

        // 左上主光源：最大、最亮
        drawGlow(canvas, 0, w * 0.08f + driftX, -h * 0.06f + driftY, w * 0.92f * breath, 255)
        // 右侧冷光：制造冷暖对比，玻璃才有「折射」感
        drawGlow(canvas, 1, w * 0.98f - driftX * 0.6f, h * 0.42f + driftY * 0.5f, w * 0.78f, 255)
        // 底部反光：把光从下方托起来
        drawGlow(canvas, 2, w * 0.46f + driftX * 0.4f, h * 1.02f - driftY * 0.5f, w * 1.05f * (2f - breath), 255)
        // 右上冷灰补光
        drawGlow(canvas, 3, w * 0.88f + driftX * 0.3f, -h * 0.02f, w * 0.52f, 255)
    }

    private fun drawGlow(
        canvas: Canvas,
        index: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Int
    ) {
        if (radius <= 1f) return
        val shader = glowShaders[index] ?: return
        paint.shader = shader
        paint.alpha = alpha
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
        paint.alpha = 255
    }
}
