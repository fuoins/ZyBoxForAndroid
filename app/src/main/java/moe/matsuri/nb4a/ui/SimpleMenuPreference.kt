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