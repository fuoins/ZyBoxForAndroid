package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.GlassBlur
import io.nekohasekai.sagernet.utils.GlassUi
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        // Liquid Glass：透明系统栏 + 自适应图标明暗，让玻璃背景一直延伸到屏幕边缘
        GlassUi.applySystemBars(this)

        /*
         * 图片背景/光斑背景必须铺到系统栏后面，否则内容区就是一块被状态栏
         * 和导航栏框起来的矩形 —— 看起来像「主页上有个长方形」。
         *
         * Android 15 起系统会对 targetSdk 35 强制 edge-to-edge；
         * 更早的版本保持原先的 fitSystemWindows 行为，避免列表被手势条盖住。
         */
        uiMode = resources.configuration.uiMode

        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val top = bars.top
                findViewById<AppBarLayout>(R.id.appbar)?.apply {
                    updatePadding(top = top)
                }
                // Liquid Glass：底部状态坞要浮在手势条之上，不能被系统导航区压住
                findViewById<android.view.View>(R.id.stats)?.let { stats ->
                    val lp = stats.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        val margin = bars.bottom + (10 * resources.displayMetrics.density).toInt()
                        if (lp.bottomMargin != margin) {
                            lp.bottomMargin = margin
                            stats.layoutParams = lp
                        }
                    }
                }
                // 抽屉同样要避开状态栏/导航栏，否则玻璃面板会被系统栏切掉一截
                findViewById<android.view.View>(R.id.nav_view)?.let { nav ->
                    val lp = nav.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        val base = (10 * resources.displayMetrics.density).toInt()
                        val topMargin = bars.top + base
                        val bottomMargin = bars.bottom + base
                        if (lp.topMargin != topMargin || lp.bottomMargin != bottomMargin) {
                            lp.topMargin = topMargin
                            lp.bottomMargin = bottomMargin
                            nav.layoutParams = lp
                        }
                    }
                }
                insets
            }
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    /**
     * Liquid Glass · 全局弹窗背景模糊。
     *
     * 任何**独立窗口**盖在页面上时（PopupMenu、Spinner 下拉、ContextMenu、
     * AlertDialog、BottomSheet……），Activity 都会失去窗口焦点。这里就以
     * 「失焦」为信号给 DecorView 挂一层 RenderEffect，弹窗自己不受影响，
     * 于是出现的是「背景糊掉 + 浮层锐利」的液态玻璃层次。
     *
     * 之所以不挨个改造弹窗：AppCompat / Material / Preference 各自创建
     * 自己的 PopupWindow，没有统一的创建入口，逐个替换必然漏。
     * 焦点是系统替我们统一发出的唯一信号。
     *
     * 例外：下拉通知栏、多任务卡片也会让 Activity 失焦，此时背景会短暂
     * 虚化一下，属可接受的副作用（本就离开了页面）。
     * 抽屉（DrawerLayout）不是独立窗口、不失焦，单独在 MainActivity 处理。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (isDialog || !GlassBlur.isSupported) return

        if (hasFocus) {
            releasePopupBlur()
        } else {
            // 焦点变化会先于弹窗真正 attach，等一帧再判断，避免误糊
            window?.decorView?.postDelayed({
                if (shouldBlurForPopup()) acquirePopupBlur()
            }, POPUP_BLUR_DELAY)
        }
    }

    /**
     * 「失焦」并不等于「有弹窗」。下面几种情况本页仍然完整可见，
     * 只是用户在别处操作，模糊反而碍事：
     *
     * - 切到后台 / 最近任务：Activity 已经不在 RESUMED，onPause 里会撤模糊
     * - 小窗、分屏（多窗口模式）：本页还在屏幕上，只是没拿到焦点
     *
     * 排除掉之后，剩下的失焦基本就是 PopupMenu / Dialog / BottomSheet
     * 这类真正盖在本页上的浮层。
     */
    private fun shouldBlurForPopup(): Boolean {
        if (hasWindowFocus() || isFinishing || isDestroyed) return false
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) return false
        return true
    }

    override fun onPause() {
        releasePopupBlur()
        super.onPause()
    }

    override fun onDestroy() {
        releasePopupBlur()
        GlassBlur.clear(window?.decorView)
        super.onDestroy()
    }

    private fun acquirePopupBlur() {
        if (popupBlurHeld) return
        popupBlurHeld = true
        GlassBlur.acquire(window?.decorView)
    }

    private fun releasePopupBlur() {
        if (!popupBlurHeld) return
        popupBlurHeld = false
        GlassBlur.release(window?.decorView)
    }

    private var popupBlurHeld = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

    private companion object {
        /** 弹窗弹出动画约 200ms，48ms 后宿主还没拿到焦点就认定「被浮层盖住了」。 */
        const val POPUP_BLUR_DELAY = 48L
    }

}