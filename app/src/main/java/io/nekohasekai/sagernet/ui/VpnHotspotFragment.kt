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

// ZyBox: VPN 热点页（学习 VPNHotspot 实现：tether_offload_disabled 硬件加速、ndc ipfwd/sysctl 转发、
// iptables MASQUERADE+FORWARD、TetheringManager 反射开关；Android 13+ 开启时自动禁用硬件加速）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    companion object {
        // VPN 隧道接口（tun0 / utun* 等）
        const val TUN_PATTERN = "tun[0-9]+|utun[0-9]+"
        // 共享接口（热点/USB/蓝牙）
        const val IFACE_PATTERN = "usb0 rndis0 bnep0 wlan0 ap0"

        // 学习 VPNHotspot：硬件加速全局设置是 tether_offload_disabled（0=加速开，1=禁用）
        const val TETHER_OFFLOAD = "tether_offload_disabled"

        fun rootAvailable(): Boolean {
            return try {
                val p = ProcessBuilder("/system/bin/su", "-c", "id").start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Exception) {
                false
            }
        }

        fun rootExec(cmd: String): String? {
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

        // VPN 热点脚本（on=true 开启转发；false 清理规则）。学习 VPNHotspot：
        // IP 转发 ndc ipfwd（fallback sysctl）+ iptables NAT/FORWARD
        fun vpnHotspotScript(on: Boolean): String {
            val tunFind = "ip -o link show | grep -oE '" + TUN_PATTERN + "' | head -1"
            val ifaceFind = "for i in " + IFACE_PATTERN + "; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done"
            val rules = if (on) {
                "iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                    "iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    "ndc ipfwd enable vpnhotspot_\$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=1"
            } else {
                "iptables -t nat -D POSTROUTING -o \$TUN -j MASQUERADE; " +
                    "iptables -D FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -D FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    "ndc ipfwd disable vpnhotspot_\$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=0"
            }
            return "TUN=\$($tunFind); IFACE=\$($ifaceFind); " +
                "[ -z \"\$TUN\" ] && echo TUN_MISS && exit 3; [ -z \"\$IFACE\" ] && echo IFACE_MISS && exit 4; " +
                rules + "; echo DONE"
        }

        // 启动清理：清除可能残留的规则并复位状态
        fun cleanupAtStartup() {
            rootExec(vpnHotspotScript(false))
        }
    }

    // ---- 系统真实状态 ----
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

    private fun usbEnabled(): Boolean = tetheredIfaces().any { it.contains("usb") || it.contains("rndis") }
    private fun btEnabled(): Boolean = tetheredIfaces().any { it.contains("bnep") }

    // 学习 VPNHotspot：API 30+ 用 TetheringManager（root 下反射调用，含 WiFi/USB/蓝牙 tethering）
    private fun setTetheringSystem(type: Int, on: Boolean): Boolean {
        return try {
            val tm = requireContext().getSystemService("tethering") ?: return false
            val cls = tm.javaClass
            val cbClass = Class.forName("android.net.TetheringManager\$TetheringCallback")
            val m = if (on) {
                cls.getMethod("startTethering", Int::class.javaPrimitiveType,
                    java.util.concurrent.Executor::class.java, cbClass)
            } else {
                cls.getMethod("stopTethering", Int::class.javaPrimitiveType,
                    java.util.concurrent.Executor::class.java, cbClass)
            }
            val exe = java.util.concurrent.Executors.newSingleThreadExecutor()
            m.invoke(tm, type, exe, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    // 热点开关：API<33 反射 setWifiApEnabled；API>=33 root cmd wifi / TetheringManager
    private fun setHotspot(on: Boolean): Boolean {
        if (setTetheringSystem(0, on)) return true
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
        if (setTetheringSystem(1, on)) return true
        return try {
            rootExec(if (on) "svc usb setFunctions rndis" else "svc usb setFunctions none")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setBt(on: Boolean): Boolean {
        if (setTetheringSystem(2, on)) return true
        return try {
            rootExec(if (on) "svc bluetooth enable" else "svc bluetooth disable")
            true
        } catch (_: Exception) {
            false
        }
    }

    // 硬件加速（tether_offload_disabled：0=开 1=关）
    private fun hwAccelOn(): Boolean {
        return try {
            val out = rootExec("settings get global $TETHER_OFFLOAD")
            out.isNullOrBlank() || out == "0"
        } catch (_: Exception) {
            true
        }
    }

    private fun setHwAccel(on: Boolean) {
        rootExec("settings put global $TETHER_OFFLOAD ${if (on) 0 else 1}")
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

        // 每次进页面默认关闭（用户要求：每次打开软件都是默认关闭）
        fun refreshToggleState() {
            DataStore.vpnHotspotEnabled = false
            toggleState.text = getString(R.string.vpn_hotspot_off)
            toggleSwitch.isChecked = false
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
                val on = hwAccelOn()
                withContext(Dispatchers.Main) {
                    hwSwitch.isChecked = on
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

        // VPN 热点开关：开启时自动禁用硬件加速（学习 VPNHotspot：Android 8.1+ 必须关加速才能转发）
        toggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rootAvailable()) {
                snack(getString(R.string.vpn_hotspot_need_root))
                toggleSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (checked) {
                    setHwAccel(false) // 禁用硬件加速 tether_offload_disabled=1
                }
                val out = rootExec(vpnHotspotScript(checked))
                withContext(Dispatchers.Main) {
                    when {
                        out == null -> snack(getString(R.string.vpn_hotspot_cmd_fail))
                        out.contains("TUN_MISS") -> snack(getString(R.string.vpn_hotspot_tun_miss))
                        out.contains("IFACE_MISS") -> snack(getString(R.string.vpn_hotspot_no_iface))
                        else -> {
                            DataStore.vpnHotspotEnabled = checked
                            refreshToggleState()
                            toggleSwitch.isChecked = checked
                            toggleState.text = getString(
                                if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
                            )
                            snack(getString(if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off))
                        }
                    }
                }
            }
        }

        // 热点开关
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setHotspot(checked)
                delay(1000)
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
                delay(1000)
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
                delay(1000)
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

        // 硬件加速（tether_offload_disabled）
        hwSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setHwAccel(checked)
            }
        }

        // 管理系统共享
        view.findViewById<MaterialCardView>(R.id.hotspot_manage).setOnClickListener {
            startTetherSettings()
        }
    }

    override fun onResume() {
        super.onResume()
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
}
