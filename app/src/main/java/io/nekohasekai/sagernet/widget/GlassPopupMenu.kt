package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat

/**
 * Liquid Glass 弹出菜单。
 *
 * 菜单背景本身由主题的 popupBackground（glass_popup，97% 实底玻璃）负责，
 * 这里只补「高斯模糊」：Android 12+ 给宿主 Activity 的 DecorView 挂 RenderEffect，
 * 菜单是独立窗口不受影响，于是背景被真实模糊、菜单保持锐利，
 * 视觉上就是一块压在磨砂玻璃上的浮层。
 *
 * 没有走 PopupMenu 内部 PopupWindow 的反射：AppCompat 1.6 的菜单是
 * MenuPopupHelper → MenuPopup → StandardMenuPopup/CascadingMenuPopup 三层结构，
 * 子菜单还会分裂成多个窗口，反射拿到的那一个往往不是当前显示的那个。
 *
 * Android 11 及以下没有 RenderEffect，退化为高不透明度的磨砂玻璃，仍然保证可读。
 */
class GlassPopupMenu @JvmOverloads constructor(
    context: Context,
    anchor: View,
    gravity: Int = GravityCompat.START,
) : PopupMenu(context, anchor, gravity) {

    private val host: Activity? = anchor.context.findActivity()
    private var dismissListener: PopupMenu.OnDismissListener? = null

    override fun show() {
        // 必须在 super.show() 之前挂上，MenuPopupHelper 只在显示时把监听器下发
        super.setOnDismissListener {
            setHostBlur(false)
            dismissListener?.onDismiss(this)
        }
        super.show()
        setHostBlur(true)
    }

    override fun dismiss() {
        super.dismiss()
        setHostBlur(false)
    }

    override fun setOnDismissListener(listener: PopupMenu.OnDismissListener?) {
        dismissListener = listener
    }

    private fun setHostBlur(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val decor = host?.window?.decorView ?: return
        decor.setRenderEffect(
            if (enable) {
                val radius = 20f * decor.resources.displayMetrics.density
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            } else {
                null
            }
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
