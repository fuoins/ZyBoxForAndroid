package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R

/**
 * Liquid Glass 按钮。
 *
 * 一块被光打亮的玻璃片：顶亮底暗的折射、边缘高光、按下时棱边变亮。
 * 强调按钮（[accent] = true）使用「深墨玻璃」，在日间是墨黑、夜间是亮白，
 * 文字色由主题的 glassOnAccent 自动反色 —— 全程不出现任何品牌色。
 */
class LiquidGlassButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var accent = false
    private var cornerDp = 28f

    private val surface: GlassSurfaceDrawable

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(
                attrs, R.styleable.LiquidGlassButton, defStyleAttr, 0
            )
            accent = ta.getBoolean(R.styleable.LiquidGlassButton_glassAccent, false)
            cornerDp = ta.getDimension(
                R.styleable.LiquidGlassButton_glassCornerRadius,
                GlassSurfaceDrawable.dpStatic(context, 28f)
            ) / context.resources.displayMetrics.density
            ta.recycle()
        }

        surface = if (accent) {
            GlassSurfaceDrawable.fromTheme(context, accent = true, cornerDp = cornerDp)
        } else {
            GlassSurfaceDrawable.fromTheme(context, soft = true, cornerDp = cornerDp)
        }

        backgroundTintList = null
        background = surface
        elevation = 0f
        stateListAnimator = null
        isAllCaps = false
        insetTop = 0
        insetBottom = 0

        if (accent) {
            setTextColor(colorAttr(R.attr.glassOnAccentColor))
        } else {
            setTextColor(colorAttr(R.attr.glassTextPrimaryColor))
        }

        // 玻璃自带的水波纹：极低透明度的墨色，按下像玻璃被压出涟漪
        // 注意：View.setForeground 从 API 23 起才有，低版本自动跳过
        if (android.os.Build.VERSION.SDK_INT >= 23) foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.glass_accent_soft)
            ),
            null,
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = GlassSurfaceDrawable.dpStatic(context, cornerDp)
                setColor(android.graphics.Color.WHITE)
            }
        )
    }

    fun setAccent(enabled: Boolean) {
        if (accent == enabled) return
        accent = enabled
        val drawable = if (enabled) {
            GlassSurfaceDrawable.fromTheme(context, accent = true, cornerDp = cornerDp)
        } else {
            GlassSurfaceDrawable.fromTheme(context, soft = true, cornerDp = cornerDp)
        }
        background = drawable
        setTextColor(
            colorAttr(if (enabled) R.attr.glassOnAccentColor else R.attr.glassTextPrimaryColor)
        )
    }

    private fun colorAttr(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, android.graphics.Color.BLACK)
        ta.recycle()
        return color
    }
}
