package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ZyBox: VPN 热点页（root 下把 VPN 流量桥接到共享接口，参考 VPNHotspot 原理简化实现）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    private fun rootAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/su", "-c", "id").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    // 执行 root 命令，返回合并输出（异常返回 null）
    private fun rootExec(cmd: String): String? {
        return try {
            val p = ProcessBuilder("/system/bin/su", "-c", cmd).start()
            val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            p.waitFor()
            out.trim()
        } catch (_: Exception) {
            null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusIcon = view.findViewById<TextView>(R.id.hotspot_root_status)
        val rootHint = view.findViewById<TextView>(R.id.hotspot_root_hint)
        val toggleState = view.findViewById<TextView>(R.id.hotspot_toggle_state)
        val toggleCard = view.findViewById<MaterialCardView>(R.id.hotspot_toggle_card)
        val wifi = view.findViewById<TextView>(R.id.hotspot_wifi)
        val usb = view.findViewById<TextView>(R.id.hotspot_usb)
        val bt = view.findViewById<TextView>(R.id.hotspot_bt)
        val hw = view.findViewById<Switch>(R.id.hotspot_hw)

        fun snack(msg: String) {
            (activity as? MainActivity)?.snackbar(msg)?.show()
        }

        // ROOT 权限检测
        fun refreshRoot() {
            statusIcon.text = "❌"
            rootHint.text = getString(R.string.vpn_hotspot_root_checking)
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = rootAvailable()
                withContext(Dispatchers.Main) {
                    statusIcon.text = if (ok) "✓" else "❌"
                    rootHint.text = if (ok) "Root" else "No Root"
                }
            }
        }

        fun refreshToggleState() {
            toggleState.text = getString(
                if (DataStore.vpnHotspotEnabled) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
            )
        }

        refreshRoot()
        refreshToggleState()

        view.findViewById<View>(R.id.hotspot_root_refresh).setOnClickListener { refreshRoot() }

        // VPN 热点主开关
        toggleCard.setOnClickListener {
            if (!rootAvailable()) {
                snack(getString(R.string.vpn_hotspot_need_root))
                return@setOnClickListener
            }
            val turnOn = !DataStore.vpnHotspotEnabled
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec(vpnHotspotScript(turnOn))
                withContext(Dispatchers.Main) {
                    when {
                        out == null -> snack(getString(R.string.vpn_hotspot_cmd_fail))
                        out.contains("IFACE_MISS") ->
                            snack(getString(R.string.vpn_hotspot_no_iface))
                        else -> {
                            DataStore.vpnHotspotEnabled = turnOn
                            refreshToggleState()
                            snack(
                                getString(
                                    if (turnOn) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
                                )
                            )
                        }
                    }
                }
            }
        }

        // 打开热点（root 命令；失败则打开系统共享设置页）
        wifi.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec("cmd wifi start-softap")
                withContext(Dispatchers.Main) {
                    if (out.isNullOrBlank() || out.contains("error", ignoreCase = true) ||
                        out.contains("exception", ignoreCase = true)
                    ) {
                        startTetherSettings()
                    } else {
                        snack(getString(R.string.vpn_hotspot_wifi))
                    }
                }
            }
        }

        // USB 网络共享
        usb.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec("svc usb setFunctions rndis")
                withContext(Dispatchers.Main) {
                    if (out.isNullOrBlank() || out.contains("error", ignoreCase = true)) {
                        startTetherSettings()
                    } else {
                        snack(getString(R.string.vpn_hotspot_usb))
                    }
                }
            }
        }

        // 蓝牙网络共享（开蓝牙并引导到系统共享设置）
        bt.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                rootExec("svc bluetooth enable")
                withContext(Dispatchers.Main) {
                    snack(getString(R.string.vpn_hotspot_bt))
                    startTetherSettings()
                }
            }
        }

        // 网络共享硬件加速（系统默认开，读取显示当前状态，可切换）
        fun refreshHw() {
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec("settings get global tether_hw_accel")
                withContext(Dispatchers.Main) {
                    hw.isChecked = out.isNullOrBlank() || out == "1" || out == "true"
                }
            }
        }
        refreshHw()
        hw.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                rootExec("settings put global tether_hw_accel ${if (checked) 1 else 0}")
            }
        }
    }

    private fun startTetherSettings() {
        try {
            startActivity(
                Intent("android.settings.TETHERING_SETTINGS")
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // VPN 热点 iptables 桥接脚本（参考 VPNHotspot 原理简化：共享接口流量转发到 VPN 隧道并 MASQUERADE）
    private fun vpnHotspotScript(on: Boolean): String {
        val tunFind = "ip -o link show | grep -oE 'tun[0-9]+|utun[0-9]+' | head -1"
        val ifaceFind = "for i in usb0 rndis0 bnep0 wlan0 ap0; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done"
        val rules = if (on) {
            "iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                "iptables -A FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                "iptables -A FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                "sysctl -w net.ipv4.ip_forward=1"
        } else {
            "iptables -t nat -D POSTROUTING -o \$TUN -j MASQUERADE; " +
                "iptables -D FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                "iptables -D FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
        }
        return "TUN=\$($tunFind); IFACE=\$($ifaceFind); " +
            "[ -z \"\$TUN\" ] && exit 3; [ -z \"\$IFACE\" ] && echo IFACE_MISS && exit 4; " +
            rules + "; echo DONE"
    }
}
