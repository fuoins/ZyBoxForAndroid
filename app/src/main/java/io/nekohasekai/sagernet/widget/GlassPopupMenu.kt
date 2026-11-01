package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import io.nekohasekai.sagernet.R

/**
 * Liquid Glass 弹出菜单。
 *
 * AppCompat 的 PopupMenu 没有暴露窗口，拿不到 [android.view.Window.setBackgroundBlurRadius]，
 * 所以这里在 show() 之后反射到内部的 PopupWindow，对它的 DecorView 直接下发
 * FLAG_BLUR_BEHIND + blurBehindRadius（Android 12+）。系统会把菜单背后的内容
 * 做一次真实的高斯模糊，菜单本身再叠 97% 的实底玻璃，形成磨砂玻璃效果。
 *
 * Android 11 及以下没有跨窗口模糊，退化为高不透明度的磨砂玻璃，仍然保证可读。
 */
class GlassPopupMenu @JvmOverloads constructor(
    context: Context,
    anchor: View,
    gravity: Int = GravityCompat.START,
) : PopupMenu(context, anchor, gravity) {

    override fun show() {
        super.show()
        try {
            applyGlassSurface()
            applyBlurBehind()
        } catch (_: Throwable) {
            // 反射失败不影响菜单本身，至少实底玻璃已经生效
        }
    }

    /** 把菜单内容层换成玻璃底，圆角与描边都由 drawable 负责 */
    private fun applyGlassSurface() {
        val content = popupWindow()?.contentView ?: return
        val radius = 20f * content.resources.displayMetrics.density
        content.setBackgroundResource(R.drawable.glass_popup)
        content.clipToOutline = true
        content.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    /** Android 12+：让系统模糊菜单背后的内容 */
    private fun applyBlurBehind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val decor = popupWindow()?.contentView?.rootView ?: return
        val radius = (26f * decor.resources.displayMetrics.density).toInt()

        fun apply() {
            val lp = decor.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = radius
            val wm = decor.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return
            wm.updateViewLayout(decor, lp)
        }

        if (decor.isAttachedToWindow) apply() else decor.post { apply() }
    }

    /**
     * PopupMenu.mPopup (MenuPopupWindow) -> ListPopupWindow.mPopup (PopupWindow)
     */
    private fun popupWindow(): android.widget.PopupWindow? {
        val listPopup = readField(this, "mPopup") ?: return null
        return readField(listPopup, "mPopup") as? android.widget.PopupWindow
    }

    private fun readField(owner: Any, name: String): Any? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(owner)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}
