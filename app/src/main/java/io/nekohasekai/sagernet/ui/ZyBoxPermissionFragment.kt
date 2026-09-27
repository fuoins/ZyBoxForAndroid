package io.nekohasekai.sagernet.ui

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

// ZyBox: 权限与初始化查询页（菜单可随时进入，与首次启动弹窗共用逻辑）
class ZyBoxPermissionFragment : ToolbarFragment(R.layout.layout_zybox_permission) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.zybox_permission_title)

        val statusAuto = view.findViewById<TextView>(R.id.perm_status_auto)
        val statusNotif = view.findViewById<TextView>(R.id.perm_status_notif)
        val statusVpn = view.findViewById<TextView>(R.id.perm_status_vpn)
        val statusRoot = view.findViewById<TextView>(R.id.perm_status_root)

        fun refreshRoot() {
            // 异步检测 root（su 可用性），不阻塞 UI
            Thread {
                val hasRoot = try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                    p.destroy()
                    ok
                } catch (_: Exception) {
                    false
                }
                view.post { statusRoot.text = if (hasRoot) "✅" else "❌" }
            }.start()
        }

        fun refresh() {
            val activity = requireActivity()
            statusAuto.text = if (activity is MainActivity && activity.isAutoInitDone) "✅" else "❌"
            val notifGranted = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
            statusNotif.text = if (notifGranted) "✅" else "❌"
            statusVpn.text = if (VpnService.prepare(requireContext()) == null) "✅" else "❌"
            refreshRoot()
        }

        view.findViewById<View>(R.id.perm_btn_auto).setOnClickListener {
            (requireActivity() as? MainActivity)?.runAutoInit()
            refresh()
        }
        view.findViewById<View>(R.id.perm_btn_notif).setOnClickListener {
            (requireActivity() as? MainActivity)?.requestNotifPermission()
        }
        view.findViewById<View>(R.id.perm_btn_vpn).setOnClickListener {
            (requireActivity() as? MainActivity)?.requestVpnPermission()
        }

        refresh()
    }

    // ZyBox: 供 MainActivity 权限回调时刷新
    fun refreshStatus() {
        view?.let { refreshViews(it) }
    }

    private fun refreshViews(it: View) {
        it.findViewById<TextView>(R.id.perm_status_auto).text =
            if ((requireActivity() as? MainActivity)?.isAutoInitDone == true) "✅" else "❌"
        it.findViewById<TextView>(R.id.perm_status_notif).text =
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) "❌" else "✅"
        it.findViewById<TextView>(R.id.perm_status_vpn).text =
            if (VpnService.prepare(requireContext()) == null) "✅" else "❌"
        val statusRoot = it.findViewById<TextView>(R.id.perm_status_root)
        Thread {
            val hasRoot = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                p.destroy()
                ok
            } catch (_: Exception) {
                false
            }
            view?.post { statusRoot.text = if (hasRoot) "✅" else "❌" }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回后刷新状态
        view?.let {
            val sA = it.findViewById<TextView>(R.id.perm_status_auto)
            val sN = it.findViewById<TextView>(R.id.perm_status_notif)
            val sV = it.findViewById<TextView>(R.id.perm_status_vpn)
            sA.text = if ((requireActivity() as? MainActivity)?.isAutoInitDone == true) "✅" else "❌"
            sN.text = if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) "✅" else "❌"
            sV.text = if (VpnService.prepare(requireContext()) == null) "✅" else "❌"
            val statusRoot = it.findViewById<TextView>(R.id.perm_status_root)
            Thread {
                val hasRoot = try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                    p.destroy()
                    ok
                } catch (_: Exception) {
                    false
                }
                view?.post { statusRoot.text = if (hasRoot) "✅" else "❌" }
            }.start()
        }
    }
}
