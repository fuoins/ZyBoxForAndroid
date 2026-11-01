package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.snackbar.Snackbar
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
         * 系统栏内边距交给 AppCompat 的 subDecor（`FitWindowsLinearLayout`
         * 自带 fitsSystemWindows）统一处理 —— 它会给内容区补上状态栏/导航栏
         * 高度的 padding，列表和顶栏都不会被系统栏压住。
         *
         * 这里绝对不能再补一次：上一版在 SDK 35+ 手动给 appbar 加 top padding，
         * 结果顶栏被推下两倍状态栏高度，上方空出一条背景色，看起来就像
         * 「主页上有个长方形」的一部分。
         *
         * 背景（光斑 + 背景图）走窗口背景，由 DecorView 铺满整窗，
         * 不受内容区 padding 影响 —— 详见 MainActivity.setupWindowBackground()。
         */
        uiMode = resources.configuration.uiMode
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
     * 但「失焦」也发生在：切后台、进最近任务、别的 App 开小窗压上来、
     * 下拉通知栏。这些场景本页要么已经不可见，要么只是被别人盖住，
     * 再把本页糊掉就是 bug。所以：
     *   1. 延迟 [POPUP_BLUR_DELAY] 再判断 —— 真正切后台时 onPause 早已执行；
     *   2. 判断时要求 Activity 仍 RESUMED、不在多窗口、窗口仍可见；
     *   3. onPause / onStop 无条件清空**所有**模糊（含 Spinner 轮询持有的那层）。
     *
     * 抽屉（DrawerLayout）不是独立窗口、不失焦，也不做模糊（见 MainActivity）。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (isDialog || !GlassBlur.isSupported) return

        val decor = window?.decorView ?: return
        if (hasFocus) {
            // 弹窗关闭、回到本页：把任何残留（包括轮询路径）一次清干净，
            // 避免「关掉弹窗后背景还是糊的」。
            popupBlurHeld = false
            GlassBlur.clear(decor)
        } else {
            // 焦点变化会先于弹窗真正 attach，也会先于 onPause；
            // 等一小段再判断，避免把「正在切后台」误判成「有弹窗」。
            decor.postDelayed({
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
     * - 别的 App 的小窗压在本页上：本页窗口仍然 VISIBLE，但没有弹窗
     *
     * 排除掉之后，剩下的失焦基本就是 PopupMenu / Dialog / BottomSheet
     * 这类真正盖在本页上的浮层。
     */
    private fun shouldBlurForPopup(): Boolean {
        if (hasWindowFocus() || isFinishing || isDestroyed) return false
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) return false
        // 窗口已经不可见（被切走/被系统收起）就不要再糊了
        val decor = window?.decorView ?: return false
        if (decor.windowVisibility != View.VISIBLE) return false
        /*
         * 最后一道闸：本页最近必须真的被点过。
         *
         * 「别的 App 开小窗压在本页上」这个场景很讨厌 —— 本页仍是全屏、
         * 仍是 RESUMED、也不在多窗口里，上面三个判断全都拦不住，但它
         * 根本没有弹窗，只是被别人的浮窗盖住了。
         *
         * 唯一的区别是：弹窗一定是用户刚点了本页某个控件才弹出来的，
         * 而小窗/切后台时用户在点别的 App，本页收不到任何触摸。
         */
        if (SystemClock.uptimeMillis() - lastTouchAt > POPUP_TOUCH_WINDOW) return false
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                lastTouchAt = SystemClock.uptimeMillis()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        clearPopupBlur()
        super.onPause()
    }

    override fun onStop() {
        clearPopupBlur()
        super.onStop()
    }

    override fun onDestroy() {
        clearPopupBlur()
        super.onDestroy()
    }

    private fun acquirePopupBlur() {
        if (popupBlurHeld) return
        popupBlurHeld = true
        GlassBlur.acquire(window?.decorView)
    }

    /**
     * 无条件清空：不按引用计数逐层 release。
     * Spinner 下拉那条 [GlassBlur.watchWhile] 路径持有的是同一块 DecorView，
     * 只 release 自己那层的话，它那层会一直挂着 —— 用户切到后台/小窗回来
     * 就发现「整个 App 还是糊的」。
     */
    private fun clearPopupBlur() {
        popupBlurHeld = false
        GlassBlur.clear(window?.decorView)
    }

    private var popupBlurHeld = false

    /** 本页最后一次收到触摸的时间（uptimeMillis），用来区分「弹窗」和「别人盖上来」。 */
    private var lastTouchAt = 0L

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
        /**
         * 弹窗弹出动画约 200ms。48ms 太激进 —— 切后台、进最近任务时
         * onWindowFocusChanged(false) 会先到，onPause 往往 100ms 后才执行，
         * 在那个时间窗里判断就会把「离开页面」误认成「有弹窗」，整页糊一下。
         */
        const val POPUP_BLUR_DELAY = 180L

        /**
         * 失焦后多久之内还认为「这个浮层是本页弹出来的」。
         * 长按菜单、二次确认弹窗都在这个范围内；而别人家的小窗、
         * 切后台操作时本页早已收不到触摸，自然就被排除了。
         */
        const val POPUP_TOUCH_WINDOW = 2500L
    }
}