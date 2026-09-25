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
        const val IFACE_PATTERN = "ap0 softap0 wlan1 usb0 rndis0 bnep0 eth0 wlan0"
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
                // ① 对齐 VPNHotspot：downstream 流量 lookup netd 的 tun 接口表（1000+ifindex），优先级插系统 tethering 规则间
                "IFIDX=\$(cat /sys/class/net/\$TUN/ifindex 2>/dev/null); " +
                    "ip rule add iif \$IFACE priority 20700 lookup \$((1000+IFIDX)) 2>/dev/null; " +
                    // ② 兜底：自定义表 97 默认走 tun（netd 未建接口表时）
                    "ip route replace 0.0.0.0/0 dev \$TUN table 96 2>/dev/null; " +
                    "ip rule add iif \$IFACE priority 20701 lookup 96 2>/dev/null; " +
                    // ③ 防泄漏：VPN 路由缺失时 unreachable（绝不走蜂窝直连）
                    "ip rule add iif \$IFACE priority 20900 unreachable 2>/dev/null; " +
                    // ④ NAT 出口指向 VPN
                    "ndc nat enable \$IFACE \$TUN 0 2>/dev/null; " +
                    "ndc ipfwd enable \$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=1; " +
                    "iptables -t nat -C POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                    // ⑤ 转发允许
                    "iptables -C FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -C FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    // ⑥ DNS 走隧道
                    "iptables -t nat -C PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "echo RULE:; ip rule show | grep -E '20700|20701|20900'; echo ROUTE96:; ip route show table 96; " +
                    "ip route del 0.0.0.0/0 dev \$TUN table 97 2>/dev/null; echo CLEAN97_OK"
            } else {
                "while ip rule del priority 20700 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20701 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20900 2>/dev/null; do :; done; " +
                    "ip route flush table 96 2>/dev/null; " +
                    "ip route del 0.0.0.0/0 dev \$TUN table 97 2>/dev/null; " +
                    "ndc nat disable \$IFACE 2>/dev/null; " +
                    "ndc ipfwd disable \$IFACE 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=0; " +
                    "iptables -t nat -D POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null; " +
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

    // ===== 热点实时状态（SoftApCallback + TetheringEventCallback，学习 VPNHotspot 的状态识别）=====
    private var hotspotClientCount = 0
    private var hotspotFreq = 0
    private var hotspotBandwidth = 0

    private fun updateHotspotInfo() {
        try {
            val ssidView = view?.findViewById<TextView>(R.id.hotspot_status_ssid) ?: return
            val detailView = view?.findViewById<TextView>(R.id.hotspot_status_detail) ?: return
            var ssid = ""
            if (Build.VERSION.SDK_INT >= 30) try {
                val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
                val cfg = WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)
                cfg?.let { ssid = it.javaClass.getMethod("getSsid").invoke(it) as? String ?: "" }
            } catch (_: Exception) { }
            val on = hotspotEnabled()
            val sb = StringBuilder()
            sb.append(if (on) "● 已开启" else "○ 已关闭")
            if (ssid.isNotBlank()) sb.append(" · ").append(ssid)
            ssidView.text = sb.toString()
            val det = StringBuilder()
            if (hotspotFreq > 0) det.append(hotspotFreq).append(" MHz")
            if (hotspotBandwidth > 0) det.append(" · 频宽 ").append(
                when (hotspotBandwidth) {
                    1 -> "20"; 2 -> "40"; 3 -> "80"; 4 -> "160"; else -> "$hotspotBandwidth"
                }
            ).append(" MHz")
            if (hotspotClientCount > 0) {
                if (det.isNotEmpty()) det.append(" · ")
                det.append(hotspotClientCount).append("/16 ").append(getString(R.string.vpn_hotspot_status_clients))
            }
            detailView.text = if (det.isNotEmpty()) det.toString() else "—"
        } catch (_: Exception) { }
    }

    private fun registerSoftApMonitor() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cbCls = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
                try {
                    when (m.name) {
                        "onConnectedClientsChanged" -> {
                            val list = args?.get(0) as? List<*> ?: emptyList<Any>()
                            hotspotClientCount = list.size
                        }
                        "onInfoChanged" -> {
                            val info = args?.get(0) ?: return@newProxyInstance null
                            try { hotspotFreq = info.javaClass.getMethod("getFrequency").invoke(info) as? Int ?: 0 } catch (_: Exception) { }
                            try { hotspotBandwidth = info.javaClass.getMethod("getBandwidth").invoke(info) as? Int ?: 0 } catch (_: Exception) { }
                        }
                        "onStateChanged" -> {
                            hotspotClientCount = 0
                            hotspotFreq = 0
                            hotspotBandwidth = 0
                        }
                    }
                    updateHotspotInfo()
                } catch (_: Exception) { }
                null
            }
            wm.javaClass.getMethod("registerSoftApCallback", cbCls, android.os.Handler::class.java)
                .invoke(wm, cb, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) { }
    }

    private fun registerTetheringMonitor() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val tm = requireContext().getSystemService("tethering")
            val cbCls = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, _ ->
                try {
                    when (m.name) {
                        "onTetheringStarted", "onTetheringStopped", "onError", "onUpstreamChanged", "onInterfaceStateChanged" -> {
                            refreshSystemStateSafe()
                            updateHotspotInfo()
                        }
                    }
                } catch (_: Exception) { }
                null
            }
            tm.javaClass.getMethod("registerTetheringEventCallback", java.util.concurrent.Executor::class.java, cbCls)
                .invoke(tm, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
        } catch (_: Exception) { }
    }

    private fun refreshSystemStateSafe() {
        try {
            lifecycleScope.launch(Dispatchers.IO) {
                val h = hotspotEnabled()
                val u = usbEnabled()
                val b = btEnabled()
                val e = ethernetEnabled()
                withContext(Dispatchers.Main) {
                    val ws = view?.findViewById<Switch>(R.id.hotspot_wifi_switch) ?: return@withContext
                    ws.isChecked = h
                    view?.findViewById<Switch>(R.id.hotspot_usb_switch)?.isChecked = u
                    view?.findViewById<Switch>(R.id.hotspot_bt_switch)?.isChecked = b
                    view?.findViewById<Switch>(R.id.hotspot_ethernet_switch)?.isChecked = e
                }
            }
        } catch (_: Exception) { }
    }

    // ===== 页面日志（完整输出，防 snack 截断）=====
    private val logBuf = StringBuilder()
    private var logView: TextView? = null
    private fun appendLog(msg: String) {
        try {
            if (logBuf.length > 20000) logBuf.setLength(0)
            logBuf.append(msg).append("\n")
            val v = logView ?: return
            activity?.runOnUiThread {
                v.text = logBuf.toString()
                v.post { v.scrollTo(0, v.layout?.height ?: 0) }
            }
        } catch (_: Exception) { }
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
    // RootHelper（root 进程反射系统服务；启动方式对齐 librootkotlinx：
    // CLASSPATH=APK exec app_process -Xnoimage-dex2oat /system/bin --nice-name；runcon 处理 SELinux 域防 killed）
    private fun rootHelper(action: String): String? {
        return try {
            val apk = requireContext().applicationInfo.sourceDir
            val logf = "/data/local/tmp/zybox_rh.log"
            val base = "setenforce 0; CLASSPATH=$apk exec "
            val cmds = listOf(
                "$base app_process -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action",
                "$base runcon u:r:su:s0 app_process -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action",
                "$base app_process /system/bin io.nekohasekai.sagernet.RootHelper $action"
            )
            for (c in cmds) {
                // 输出重定向到文件再读（app_process stdout 经 su 传递不可靠，见 v16 空输出问题）
                val o = rootExec("$c > $logf 2>&1; cat $logf; rm -f $logf")
                appendLog("root: $action\n${o ?: "(null)"}")
                if (o != null && !o.contains("killed") && !cmdFailed(o)) return o
            }
            null
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

    // 热点：先普通进程 startTethering 并等待系统回调；
    // entitlement 豁免需 TETHER_PRIVILEGED（普通进程拿不到），onTetheringFailed/异常 → 自动 root 会话兜底（同 VPNHotspot）
    private fun setHotspot(on: Boolean): Pair<Boolean, String> {
        var started = false
        var failed = false
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            val tm = requireContext().getSystemService("tethering") ?: throw RuntimeException("no tether svc")
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val exe = java.util.concurrent.Executors.newSingleThreadExecutor()
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, _ ->
                when (m.name) {
                    "onTetheringStarted" -> { started = true; appendLog("TetheringManager: 热点已启动 (onTetheringStarted)") }
                    "onTetheringFailed" -> { failed = true; appendLog("TetheringManager: 热点启动失败 (onTetheringFailed)") }
                }
                try { latch.countDown() } catch (_: Throwable) { }
                null
            }
            if (on) {
                val bCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = bCls.getConstructor(Int::class.java).newInstance(0) // TETHERING_WIFI
                try {
                    bCls.getMethod("setExemptFromEntitlementCheck", Boolean::class.java).invoke(builder, true)
                } catch (_: Throwable) { }
                try {
                    bCls.getMethod("setShouldShowEntitlementUi", Boolean::class.java).invoke(builder, false)
                } catch (_: Throwable) { }
                val req = bCls.getMethod("build").invoke(builder)
                tm.javaClass.getMethod("startTethering", req.javaClass, java.util.concurrent.Executor::class.java, cbCls)
                    .invoke(tm, req, exe, cb)
                appendLog("TetheringManager.startTethering(wifi) 已调用，等待系统回调 2s…")
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                if (started) return true to "startTethering 成功（系统回调确认）"
            } else {
                try {
                    tm.javaClass.getMethod("stopTethering", Int::class.java, java.util.concurrent.Executor::class.java, cbCls)
                        .invoke(tm, 0, exe, cb)
                } catch (e: NoSuchMethodException) {
                    tm.javaClass.getMethod("stopTethering", Int::class.java, String::class.java, java.util.concurrent.Executor::class.java, cbCls)
                        .invoke(tm, 0, requireContext().packageName, exe, cb)
                }
                appendLog("TetheringManager.stopTethering(wifi) 已调用，等待系统处理…")
                Thread.sleep(1200)
                if (!hotspotEnabled()) return true to "stopTethering 已生效"
                appendLog("普通进程关闭未生效 → root 会话兜底")
            }
        } catch (e: Exception) {
            appendLog("普通进程 startTethering 异常：${e.message}")
        }
        if (failed || !started) {
            appendLog("普通进程路径未成功（started=$started failed=$failed）→ root 会话兜底（entitlement 豁免需 TETHER_PRIVILEGED）")
            val o = rootHelper("tether wifi ${if (on) "on" else "off"}")
            if (o != null && !cmdFailed(o)) return true to o
            return false to (o ?: "roothelper 执行失败")
        }
        return false to "未收到系统回调"
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
        logView = view.findViewById<TextView>(R.id.hotspot_log)

        // 附近设备权限（Android 13+，VPNHotspot 开热点前也请求）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requireActivity().requestPermissions(
                    arrayOf("android.permission.NEARBY_WIFI_DEVICES"), 0x5A17
                )
            } catch (_: Exception) { }
        }

        view.findViewById<View>(R.id.hotspot_log_clear)?.setOnClickListener {
            logBuf.setLength(0)
            logView?.text = ""
        }

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
            val on = DataStore.vpnHotspotEnabled
            suppressToggle = true
            toggleSwitch.isChecked = on
            toggleState.text = getString(if (on) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off)
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
        registerSoftApMonitor()
        registerTetheringMonitor()
        updateHotspotInfo()

        view.findViewById<View>(R.id.hotspot_root_refresh).setOnClickListener {
            refreshRoot()
            refreshSystemState()
        }

        // VPN 热点开关（开启时自动禁用硬件加速；关闭时清转发并一并关热点）
        toggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
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
                            appendLog("自动开启热点未成功: ${hout.take(200)}")
                        }
                    }
                    val out = rootExec(vpnHotspotScript(checked))
                    appendLog("转发脚本输出:\n$out")
                    if (!checked) {
                        // 真关闭：转发已清理，热点一并关闭
                        val (hok2, hout2) = setHotspot(false)
                        appendLog("VPN热点关闭时热点关闭: ok=$hok2 ${hout2.take(200)}")
                    }
                    withContext(Dispatchers.Main) {
                        when {
                            out == null -> {
                                suppressToggle = true
                                toggleSwitch.isChecked = !checked
                                suppressToggle = false
                            }
                            out.contains("TUN_MISS") || out.contains("IFACE_MISS") -> {
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
                        appendLog("热点开启: ok=$ok ${out.take(200)}")
                        if (ok) {
                            // AP 开起来后补转发规则（start-softap 不激活互联网共享）
                            var waited = 0
                            while (!shareIfaceExists() && waited < 20) {
                                delay(500)
                                waited++
                            }
                            rootExec(vpnHotspotScript(true))?.let { appendLog("热点转发脚本:\n$it") }
                        }
                        delay(1200)
                        val now = hotspotEnabled()
                        withContext(Dispatchers.Main) {
                            if (!now) {
                                suppressToggle = true
                                wifiSwitch.isChecked = false
                                suppressToggle = false
                            }
                        }
                    } else {
                        rootExec(vpnHotspotScript(false))?.let { appendLog("热点清理脚本:\n$it") }
                        val (ok, out) = setHotspot(false)
                        appendLog("热点关闭: ok=$ok ${out.take(200)}")
                        delay(1200)
                        val now = hotspotEnabled()
                        withContext(Dispatchers.Main) {
                            if (now) {
                                suppressToggle = true
                                wifiSwitch.isChecked = true
                                suppressToggle = false
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
                    appendLog("USB开关: ok=$ok ${out.take(200)}")
                    delay(1500)
                    val now = usbEnabled()
                    withContext(Dispatchers.Main) {
                        if (now != checked) {
                            suppressToggle = true
                            usbSwitch.isChecked = checked
                            suppressToggle = false
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
                    appendLog("蓝牙开关: ok=$ok ${out.take(200)}")
                    delay(1500)
                    val now = btEnabled()
                    withContext(Dispatchers.Main) {
                        if (now != checked) {
                            suppressToggle = true
                            btSwitch.isChecked = checked
                            suppressToggle = false
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
                    appendLog("以太网开关: ok=$ok ${out.take(200)}")
                    delay(1500)
                    val now = ethernetEnabled()
                    withContext(Dispatchers.Main) {
                        if (now != checked) {
                            suppressToggle = true
                            ethernetSwitch.isChecked = checked
                            suppressToggle = false
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
