package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R

/**
 * Liquid Glass 卡片。
 *
 * MaterialCardView 只保留它的行为（点击、选中、圆角裁剪、无障碍），
 * 视觉全部交给 [GlassSurfaceDrawable]：折射渐变 + 镜面 + 棱边高光。
 *
 * 关键：把卡片自身的 MaterialShapeDrawable 背景置空，否则它会在玻璃层上
 * 盖一层纯色，玻璃就「死」了。
 */
class LiquidGlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val surface: GlassSurfaceDrawable
    private val cornerPx: Float
    private val themeFillTop: Int
    private val themeFillBottom: Int
    private val themeStroke: Int

    init {
        var cornerDp = 22f
        var tint = Color.TRANSPARENT
        var refraction = true

        if (attrs != null) {
            val ta = context.obtainStyledAttributes(
                attrs, R.styleable.LiquidGlassCardView, defStyleAttr, 0
            )
            cornerDp = ta.getDimension(
                R.styleable.LiquidGlassCardView_glassCornerRadius,
                GlassSurfaceDrawable.dpStatic(context, 22f)
            ) / context.resources.displayMetrics.density
            tint = ta.getColor(R.styleable.LiquidGlassCardView_glassTint, Color.TRANSPARENT)
            refraction = ta.getBoolean(R.styleable.LiquidGlassCardView_glassRefraction, true)
            ta.recycle()
        }

        val glass = context.obtainStyledAttributes(
            intArrayOf(
                R.attr.glassSurfaceStrongColor,
                R.attr.glassSurfaceColor,
                R.attr.glassStrokeColor,
                R.attr.glassHighlightColor
            )
        )
        themeFillTop = glass.getColor(0, Color.WHITE)
        themeFillBottom = glass.getColor(1, Color.WHITE)
        themeStroke = glass.getColor(2, Color.WHITE)
        val highlight = glass.getColor(3, Color.WHITE)
        glass.recycle()

        cornerPx = GlassSurfaceDrawable.dpStatic(context, cornerDp)
        surface = GlassSurfaceDrawable(
            context = context,
            cornerRadius = cornerPx,
            fillTop = if (tint == Color.TRANSPARENT) themeFillTop else tint,
            fillBottom = if (tint == Color.TRANSPARENT) themeFillBottom else tint,
            strokeColor = themeStroke,
            highlightColor = highlight
        )
        if (!refraction) surface.setAlpha(220)

        // 清空 Material 自带背景与描边，交给我们自己的材质
        setCardBackgroundColor(Color.TRANSPARENT)
        cardElevation = 0f
        strokeWidth = 0

        background = surface
        clipToOutline = true

        val rippleColor = ContextCompat.getColor(context, R.color.glass_accent_soft)
        foreground = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
        )
    }

    /** 整卡染色（多选区间选中时用）。传 0 恢复默认玻璃。 */
    fun setGlassTintColor(color: Int) {
        if (color == 0) {
            surface.setFillColors(themeFillTop, themeFillBottom)
        } else {
            surface.setFillColors(color, color)
        }
    }

    /** 选中态棱边：比 Material 的 strokeWidth 更真实，动的是玻璃本身的边。 */
    fun setGlassStroke(color: Int, widthPx: Float) {
        surface.setStrokeColor(color)
        surface.setStrokeWidth(widthPx)
    }

    fun resetGlassStroke(widthPx: Float) {
        surface.setStrokeColor(themeStroke)
        surface.setStrokeWidth(widthPx)
    }
}
