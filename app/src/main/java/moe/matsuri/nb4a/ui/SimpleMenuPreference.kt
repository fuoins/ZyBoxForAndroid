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
         * Liquid Glass：Spinner 的下拉是 framework 自己开的 PopupWindow，
         * 而且**不抢焦点** —— Activity 不会收到 onWindowFocusChanged(false)，
         * 全局限焦模糊对它失效。这里在下拉被触碰时手动盯一会儿：
         * 一旦 isPopupShowing 为 true 就模糊宿主，收起即撤。
         * 不拦截事件（返回 false），点击行为保持原样。
         */
        mSpinner.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                    GlassBlur.watchWhile(GlassBlur.decorOf(v.context)) { mSpinner.isPopupShowing }
            }
            false
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