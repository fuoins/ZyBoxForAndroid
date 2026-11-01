/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.GlassBlur

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: SimpleMenuAdapter

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val mSpinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        mSpinner.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT

        /*
         * 三处白底，一处都不能留：
         *
         * 1. Spinner 控件本身 —— 某些 Material 版本的 spinnerStyle 会挂一块
         *    实色 background，在玻璃行里就是贴在右边的白方块。直接置空。
         * 2. 装 Spinner 的 widget_frame —— preference 布局给它挂了
         *    ?android:attr/selectableItemBackground。那是**直角**的水波纹，
         *    手指一按下，按钮位置立刻亮起一块白色长方形 —— 用户两轮反馈的
         *    「分组类型 / 排序点击后有个长方形的白色，位置和按钮一样」就是它。
         *    涟漪的圆角由外层玻璃行提供，这里不需要再画一层。
         * 3. 下拉浮层 —— 默认取 ?attr/colorPopupBackground（colorSurface），
         *    浅色下是一块 97% 白的板子。换成 glass_popup_menu（薄玻璃），
         *    配合背景高斯模糊才是同一套材质。
         *
         * 注意 Spinner 的 setter 叫 setPopupBackgroundDrawable（getter 是
         * getPopupBackground），名字不一致，Kotlin 合成属性用不了，必须显式调用。
         */
        mSpinner.background = null
        mSpinner.setPopupBackgroundDrawable(
            ContextCompat.getDrawable(mSpinner.context, R.drawable.glass_popup_menu)
        )
        // preference.xml 里用的是 framework 的 id（@android:id/widget_frame），
        // 不是 androidx.preference.R.id.widget_frame —— 后者编译期找不到。
        holder.itemView.findViewById<View>(android.R.id.widget_frame)
            ?.background = null

        /*
         * Liquid Glass：Spinner 的下拉是 framework 自己开的 PopupWindow。
         * 它多半是 modal 的、会夺焦点，全局限焦模糊能兜住；但为了在
         * 「不夺焦点」的 ROM 上也有同样的效果，这里在触摸时盯一眼下拉状态。
         *
         * Spinner.isPopupShowing() 是 @hide（编译期 Unresolved），只能反射。
         * 反射被 hidden-api 拦掉时退化成「点击后固定模糊 1.6s」——
         * 至少覆盖用户快速选择的动作，且不会留下后台任务。
         * 不拦截事件（返回 false），点击行为保持原样。
         */
        mSpinner.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val decor = GlassBlur.decorOf(v.context)
                val probe = popupShowingMethod
                if (decor != null && probe != null) {
                    GlassBlur.watchWhile(decor) {
                        probe.invoke(mSpinner) as? Boolean ?: false
                    }
                } else if (decor != null) {
                    GlassBlur.acquire(decor)
                    v.postDelayed({ GlassBlur.release(decor) }, 1600L)
                }
            }
            false
        }
    }

    private companion object {
        /** `Spinner.isPopupShowing()` 是 @hide，编译期找不到，只能反射。 */
        val popupShowingMethod = try {
            Spinner::class.java.getMethod("isPopupShowing")
        } catch (_: Throwable) {
            null
        }
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = SimpleMenuAdapter(getContext(), R.layout.simple_menu_dropdown_item)
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = entryValues.indexOf(value)
            mAdapter.notifyDataSetChanged()
        }
    }

    private class SimpleMenuAdapter(context: Context, resource: Int) :
        ArrayAdapter<CharSequence?>(context, resource) {

        var currentPosition = -1

        /**
         * Spinner 自己显示的「当前值」也是用这个布局 + spinnerDropDownItemStyle
         * inflate 的，那个 style 自带一块白底 —— 用户看到的「点击位置突然出现
         * 一个白色长方形」其实就是它，一直贴在设置行右侧。这里抹成透明。
         */
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            view.setBackgroundColor(Color.TRANSPARENT)
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View = super.getDropDownView(position, convertView, parent)
            /*
             * 不能用 setBackgroundColor：实色块会把弹窗的玻璃底整片盖住，
             * 下拉就变成一条白条（旧版就是 #FAFAFA，夜间尤其突兀）。
             * 普通项透明、当前项贴一片内缩圆角的薄玻璃，玻璃质感才连得上。
             */
            if (position == currentPosition) {
                view.setBackgroundResource(R.drawable.glass_menu_item_selected)
            } else {
                view.setBackgroundColor(Color.TRANSPARENT)
            }
            return view
        }
    }
}