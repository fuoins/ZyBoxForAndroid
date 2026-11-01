package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.nekohasekai.sagernet.R

/**
 * Liquid Glass 的「环境层」：把系统栏、窗口、对话框都调成同一块玻璃。
 *
 * 很多玻璃化改造失败，是因为内容玻璃了、系统栏还是实心色块。
 * 这里统一处理，保证从状态栏到底部导航栏都是连续的材质。
 */
object GlassUi {

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    /** 透明系统栏 + 自适应图标明暗 + 关闭系统强制对比度遮罩。 */
    fun applySystemBars(activity: Activity) {
        val window = activity.window ?: return
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }

            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val light = !isNight(activity)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        } catch (_: Throwable) {
            // 个别 ROM 会抛异常，忽略即可，视觉上仍能降级为半透明
        }
    }

    /**
     * 对话框玻璃化：透明窗口底 + 系统级背景模糊（Android 12+）。
     * 系统不支持时自动降级为主题里的半透明面板。
     */
    fun applyDialogGlass(dialog: Dialog) {
        val window = dialog.window ?: return
        try {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= 31) {
                window.setBackgroundBlurRadius(dp(dialog.context, 28f).toInt())
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val light = !isNight(dialog.context)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        } catch (_: Throwable) {
        }
    }

    /**
     * 给页面铺一层流动光源。已经有 Backdrop 的页面重复调用会被忽略。
     */
    fun ensureBackdrop(activity: Activity, container: ViewGroup) {
        if (container.findViewWithTag<View>(BACKDROP_TAG) != null) return
        val backdrop = io.nekohasekai.sagernet.widget.LiquidGlassBackdrop(activity)
        backdrop.tag = BACKDROP_TAG
        container.addView(
            backdrop,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** 让某个 View 轻微上浮，模拟玻璃片与桌面的间隙。 */
    fun lift(view: View, dp: Float = 6f) {
        view.translationZ = dp(view.context, dp)
        view.elevation = 0f
    }

    /** 把状态栏高度作为顶部内边距补到给定 View 上（避免内容被挖孔/状态栏压住）。 */
    fun padForStatusBar(view: View) {
        val res = view.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val height = if (id > 0) res.getDimensionPixelSize(id) else 0
        view.setPadding(view.paddingLeft, view.paddingTop + height, view.paddingRight, view.paddingBottom)
    }

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private const val BACKDROP_TAG = "liquid_glass_backdrop"
}
