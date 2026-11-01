package io.nekohasekai.sagernet.widget

import android.content.Context
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import io.nekohasekai.sagernet.utils.GlassBlur

/**
 * Liquid Glass 弹出菜单。
 *
 * 菜单背景由主题的 popupBackground（glass_popup 磨砂玻璃）负责，
 * 这里补的是「背景高斯模糊」：菜单弹出时把宿主界面虚化，
 * 菜单是独立窗口不受影响，于是背景糊掉、菜单保持锐利。
 *
 * 真正兜底的是 [io.nekohasekai.sagernet.ui.ThemedActivity] 里的
 * 全局限焦模糊 —— 它会盖住所有 AppCompat 内部创建的弹窗
 * （包括 Toolbar 右上角三点）。这里仍然显式申请一次，是为了在
 * 「弹窗不抢焦点」的设备上也能拿到效果；两条路径由 [GlassBlur]
 * 的引用计数合并，关闭时不会互相踩掉。
 *
 * Android 11 及以下没有 RenderEffect，退化为高不透明度的磨砂玻璃。
 *
 * 注意 `context` 必须自己存一份：AppCompat 的 `PopupMenu` 没有公开
 * 的 `getContext()`，在类体里写 `context` 会直接编译失败。
 */
class GlassPopupMenu @JvmOverloads constructor(
    private val hostContext: Context,
    anchor: View,
    gravity: Int = GravityCompat.START,
) : PopupMenu(hostContext, anchor, gravity) {

    private var dismissListener: PopupMenu.OnDismissListener? = null

    /**
     * 只有「本菜单申请的那一层」需要自己还回去；
     * 标记存在说明尚未归还，避免 dismiss 被调用两次时多减一次计数。
     */
    private var blurAnchor: View? = null

    override fun show() {
        // 必须在 super.show() 之前挂上，MenuPopupHelper 只在显示时把监听器下发
        super.setOnDismissListener {
            releaseBlur()
            dismissListener?.onDismiss(this)
        }
        super.show()
        acquireBlur()
    }

    override fun dismiss() {
        super.dismiss()
        releaseBlur()
    }

    override fun setOnDismissListener(listener: PopupMenu.OnDismissListener?) {
        dismissListener = listener
    }

    private fun acquireBlur() {
        if (blurAnchor != null) return
        val decor = GlassBlur.decorOf(hostContext) ?: return
        blurAnchor = decor
        GlassBlur.acquire(decor)
    }

    private fun releaseBlur() {
        val decor = blurAnchor ?: return
        blurAnchor = null
        GlassBlur.release(decor)
    }
}
