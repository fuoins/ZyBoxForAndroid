package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

/**
 * Liquid Glass · 外观设置。
 *
 * 抽屉里单独开的一页：排序、布局、卡片样式，以及日间/夜间各自独立的背景图。
 */
class AppearanceFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.appearance_settings)

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, AppearancePreferenceFragment())
            .commitAllowingStateLoss()
    }
}
