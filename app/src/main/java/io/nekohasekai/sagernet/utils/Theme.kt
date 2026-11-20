package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    // ZyBox One Theme: 全局固定 Teal 单色，明暗由 nightTheme 四态控制。
    // 原 21 色选择器已移除；保留 R.style 常量名以兼容既有引用。
    private const val THEME = R.style.Theme_SagerNet
    private const val DIALOG_THEME = R.style.Theme_SagerNet_Dialog

    fun apply(context: Context) {
        context.setTheme(THEME)
    }

    fun applyDialog(context: Context) {
        context.setTheme(DIALOG_THEME)
    }

    fun getTheme(): Int = THEME

    fun getDialogTheme(): Int = DIALOG_THEME

    // 保留带参重载：老配置里 appTheme 仍可读，但统一忽略，兼容不崩。
    fun getTheme(theme: Int): Int = THEME

    fun getDialogTheme(theme: Int): Int = DIALOG_THEME

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