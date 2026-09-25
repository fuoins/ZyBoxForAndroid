package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ZyBox: VPN 热点页（root 桥接 VPN 隧道与共享接口；参考 VPNHotspot 原理简化）
// v2：全部功能带开关，状态读取系统真实状态，失败引导系统共享设置
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

    // ---- 系统真实状态读取 ----
    private fun hotspotEnabled(): Boolean {
        return try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = if (Build.VERSION.SDK_INT >= 33) {
                WifiManager::class.java.getMethod("isTetheredHotspotEnabled")
            } else {
                WifiManager::class.java.getMethod("isWifiApEnabled")
            }
            m.invoke(wm) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun tetheredIfaces(): List<String> {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val m = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            (m.invoke(cm) as Array<*>).map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun usbEnabled(): Boolean = tetheredIfaces().any {
        it.contains("usb") || it.contains("rndis")
    }

    private fun btEnabled(): Boolean = tetheredIfaces().any { it.contains("bnep") }

    // ---- 开关操作 ----
    // 热点：API<33 反射 setWifiApEnabled；API>=33 root 命令（cmd wifi）
    private fun setHotspot(on: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                rootExec(if (on) "cmd wifi start-softap" else "cmd wifi stop-softap")
                true
            } else {
                val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
                val cfg = WifiConfiguration::class.java.getDeclaredConstructor().newInstance()
                val m = WifiManager::class.java.getMethod(
                    "setWifiApEnabled", WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(wm, cfg, on)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun setUsb(on: Boolean): Boolean {
        return try {
            val out = rootExec(if (on) "svc usb setFunctions rndis" else "svc usb setFunctions none")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setBt(on: Boolean): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val m = ConnectivityManager::class.java.getMethod(
                "startTethering", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Class.forName("android.os.ResultReceiver")
            )
            m.invoke(cm, 2, on, null) // TETHERING_BLUETOOTH(@hide)=2
            true
        } catch (_: Exception) {
            // 权限不足，退化为 root 命令尝试
            val out = rootExec("svc bluetooth enable")
            out != null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusIcon = view.findViewById<TextView>(R.id.hotspot_root_status)
        val rootHint = view.findViewById<TextView>(R.id.hotspot_root_hint)
        val toggleState = view.findViewById<TextView>(R.id.hotspot_toggle_state)
        val toggleSwitch = view.findViewById<Switch>(R.id.hotspot_toggle_switch)
        val wifiSwitch = view.findViewById<Switch>(R.id.hotspot_wifi_switch)
        val usbSwitch = view.findViewById<Switch>(R.id.hotspot_usb_switch)
        val btSwitch = view.findViewById<Switch>(R.id.hotspot_bt_switch)
        val hwSwitch = view.findViewById<Switch>(R.id.hotspot_hw)

        fun snack(msg: String) {
            (activity as? MainActivity)?.snackbar(msg)?.show()
        }

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
            toggleSwitch.isChecked = DataStore.vpnHotspotEnabled
        }

        fun refreshSystemState() {
            lifecycleScope.launch(Dispatchers.IO) {
                val h = hotspotEnabled()
                val u = usbEnabled()
                val b = btEnabled()
                withContext(Dispatchers.Main) {
                    wifiSwitch.isChecked = h
                    usbSwitch.isChecked = u
                    btSwitch.isChecked = b
                }
            }
        }

        fun refreshHw() {
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec("settings get global tether_hw_accel")
                withContext(Dispatchers.Main) {
                    hwSwitch.isChecked = out.isNullOrBlank() || out == "1" || out == "true"
                }
            }
        }

        refreshRoot()
        refreshToggleState()
        refreshSystemState()
        refreshHw()

        view.findViewById<View>(R.id.hotspot_root_refresh).setOnClickListener {
            refreshRoot()
            refreshSystemState()
        }

        // VPN 热点开关
        toggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rootAvailable()) {
                snack(getString(R.string.vpn_hotspot_need_root))
                toggleSwitch.isChecked = !checked
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val out = rootExec(vpnHotspotScript(checked))
                val tunOk = out?.contains("TUN_MISS") != true
                val ifaceOk = out?.contains("IFACE_MISS") != true
                withContext(Dispatchers.Main) {
                    when {
                        out == null -> snack(getString(R.string.vpn_hotspot_cmd_fail))
                        !tunOk -> {
                            DataStore.vpnHotspotEnabled = false
                            refreshToggleState()
                            snack(getString(R.string.vpn_hotspot_tun_miss))
                        }
                        !ifaceOk -> {
                            DataStore.vpnHotspotEnabled = false
                            refreshToggleState()
                            snack(getString(R.string.vpn_hotspot_no_iface))
                        }
                        else -> {
                            DataStore.vpnHotspotEnabled = checked
                            refreshToggleState()
                            snack(
                                getString(
                                    if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
                                ) + if (checked) "\n" + getString(R.string.vpn_hotspot_hw_tip) else ""
                            )
                        }
                    }
                }
            }
        }

        // 热点开关
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setHotspot(checked)
                delay(800)
                val now = hotspotEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        wifiSwitch.isChecked = now
                        snack(getString(R.string.vpn_hotspot_open_settings))
                        startTetherSettings()
                    }
                }
            }
        }

        // USB 开关
        usbSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setUsb(checked)
                delay(800)
                val now = usbEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        usbSwitch.isChecked = now
                        snack(getString(R.string.vpn_hotspot_open_settings))
                        startTetherSettings()
                    }
                }
            }
        }

        // 蓝牙开关
        btSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setBt(checked)
                delay(800)
                val now = btEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        btSwitch.isChecked = now
                        snack(getString(R.string.vpn_hotspot_open_settings))
                        startTetherSettings()
                    }
                }
            }
        }

        // 硬件加速
        hwSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                rootExec("settings put global tether_hw_accel ${if (checked) 1 else 0}")
            }
        }

        // 管理系统共享
        view.findViewById<MaterialCardView>(R.id.hotspot_manage).setOnClickListener {
            startTetherSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新真实状态
        view?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                val h = hotspotEnabled()
                val u = usbEnabled()
                val b = btEnabled()
                withContext(Dispatchers.Main) {
                    it.findViewById<Switch>(R.id.hotspot_wifi_switch).isChecked = h
                    it.findViewById<Switch>(R.id.hotspot_usb_switch).isChecked = u
                    it.findViewById<Switch>(R.id.hotspot_bt_switch).isChecked = b
                }
            }
        }
    }

    private fun startTetherSettings() {
        try {
            startActivity(Intent("android.settings.TETHERING_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // VPN 热点 iptables 桥接脚本（参考 VPNHotspot 原理：共享接口流量转发 VPN 隧道 + MASQUERADE）
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
            "[ -z \"\$TUN\" ] && echo TUN_MISS && exit 3; [ -z \"\$IFACE\" ] && echo IFACE_MISS && exit 4; " +
            rules + "; echo DONE"
    }
}
