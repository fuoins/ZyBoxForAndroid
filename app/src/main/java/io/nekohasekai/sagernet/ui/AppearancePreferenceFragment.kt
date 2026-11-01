package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.AppearanceBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Liquid Glass · 外观设置内容。
 *
 * 「排序与外观」原本藏在主页右上角的三点菜单里，这里给它一个固定入口，
 * 并补上日间/夜间各自独立的背景图。
 */
class AppearancePreferenceFragment : PreferenceFragmentCompat() {

    /** 记住这一次选图是给日间还是夜间用的。 */
    private var pendingNight = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importBackground(uri, pendingNight)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(R.xml.appearance_preferences)

        /*
         * 排序是「每个分组各自的属性」（ProxyGroup.order），不是全局开关。
         * 放在外观页时按「应用到全部分组」处理，summary 里也这么写，
         * 免得用户以为只改了当前分组。
         */
        findPreference<SimpleMenuPreference>("appearance_group_order")?.apply {
            value = DataStore.groupOrder.toString()
            setOnPreferenceChangeListener { _, newValue ->
                val order = newValue.toString().toIntOrNull() ?: 0
                DataStore.groupOrder = order
                applyOrderToAllGroups(order)
                true
            }
        }

        findPreference<Preference>("appearance_bg_day")?.setOnPreferenceClickListener {
            pendingNight = false
            pickImage.launch("image/*")
            true
        }

        findPreference<Preference>("appearance_bg_night")?.setOnPreferenceClickListener {
            pendingNight = true
            pickImage.launch("image/*")
            true
        }

        findPreference<Preference>("appearance_bg_clear")?.setOnPreferenceClickListener {
            AppearanceBackground.clearAll()
            DataStore.appearanceBgEnabled = false
            refresh()
            (activity as? MainActivity)?.applyAppearanceBackground()
            true
        }

        // 开关/滑杆不在这里实时重绘背景（拖动时会反复解码大图），
        // 回主页时 MainActivity.onResume 统一应用。
        findPreference<SwitchPreferenceCompat>(Key.APPEARANCE_BG_ENABLED)?.apply {
            isChecked = DataStore.appearanceBgEnabled
            setOnPreferenceChangeListener { _, newValue ->
                DataStore.appearanceBgEnabled = newValue as Boolean
                (activity as? MainActivity)?.applyAppearanceBackground()
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findPreference<Preference>("appearance_bg_day")?.summary = backgroundSummary(false)
        findPreference<Preference>("appearance_bg_night")?.summary = backgroundSummary(true)
        findPreference<SwitchPreferenceCompat>(Key.APPEARANCE_BG_ENABLED)?.isChecked =
            DataStore.appearanceBgEnabled
    }

    private fun backgroundSummary(night: Boolean): String = getString(
        if (AppearanceBackground.isSet(night)) R.string.appearance_bg_set
        else R.string.appearance_bg_unset
    )

    private fun applyOrderToAllGroups(order: Int) {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // forEach 的 lambda 不是 suspend，不能在这里直接调 updateGroup
                val groups = SagerDatabase.groupDao.allGroups()
                for (group in groups) {
                    if (group.order != order) {
                        group.order = order
                        GroupManager.updateGroup(group)
                    }
                }
            }
        }
    }

    private fun importBackground(uri: Uri, night: Boolean) {
        val appContext = requireContext().applicationContext
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                AppearanceBackground.save(appContext, uri, night)
            }
            if (!isAdded) return@launch
            if (saved) {
                DataStore.appearanceBgEnabled = true
                refresh()
                (activity as? MainActivity)?.applyAppearanceBackground()
            } else {
                Snackbar.make(
                    requireView(),
                    R.string.appearance_bg_failed,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }
}
