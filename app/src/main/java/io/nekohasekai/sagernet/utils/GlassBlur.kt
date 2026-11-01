package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import java.util.WeakHashMap

/**
 * Liquid Glass · 高斯模糊（背景虚化）。
 *
 * 弹窗、下拉菜单、抽屉都要「压在一块毛玻璃上」，但 Android 没有一个
 * 通用的「模糊我身后那张位图」的公开 API。这里用 [RenderEffect]
 * （Android 12 / API 31 起）把宿主界面整体做一次离屏模糊：
 * 弹窗是独立窗口，不受影响，于是背景糊掉、弹窗保持锐利。
 *
 * 关键点是 **引用计数**。同一个 DecorView 可能同时被
 * 「弹窗自己」和「Activity 失焦监听」两条路径要求模糊，
 * 任何一条先松手都不能把另一条的效果清掉，否则会看到
 * 弹窗还在、背景却突然清晰回来的闪跳。
 *
 * Android 11 及以下没有 RenderEffect，方法全部退化为空操作，
 * 由材质本身（[io.nekohasekai.sagernet.widget.GlassSurfaceDrawable]）保证可读性。
 */
object GlassBlur {

    /** 模糊半径（dp）。太大的半径在长列表上会掉帧，20dp 已经足够“糊”。 */
    private const val RADIUS_DP = 20f

    private val holders = WeakHashMap<View, Int>()
    private val watchers = WeakHashMap<View, Boolean>()

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 申请一层模糊；同一 View 多次申请需要对应次数的 [release]。
     *
     * 版本判断必须写成内联的 `Build.VERSION.SDK_INT < ...` 而不是封装成
     * [isSupported]：Android Lint 的 NewApi 检查只认前者，走属性的话
     * 会在 lintVitalRelease 阶段被判定为「在 API 21 上调用了 API 31」。
     */
    fun acquire(view: View?) {
        val target = view ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val count = (holders[target] ?: 0) + 1
        holders[target] = count
        if (count == 1) applyEffect(target)
    }

    /** 释放一层模糊，计数归零时真正清除。 */
    fun release(view: View?) {
        val target = view ?: return
        val count = (holders[target] ?: 1) - 1
        if (count > 0) {
            holders[target] = count
        } else {
            holders.remove(target)
            clearEffect(target)
        }
    }

    /**
     * 无条件清除（页面销毁、切后台时调用，避免残留）。
     *
     * 同时摘掉 [watchWhile] 的轮询登记 —— 否则轮询下一轮看到浮层还在，
     * 又会把刚清掉的模糊重新加回去，表现为「切后台回来整页还是糊的」。
     */
    fun clear(view: View?) {
        val target = view ?: return
        holders.remove(target)
        watchers.remove(target)
        clearEffect(target)
    }

    fun acquire(activity: Activity?) {
        acquire(activity?.window?.decorView)
    }

    fun release(activity: Activity?) {
        release(activity?.window?.decorView)
    }

    fun clear(activity: Activity?) {
        clear(activity?.window?.decorView)
    }

    /** 从任意 Context 反查出它所在窗口的 DecorView。 */
    fun decorOf(context: Context?): View? {
        var ctx = context
        var guard = 0
        while (ctx is ContextWrapper && guard++ < 8) {
            if (ctx is Activity) return ctx.window?.decorView
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * 跟踪一个「弹出即消失」的浮层：只要 [isShowing] 返回 true 就保持模糊，
     * 一旦收起立刻撤掉。
     *
     * 给 Spinner 这类**不抢焦点**的 PopupWindow 用 —— 它们不会触发
     * Activity 的 onWindowFocusChanged，只能靠轮询自己的可见状态。
     * 轮询只在浮层存活期间存在，收起即停；[GRACE_MS] 内还没弹出来
     * （用户只是点了一下没选中）就自动放弃，不会留下后台任务。
     */
    fun watchWhile(view: View?, isShowing: () -> Boolean) {
        val target = view ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (watchers.containsKey(target)) return
        watchers[target] = true

        val poller = object : Runnable {
            private var held = false
            private var waited = 0L

            override fun run() {
                /*
                 * 三条退出路径，缺一不可：
                 * 1. 宿主被 clear() 摘掉登记 —— 用户切了后台/小窗，别再抢回来；
                 * 2. 宿主窗口已经不可见 —— 浮层即使还"开着"也看不见了；
                 * 3. 浮层自己收起 —— 正常路径。
                 */
                if (watchers[target] != true) {
                    if (held) {
                        held = false
                        release(target)
                    }
                    return
                }
                if (!target.isAttachedToWindow || target.windowVisibility != View.VISIBLE) {
                    if (held) {
                        held = false
                        release(target)
                    }
                    watchers.remove(target)
                    return
                }
                val showing = try {
                    isShowing()
                } catch (_: Throwable) {
                    false
                }
                if (showing) {
                    if (!held) {
                        acquire(target)
                        held = true
                    }
                    target.postDelayed(this, POLL_STEP_MS)
                    return
                }
                // 收起（或从未弹出）：先还掉模糊，再决定要不要继续等
                if (held) {
                    held = false
                    release(target)
                    watchers.remove(target)
                    return
                }
                if (waited < GRACE_MS) {
                    waited += POLL_STEP_MS
                    target.postDelayed(this, POLL_STEP_MS)
                    return
                }
                watchers.remove(target)
            }
        }
        target.postDelayed(poller, POLL_STEP_MS)
    }

    private fun applyEffect(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val radius = RADIUS_DP * view.resources.displayMetrics.density
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        } catch (_: Throwable) {
            // 个别 GPU / ROM 会拒绝：保持清晰，不影响功能
            holders.remove(view)
        }
    }

    private fun clearEffect(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            view.setRenderEffect(null)
        } catch (_: Throwable) {
        }
    }

    private const val POLL_STEP_MS = 110L
    private const val GRACE_MS = 1500L
}
