package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutZyboxAboutBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class ZyBoxAboutFragment : ToolbarFragment(R.layout.layout_zybox_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutZyboxAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.zybox_about)

        binding.zyboxAboutVersion.text =
            getString(R.string.zybox_about_version, BuildConfig.VERSION_NAME)

        // 捐赠支持 → 浏览器
        binding.zyboxAboutDonateButton.setOnClickListener {
            try {
                requireContext().launchCustomTab("https://zy520.de5.net/juanzeng/")
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        // 开源地址 → GitHub
        binding.zyboxAboutGithub.setOnClickListener {
            try {
                requireContext().launchCustomTab("https://github.com/fuoins/ZyBoxForAndroid")
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        // 检查更新：GitHub releases/latest 对比当前版本
        binding.zyboxAboutCheckUpdate.setOnClickListener {
            checkUpdate()
        }

        // 授权协议
        runOnDefaultDispatcher {
            try {
                val license = view.context.assets.open("LICENSE").bufferedReader().readText()
                onMainDispatcher {
                    binding.zyboxAboutLicense.text = license
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    private fun checkUpdate() {
        Toast.makeText(requireContext(), R.string.zybox_checking_update, Toast.LENGTH_SHORT).show()
        runOnIoDispatcher {
            try {
                val client = Libcore.newHttpClient().apply {
                    modernTLS()
                    trySocks5(DataStore.mixedPort)
                }
                val response = client.newRequest().apply {
                    setURL("https://api.github.com/repos/fuoins/ZyBoxForAndroid/releases/latest")
                }.execute()
                val release = JSONObject(Util.getStringBox(response.contentString))
                val releaseName = release.getString("name")
                val releaseUrl = release.getString("html_url")
                val haveUpdate = releaseName.isNotBlank() && !releaseName.contains(BuildConfig.VERSION_NAME)
                onMainDispatcher {
                    if (haveUpdate) {
                        val context = requireContext()
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.zybox_update_available)
                            .setMessage(
                                context.getString(
                                    R.string.update_dialog_message,
                                    BuildConfig.VERSION_NAME,
                                    releaseName
                                )
                            )
                            .setPositiveButton(R.string.yes) { _, _ ->
                                try {
                                    context.launchCustomTab(releaseUrl)
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    } else {
                        Toast.makeText(context, R.string.zybox_no_update, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(requireContext(), e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
