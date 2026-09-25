package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ZyBox: VPN 热点页 v6（参考 Mygod/VPNHotspot 实现思路）
// - 开关一律走 root shell 命令（cmd wifi / cmd tethering / svc），普通进程不触碰 TetheringManager，避免权限异常与闪退
// - 热点多级尝试：cmd wifi start-softap → cmd tethering start wifi → 反射 startTetheredHotspot → setWifiApEnabled
// - VPN 转发：MASQUERADE + FORWARD + DNS 重定向（53→8.8.8.8 使客户端 DNS 走隧道）
// - 管理系统共享：android.settings.TETHER_SETTINGS → Settings$TetherSettingsActivity（同 ManageBar）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    companion object {
        const val TUN_PATTERN = "tun[0-9]+|utun[0-9]+"
        const val IFACE_PATTERN = "usb0 rndis0 bnep0 eth0 wlan0 ap0"
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

        // VPN 转发脚本（参考 VPNHotspot routing 思路的可落地子集）：
        // 转发 + NAT + 双向 FORWARD + DNS 重定向到 8.8.8.8（客户端 DNS 查询走隧道，避免被墙 DNS 污染）
        fun vpnHotspotScript(on: Boolean): String {
            val tunFind = "ip -o link show | grep -oE '" + TUN_PATTERN + "' | head -1"
            val ifaceFind = "for i in " + IFACE_PATTERN + "; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done"
            val rules = if (on) {
                "iptables -t nat -C POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                    "iptables -C FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -C FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "ndc ipfwd enable \$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=1"
            } else {
                "iptables -t nat -D PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -t nat -D PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -t nat -D POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null; " +
                    "iptables -D FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null; " +
                    "iptables -D FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; " +
                    "ndc ipfwd disable \$IFACE 2>/dev/null; " +
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

    // ===== 状态读取（多源兜底，全部 try-catch）=====
    private fun hotspotEnabled(): Boolean {
        // 1) isTetheredHotspotEnabled (API 33+)
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = WifiManager::class.java.getMethod("isTetheredHotspotEnabled")
            if (m.invoke(wm) as? Boolean == true) return true
        } catch (_: Exception) {
        }
        // 2) isWifiApEnabled（旧接口，Oplus 常见）
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = WifiManager::class.java.getMethod("isWifiApEnabled")
            if (m.invoke(wm) as? Boolean == true) return true
        } catch (_: Exception) {
        }
        // 3) tethered ifaces 含 wlan0/ap0
        if (tetheredIfaces().any { it.contains("wlan") || it == "ap0" }) return true
        // 4) cmd wifi status 文本
        return try {
            val out = rootExec("cmd wifi status") ?: return false
            out.contains("SoftApState: STATE_ENABLED") ||
                out.contains("Wi-Fi AP state: enabled") ||
                out.contains("AP state: enabled") ||
                out.contains("ENABLED")
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
    private fun ethernetEnabled(): Boolean = tetheredIfaces().any {
        it.contains("eth") || it.contains("rndis") || it.contains("ncm")
    }

    // ===== 开关（一律 root shell 命令，多级尝试）=====
    // 命令输出是否失败（ColorOS/Android15 报错文本不能当作成功）
    private fun cmdFailed(out: String): Boolean {
        val s = out.lowercase()
        return s.contains("no shell command") || s.contains("invalid args") ||
            s.contains("unknown command") || s.contains("not found") ||
            s.contains("permission denied") || s.contains("exception") ||
            s.contains("error") || s.contains("usage:") || s.contains("fail")
    }

    // 热点（返回 是否真正执行成功 与 最后输出）：Android 15 的 start-softap 必须带频段参数
    // 先断 WiFi（ColorOS 常要求 STA 关闭），再依次尝试 -1(任意)/0(2.4G)/ANY/2G/1(5G)/5G
    private fun setHotspot(on: Boolean): Pair<Boolean, String> {
        var lastOut: String = ""
        fun run(cmd: String): Boolean {
            val o = rootExec(cmd)
            if (o != null) lastOut = o
            return o != null && !cmdFailed(o)
        }
        if (on) {
            // ColorOS/多数设备：开热点前需断开 WiFi STA
            run("cmd wifi set-wifi-enabled disabled")
            run("svc wifi disable")
            // Android15/ColorOS: start-softap 需要 频段+信道 两个参数（信道 0=自动）
            val startCmds = listOf(
                "cmd wifi start-softap -1 0",
                "cmd wifi start-softap ANY 0",
                "cmd wifi start-softap 2G 0",
                "cmd wifi start-softap 0 0",
                "cmd wifi start-softap 5G 0",
                "cmd wifi start-softap 1 0"
            )
            for (c in startCmds) if (run(c)) return true to lastOut
        } else {
            if (run("cmd wifi stop-softap")) return true to lastOut
        }
        // 无 root 兜底：反射 startTetheredHotspot / stopTetheredHotspot
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (on) {
                val m = WifiManager::class.java.getMethod(
                    "startTetheredHotspot", java.util.concurrent.Executor::class.java,
                    Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
                )
                m.invoke(wm, java.util.concurrent.Executors.newSingleThreadExecutor(), null)
            } else {
                val m = WifiManager::class.java.getMethod("stopTetheredHotspot")
                m.invoke(wm)
            }
            return true to "reflect"
        } catch (_: Exception) {
        }
        // API<33 setWifiApEnabled
        return try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cfg = android.net.wifi.WifiConfiguration::class.java.getDeclaredConstructor().newInstance()
            val m = WifiManager::class.java.getMethod(
                "setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            m.invoke(wm, cfg, on)
            true to "reflect"
        } catch (_: Exception) {
            false to lastOut
        }
    }

    // 是否有共享接口（热点/usb/蓝牙/以太网任一已建立）
    private fun shareIfaceExists(): Boolean {
        val out = rootExec("ip -o link show") ?: return false
        return IFACE_PATTERN.split(" ").any { out.contains(": $it:") }
    }

    // USB：svc usb setFunctions rndis（ColorOS 上 cmd tethering 不存在，直接走 svc）
    private fun setUsb(on: Boolean): Pair<Boolean, String> {
        val o = rootExec(if (on) "svc usb setFunctions rndis" else "svc usb setFunctions none")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "")
    }

    // 以太网：无 root shell 途径（cmd tethering 在 ColorOS 不存在）→ 诚实提示
    private fun setEthernet(on: Boolean): Pair<Boolean, String> {
        val o = rootExec(if (on) "cmd tethering start ethernet" else "cmd tethering stop ethernet")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "cmd tethering 不存在")
    }

    // 蓝牙：svc bluetooth 开关 + PAN 反射（ColorOS 无 cmd tethering）
    private fun setBt(on: Boolean): Pair<Boolean, String> {
        val svcOut = rootExec(if (on) "svc bluetooth enable" else "svc bluetooth disable")
        if (svcOut != null && cmdFailed(svcOut)) return false to svcOut
        val btOk = svcOut != null && svcOut.contains("Success")
        return try {
            val adapter = requireContext().getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothAdapter ?: return false to (svcOut ?: "")
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
            val proxy = pan[0] ?: return false to (svcOut ?: "")
            val setM = proxy.javaClass.getMethod("setBluetoothTethering", Boolean::class.javaPrimitiveType)
            setM.invoke(proxy, on)
            true to "PAN"
        } catch (_: Exception) {
            // 蓝牙已开但 PAN 反射无权限：提示系统设置
            if (btOk) true to "蓝牙已开启；网络共享请在系统蓝牙设置中开启（需已配对设备）"
            else false to (svcOut ?: "")
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
        try {
            rootExec("settings put global $TETHER_OFFLOAD ${if (on) 0 else 1}")
        } catch (_: Exception) {
        }
    }

    // 管理系统共享：android.settings.TETHER_SETTINGS → Settings$TetherSettingsActivity（同 VPNHotspot ManageBar）
    private fun startTetherSettings() {
        var eSuppressed: RuntimeException? = null
        if (Build.VERSION.SDK_INT >= 30) try {
            startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: RuntimeException) {
            eSuppressed = e
        }
        try {
            startActivity(Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"))
            return
        } catch (e: RuntimeException) {
            eSuppressed?.let { e.addSuppressed(it) }
        }
        try {
            startActivity(Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
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
        val ethernetSwitch = view.findViewById<Switch>(R.id.hotspot_ethernet_switch)
        val hwSwitch = view.findViewById<Switch>(R.id.hotspot_hw)

        fun snack(msg: String) {
            try {
                (activity as? MainActivity)?.snackbar(msg)?.show()
            } catch (_: Exception) {
            }
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
                val e = ethernetEnabled()
                withContext(Dispatchers.Main) {
                    wifiSwitch.isChecked = h
                    usbSwitch.isChecked = u
                    btSwitch.isChecked = b
                    ethernetSwitch.isChecked = e
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
                try {
                    if (checked) setHwAccel(false)
                    // ZyBox: 开启 VPN 热点时若系统热点未开，自动开热点并等待共享接口出现
                    if (checked && !shareIfaceExists()) {
                        val (hok, hout) = setHotspot(true)
                        var waited = 0
                        while (!shareIfaceExists() && waited < 20) {
                            delay(500)
                            waited++
                        }
                        if (!shareIfaceExists() && !hok) {
                            withContext(Dispatchers.Main) {
                                snack("自动开启热点失败${if (hout.isNotBlank()) "：${hout.take(120)}" else ""}")
                            }
                        }
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
                } catch (_: Exception) {
                }
            }
        }

        // 热点开关
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (ok, out) = setHotspot(checked)
                    delay(1500)
                    val now = hotspotEnabled()
                    withContext(Dispatchers.Main) {
                        if (now == checked) {
                            snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                        } else if (ok) {
                            // 命令已执行：保持开关状态，不自动弹回；输出可见便于排查
                            suppressToggle = true
                            wifiSwitch.isChecked = checked
                            suppressToggle = false
                            snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                        } else {
                            suppressToggle = true
                            wifiSwitch.isChecked = !checked
                            suppressToggle = false
                            snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // USB 开关
        usbSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (ok, out) = setUsb(checked)
                    delay(1500)
                    val now = usbEnabled()
                    withContext(Dispatchers.Main) {
                        if (now == checked) {
                            snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                        } else if (ok) {
                            suppressToggle = true
                            usbSwitch.isChecked = checked
                            suppressToggle = false
                            snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                        } else {
                            suppressToggle = true
                            usbSwitch.isChecked = !checked
                            suppressToggle = false
                            snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 蓝牙开关
        btSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (ok, out) = setBt(checked)
                    delay(1500)
                    val now = btEnabled()
                    withContext(Dispatchers.Main) {
                        if (now == checked) {
                            snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                        } else if (ok) {
                            suppressToggle = true
                            btSwitch.isChecked = checked
                            suppressToggle = false
                            snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                        } else {
                            suppressToggle = true
                            btSwitch.isChecked = !checked
                            suppressToggle = false
                            snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 以太网开关
        ethernetSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (ok, out) = setEthernet(checked)
                    delay(1500)
                    val now = ethernetEnabled()
                    withContext(Dispatchers.Main) {
                        if (now == checked) {
                            snack(getString(if (checked) R.string.vpn_hotspot_open else R.string.vpn_hotspot_close))
                        } else if (ok) {
                            suppressToggle = true
                            ethernetSwitch.isChecked = checked
                            suppressToggle = false
                            snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                        } else {
                            suppressToggle = true
                            ethernetSwitch.isChecked = !checked
                            suppressToggle = false
                            snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 硬件加速
        hwSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    setHwAccel(checked)
                } catch (_: Exception) {
                }
            }
        }

        // 管理系统共享
        view.findViewById<View>(R.id.hotspot_manage).setOnClickListener {
            startTetherSettings()
        }
    }
}
