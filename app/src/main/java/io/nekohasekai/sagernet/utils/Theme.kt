package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

/**
 * 主题入口。
 *
 * 旧的「21 套配色主题」已被整体移除：应用只有一套 Liquid Glass 主题，
 * 日间 = 冷银玻璃，夜间 = 烟熏玻璃，由系统深色模式或「夜间模式」偏好驱动。
 *
 * 保留本类是为了不破坏既有调用点（ThemedActivity / 设置页 / 对话框）。
 */
object Theme {

    fun apply(context: Context) {
        context.setTheme(R.style.Theme_SagerNet)
    }

    fun applyDialog(context: Context) {
        context.setTheme(R.style.Theme_SagerNet_Dialog)
    }

    fun getTheme(): Int = R.style.Theme_SagerNet

    fun getDialogTheme(): Int = R.style.Theme_SagerNet_Dialog

    /**
     * 兼容旧签名：无论传入哪个历史主题 id，都只有液态玻璃主题。
     * 这样即使旧数据里残留 appTheme 值，也不会崩溃或回退到彩色主题。
     */
    fun getTheme(@Suppress("UNUSED_PARAMETER") theme: Int): Int = R.style.Theme_SagerNet

    fun getDialogTheme(@Suppress("UNUSED_PARAMETER") theme: Int): Int =
        R.style.Theme_SagerNet_Dialog

    var currentNightMode = -1

    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}
