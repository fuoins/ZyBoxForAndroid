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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ZyBox: VPN 热点页 v4——不跳设置，root 命令直接开关并读回验证；修复开关死循环；
// 管理系统共享按钮单独跳正确系统页（WIFI_TETHER_SETTINGS）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    companion object {
        const val TUN_PATTERN = "tun[0-9]+|utun[0-9]+"
        const val IFACE_PATTERN = "usb0 rndis0 bnep0 wlan0 ap0"
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

        fun cleanupAtStartup() {
            rootExec(vpnHotspotScript(false))
        }
    }

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

    // 热点：API<33 反射 setWifiApEnabled（普通权限即可）；API>=33 root cmd wifi start-softap
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

    // USB：root svc usb setFunctions rndis/none
    private fun setUsb(on: Boolean): Boolean {
        return try {
            val out = rootExec(if (on) "svc usb setFunctions rndis" else "svc usb setFunctions none")
            true
        } catch (_: Exception) {
            false
        }
    }

    // 蓝牙：root 开/关蓝牙 + 反射 BluetoothPan.setBluetoothTethering
    private fun setBt(on: Boolean): Boolean {
        try {
            rootExec(if (on) "svc bluetooth enable" else "svc bluetooth disable")
        } catch (_: Exception) {
        }
        return try {
            val adapter = requireContext().getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothAdapter ?: return false
            val pan = arrayOfNulls<android.bluetooth.BluetoothProfile>(1)
            val latch = CountDownLatch(1)
            val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    pan[0] = proxy
                    latch.countDown()
                }

                override fun onServiceDisconnected(profile: Int) {}
            }
            val m = android.bluetooth.BluetoothAdapter::class.java.getMethod(
                "getProfileProxy", Context::class.java,
                android.bluetooth.BluetoothProfile.ServiceListener::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(adapter, requireContext(), listener, 2) // BluetoothProfile.PAN = 2
            latch.await(3, TimeUnit.SECONDS)
            val proxy = pan[0] ?: return false
            val setM = proxy.javaClass.getMethod("setBluetoothTethering", Boolean::class.javaPrimitiveType)
            setM.invoke(proxy, on)
            true
        } catch (_: Exception) {
            false
        }
    }

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

        // 防死循环：程序性设置 isChecked 时暂停 listener
        var suppressToggle = false
        fun refreshToggleState() {
            DataStore.vpnHotspotEnabled = false
            suppressToggle = true
            toggleSwitch.isChecked = false
            toggleState.text = getString(R.string.vpn_hotspot_off)
            suppressToggle = false
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

        // VPN 热点开关（开启时自动禁用硬件加速）
        toggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (!rootAvailable()) {
                snack(getString(R.string.vpn_hotspot_need_root))
                suppressToggle = true
                toggleSwitch.isChecked = false
                suppressToggle = false
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (checked) {
                    setHwAccel(false)
                }
                val out = rootExec(vpnHotspotScript(checked))
                withContext(Dispatchers.Main) {
                    when {
                        out == null -> {
                            snack(getString(R.string.vpn_hotspot_cmd_fail))
                            suppressToggle = true
                            toggleSwitch.isChecked = !checked
                            suppressToggle = false
                        }
                        out.contains("TUN_MISS") -> {
                            snack(getString(R.string.vpn_hotspot_tun_miss))
                            suppressToggle = true
                            toggleSwitch.isChecked = false
                            suppressToggle = false
                        }
                        out.contains("IFACE_MISS") -> {
                            snack(getString(R.string.vpn_hotspot_no_iface))
                            suppressToggle = true
                            toggleSwitch.isChecked = false
                            suppressToggle = false
                        }
                        else -> {
                            DataStore.vpnHotspotEnabled = checked
                            suppressToggle = true
                            toggleSwitch.isChecked = checked
                            suppressToggle = false
                            toggleState.text = getString(
                                if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
                            )
                            snack(getString(if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off))
                        }
                    }
                }
            }
        }

        // 热点开关：不跳设置，命令失败给出提示并回读状态
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                setHotspot(checked)
                delay(1500)
                val now = hotspotEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        suppressToggle = true
                        wifiSwitch.isChecked = now
                        suppressToggle = false
                        snack(getString(R.string.vpn_hotspot_wifi_fail))
                    }
                }
            }
        }

        // USB 开关
        usbSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                setUsb(checked)
                delay(1500)
                val now = usbEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        suppressToggle = true
                        usbSwitch.isChecked = now
                        suppressToggle = false
                        snack(getString(R.string.vpn_hotspot_usb_fail))
                    }
                }
            }
        }

        // 蓝牙开关
        btSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                setBt(checked)
                delay(1500)
                val now = btEnabled()
                withContext(Dispatchers.Main) {
                    if (now == checked) {
                        snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                    } else {
                        suppressToggle = true
                        btSwitch.isChecked = now
                        suppressToggle = false
                        snack(getString(R.string.vpn_hotspot_bt_fail))
                    }
                }
            }
        }

        // 硬件加速
        hwSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                setHwAccel(checked)
            }
        }

        // 管理系统共享（跳正确系统页）
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

    // 系统共享设置：优先热点共享页，逐级 fallback
    private fun startTetherSettings() {
        val actions = listOf(
            "android.settings.WIFI_TETHER_SETTINGS",
            "android.settings.TETHERING_SETTINGS",
            "android.settings.SETTINGS"
        )
        for (a in actions) {
            try {
                startActivity(Intent(a))
                return
            } catch (_: Exception) {
            }
        }
    }
}
