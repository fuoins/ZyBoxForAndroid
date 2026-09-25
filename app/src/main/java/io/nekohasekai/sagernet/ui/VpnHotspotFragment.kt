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
        const val IFACE_PATTERN = "usb0 rndis0 bnep0 eth0 wlan0 wlan1 ap0 softap0"
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
                // 系统 tethering 的 NAT 出口改为 VPN 接口（VPNHotspot 的 ndc nat 方式，拦截系统默认直连）
                "ndc nat enable \$IFACE \$TUN 0 2>/dev/null; " +
                    "ndc ipfwd enable \$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=1; " +
                    "iptables -C FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -C FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "echo NAT_STATUS:; ndc nat status 2>/dev/null | head -5"
            } else {
                "ndc nat disable \$IFACE 2>/dev/null; " +
                    "ndc ipfwd disable \$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=0; " +
                    "iptables -t nat -D PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -t nat -D PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -D FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null; " +
                    "iptables -D FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null"
            }
            return if (on) {
                "TUN=\$($tunFind); IFACE=\$($ifaceFind); " +
                    "[ -z \"\$TUN\" ] && echo TUN_MISS && exit 3; [ -z \"\$IFACE\" ] && echo IFACE_MISS && exit 4; " +
                    rules + "; echo IFACE=\$IFACE; echo DONE"
            } else {
                // 关闭：接口可能已消失，不清检查，逐条清理防残留
                "TUN=\$($tunFind); IFACE=\$($ifaceFind); " + rules + "; echo IFACE=\$IFACE; echo DONE"
            }
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
    // RootHelper（app_process 以 root 反射系统 TetheringManager，效果同系统设置开关）
    private fun rootHelper(action: String): String? {
        return try {
            val dex = java.io.File(requireContext().filesDir, "roothelper.dex")
            if (!dex.exists()) {
                requireContext().assets.open("roothelper.dex").use { input ->
                    dex.outputStream().use { output -> input.copyTo(output) }
                }
            }
            rootExec("CLASSPATH=${dex.absolutePath} app_process /system/bin io.nekohasekai.sagernet.RootHelper $action")
        } catch (_: Exception) {
            null
        }
    }

    // 命令输出是否失败（ColorOS/Android15 报错文本不能当作成功）
    private fun cmdFailed(out: String): Boolean {
        val s = out.lowercase()
        return s.contains("no shell command") || s.contains("invalid args") ||
            s.contains("unknown command") || s.contains("not found") ||
            s.contains("permission denied") || s.contains("exception") ||
            s.contains("error") || s.contains("usage:") || s.contains("fail")
    }

    // 热点：系统级 tethering（RootHelper 反射 TetheringManager，用系统已有热点配置）
    private fun setHotspot(on: Boolean): Pair<Boolean, String> {
        val o = rootHelper("tether wifi ${if (on) "on" else "off"}")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "roothelper 执行失败")
    }

    // 是否有共享接口（热点/usb/蓝牙/以太网任一已建立）
    private fun shareIfaceExists(): Boolean {
        val out = rootExec("ip -o link show") ?: return false
        return IFACE_PATTERN.split(" ").any { out.contains(": $it:") }
    }

    // USB：系统级 tethering（RootHelper）
    private fun setUsb(on: Boolean): Pair<Boolean, String> {
        val o = rootHelper("tether usb ${if (on) "on" else "off"}")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "roothelper 执行失败")
    }

    // 以太网：系统级 tethering（RootHelper）
    private fun setEthernet(on: Boolean): Pair<Boolean, String> {
        val o = rootHelper("tether ethernet ${if (on) "on" else "off"}")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "roothelper 执行失败")
    }

    // 蓝牙：系统级 tethering（RootHelper 反射 TetheringManager 蓝牙共享）
    private fun setBt(on: Boolean): Pair<Boolean, String> {
        val o = rootHelper("tether bluetooth ${if (on) "on" else "off"}")
        if (o != null && !cmdFailed(o)) return true to o
        return false to (o ?: "roothelper 执行失败")
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
                                if (checked && out.isNotBlank()) {
                                    snack(getString(R.string.vpn_hotspot_on) + "\n" + out.take(300))
                                } else {
                                    snack(getString(if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 热点开关：开=开AP并配置转发（客户端可上网）；关=清转发并关AP
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (checked) {
                        val (ok, out) = setHotspot(true)
                        if (ok) {
                            // AP 开起来后补转发规则（start-softap 不激活互联网共享）
                            var waited = 0
                            while (!shareIfaceExists() && waited < 20) {
                                delay(500)
                                waited++
                            }
                            rootExec(vpnHotspotScript(true))
                        }
                        delay(1200)
                        val now = hotspotEnabled()
                        withContext(Dispatchers.Main) {
                            if (now) {
                                snack(getString(R.string.vpn_hotspot_open))
                            } else if (ok) {
                                suppressToggle = true
                                wifiSwitch.isChecked = true
                                suppressToggle = false
                                snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                            } else {
                                suppressToggle = true
                                wifiSwitch.isChecked = false
                                suppressToggle = false
                                snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                            }
                        }
                    } else {
                        rootExec(vpnHotspotScript(false))
                        val (ok, out) = setHotspot(false)
                        delay(1200)
                        val now = hotspotEnabled()
                        withContext(Dispatchers.Main) {
                            if (!now) {
                                snack(getString(R.string.vpn_hotspot_close))
                            } else if (ok) {
                                suppressToggle = true
                                wifiSwitch.isChecked = true
                                suppressToggle = false
                                snack("已发送命令${if (out.isNotBlank()) "：${out.take(120)}" else ""}")
                            } else {
                                suppressToggle = true
                                wifiSwitch.isChecked = false
                                suppressToggle = false
                                snack(getString(R.string.vpn_hotspot_cmd_fail) + (if (out.isNotBlank()) "：${out.take(120)}" else ""))
                            }
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
